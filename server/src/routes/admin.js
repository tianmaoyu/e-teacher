import { config, retailPerSecondMicroUsd } from "../config.js";
import { store } from "../store.js";
import { generateRedeemCode, hashRedeemCode } from "../auth.js";
import { applyLedger, creditCny, publicAccountView } from "../wallet.js";
import { sendError, sendJson } from "../http-util.js";

/**
 * 运营后台。全部接口要求 X-Admin-Token。
 *
 * 兑换码明文只在生成时返回一次，库里只存哈希 —— 运营把明文导出到卡密系统后，
 * 即使本服务被拖库也无法反推出卡号。
 */

const MAX_CODES_PER_BATCH = 1000;

export async function handleAdmin(req, res, body, segments) {
  const [resource, id, action] = segments; // /admin/<resource>/<id>/<action>

  if (req.method === "POST" && resource === "redeem-codes") {
    return createRedeemCodes(req, res, body);
  }
  if (req.method === "GET" && resource === "accounts" && !id) {
    return listAccounts(req, res);
  }
  if (req.method === "POST" && resource === "accounts" && id && action === "topup") {
    return topupAccount(req, res, id, body);
  }
  if (req.method === "POST" && resource === "accounts" && id && action === "suspend") {
    return suspendAccount(req, res, id, body);
  }
  if (req.method === "GET" && resource === "stats") {
    return stats(req, res);
  }
  if (req.method === "GET" && resource === "sessions" && id === "live") {
    return liveSessions(req, res);
  }

  sendError(res, 404, "not_found", "未知的管理接口");
}

function createRedeemCodes(req, res, body) {
  const count = Math.max(1, Math.min(MAX_CODES_PER_BATCH, Number(body?.count) || 1));
  const amountCny = Number(body?.amountCny);
  if (!Number.isFinite(amountCny) || amountCny <= 0) {
    sendError(res, 400, "invalid_request", "amountCny 必须是正数");
    return;
  }
  const batch = String(body?.batch ?? "").trim() || `batch-${Date.now().toString(36)}`;

  const plainCodes = [];
  for (let i = 0; i < count; i += 1) {
    let code;
    let codeHash;
    // 极小概率碰撞，循环到不重复为止
    do {
      code = generateRedeemCode();
      codeHash = hashRedeemCode(code);
    } while (store.redeemCode(codeHash));

    store.addRedeemCode({
      id: store.nextId("code", "cd"),
      codeHash,
      codePrefix: code.slice(0, 8),
      amountCny,
      batch,
      revoked: false,
      redeemedByDeviceId: null,
      redeemedByAccountId: null,
      redeemedAt: null,
      createdAt: new Date().toISOString(),
    });
    plainCodes.push(code);
  }
  store.flush();

  sendJson(res, 200, {
    batch,
    amountCny,
    count: plainCodes.length,
    // 只此一次返回明文，请立即导出保存
    codes: plainCodes,
  });
}

function listAccounts(req, res) {
  const url = new URL(req.url, "http://localhost");
  const limit = Math.max(1, Math.min(500, Number(url.searchParams.get("limit")) || 50));
  const accounts = store
    .accounts()
    .sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))
    .slice(0, limit)
    .map((a) => ({
      ...publicAccountView(a),
      label: a.label,
      deviceId: a.deviceId,
      createdAt: a.createdAt,
      updatedAt: a.updatedAt,
    }));
  sendJson(res, 200, { count: accounts.length, accounts });
}

function topupAccount(req, res, id, body) {
  const account = store.account(id);
  if (!account) {
    sendError(res, 404, "account_not_found", `账号 ${id} 不存在`);
    return;
  }
  const amountCny = Number(body?.amountCny);
  if (!Number.isFinite(amountCny) || amountCny === 0) {
    sendError(res, 400, "invalid_request", "amountCny 必须是非零数字（负数表示扣减）");
    return;
  }
  const note = String(body?.note ?? "").trim();
  creditCny(id, amountCny, "adjust", null, { by: "admin", note });
  store.flush();
  sendJson(res, 200, publicAccountView(store.account(id)));
}

