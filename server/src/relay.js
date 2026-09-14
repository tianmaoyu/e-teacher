import crypto from "node:crypto";
import { WebSocket } from "ws";
import {
  balanceToSeconds,
  config,
  retailPerSecondMicroUsd,
} from "./config.js";
import { store } from "./store.js";
import { applyLedger, voiceChargeMicroUsd, voiceCostMicroUsd } from "./wallet.js";

/**
 * 一次语音会话的中继。一个 LiveRelay 实例 = 一个客户端 WS + 一个上游 WS。
 *
 * 职责顺序：
 *   1. 鉴权与余额门禁（在 index.js 里已完成，这里只管会话）
 *   2. 强制覆写客户端提交的 session 配置（防白嫖，见 DESIGN.md §5.3）
 *   3. 双向透明转发，只放行白名单事件
 *   4. 按秒计费，余额耗尽/超时主动收尾
 *   5. 以 OpenAI 的 usage.seconds 为准对账，多退少补
 */

const ALLOWED_CLIENT_EVENTS = new Set([
  "session.start",
  "session.input_audio.append",
  "session.input_audio.mute",
  "session.input_audio.unmute",
  "session.instructions.append",
  "session.close",
]);

const BILLING_TICK_MS = 5000;
const LOW_BALANCE_THRESHOLD_SEC = 60;
const MAX_AUDIO_APPENDS_PER_SEC = 40;

/** Emma：全双工口语陪练的会话层指令（业务规则留给后端，见 DESIGN.md §6.5）。 */
const DEFAULT_INSTRUCTIONS = [
  "You are Emma, a warm and patient English speaking partner for a Chinese learner.",
  "Speak natural, conversational English at a moderate pace with clear articulation.",
  "Keep your turns short — one or two sentences, and ask at most one question at a time.",
  "Never correct the learner's grammar out loud and never translate into Chinese.",
  "Never switch to another language, even if the learner speaks Chinese.",
  "If the learner pauses or hesitates, wait quietly or offer a gentle encouraging cue.",
  "If the learner goes silent for a long time, ask a simple follow-up question to restart.",
  "Delegate questions that need factual lookup or deeper explanation to the backend.",
].join(" ");

/** 会话上下文（session.start 里的 input）可以带一段历史，但我们不带，保持每节课干净。 */

const nowMs = () => Date.now();

export class LiveRelay {
  /**
   * @param {import("ws").WebSocket} clientWs
   * @param {object} account
   * @param {{ ip?: string, deviceId?: string }} ctx
   */
  constructor(clientWs, account, ctx = {}) {
    this.clientWs = clientWs;
    this.account = account;
    this.ip = ctx.ip || null;
    this.deviceId = ctx.deviceId || null;

    this.state = "awaiting_start"; // awaiting_start | connecting | live | closing | closed
    this.upstream = null;
    this.sessionId = `ses_${crypto.randomBytes(8).toString("hex")}`;
    this.openaiSessionId = null;

    this.startedAtMs = null;          // 上游 session.started 的时刻，计费时钟起点
    this.billedSeconds = 0;           // 已扣费的秒数（累计）
    this.chargedMicroUsd = 0;         // 本次会话累计零售扣费
    this.settled = false;
    this.clientCloseRequested = false;
    this.lowBalanceNoticed = false;

    this.billTimer = null;
    this.startTimeout = null;

    this.audioAppendWindowStart = nowMs();
    this.audioAppendCount = 0;
  }

  log(...args) {
    // eslint-disable-next-line no-console
    console.log(`[relay ${this.sessionId}]`, ...args);
  }

  start() {
    this.bindClient();
    this.send({
      type: "gateway.ready",
      protocolVersion: 1,
      accountId: this.account.id,
      voice: config.voiceDefault,
      audioRate: config.supportedRates[config.supportedRates.length - 1],
      balanceMicroUsd: this.account.balanceMicroUsd,
      remainingSeconds: balanceToSeconds(this.account.balanceMicroUsd),
      retailPerSecondUsd: retailPerSecondMicroUsd / 1e6,
      usdCny: config.usdCny,
      maxSessionSeconds: config.maxSessionSec,
      serverTime: new Date().toISOString(),
    });

    this.startTimeout = setTimeout(() => {
      if (this.state !== "awaiting_start") return;
      this.log("等待 session.start 超时");
      this.sendError("invalid_request_error", "session_start_timeout", "未在超时前收到 session.start");
      this.closeClient();
    }, config.sessionStartTimeoutSec * 1000);
    if (this.startTimeout.unref) this.startTimeout.unref();
  }

