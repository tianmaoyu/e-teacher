import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const serverRoot = path.resolve(here, "..");

/** 极简 .env 读取：只支持 KEY=VALUE、# 注释、可选引号。不覆盖已存在的环境变量。 */
function loadDotEnv(file) {
  if (!fs.existsSync(file)) return;
  const text = fs.readFileSync(file, "utf8");
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const eq = line.indexOf("=");
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    if (process.env[key] === undefined) process.env[key] = value;
  }
}

loadDotEnv(path.join(serverRoot, ".env"));

const num = (key, fallback) => {
  const v = process.env[key];
  if (v === undefined || v === "") return fallback;
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
};
const str = (key, fallback) => {
  const v = process.env[key];
  return v === undefined || v === "" ? fallback : v;
};

const dataDir = path.resolve(serverRoot, str("DATA_DIR", "./data"));

export const config = {
  serverRoot,
  port: num("PORT", 8787),
  host: str("HOST", "127.0.0.1"),
  dataDir,

  // 上游
  openaiApiKey: str("OPENAI_API_KEY", ""),
  upstreamLiveUrl: str("UPSTREAM_LIVE_URL", "wss://api.openai.com/v1/live/sessions"),
  upstreamApiBase: str("UPSTREAM_API_BASE", "https://api.openai.com/v1"),
  liveModel: str("LIVE_MODEL", "gpt-live-1"),
  backendModel: str("BACKEND_MODEL", "gpt-5.6-luna"),
  translateModel: str("TRANSLATE_MODEL", "gpt-5.6-luna"),

  // 计费
  voiceCostPerMinUsd: num("VOICE_COST_PER_MIN_USD", 0.05),
  backendCostPer1kTokUsd: num("BACKEND_COST_PER_1K_TOK_USD", 0.0008),
  markup: num("MARKUP", 3.0),
  usdCny: num("USD_CNY", 7.2),

  // 风控
  minStartBalanceSec: num("MIN_START_BALANCE_SEC", 30),
  maxSessionSec: num("MAX_SESSION_SEC", 1800),
  maxConcurrentPerAccount: num("MAX_CONCURRENT_PER_ACCOUNT", 1),
  maxClientInstructions: num("MAX_CLIENT_INSTRUCTIONS", 2000),
  sessionStartTimeoutSec: num("SESSION_START_TIMEOUT_SEC", 20),

  // 运营
  adminToken: str("ADMIN_TOKEN", "change-me-admin-token"),
  tokenSalt: str("TOKEN_SALT", "change-me-token-salt"),
  allowedOrigin: str("ALLOWED_ORIGIN", "*"),

  // GPT-Live-1 官方音色（白名单）
  voiceDefault: "marin",
  voiceAllowlist: [
    "marin",
    "quartz",
    "ripple",
    "vesper",
    "willow",
    "stone",
    "gleam",
    "meridian",
    "bossa",
    "tempo",
    "beacon",
    "delta",
    "cinder",
  ],

  // 音频采样率白名单（GPT-Live 支持 16k / 24k PCM16）
  supportedRates: [16000, 24000],
};

// ---------------------------------------------------------------
// 派生定价（全部转成「整数微美元」，杜绝浮点误差）
// ---------------------------------------------------------------

/** 语音成本：微美元 / 秒 */
export const voiceCostPerSecondMicroUsd = Math.round(
  (config.voiceCostPerMinUsd / 60) * 1e6,
);

/** 语音零售：微美元 / 秒 */
export const retailPerSecondMicroUsd = Math.round(
  (config.voiceCostPerMinUsd / 60) * config.markup * 1e6,
);

/** 文本零售：微美元 / 1K tokens */
export const retailPer1kTokenMicroUsd = Math.round(
  config.backendCostPer1kTokUsd * config.markup * 1e6,
);

/** 文本成本：微美元 / 1K tokens */
export const costPer1kTokenMicroUsd = Math.round(
  config.backendCostPer1kTokUsd * 1e6,
);

/** 人民币金额 → 微美元 */
export const cnyToMicroUsd = (cny) => Math.round((cny / config.usdCny) * 1e6);

/** 微美元 → 零售口径剩余秒数 */
export const balanceToSeconds = (microUsd) =>
  retailPerSecondMicroUsd > 0
    ? Math.floor(Math.max(0, microUsd) / retailPerSecondMicroUsd)
    : 0;

export function validateConfig() {
  const problems = [];
  if (!config.openaiApiKey || config.openaiApiKey.includes("xxxx")) {
    problems.push("OPENAI_API_KEY 未配置（.env）");
  }
  if (config.adminToken === "change-me-admin-token") {
    problems.push("ADMIN_TOKEN 仍是默认值，请务必更换");
  }
  if (config.tokenSalt === "change-me-token-salt") {
    problems.push("TOKEN_SALT 仍是默认值，上线前请务必更换");
  }
  return problems;
}