function suspendAccount(req, res, id, body) {
  const account = store.account(id);
  if (!account) {
    sendError(res, 404, "account_not_found", `账号 ${id} 不存在`);
    return;
  }
  const suspended = body?.suspended !== false;
  store.updateAccount(id, { status: suspended ? "suspended" : "active" });
  store.flush();
  sendJson(res, 200, publicAccountView(store.account(id)));
}

function stats(req, res) {
  const url = new URL(req.url, "http://localhost");
  const days = Math.max(1, Math.min(90, Number(url.searchParams.get("days")) || 7));
  const since = Date.now() - days * 24 * 3600 * 1000;

  const entries = store.readLedgerTail(200000).filter(
    (e) => new Date(e.at).getTime() >= since,
  );

  const sum = (kind, field = "amountMicroUsd") =>
    entries
      .filter((e) => e.kind === kind)
      .reduce((acc, e) => acc + (e[field] || 0), 0);

  const revenueMicroUsd =
    -sum("voice_usage") - sum("text_usage") + sum("purchase") + sum("adjust");

  const costMicroUsd = entries
    .filter((e) => e.kind === "voice_usage" || e.kind === "text_usage")
    .reduce((acc, e) => acc + (e.meta?.costMicroUsd || 0), 0);

  const voiceSeconds = entries
    .filter((e) => e.kind === "voice_usage")
    .reduce((acc, e) => acc + (e.meta?.deltaSeconds || 0), 0);

  const sessions = store.data.sessions.filter(
    (s) => new Date(s.createdAt).getTime() >= since,
  );
  const ended = sessions.filter((s) => s.status === "ended");

  const byDay = new Map();
  for (const e of entries) {
    const day = e.at.slice(0, 10);
    if (!byDay.has(day)) {
      byDay.set(day, { day, revenueMicroUsd: 0, costMicroUsd: 0, voiceSeconds: 0 });
    }
    const bucket = byDay.get(day);
    if (e.kind === "voice_usage" || e.kind === "text_usage") {
      bucket.revenueMicroUsd += -e.amountMicroUsd;
      bucket.costMicroUsd += e.meta?.costMicroUsd || 0;
      bucket.voiceSeconds += e.meta?.deltaSeconds || 0;
    }
  }

  sendJson(res, 200, {
    windowDays: days,
    totals: {
      revenueMicroUsd,
      costMicroUsd,
      grossMarginMicroUsd: revenueMicroUsd - costMicroUsd,
      grossMarginRatio:
        revenueMicroUsd > 0
          ? Number(((revenueMicroUsd - costMicroUsd) / revenueMicroUsd).toFixed(4))
          : null,
      voiceSeconds,
      sessions: sessions.length,
      avgSessionSeconds:
        ended.length > 0
          ? Math.round(
              ended.reduce((a, s) => a + (s.voiceSeconds || 0), 0) / ended.length,
            )
          : 0,
      accounts: store.accounts().length,
      activeAccounts: store.accounts().filter((a) => a.status === "active").length,
    },
    pricing: {
      retailPerSecondUsd: retailPerSecondMicroUsd / 1e6,
      retailPerMinuteCny: (retailPerSecondMicroUsd * 60) / 1e6 * config.usdCny,
      markup: config.markup,
    },
    byDay: [...byDay.values()].sort((a, b) => (a.day < b.day ? -1 : 1)),
  });
}

function liveSessions(req, res) {
  const live = store.data.sessions.filter((s) => s.status === "live" || s.status === "connecting");
  sendJson(res, 200, {
    count: live.length,
    sessions: live.map((s) => ({
      id: s.id,
      accountId: s.accountId,
      deviceId: s.deviceId,
      startedAt: s.startedAt || s.createdAt,
      status: s.status,
      voice: s.voice,
      audioRate: s.audioRate,
    })),
  });
}

export { applyLedger, publicAccountView };