  // ------------------------------------------------------------------
  // 客户端 -> 网关
  // ------------------------------------------------------------------

  bindClient() {
    this.clientWs.on("message", (data, isBinary) => {
      if (isBinary) {
        this.sendError("invalid_request_error", "binary_frame_not_allowed", "只接受 JSON 文本帧");
        return;
      }
      let evt;
      try {
        evt = JSON.parse(data.toString("utf8"));
      } catch {
        this.sendError("invalid_request_error", "malformed_json", "无法解析为 JSON");
        return;
      }
      try {
        this.handleClientEvent(evt);
      } catch (err) {
        this.log("处理客户端事件异常", err.message);
        this.sendError("internal_error", "relay_error", err.message);
      }
    });

    this.clientWs.on("close", () => {
      this.log("客户端断开");
      this.teardown("client_closed");
    });

    this.clientWs.on("error", (err) => {
      this.log("客户端连接错误", err.message);
      this.teardown("client_error");
    });
  }

  handleClientEvent(evt) {
    const type = evt?.type;

    if (!ALLOWED_CLIENT_EVENTS.has(type)) {
      this.sendError(
        "invalid_request_error",
        "event_not_allowed",
        `${type ?? "(missing type)"} is not permitted`,
        evt?.event_id,
      );
      return;
    }

    if (type === "session.start") {
      if (this.state !== "awaiting_start") {
        this.sendError(
          "invalid_request_error",
          "session_already_started",
          "session.start 只能发送一次",
          evt.event_id,
        );
        return;
      }
      this.handleSessionStart(evt);
      return;
    }

    if (this.state !== "live" && this.state !== "closing") {
      // 会话未就绪时静默丢弃音频，避免刷错误日志
      if (type !== "session.close") {
        this.sendError(
          "invalid_request_error",
          "session_not_ready",
          "会话尚未就绪",
          evt.event_id,
        );
      }
      return;
    }

    switch (type) {
      case "session.input_audio.append": {
        if (this.state !== "live") return; // 收尾阶段不再接收音频
        if (!this.checkAudioRate()) {
          this.sendError(
            "rate_limit_error",
            "audio_flood",
            "音频上行过于频繁",
            evt.event_id,
          );
          return;
        }
        const buf = decodePcmBase64(evt.audio);
        if (!buf) {
          this.sendError(
            "invalid_request_error",
            "invalid_audio",
            "audio 必须是偶数长度的 base64 原始 PCM",
            evt.event_id,
          );
          return;
        }
        this.forwardUpstream({
          type: "session.input_audio.append",
          audio: buf.toString("base64"),
        });
        return;
      }

      case "session.instructions.append": {
        const content = truncate(evt.content, config.maxClientInstructions);
        this.forwardUpstream({
          type: "session.instructions.append",
          event_id: evt.event_id || randomEventId(),
          content,
        });
        return;
      }

      case "session.input_audio.mute":
      case "session.input_audio.unmute": {
        this.forwardUpstream({ type, event_id: evt.event_id || randomEventId() });
        return;
      }

      case "session.close": {
        this.clientCloseRequested = true;
        this.state = "closing";
        if (this.upstream && this.upstream.readyState === WebSocket.OPEN) {
          this.forwardUpstream({ type: "session.close" });
        } else {
          this.finalize(null, "close_requested");
        }
        return;
      }

      default:
        return;
    }
  }

  checkAudioRate() {
    const t = nowMs();
    if (t - this.audioAppendWindowStart >= 1000) {
      this.audioAppendWindowStart = t;
      this.audioAppendCount = 0;
    }
    this.audioAppendCount += 1;
    return this.audioAppendCount <= MAX_AUDIO_APPENDS_PER_SEC;
  }

