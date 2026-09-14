#!/usr/bin/env node
/**
 * 批量生成兑换码。
 *
 *   node scripts/gen-codes.js --count 100 --amount 30 --batch 2026-09-A
 *   node scripts/gen-codes.js --count 10 --amount 9.9 --out codes.txt
 *
 * 明文只打印/写入一次，库里只存 sha256(盐 + 码)。
 */
import fs from "node:fs";
import path from "node:path";
import { config } from "../src/config.js";
import { store } from "../src/store.js";
import { generateRedeemCode, hashRedeemCode } from "../src/auth.js";

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const count = Math.max(1, Number(arg("count", 10)));
const amountCny = Number(arg("amount", 30));
const batch = arg("batch", `batch-${new Date().toISOString().slice(0, 10)}`);
const out = arg("out", null);

if (!Number.isFinite(amountCny) || amountCny <= 0) {
  console.error("--amount 必须是正数（人民币）");
  process.exit(1);
}
if (config.tokenSalt === "change-me-token-salt") {
  console.error("⚠️  TOKEN_SALT 还是默认值。生产环境请先改成随机串，否则换盐会导致所有卡密失效。");
  process.exit(1);
}

store.load();

const codes = [];
for (let i = 0; i < count; i += 1) {
  let code;
  let codeHash;
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
  codes.push(code);
}
store.flush();

console.log(`已生成 ${codes.length} 个兑换码 · 面额 ¥${amountCny} · 批次 ${batch}`);
console.log(`折算可聊时长：约 ${Math.round((amountCny / config.usdCny) / ((config.voiceCostPerMinUsd / 60) * config.markup) / 60)} 分钟/张`);
console.log("");

const text = codes.join("\n");
if (out) {
  const target = path.resolve(process.cwd(), out);
  fs.writeFileSync(target, `${text}\n`, "utf8");
  console.log(`明文已写入 ${target}（请立即转移到卡密系统，不要提交到仓库）`);
} else {
  console.log(text);
}
