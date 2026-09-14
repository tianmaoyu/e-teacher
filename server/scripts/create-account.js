#!/usr/bin/env node
/**
 * 手工开一个账号并打印令牌（用于内部测试 / 白名单用户）。
 *
 *   node scripts/create-account.js --label "测试机" --cny 30
 */
import { config } from "../src/config.js";
import { store } from "../src/store.js";
import { createAccount } from "../src/auth.js";
import { creditCny, publicAccountView } from "../src/wallet.js";

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const label = arg("label", "manual-account");
const amountCny = Number(arg("cny", 0));

store.load();

const { account, plainToken } = createAccount({ label, deviceId: null, note: "created by CLI" });
if (Number.isFinite(amountCny) && amountCny > 0) {
  creditCny(account.id, amountCny, "adjust", null, { by: "cli", note: "initial balance" });
}
store.flush();

const view = publicAccountView(store.account(account.id));
console.log("账号已创建");
console.log(JSON.stringify(view, null, 2));
console.log("");
console.log("access_token（只显示这一次，请立即保存）：");
console.log(plainToken);
console.log("");
console.log(`零售价：$${((config.voiceCostPerMinUsd / 60) * config.markup).toFixed(6)}/秒`);