  handleSessionStart(evt) {
    this.state = "connecting";
    clearTimeout(this.startTimeout);
    this.startTimeout = null;

    const session = buildServerSession(evt?.session, (msg) =>
      this.send({ type: "gateway.notice", level: "info", code: "config_overridden", message: msg }),
    );

    this.storeSession({
      accountId: this.account.id,
      deviceId: this.deviceId,
      ip: this.ip,
      voice: session.audio.output.voice,
      audioRate: session.audio.format.rate,
      status: "connecting",
    });

    this.log(`连接上游 ${config.upstreamLiveUrl}（voice=${session.audio.output.voice} rate=${session.audio.format.rate}）`);

    const upstream = new WebSocket(config.upstreamLiveUrl, {
      headers: {
        Authorization: `Bearer ${config.openaiApiKey}`,
      },
      // 音频是持续小包，禁用 permessage-deflate 省 CPU
      perMessageDeflate: false,
      handshakeTimeout: 15000,
    });
    this.upstream = upstream;

    upstream.on("open", () => {
      this.log("上游已连接，发送 session.start");
      upstream.send(
        JSON.stringify({
          type: "session.start",
          event_id: evt?.event_id || "event_start",
          session,
        }),
      );
    });

    upstream.on("message", (data) => {
      let evtIn;
      try {
        evtIn = JSON.parse(data.toString("utf8"));
      } catch {
        return;
      }
      this.handleUpstreamEvent(evtIn);
    });

    upstream.on("error", (err) => {
      this.log("上游连接错误", err.message);
      if (this.state !== "live") {
        this.sendError("upstream_error", "upstream_unavailable", `上游连接失败：${err.message}`);
        this.sendNotice("error", "upstream_error", "无法连接语音服务，请稍后重试");
        this.finalize(null, "upstream_error");
      }
    });

    upstream.on("close", (code) => {
      this.log(`上游关闭 code=${code}`);
      if (!this.settled && this.state !== "closed") {
        // 上游掉了我们拿不到 session.closed，按墙钟估算兜底
        this.finalize(null, "upstream_closed");
      }
    });
  }

  // ------------------------------------------------------------------
  // 上游 -> 网关 -> 客户端
  // ------------------------------------------------------------------

  handleUpstreamEvent(evt) {
    const type = evt?.type;

    if (type === "session.started") {
      this.state = "live";
      this.openaiSessionId = evt?.session?.id || null;
      this.startedAtMs = nowMs();
      store.updateSession(this.sessionId, {
        status: "live",
        openaiSessionId: this.openaiSessionId,
        startedAt: new Date(this.startedAtMs).toISOString(),
      });
      this.log(`会话就绪 openai_session=${this.openaiSessionId}`);
      this.startBilling();
      this.forwardToClient(evt);
      return;
    }

    if (type === "session.closed") {
      this.forwardToClient(evt);
      this.finalize(evt, evt?.reason || "close_requested");
      return;
    }

    if (type === "error") {
      this.log("上游返回错误", JSON.stringify(evt).slice(0, 400));
      this.forwardToClient(evt);
      return;
    }

    this.forwardToClient(evt);
  }

  // ------------------------------------------------------------------
  // 计费
  // ------------------------------------------------------------------

  startBilling() {
    this.stopBilling();
    this.billTimer = setInterval(() => this.billingTick(), BILLING_TICK_MS);
    if (this.billTimer.unref) this.billTimer.unref();
    // 立即跑一次，让客户端第一时间看到用量
    this.billingTick();
  }

  stopBilling() {
    if (this.billTimer) {
      clearInterval(this.billTimer);
      this.billTimer = null;
    }
  }

  /** 当前会话已进行的秒数（墙钟估算，抓不到 usage 时兜底）。 */
  estimateSeconds() {
    if (!this.startedAtMs) return 0;
    return Math.max(0, Math.floor((nowMs() - this.startedAtMs) / 1000));
  }

  billingTick() {
    if (this.state !== "live" && this.state !== "closing") return;
    const elapsed = Math.min(this.estimateSeconds(), config.maxSessionSec);
    this.chargeUpTo(elapsed, { partial: true });

    const account = store.account(this.account.id) || this.account;
    this.account = account;
    const remaining = balanceToSeconds(account.balanceMicroUsd);

    this.send({
      type: "gateway.usage",
      elapsedSeconds: elapsed,
      remainingSeconds: remaining,
      balanceMicroUsd: account.balanceMicroUsd,
      costMicroUsd: this.chargedMicroUsd,
      final: false,
    });

    if (remaining <= 0) {
      this.log("余额耗尽，主动收尾");
      this.terminate("insufficient_balance", "余额不足，会话已结束");
      return;
    }
    if (elapsed >= config.maxSessionSec) {
      this.log("达到单次会话上限，主动收尾");
      this.terminate("max_session_seconds", "已达单次会话时长上限");
      return;
    }
    if (remaining < LOW_BALANCE_THRESHOLD_SEC && !this.lowBalanceNoticed) {
      this.lowBalanceNoticed = true;
      this.sendNotice("warning", "low_balance", `剩余约 ${Math.floor(remaining / 60)} 分 ${remaining % 60} 秒`);
    } else if (remaining >= LOW_BALANCE_THRESHOLD_SEC) {
      this.lowBalanceNoticed = false;
    }
  }

