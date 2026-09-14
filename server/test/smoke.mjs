/**
 * 端到端冒烟测试：用一个假的 OpenAI 上游，把整条计费链路跑一遍。
 *
 *   cd server && npm run smoke
 *
 * 覆盖：
 *   · 令牌鉴权与余额门禁
 *   · session.start 强制覆写（防白嫖）
 *   · 事件白名单（session.update 必须被拒）
 *   · 音频偶数对齐校验
 *   · 按秒计费 tick
 *   · 以 usage.seconds 对账、多退少补
 *   · 兑换码 → 建号 → 查余额
 *   · 运营后台接口
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { WebSocketServer, WebSocket } from "ws";

// ---------------------------------------------------------------- 环境
const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "speakmate-smoke-"));
process.env.PORT = "0";
process.env.HOST = "127.0.0.1";
process.env.DATA_DIR = dataDir;
process.env.OPENAI_API_KEY = "sk-test-dummy";
process.env.TOKEN_SALT = "smoke-salt";
process.env.ADMIN_TOKEN = "smoke-admin";
process.env.MARKUP = "3.0";
process.env.VOICE_COST_PER_MIN_USD = "0.05";
process.env.USD_CNY = "7.2";
process.env.MAX_SESSION_SEC = "600";
process.env.MIN_START_BALANCE_SEC = "10";
process.env.API_BASE_UNUSED = "";

// ------------------------------------------------- 假上游（冒充 GPT-Live）
const upstream = new WebSocketServer({ port: 0 });
await new Promise((resolve) => upstream.once("listening", resolve));
const upstreamPort = upstream.address().port;
process.env.UPSTREAM_LIVE_URL = `ws://127.0.0.1:${upstreamPort}`;

const upstreamState = { sessionStart: null, audioBytes: 0, sawClose: false, authOk: false };

upstream.on("connection", (ws, req) => {
  upstreamState.authOk = req.headers.authorization === "Bearer sk-test-dummy";
  ws.on("message", (data) => {
    let evt;
    try {
      evt = JSON.parse(data.toString("utf8"));
    } catch {
      return;
    }
    if (evt.type === "session.start") {
      upstreamState.sessionStart = evt.session;
      ws.send(
        JSON.stringify({
          type: "session.started",
          session: { id: "sess_fake_0001", ...evt.session },
        }),
      );
    } else if (evt.type === "session.input_audio.append") {
      upstreamState.audioBytes += Buffer.from(evt.audio, "base64").length;
    } else if (evt.type === "session.close") {
      upstreamState.sawClose = true;
      ws.send(JSON.stringify({ type: "session.closed", usage: { seconds: 12 }, reason: "close_requested" }));
    }
  });
});

// ---------------------------------------------------------------- 被测系统
const { server, activeRelays } = await import("../src/index.js");
const { config, retailPerSecondMicroUsd } = await import("../src/config.js");
const { store } = await import("../src/store.js");
const { createAccount } = await import("../src/auth.js");
const { creditCny } = await import("../src/wallet.js");

await new Promise((resolve) => {
  if (server.listening) resolve();
  else server.once("listening", resolve);
});
const base = `http://127.0.0.1:${server.address().port}`;
const wsBase = `ws://127.0.0.1:${server.address().port}`;

// ---------------------------------------------------------------- 断言工具
let failures = 0;
let passed = 0;
function check(name, condition, extra = "") {
  if (condition) {
    passed += 1;
    console.log(`  ✓ ${name}`);
  } else {
    failures += 1;
    console.log(`  ✗ ${name}${extra ? `  →  ${extra}` : ""}`);
  }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function openClient(token, deviceId = "dev-smoke") {
  const ws = new WebSocket(`${wsBase}/v1/live/sessions`, {
    headers: { authorization: `Bearer ${token}`, "x-device-id": deviceId },
  });
  const events = [];
  ws.on("message", (d) => {
    try {
      events.push(JSON.parse(d.toString("utf8")));
    } catch {
      /* ignore */
    }
  });
  const waitFor = async (predicate, timeoutMs = 8000) => {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const hit = events.find(predicate);
      if (hit) return hit;
      await sleep(60);
    }
    return null;
  };
  return { ws, events, waitFor };
}

