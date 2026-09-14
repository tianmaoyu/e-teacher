import { config } from "../config.js";
import { applyLedger, textChargeMicroUsd } from "../wallet.js";
import { costPer1kTokenMicroUsd } from "../config.js";
import { sendError, sendJson } from "../http-util.js";

/**
 * 双语字幕翻译。
 *
 * 为什么不直接让 GPT-Live 说中文：中文会污染口语环境，模型可能开始用中文回话。
 * 所以翻译独立成 HTTP 端点，由 App 攒批调用，成本也独立计量。
 */

const MAX_ITEMS = 20;
const MAX_CHARS_PER_ITEM = 400;

const SYSTEM_PROMPT = [
  "You are a subtitle translator for an English learning app used by Chinese speakers.",
  "Translate each numbered English line into natural, colloquial Simplified Chinese.",
  "Keep it short enough to read as a subtitle on a phone: do not add explanations.",
  "Preserve the speaker's tone; translate idioms into their idiomatic Chinese equivalent.",
  "Return ONLY a JSON array of strings, in the same order and with the same length as the input.",
  "Do not wrap the JSON in markdown fences.",
].join(" ");

export async function handleTranslate(req, res, account, body) {
  const texts = Array.isArray(body?.texts) ? body.texts : null;
  if (!texts || texts.length === 0) {
    sendError(res, 400, "invalid_request", "texts 必须是非空数组");
    return;
  }
  if (texts.length > MAX_ITEMS) {
    sendError(res, 413, "payload_too_large", `单次最多 ${MAX_ITEMS} 条`);
    return;
  }
  const cleaned = texts.map((t) => String(t ?? "").slice(0, MAX_CHARS_PER_ITEM));

  const totalTokensEstimate = cleaned.reduce((sum, t) => sum + t.length, 0) / 4 + 64;
  const estimatedCharge = textChargeMicroUsd(totalTokensEstimate);
  if ((account.balanceMicroUsd || 0) < estimatedCharge) {
    sendError(res, 402, "insufficient_balance", "余额不足以完成翻译");
    return;
  }

  const input = cleaned.map((t, i) => `[${i + 1}] ${t}`).join("\n");

  let result;
  try {
    result = await callModel(input);
  } catch (err) {
    sendError(res, 502, "upstream_error", `翻译服务不可用：${err.message}`);
    return;
  }

  const translations = normalize(result.text, cleaned);
  const tokens = result.tokens || totalTokensEstimate;
  const charge = textChargeMicroUsd(tokens);

  applyLedger(account.id, -charge, "text_usage", null, {
    tokens,
    items: cleaned.length,
    costMicroUsd: Math.round((tokens / 1000) * costPer1kTokenMicroUsd),
  });

  sendJson(res, 200, { translations, costMicroUsd: charge });
}

async function callModel(input) {
  const responsesAttempt = await tryResponsesApi(input);
  if (responsesAttempt) return responsesAttempt;
  return await tryChatCompletions(input);
}

async function tryResponsesApi(input) {
  const url = `${config.upstreamApiBase}/responses`;
  const resp = await fetch(url, {
    method: "POST",
    headers: {
      authorization: `Bearer ${config.openaiApiKey}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({
      model: config.translateModel,
      instructions: SYSTEM_PROMPT,
      input,
      max_output_tokens: 2000,
    }),
  });
  if (resp.status === 404) return null;
  if (!resp.ok) {
    const text = await resp.text().catch(() => "");
    throw new Error(`responses ${resp.status} ${text.slice(0, 200)}`);
  }
  const payload = await resp.json();
  const text = extractResponsesText(payload);
  if (text == null) throw new Error("responses 返回中找不到文本内容");
  return { text, tokens: usageTokens(payload) };
}

async function tryChatCompletions(input) {
  const url = `${config.upstreamApiBase}/chat/completions`;
  const resp = await fetch(url, {
    method: "POST",
    headers: {
      authorization: `Bearer ${config.openaiApiKey}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({
      model: config.translateModel,
      messages: [
        { role: "system", content: SYSTEM_PROMPT },
        { role: "user", content: input },
      ],
      temperature: 0.2,
    }),
  });
  if (!resp.ok) {
    const text = await resp.text().catch(() => "");
    throw new Error(`chat/completions ${resp.status} ${text.slice(0, 200)}`);
  }
  const payload = await resp.json();
  const text = payload?.choices?.[0]?.message?.content;
  if (typeof text !== "string") throw new Error("chat/completions 返回中没有 content");
  return { text, tokens: usageTokens(payload) };
}

/** 容忍两种响应结构，避免因 API 形态差异直接失败。 */
function extractResponsesText(payload) {
  if (typeof payload?.output_text === "string") return payload.output_text;
  const out = payload?.output;
  if (!Array.isArray(out)) return null;
  const parts = [];
  for (const item of out) {
    if (Array.isArray(item?.content)) {
      for (const c of item.content) {
        if (typeof c?.text === "string") parts.push(c.text);
        else if (typeof c?.output_text === "string") parts.push(c.output_text);
      }
    } else if (typeof item?.text === "string") {
      parts.push(item.text);
    }
  }
  return parts.length ? parts.join("") : null;
}

function usageTokens(payload) {
  const u = payload?.usage;
  if (!u) return 0;
  if (Number.isFinite(u.total_tokens)) return u.total_tokens;
  return (u.input_tokens || 0) + (u.output_tokens || 0);
}

/** 把模型输出解析成与输入等长的数组；解析不出来就降级为逐行切分。 */
function normalize(raw, source) {
  const text = String(raw ?? "").trim();
  const fenced = text.replace(/^```(?:json)?/i, "").replace(/```$/, "").trim();

  try {
    const parsed = JSON.parse(fenced);
    if (Array.isArray(parsed)) {
      return source.map((_, i) => String(parsed[i] ?? ""));
    }
  } catch {
    /* 落到下面的降级路径 */
  }

  const lines = fenced
    .split("\n")
    .map((l) => l.replace(/^\s*(\[\d+\]|\d+[.、)])\s*/, "").trim())
    .filter((l) => l.length > 0);

  if (lines.length === source.length) return lines;
  return source.map((_, i) => lines[i] ?? "");
}
