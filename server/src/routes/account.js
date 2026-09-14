import { config } from "../config.js";
import { store } from "../store.js";
import {
  authenticateRequest,
  createAccount,
  generateToken,
  hashRedeemCode,
  hashToken,
  tokenPrefixOf,
} from "../auth.js";
import { creditCny, publicAccountView, remainingSeconds } from "../wallet.js";
import { clientIp, deviceIdOf, rateLimit, sendError, sendJson } from "../http-util.js";

/**
 * 用户侧账号接口。
 *
 * 设计要点：用户不需要注册。拿一个兑换码 + 一个设备 ID，第一次核销就自动建号并下发
 * 长期令牌；之后同一设备再核销只充值。令牌丢失则用 /v1/token/rotate 轮换。
 */

export async function handleRedeem(req, res, body) {
  const ip = clientIp(req) || "unknown";
  if (!rateLimit(`redeem:${ip}`, 10, 10 * 60 * 1000)) {
    sendError(res, 429, "rate_limited", "尝试过于频繁，请稍后再试");
    return;
  }

  const code = String(body?.code ?? "").trim().toUpperCase();
  if (!code) {
    sendError(res, 400, "invalid_request", "缺少 code");
    return;
  }
  const deviceId = String(body?.deviceId ?? deviceIdOf(req) ?? "").trim();
  if (!deviceId) {
    sendError(res, 400, "invalid_request", "缺少 deviceId");
    return;
  }

  const codeHash = hashRedeemCode(code);
  const record = store.redeemCode(codeHash);
  if (!record) {
    sendError(res, 400, "invalid_code", "兑换码不存在");
    return;
  }

  // 已被别的设备用掉
  if (record.redeemedByDeviceId && record.redeemedByDeviceId !== deviceId) {
    sendError(res, 409, "already_redeemed", "该兑换码已被使用");
    return;
  }
  if (record.redeemedByDeviceId === deviceId) {
    sendError(res, 409, "already_redeemed", "该兑换码已在本设备使用过");
    return;
  }
  if (record.revoked) {
    sendError(res, 400, "invalid_code", "兑换码已作废");
    return;
  }

  // 找或建设备对应的账号
  let account = store
    .accounts()
    .find((a) => a.deviceId === deviceId && a.status !== "deleted") || null;
  let plainToken = null;

  if (!account) {
    const created = createAccount({
      label: String(body?.deviceName ?? "").trim() || `device:${deviceId.slice(0, 8)}`,
      deviceId,
    });
    account = created.account;
    plainToken = created.plainToken;
  }

  record.redeemedByDeviceId = deviceId;
  record.redeemedByAccountId = account.id;
  record.redeemedAt = new Date().toISOString();
  record.redeemedIp = ip;

  const { account: updated } = creditCny(account.id, record.amountCny, "purchase", codeHash, {
    channel: "redeem_code",
    batch: record.batch,
  });

  store.scheduleSave();

  sendJson(res, 200, {
    // 明文令牌只在建号时返回一次；已有账号时用 /v1/token/rotate 取回
    accessToken: plainToken,
    ...publicAccountView(updated),
    creditedCny: record.amountCny,
  });
}

export async function handleMe(req, res) {
  const { account, reason } = authenticateRequest(req);
  if (!account) {
    sendError(res, reason === "account_suspended" ? 403 : 401, reason, "令牌无效或已失效");
    return;
  }
  sendJson(res, 200, publicAccountView(account));
}

export async function handleTokenRotate(req, res, body) {
  const oldPlain = String(body?.accessToken ?? "").trim();
  if (!oldPlain) {
    sendError(res, 400, "invalid_request", "缺少 accessToken");
    return;
  }
  const oldHash = hashToken(oldPlain);
  const account = store.accountByTokenHash(oldHash);
  if (!account) {
    sendError(res, 401, "invalid_token", "令牌无效");
    return;
  }
  if (account.status !== "active") {
    sendError(res, 403, "account_suspended", "账号已停用");
    return;
  }

  const { plain, hash } = generateToken();
  store.replaceTokenHash(account.id, oldHash, hash);
  store.updateAccount(account.id, { tokenPrefix: tokenPrefixOf(plain) });
  store.scheduleSave();

  sendJson(res, 200, {
    accessToken: plain,
    ...publicAccountView(store.account(account.id)),
    remainingSeconds: remainingSeconds(account),
  });
}

export { config };