// ================================================================ 1. REST 层
console.log("\n[1] REST / 兑换码 / 运营后台");
{
  const health = await (await fetch(`${base}/health`)).json();
  check("GET /health 返回 ok", health.ok === true);

  const noAuth = await fetch(`${base}/admin/accounts`);
  check("无 X-Admin-Token 的管理请求被拒", noAuth.status === 401);

  const genResp = await fetch(`${base}/admin/redeem-codes`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-admin-token": "smoke-admin" },
    body: JSON.stringify({ count: 2, amountCny: 30, batch: "smoke" }),
  });
  const gen = await genResp.json();
  check("批量生成 2 个兑换码", genResp.status === 200 && gen.codes?.length === 2, JSON.stringify(gen));
  check("兑换码格式为 SM-XXXX-XXXX", /^SM-[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(gen.codes?.[0] || ""), gen.codes?.[0]);

  const badCode = await fetch(`${base}/v1/redeem`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: "SM-ZZZZ-ZZZZ", deviceId: "dev-code-1" }),
  });
  check("不存在的兑换码返回 400 invalid_code", badCode.status === 400);

  const redeemResp = await fetch(`${base}/v1/redeem`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: gen.codes[0], deviceId: "dev-code-1", deviceName: "Smoke Phone" }),
  });
  const redeemed = await redeemResp.json();
  check("首次核销建号并下发令牌", redeemResp.status === 200 && typeof redeemed.accessToken === "string", JSON.stringify(redeemed));
  check("充值后余额 = ¥30 折算", redeemed.balanceMicroUsd === Math.round((30 / 7.2) * 1e6), String(redeemed.balanceMicroUsd));

  const repeat = await fetch(`${base}/v1/redeem`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: gen.codes[0], deviceId: "dev-code-1" }),
  });
  check("同一兑换码重复核销被拒", repeat.status === 409);

  const me = await (await fetch(`${base}/v1/me`, { headers: { authorization: `Bearer ${redeemed.accessToken}` } })).json();
  check("GET /v1/me 可查余额", me.accountId === redeemed.accountId && me.remainingSeconds > 0, JSON.stringify(me));

  const badMe = await fetch(`${base}/v1/me`, { headers: { authorization: "Bearer sm_live_nope" } });
  check("非法令牌返回 401", badMe.status === 401);
}

// ================================================================ 2. 会话计费
console.log("\n[2] 语音会话 · 鉴权与配置覆写");
const accountInfo = createAccount({ label: "smoke-voice", deviceId: "dev-voice" });
const { account, plainToken } = accountInfo;
creditCny(account.id, 30, "purchase", null, { note: "smoke" });
const startBalance = store.account(account.id).balanceMicroUsd;

const client = openClient(plainToken, "dev-voice");
await new Promise((resolve) => client.ws.once("open", resolve));

const ready = await client.waitFor((e) => e.type === "gateway.ready");
check("收到 gateway.ready", !!ready, JSON.stringify(client.events));
check("gateway.ready 带零售价与上限", ready?.retailPerSecondUsd === 0.0025 && ready?.maxSessionSeconds === 600, JSON.stringify(ready));
check("剩余秒数按零售价折算", ready?.remainingSeconds === Math.floor(startBalance / retailPerSecondMicroUsd), `${ready?.remainingSeconds} vs ${Math.floor(startBalance / retailPerSecondMicroUsd)}`);

// 事件白名单
client.ws.send(JSON.stringify({ type: "session.update", event_id: "evt_bad", session: { model: "gpt-4o" } }));
const rejected = await client.waitFor((e) => e.type === "error" && e.error?.code === "event_not_allowed");
check("session.update 被拒绝（防改模型）", !!rejected, JSON.stringify(client.events.slice(-3)));
check("拒绝事件回带 client_event_id", rejected?.client_event_id === "evt_bad");

// session.start：客户端恶意塞模型、非法音色、非法采样率
client.ws.send(
  JSON.stringify({
    type: "session.start",
    event_id: "evt_start",
    session: {
      model: "gpt-6-astra-free-hack",
      instructions: "Speak English.",
      audio: { format: { rate: 48000 }, output: { voice: "evilvoice" } },
      delegation: { type: "client" },
      store: true,
    },
  }),
);

const started = await client.waitFor((e) => e.type === "session.started");
check("收到 session.started", !!started);
check("上行会话真的带上了运营方的 key", upstreamState.authOk === true);

const s = upstreamState.sessionStart || {};
check("model 被强制覆写为 gpt-live-1", s.model === config.liveModel, String(s.model));
check("不支持 model 时也给客户提示", client.events.some((e) => e.type === "gateway.notice" && e.code === "config_overridden"));
check("非法音色回落 marin", s.audio?.output?.voice === "marin", String(s.audio?.output?.voice));
check("非法采样率回落 24000", s.audio?.format?.rate === 24000, String(s.audio?.format?.rate));
check("delegation 被强制覆写为 responses", s.delegation?.type === "responses", JSON.stringify(s.delegation));
check("store 被强制为 false", s.store === false, String(s.store));

