#!/usr/bin/env node
/**
 * SpeakMate 本地模拟网关 —— 用于在没有 OpenAI Key 的情况下调 UI。
 *
 * 它实现与 docs/PROTOCOL.md 一致的那部分协议：
 *   POST /v1/redeem      任意兑换码都能激活，下发固定令牌
 *   GET  /v1/me          固定余额
 *   POST /v1/translate   用内置词表返回中文（演示双语字幕）
 *   WS   /v1/live/sessions
 *       · 回 gateway.ready / session.started
 *       · 每 5 秒推 gateway.usage（余额、花费、剩余秒数会动起来）
 *       · 收到上行音频就「假装听懂了」，回一段学员字幕 + 老师回应
 *       · session.close 后回 gateway.closed 结算单
 *
 * 用法：
 *   cd server && node tools/mock-gateway.mjs          # 默认 127.0.0.1:8099
 *   node tools/mock-gateway.mjs --port 9000
 *
 * 然后在 App 的「设置 → 服务器」里填 http://127.0.0.1:8099，
 * 兑换码随便填（例如 SM-DEMO-0001）。
 */

import { createServer } from "node:http";
import { WebSocketServer } from "ws";

const argv = process.argv.slice(2);
const portArgIndex = argv.indexOf("--port");
const PORT = portArgIndex >= 0 ? Number(argv[portArgIndex + 1]) : 8099;
const HOST = "127.0.0.1";

const RETAIL_PER_SECOND_MICRO_USD = 2500; // $0.0025/s ≈ ¥1.08/分钟
const START_BALANCE_MICRO_USD = 41_500_000; // ¥30 左右
const MAX_SESSION_SEC = 1800;

/** 演示对话脚本：老师每轮一句，学员的「识别结果」是固定的假台词。 */
const SCRIPT = [
  {
    learner: "I go to the park with my friend yesterday and we play basketball.",
    coach: "That sounds like a good time! How long did you two play for?",
  },
  {
    learner: "About two hour. Then we eat noodles near the park.",
    coach: "Nice — two hours is a solid workout. What kind of noodles did you have?",
  },
  {
    learner: "Beef noodles. The soup is very delicious.",
    coach: "Beef noodle soup is my favourite too. Would you go back there again?",
  },
  {
    learner: "Yes, I will go there next weekend with my brother.",
    coach: "Great plan. Tell your brother the soup is on me — metaphorically speaking!",
  },
];

/** 演示词表：让双语字幕看起来是真的翻过。 */
const TRANSLATIONS = new Map([
  ["That sounds like a good time! How long did you two play for?", "听起来很开心！你们打了多久？"],
  ["Nice — two hours is a solid workout. What kind of noodles did you have?", "不错，两小时算是很扎实的运动量了。你们吃的什么面？"],
  ["Beef noodle soup is my favourite too. Would you go back there again?", "牛肉面也是我的最爱。你还会再去那家吗？"],
  ["Great plan. Tell your brother the soup is on me — metaphorically speaking!", "好计划。跟你弟弟说这顿面我请——比喻意义上的！"],
  ["I go to the park with my friend yesterday and we play basketball.", "我昨天和朋友去了公园，我们打了篮球。"],
  ["About two hour. Then we eat noodles near the park.", "大概两小时。然后我们在公园附近吃了面。"],
  ["Beef noodles. The soup is very delicious.", "牛肉面。汤很好喝。"],
  ["Yes, I will go there next weekend with my brother.", "会的，我下周末和弟弟一起去。"],
]);

const httpServer = createServer((req, res) => {
  const chunks = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", () => {
    const raw = Buffer.concat(chunks).toString("utf8");
    const body = raw ? safeJson(raw) : {};
    const url = new URL(req.url, `http://${req.headers.host}`);

    if (url.pathname === "/v1/redeem" && req.method === "POST") {
      console.log(`[mock] 兑换码核销：${body.code ?? "(空)"} 设备=${body.deviceName ?? "?"}`);
      return sendJson(res, 200, {
        accessToken: "sm_live_demo_token",
        accountId: "acc_demo_0001",
        creditedCny: 30,
        balanceMicroUsd: START_BALANCE_MICRO_USD,
        remainingSeconds: Math.floor(START_BALANCE_MICRO_USD / RETAIL_PER_SECOND_MICRO_USD),
        usdCny: 7.2,
      });
    }

    if (url.pathname === "/v1/me" && req.method === "GET") {
      return sendJson(res, 200, {
        accountId: "acc_demo_0001",
        status: "active",
        balanceMicroUsd: START_BALANCE_MICRO_USD,
        remainingSeconds: Math.floor(START_BALANCE_MICRO_USD / RETAIL_PER_SECOND_MICRO_USD),
        totalVoiceSeconds: 0,
        totalCostMicroUsd: 0,
        usdCny: 7.2,
      });
    }

    if (url.pathname === "/v1/translate" && req.method === "POST") {
      const texts = Array.isArray(body.texts) ? body.texts : [];
      const translations = texts.map((t) => TRANSLATIONS.get(t) ?? `【模拟译文】${t}`);
      return sendJson(res, 200, { translations, costMicroUsd: 0 });
    }

    sendJson(res, 404, { error: "not_found", message: `模拟网关没有实现 ${url.pathname}` });
  });
});

const wss = new WebSocketServer({ server: httpServer, path: "/v1/live/sessions" });