  /** 按「秒」增量扣费，只扣差额，保证 tick 与 tick 之间不重复扣。 */
  chargeUpTo(seconds, meta = {}) {
    if (seconds <= this.billedSeconds) return 0;
    const prev = this.billedSeconds;
    const target = voiceChargeMicroUsd(seconds);
    const delta = target - this.chargedMicroUsd;
    const costDelta = Math.max(0, voiceCostMicroUsd(seconds) - voiceCostMicroUsd(prev));
    this.billedSeconds = seconds;
    if (delta <= 0) return 0;
    applyLedger(this.account.id, -delta, "voice_usage", this.sessionId, {
      seconds,
      deltaSeconds: seconds - prev,
      costMicroUsd: costDelta,
      ...meta,
    });
    this.chargedMicroUsd = target;
    return delta;
  }

  terminate(reason, message) {
    if (this.state === "closed") return;
    this.send({
      type: "gateway.session.terminated",
      reason,
      message: message || reason,
    });
    this.state = "closing";
    if (this.upstream && this.upstream.readyState === WebSocket.OPEN) {
      this.forwardUpstream({ type: "session.close" });
      // 上游若不回 session.closed，5 秒后兜底结算
      setTimeout(() => {
        if (!this.settled) this.finalize(null, reason);
      }, 5000).unref?.();
    } else {
      this.finalize(null, reason);
    }
  }

  /**
   * 结算。以 OpenAI 的 usage.seconds 为权威值，与已扣金额对账，多退少补。
   */
  finalize(closedEvent, fallbackReason) {
    if (this.settled) return;
    this.settled = true;
    this.state = "closed";
    this.stopBilling();
    clearTimeout(this.startTimeout);

    const usageSeconds = closedEvent?.usage?.seconds;
    const authoritative = Number.isFinite(usageSeconds);
    const finalSeconds = authoritative
      ? Math.max(0, Math.floor(usageSeconds))
      : Math.min(this.estimateSeconds(), config.maxSessionSec);

    const target = voiceChargeMicroUsd(finalSeconds);
    const delta = target - this.chargedMicroUsd;
    const prevSeconds = this.billedSeconds;
    const costDelta = Math.max(
      0,
      voiceCostMicroUsd(finalSeconds) - voiceCostMicroUsd(prevSeconds),
    );
    if (delta > 0) {
      applyLedger(this.account.id, -delta, "voice_usage", this.sessionId, {
        seconds: finalSeconds,
        deltaSeconds: finalSeconds - prevSeconds,
        costMicroUsd: costDelta,
        final: true,
        authoritative,
      });
    } else if (delta < 0) {
      applyLedger(this.account.id, -delta, "refund", this.sessionId, {
        reason: "estimated_over_actual",
        estimatedSeconds: prevSeconds,
        actualSeconds: finalSeconds,
      });
    }
    this.chargedMicroUsd = target;
    this.billedSeconds = finalSeconds;

    const account = store.account(this.account.id) || this.account;
    const remaining = balanceToSeconds(account.balanceMicroUsd);
    const costMicroUsd = voiceCostMicroUsd(finalSeconds);

    store.updateSession(this.sessionId, {
      status: "ended",
      endedAt: new Date().toISOString(),
      voiceSeconds: finalSeconds,
      authoritativeUsage: authoritative,
      unmetered: !authoritative,
      chargedMicroUsd: target,
      costMicroUsd,
      closeReason: closedEvent?.reason || fallbackReason || "unknown",
    });

    this.send({
      type: "gateway.usage",
      elapsedSeconds: finalSeconds,
      remainingSeconds: remaining,
      balanceMicroUsd: account.balanceMicroUsd,
      costMicroUsd: target,
      final: true,
    });
    this.send({
      type: "gateway.closed",
      voiceSeconds: finalSeconds,
      costMicroUsd: target,
      remainingSeconds: remaining,
      balanceMicroUsd: account.balanceMicroUsd,
      closeReason: closedEvent?.reason || fallbackReason || "unknown",
      unmetered: !authoritative,
    });

    this.log(
      `结算：${finalSeconds}s 扣费 ${(target / 1e6).toFixed(4)} USD` +
        `（成本 ${(costMicroUsd / 1e6).toFixed(4)}）authoritative=${authoritative}`,
    );

    this.closeUpstream();
    setTimeout(() => this.closeClient(), 150).unref?.();
  }