// 音频
console.log("\n[3] 音频上行校验");
client.ws.send(JSON.stringify({ type: "session.input_audio.append", audio: Buffer.from([1, 2, 3]).toString("base64") }));
const oddRejected = await client.waitFor((e) => e.type === "error" && e.error?.code === "invalid_audio");
check("奇数长度 PCM 被拒", !!oddRejected);

const pcm = Buffer.alloc(4800); // 100ms @ 24kHz mono PCM16
client.ws.send(JSON.stringify({ type: "session.input_audio.append", audio: pcm.toString("base64") }));
await sleep(300);
check("合法音频被转发到上游", upstreamState.audioBytes === 4800, String(upstreamState.audioBytes));

// 计费 tick
console.log("\n[4] 按秒计费与对账");
const firstUsage = await client.waitFor((e) => e.type === "gateway.usage");
check("建立会话后立刻收到 usage 心跳（0 秒）", firstUsage?.elapsedSeconds === 0, JSON.stringify(firstUsage));
check("usage 含剩余秒数与累计花费", typeof firstUsage?.remainingSeconds === "number" && typeof firstUsage?.costMicroUsd === "number");

const after5s = await client.waitFor((e) => e.type === "gateway.usage" && e.elapsedSeconds >= 5, 12000);
check("5 秒后推送递增的 usage", !!after5s, JSON.stringify(client.events.filter((e) => e.type === "gateway.usage")));
check("已用秒数单调递增", (after5s?.elapsedSeconds || 0) >= 5, String(after5s?.elapsedSeconds));

const balanceAfterTick = store.account(account.id).balanceMicroUsd;
check("tick 已实际扣减余额", balanceAfterTick < startBalance, `${balanceAfterTick} vs ${startBalance}`);
check("扣减金额与秒数一致", startBalance - balanceAfterTick === after5s.elapsedSeconds * retailPerSecondMicroUsd, `差 ${startBalance - balanceAfterTick}，应为 ${after5s.elapsedSeconds * retailPerSecondMicroUsd}`);

client.ws.send(JSON.stringify({ type: "session.close" }));
const closed = await client.waitFor((e) => e.type === "gateway.closed", 9000);
check("收到 gateway.closed 结算单", !!closed, JSON.stringify(client.events.slice(-3)));
check("上游确实收到了 session.close", upstreamState.sawClose === true);
check("以 usage.seconds 为权威值（12 秒）", closed?.voiceSeconds === 12, String(closed?.voiceSeconds));
check("对账金额 = 12 秒 × 零售价", closed?.costMicroUsd === 12 * retailPerSecondMicroUsd, `${closed?.costMicroUsd} vs ${12 * retailPerSecondMicroUsd}`);

const finalBalance = store.account(account.id).balanceMicroUsd;
check("最终余额 = 初始 − 12 秒零售价", finalBalance === startBalance - 12 * retailPerSecondMicroUsd, `${finalBalance} vs ${startBalance - 12 * retailPerSecondMicroUsd}`);
check("结算单 unmetered = false", closed?.unmetered === false);

const ledger = store.readLedgerTail(1000).filter((e) => e.accountId === account.id && e.kind === "voice_usage");
const lastVoice = ledger[ledger.length - 1];
check("流水写入 voice_usage 且标记 final", lastVoice?.meta?.final === true && lastVoice?.meta?.seconds === 12, JSON.stringify(lastVoice?.meta));
check("流水记录了成本用于毛利统计", typeof lastVoice?.meta?.costMicroUsd === "number");

// ================================================================ 5. 余额门禁
console.log("\n[5] 门禁与并发");
{
  const poor = createAccount({ label: "smoke-poor", deviceId: "dev-poor" });
  const poorClient = openClient(poor.plainToken, "dev-poor");
  const terminated = await new Promise((resolve) => {
    poorClient.ws.on("message", (d) => {
      const evt = JSON.parse(d.toString("utf8"));
      if (evt.type === "gateway.session.terminated") resolve(evt);
    });
    setTimeout(() => resolve(null), 4000);
  });
  check("零余额账号连上即被拒", terminated?.reason === "insufficient_balance", JSON.stringify(terminated));
}

// ================================================================ 收尾
client.ws.close();
await sleep(200);
upstream.close();
store.close();
await new Promise((resolve) => server.close(resolve));
fs.rmSync(dataDir, { recursive: true, force: true });

console.log(`\n${"-".repeat(52)}`);
console.log(failures === 0 ? `全部通过：${passed} 项` : `失败 ${failures} 项 / 通过 ${passed} 项`);
console.log(`活跃会话残留：${activeRelays.size}`);
console.log(`${"-".repeat(52)}`);
process.exit(failures === 0 ? 0 : 1);
