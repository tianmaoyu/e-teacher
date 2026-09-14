import http from "node:http";
import { WebSocketServer, WebSocket } from "ws";
import { balanceToSeconds, config, validateConfig } from "./config.js";
import { store } from "./store.js";
import { authenticateRequest, isAdminRequest } from "./auth.js";
import { LiveRelay } from "./relay.js";
import { handleMe, handleRedeem, handleTokenRotate } from "./routes/account.js";
import { handleAdmin } from "./routes/admin.js";
import { handleTranslate } from "./routes/translate.js";
import {
  clientIp,
  deviceIdOf,
  readJson,
  sendError,
  sendJson,
} from "./http-util.js";

/**
 * SpeakMate 网关入口。
 *
 * 两条通道：
 *   HTTP  —— 兑换码、账户查询、翻译、运营后台
 *   WS    —— /v1/live/sessions，鉴权后交给 LiveRelay 做计费中继
 */

const startedAt = Date.now();

/** accountId -> Set<LiveRelay>，用于并发门禁与优雅关停 */
const activeRelays = new Map();

function registerRelay(relay) {
  const accountId = relay.account.id;
  let set = activeRelays.get(accountId);
  if (!set) {
    set = new Set();
    activeRelays.set(accountId, set);
  }
  set.add(relay);
  return set.size;
}

function unregisterRelay(relay) {
  const accountId = relay.account.id;
  const set = activeRelays.get(accountId);
  if (!set) return;
  set.delete(relay);
  if (set.size === 0) activeRelays.delete(accountId);
}

function activeCount(accountId) {
  const set = activeRelays.get(accountId);
  return set ? set.size : 0;
}

// ----------------------------------------------------------------------
// HTTP
// ----------------------------------------------------------------------

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  const path = url.pathname;

  if (req.method === "OPTIONS") {
    res.writeHead(204, {
      "access-control-allow-origin": config.allowedOrigin,
      "access-control-allow-headers": "authorization,content-type,x-admin-token,x-device-id",
      "access-control-allow-methods": "GET,POST,OPTIONS",
      "access-control-max-age": "86400",
    });
    res.end();
    return;
  }

  try {
    if (path === "/health") {
      sendJson(res, 200, {
        ok: true,
        uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
        activeSessions: [...activeRelays.values()].reduce((a, s) => a + s.size, 0),
        accounts: store.accounts().length,
        version: "1.0.0",
      });
      return;
    }

    if (path === "/v1/redeem" && req.method === "POST") {
      await handleRedeem(req, res, await readJson(req));
      return;
    }

    if (path === "/v1/me" && req.method === "GET") {
      await handleMe(req, res);
      return;
    }

    if (path === "/v1/token/rotate" && req.method === "POST") {
      await handleTokenRotate(req, res, await readJson(req));
      return;
    }

    if (path === "/v1/translate" && req.method === "POST") {
      const { account, reason } = authenticateRequest(req);
      if (!account) {
        sendError(res, reason === "account_suspended" ? 403 : 401, reason, "令牌无效或已失效");
        return;
      }
      await handleTranslate(req, res, account, await readJson(req));
      return;
    }

    if (path.startsWith("/admin/")) {
      if (!isAdminRequest(req)) {
        sendError(res, 401, "unauthorized", "缺少或错误的 X-Admin-Token");
        return;
      }
      const segments = path.replace(/^\/admin\//, "").split("/").filter(Boolean);
      const body = ["POST", "PUT", "PATCH"].includes(req.method) ? await readJson(req) : {};
      await handleAdmin(req, res, body, segments);
      return;
    }

    sendError(res, 404, "not_found", `未知路径 ${path}`);
  } catch (err) {
    const status = err?.status && err.status < 500 ? err.status : 500;
    // eslint-disable-next-line no-console
    console.error(`[http] ${req.method} ${path} 失败：`, err);
    sendError(res, status, status === 500 ? "internal_error" : "bad_request", err.message);
  }
});

// ----------------------------------------------------------------------
// WebSocket：/v1/live/sessions
// ----------------------------------------------------------------------

const wss = new WebSocketServer({ noServer: true, perMessageDeflate: false });

server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url, "http://localhost");

  if (url.pathname !== "/v1/live/sessions") {
    rejectUpgrade(socket, 404, "Not Found");
    return;
  }

  const ip = clientIp(req);
  const deviceId = deviceIdOf(req);
  const { account, reason } = authenticateRequest(req);

  wss.handleUpgrade(req, socket, head, (ws) => {
    if (!account) {
      denySession(ws, reason === "account_suspended" ? "account_suspended" : "invalid_token",
        reason === "invalid_token" ? "令牌无效，请在 App 内重新兑换" : "账号已被停用");
      return;
    }

    const remaining = balanceToSeconds(account.balanceMicroUsd);
    if (remaining < config.minStartBalanceSec) {
      denySession(ws, "insufficient_balance", "余额不足，请先充值");
      return;
    }

    if (activeCount(account.id) >= config.maxConcurrentPerAccount) {
      denySession(ws, "concurrent_limit", "已有进行中的会话，请先结束它");
      return;
    }

    const relay = new LiveRelay(ws, account, { ip, deviceId });
    registerRelay(relay);
    ws.on("close", () => unregisterRelay(relay));
    ws.on("error", () => unregisterRelay(relay));
    relay.start();
  });
});

/** 建连即拒：用一次正常的 WS 生命周期把原因说清楚，客户端好展示。 */
function denySession(ws, reason, message) {
  try {
    ws.send(JSON.stringify({ type: "gateway.session.terminated", reason, message }));
  } catch {
    /* ignore */
  }
  setTimeout(() => {
    try {
      ws.close(4403, reason);
    } catch {
      /* ignore */
    }
  }, 80).unref?.();
}

function rejectUpgrade(socket, status, text) {
  socket.write(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\n\r\n`);
  socket.destroy();
}

// ----------------------------------------------------------------------
// 启动 / 关停
// ----------------------------------------------------------------------

store.load();

const problems = validateConfig();
if (problems.length) {
  // eslint-disable-next-line no-console
  console.warn("[config] 需要注意：\n  - " + problems.join("\n  - "));
}

server.listen(config.port, config.host, () => {
  // eslint-disable-next-line no-console
  console.log(
    [
      "SpeakMate gateway 已启动",
      `  HTTP      http://${config.host}:${config.port}`,
      `  WS        ws://${config.host}:${config.port}/v1/live/sessions`,
      `  上游      ${config.upstreamLiveUrl}（${config.liveModel} / 后端 ${config.backendModel}）`,
      `  零售价    $${((config.voiceCostPerMinUsd / 60) * config.markup).toFixed(6)}/秒` +
        `  ≈ ¥${(((config.voiceCostPerMinUsd / 60) * config.markup * config.usdCny) * 60).toFixed(2)}/分钟`,
      `  数据目录  ${config.dataDir}`,
    ].join("\n"),
  );
});

let shuttingDown = false;
for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    if (shuttingDown) return;
    shuttingDown = true;
    // eslint-disable-next-line no-console
    console.log(`\n收到 ${signal}，正在关停…`);
    for (const set of activeRelays.values()) {
      for (const relay of set) {
        try {
          relay.terminate("server_shutdown", "服务端维护，会话已结束");
        } catch {
          /* ignore */
        }
      }
    }
    store.close();
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 3000).unref?.();
  });
}

export { server, activeRelays };
