import crypto from "node:crypto";
import { config } from "./config.js";
import { store } from "./store.js";

/**
 * 令牌体系。
 *
 * 明文令牌只在两个时刻离开服务器：
 *   1. 兑换码首次核销、为新设备建号时；
 *   2. 主动调用 /v1/token/rotate 轮换时。
 * 其余时间服务端只保存 sha256(salt + token)，库被拖走也无法直接冒用。
 */

const TOKEN_PREFIX = "sm_live_";

export function hashToken(plain) {
  return crypto
    .createHash("sha256")
    .update(`${config.tokenSalt}:${plain}`)
    .digest("hex");
}

export function generateToken() {
  const plain = TOKEN_PREFIX + crypto.randomBytes(24).toString("base64url");
  return { plain, hash: hashToken(plain) };
}

export function tokenPrefixOf(plain) {
  return plain.slice(0, TOKEN_PREFIX.length + 8);
}

/** 兑换码：SM-XXXX-XXXX，去掉易混字符（0/O/1/I/L）。 */
const CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

export function generateRedeemCode() {
  const group = () =>
    Array.from(
      crypto.randomBytes(4),
      (b) => CODE_ALPHABET[b % CODE_ALPHABET.length],
    ).join("");
  return `SM-${group()}-${group()}`;
}

export function hashRedeemCode(code) {
  return crypto
    .createHash("sha256")
    .update(`${config.tokenSalt}:code:${code.trim().toUpperCase()}`)
    .digest("hex");
}

export function createAccount({ label, deviceId, note = "" }) {
  const { plain, hash } = generateToken();
  const id = store.nextId("account", "acc");
  const now = new Date().toISOString();
  const account = store.addAccount({
    id,
    label: label || `device:${(deviceId || "unknown").slice(0, 8)}`,
    deviceId: deviceId || null,
    note,
    tokenHash: hash,
    tokenPrefix: tokenPrefixOf(plain),
    balanceMicroUsd: 0,
    status: "active",
    totalVoiceSeconds: 0,
    totalCostMicroUsd: 0,
    createdAt: now,
    updatedAt: now,
  });
  return { account, plainToken: plain };
}

/**
 * 从 HTTP 请求里取出 Bearer 令牌对应的账号。
 * @returns {{ account: object|null, reason?: string }}
 */
export function authenticateRequest(req, { fromHeader } = {}) {
  const raw = fromHeader ?? req.headers?.authorization ?? req.headers?.Authorization;
  if (!raw || !raw.startsWith("Bearer ")) {
    return { account: null, reason: "missing_token" };
  }
  const plain = raw.slice("Bearer ".length).trim();
  if (!plain) return { account: null, reason: "missing_token" };

  const account = store.accountByTokenHash(hashToken(plain));
  if (!account) return { account: null, reason: "invalid_token" };
  if (account.status !== "active") {
    return { account: null, reason: "account_suspended", raw: account };
  }
  return { account, reason: null };
}

export function isAdminRequest(req) {
  const token = req.headers["x-admin-token"];
  if (!token) return false;
  const a = Buffer.from(String(token));
  const b = Buffer.from(config.adminToken);
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

export { TOKEN_PREFIX };