wss.on("connection", (ws, req) => {
  const deviceId = req.headers["x-device-id"] ?? "unknown";
  console.log(`[mock] 会话连接建立 device=${deviceId}`);

  const started = Date.now();
  let closed = false;
  let scriptIndex = 0;
  let turnPlaying = false;
  let waitForLearner = false;
  let current = SCRIPT[0];
  let usageTimer = null;
  let scriptTimer = null;

  const send = (payload) => {
    if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(payload));
  };

  send({
    type: "gateway.ready",
    protocolVersion: 1,
    accountId: "acc_demo_0001",
    voice: "marin",
    audioRate: 24000,
    balanceMicroUsd: START_BALANCE_MICRO_USD,
    remainingSeconds: Math.floor(START_BALANCE_MICRO_USD / RETAIL_PER_SECOND_MICRO_USD),
    retailPerSecondUsd: RETAIL_PER_SECOND_MICRO_USD / 1e6,
    maxSessionSeconds: MAX_SESSION_SEC,
    serverTime: new Date().toISOString(),
  });

  const elapsedSeconds = () => Math.floor((Date.now() - started) / 1000);

  const startUsage = () => {
    usageTimer = setInterval(() => {
      const elapsed = elapsedSeconds();
      const cost = elapsed * RETAIL_PER_SECOND_MICRO_USD;
      send({
        type: "gateway.usage",
        elapsedSeconds: elapsed,
        remainingSeconds: Math.floor((START_BALANCE_MICRO_USD - cost) / RETAIL_PER_SECOND_MICRO_USD),
        balanceMicroUsd: START_BALANCE_MICRO_USD - cost,
        costMicroUsd: cost,
        final: false,
      });
    }, 5000);
  };

  /** 一小段静音帧，用来驱动客户端的播放状态机（界面会切到「老师在说」）。 */
  const sendSilentAudio = (frames) => {
    for (let i = 0; i < frames; i += 1) {
      setTimeout(() => {
        send({ type: "session.output_audio.delta", delta: Buffer.alloc(4800).toString("base64") });
      }, i * 100);
    }
  };

  /** 把一句台词按 120ms 一片流式吐出去，模拟真实转写增量。 */
  const streamTranscript = (type, text, startMs, done) => {
    const chunkSize = 6;
    const chunks = [];
    for (let i = 0; i < text.length; i += chunkSize) chunks.push(text.slice(i, i + chunkSize));
    let i = 0;
    const tick = () => {
      if (i >= chunks.length) return done?.();
      const endMs = startMs + (i + 1) * 450;
      send({ type, delta: chunks[i], start_ms: startMs + i * 450, end_ms: endMs });
      i += 1;
      setTimeout(tick, 120);
    };
    tick();
  };

  /** 一轮对话：老师先回应，再等学员说话。 */
  const playTurn = () => {
    if (closed || turnPlaying) return;
    current = SCRIPT[scriptIndex % SCRIPT.length];
    scriptIndex += 1;
    turnPlaying = true;

    const startMs = scriptIndex * 10_000;
    streamTranscript("session.output_transcript.delta", current.coach, startMs, () => {
      sendSilentAudio(6);
      turnPlaying = false;
      // 之后等学员音频触发下一轮
    });
  };

  ws.on("message", (data) => {
    let event;
    try {
      event = JSON.parse(data.toString());
    } catch {
      return;
    }

    switch (event.type) {
      case "session.start":
        console.log("[mock] 收到 session.start");
        send({ type: "session.started" });
        startUsage();
        scriptTimer = setTimeout(playTurn, 600);
        break;

      case "session.input_audio.append": {
        // 第一条音频到达 → 模拟识别结果，再让老师回应
        if (turnPlaying || waitForLearner) break;
        waitForLearner = true;
        setTimeout(() => {
          streamTranscript("session.input_transcript.delta", current.learner, scriptIndex * 10_000 + 2000, () => {
            setTimeout(() => {
              waitForLearner = false;
              playTurn();
            }, 700);
          });
        }, 900);
        break;
      }

      case "session.close": {
        closed = true;
        if (usageTimer) clearInterval(usageTimer);
        if (scriptTimer) clearTimeout(scriptTimer);
        const voiceSeconds = elapsedSeconds();
        const cost = voiceSeconds * RETAIL_PER_SECOND_MICRO_USD;
        send({
          type: "gateway.closed",
          voiceSeconds,
          costMicroUsd: cost,
          remainingSeconds: Math.floor((START_BALANCE_MICRO_USD - cost) / RETAIL_PER_SECOND_MICRO_USD),
          balanceMicroUsd: START_BALANCE_MICRO_USD - cost,
          closeReason: "close_requested",
          unmetered: false,
        });
        setTimeout(() => ws.close(), 300);
        break;
      }

      default:
        break;
    }
  });

  ws.on("close", () => {
    closed = true;
    if (usageTimer) clearInterval(usageTimer);
    if (scriptTimer) clearTimeout(scriptTimer);
    console.log(`[mock] 会话关闭，时长 ${elapsedSeconds()}s`);
  });
});

function safeJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return {};
  }
}

function sendJson(res, status, payload) {
  const body = Buffer.from(JSON.stringify(payload));
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": body.length,
  });
  res.end(body);
}

httpServer.listen(PORT, HOST, () => {
  console.log(`SpeakMate 模拟网关已启动：`);
  console.log(`  REST     http://${HOST}:${PORT}/v1/*`);
  console.log(`  WS       ws://${HOST}:${PORT}/v1/live/sessions`);
  console.log(`  App 设置页填 http://${HOST}:${PORT}，兑换码随便填（如 SM-DEMO-0001）`);
});
