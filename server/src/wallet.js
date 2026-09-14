import { store } from "./store.js";
import {
  balanceToSeconds,
  cnyToMicroUsd,
  config,
  retailPer1kTokenMicroUsd,
  retailPerSecondMicroUsd,
  voiceCostPerSecondMicroUsd,
} from "./config.js";

/**
 * 钱包。
 *
 * 所有金额都是**整数微美元**（micro_usd，1e-6 USD）。位运算之外不做浮点累计，
 * 保证「充 30 元 → 聊了 N 秒 → 余额剩多少」永远可以对上账。
 *
 * 流水类型：
 *   purchase      充值（兑换码 / 后台手工）
 *   voice_usage   语音会话扣费
 *   text_usage    文本用量扣费（翻译等）
 *   refund        退还（预估与实耗的差额、上游故障补偿）
 *   adjust        运营手工调整
 */

export const LEDGER_KINDS = [
  "purchase",
  "voice_usage",
  "text_usage",
  "refund",
  "adjust",
];

/**
 * 记一笔账并更新余额。
 * @param {string} accountId
 * @param {number} deltaMicroUsd 正数为充值，负数为扣费
 * @returns {{entry: object, account: object}}
 */
export function applyLedger(accountId, deltaMicroUsd, kind, refId, meta = {}) {
  const account = store.account(accountId);
  if (!account) throw new Error(`account not found: ${accountId}`);

  const before = account.balanceMicroUsd || 0;
  // 余额不允许为负：扣费时最多扣到 0，差额记在 meta.uncovered 里供运营核查。
  const after = Math.max(0, before + deltaMicroUsd);
  const entry = {
    id: store.nextId("ledger", "led"),
    accountId,
    kind,
    amountMicroUsd: deltaMicroUsd,
    balanceBeforeMicroUsd: before,
    balanceAfterMicroUsd: after,
    refId: refId || null,
    meta,
  };

  account.balanceMicroUsd = after;
  account.updatedAt = new Date().toISOString();
  if (kind === "voice_usage" && meta.seconds) {
    account.totalVoiceSeconds = (account.totalVoiceSeconds || 0) + meta.seconds;
  }
  if (deltaMicroUsd < 0) {
    account.totalCostMicroUsd =
      (account.totalCostMicroUsd || 0) + Math.abs(deltaMicroUsd);
  }

  store.appendLedger(entry);
  store.scheduleSave();
  return { entry, account };
}

export function creditCny(accountId, amountCny, kind = "purchase", refId = null, meta = {}) {
  return applyLedger(accountId, cnyToMicroUsd(amountCny), kind, refId, {
    ...meta,
    amountCny,
  });
}

/** 零售口径的可聊秒数（UI 直接展示）。 */
export function remainingSeconds(account) {
  return balanceToSeconds(account?.balanceMicroUsd || 0);
}

/** 语音：N 秒的零售价（微美元）。 */
export function voiceChargeMicroUsd(seconds) {
  return Math.round(Math.max(0, seconds) * retailPerSecondMicroUsd);
}

/** 语音：N 秒的成本（微美元），用于毛利统计。 */
export function voiceCostMicroUsd(seconds) {
  return Math.round(Math.max(0, seconds) * voiceCostPerSecondMicroUsd);
}

/** 文本：token 数的零售价（微美元）。 */
export function textChargeMicroUsd(tokens) {
  return Math.round((Math.max(0, tokens) / 1000) * retailPer1kTokenMicroUsd);
}

export function publicAccountView(account) {
  return {
    accountId: account.id,
    status: account.status,
    balanceMicroUsd: account.balanceMicroUsd || 0,
    remainingSeconds: remainingSeconds(account),
    totalVoiceSeconds: account.totalVoiceSeconds || 0,
    totalCostMicroUsd: account.totalCostMicroUsd || 0,
    tokenPrefix: account.tokenPrefix,
    /** 汇率由服务端下发，客户端调价不需要发版 */
    usdCny: config.usdCny,
    retailPerSecondUsd: retailPerSecondMicroUsd / 1e6,
  };
}

export { retailPerSecondMicroUsd, retailPer1kTokenMicroUsd };