  // ------------------------------------------------------------------
  // 传输辅助
  // ------------------------------------------------------------------

  send(obj) {
    if (this.clientWs.readyState !== WebSocket.OPEN) return;
    try {
      this.clientWs.send(JSON.stringify(obj));
    } catch {
      /* ignore */
    }
  }

  sendError(type, code, message, clientEventId) {
    this.send({
      type: "error",
      error: { type, code, message },
      ...(clientEventId ? { client_event_id: clientEventId } : {}),
    });
  }

  sendNotice(level, code, message) {
    this.send({ type: "gateway.notice", level, code, message });
  }

  forwardToClient(evt) {
    this.send(evt);
  }

  forwardUpstream(evt) {
    if (!this.upstream || this.upstream.readyState !== WebSocket.OPEN) return;
    try {
      this.upstream.send(JSON.stringify(evt));
    } catch {
      /* ignore */
    }
  }

  closeUpstream() {
    if (!this.upstream) return;
    try {
      if (this.upstream.readyState === WebSocket.OPEN || this.upstream.readyState === WebSocket.CONNECTING) {
        this.upstream.close(1000, "relay finalized");
      }
    } catch {
      /* ignore */
    }
  }

  closeClient() {
    try {
      if (this.clientWs.readyState === WebSocket.OPEN) {
        this.clientWs.close(1000, "session ended");
      }
    } catch {
      /* ignore */
    }
  }

  teardown(reason) {
    this.stopBilling();
    clearTimeout(this.startTimeout);
    if (!this.settled) {
      if (this.state === "awaiting_start" || this.state === "connecting") {
        // 还没开始烧钱，不产生任何流水
        this.settled = true;
        this.state = "closed";
        store.updateSession(this.sessionId, {
          status: "aborted",
          endedAt: new Date().toISOString(),
          voiceSeconds: 0,
          chargedMicroUsd: 0,
          closeReason: reason,
        });
      } else {
        this.finalize(null, reason);
      }
    }
    this.closeUpstream();
  }

  storeSession(extra) {
    store.addSession({
      id: this.sessionId,
      createdAt: new Date().toISOString(),
      ...extra,
    });
  }
}

// ----------------------------------------------------------------------
// 纯函数辅助
// ----------------------------------------------------------------------

/**
 * 覆写客户端提交的会话配置。
 * 这是防止「拿我们的 key 当通用语音助手」的核心闸门。
 */
export function buildServerSession(clientSession, warn = () => {}) {
  const requestedRate = Number(clientSession?.audio?.format?.rate);
  let rate = config.supportedRates[config.supportedRates.length - 1];
  if (Number.isFinite(requestedRate)) {
    if (config.supportedRates.includes(requestedRate)) {
      rate = requestedRate;
    } else {
      warn(`不支持的采样率 ${requestedRate}，已回落 ${rate}`);
    }
  }

  const requestedVoice = String(clientSession?.audio?.output?.voice || "").toLowerCase();
  let voice = config.voiceDefault;
  if (requestedVoice && config.voiceAllowlist.includes(requestedVoice)) {
    voice = requestedVoice;
  } else if (requestedVoice) {
    warn(`未知音色 ${requestedVoice}，已回落 ${voice}`);
  }

  const instructions =
    truncate(clientSession?.instructions, config.maxClientInstructions) ||
    DEFAULT_INSTRUCTIONS;

  return {
    // 客户端提交的 model / delegation / store 一律丢弃
    model: config.liveModel,
    instructions,
    audio: {
      format: { type: "audio/pcm", rate },
      output: { voice },
    },
    delegation: {
      type: "responses",
      responses: {
        model: config.backendModel,
        tool_choice: "auto",
        tools: [],
      },
    },
    store: false,
  };
}

function decodePcmBase64(value) {
  if (typeof value !== "string" || value.length === 0) return null;
  if (value.length > 512 * 1024) return null;
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(value)) return null;
  const buf = Buffer.from(value, "base64");
  if (buf.length === 0 || buf.length % 2 !== 0) return null;
  return buf;
}

function truncate(value, max) {
  if (typeof value !== "string") return "";
  const trimmed = value.trim();
  return trimmed.length > max ? trimmed.slice(0, max) : trimmed;
}

function randomEventId() {
  return `evt_${crypto.randomBytes(6).toString("hex")}`;
}

export { ALLOWED_CLIENT_EVENTS, DEFAULT_INSTRUCTIONS };
