import fs from "node:fs";
import path from "node:path";
import { config } from "./config.js";

/**
 * 持久化层。
 *
 * 两个文件：
 *   data/store.json   —— 账号、兑换码、会话索引（可整体重写）
 *   data/ledger.jsonl —— 资金流水，只追加、不修改（append-only）
 *
 * 这是「单机可跑」的默认驱动。生产量级上来后把本文件换成 Postgres 驱动即可，
 * 对外暴露的方法签名保持不变。
 */

const STORE_VERSION = 1;

function emptyStore() {
  return {
    version: STORE_VERSION,
    createdAt: new Date().toISOString(),
    seq: { account: 0, session: 0, ledger: 0, code: 0 },
    accounts: {},
    /** tokenHash -> accountId */
    tokenIndex: {},
    redeemCodes: {},
    /** 最近 N 条会话，完整历史走 ledger 与日志 */
    sessions: [],
  };
}

export class Store {
  constructor(dataDir = config.dataDir) {
    this.dataDir = dataDir;
    this.storeFile = path.join(dataDir, "store.json");
    this.ledgerFile = path.join(dataDir, "ledger.jsonl");
    this.data = emptyStore();
    this._saveTimer = null;
    this._ledgerStream = null;
  }

  load() {
    fs.mkdirSync(this.dataDir, { recursive: true });
    if (fs.existsSync(this.storeFile)) {
      try {
        const parsed = JSON.parse(fs.readFileSync(this.storeFile, "utf8"));
        if (parsed && typeof parsed === "object") {
          this.data = { ...emptyStore(), ...parsed };
          this.data.seq = { ...emptyStore().seq, ...(parsed.seq || {}) };
        }
      } catch (err) {
        const backup = `${this.storeFile}.corrupt-${Date.now()}`;
        fs.renameSync(this.storeFile, backup);
        // eslint-disable-next-line no-console
        console.error(
          `[store] store.json 解析失败，已备份到 ${backup}：${err.message}`,
        );
        this.data = emptyStore();
      }
    }
    this._ledgerStream = fs.createWriteStream(this.ledgerFile, { flags: "a" });
    return this;
  }

  /** 原子写：先写临时文件再 rename，避免进程被杀时留下半个 JSON。 */
  save() {
    const tmp = `${this.storeFile}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2), "utf8");
    fs.renameSync(tmp, this.storeFile);
  }

  /** 合并短时间内的多次写，降低磁盘压力。 */
  scheduleSave(delayMs = 2000) {
    if (this._saveTimer) return;
    this._saveTimer = setTimeout(() => {
      this._saveTimer = null;
      try {
        this.save();
      } catch (err) {
        // eslint-disable-next-line no-console
        console.error(`[store] 落盘失败：${err.message}`);
      }
    }, delayMs);
    if (typeof this._saveTimer.unref === "function") this._saveTimer.unref();
  }

  flush() {
    if (this._saveTimer) {
      clearTimeout(this._saveTimer);
      this._saveTimer = null;
    }
    this.save();
  }

  close() {
    this.flush();
    if (this._ledgerStream) this._ledgerStream.end();
  }

  nextId(kind, prefix) {
    this.data.seq[kind] = (this.data.seq[kind] || 0) + 1;
    return `${prefix}_${this.data.seq[kind].toString(36).padStart(6, "0")}`;
  }

  // ---------------- 账号 ----------------

  accounts() {
    return Object.values(this.data.accounts);
  }

  account(id) {
    return this.data.accounts[id] || null;
  }

  addAccount(record) {
    this.data.accounts[record.id] = record;
    if (record.tokenHash) this.data.tokenIndex[record.tokenHash] = record.id;
    this.scheduleSave();
    return record;
  }

  updateAccount(id, patch) {
    const acc = this.data.accounts[id];
    if (!acc) return null;
    Object.assign(acc, patch, { updatedAt: new Date().toISOString() });
    this.scheduleSave();
    return acc;
  }

  accountByTokenHash(hash) {
    const id = this.data.tokenIndex[hash];
    return id ? this.data.accounts[id] || null : null;
  }

  /** 令牌轮换：旧 hash 立刻从索引摘除。 */
  replaceTokenHash(accountId, oldHash, newHash) {
    delete this.data.tokenIndex[oldHash];
    this.data.tokenIndex[newHash] = accountId;
    this.data.accounts[accountId].tokenHash = newHash;
    this.scheduleSave();
  }

  // ---------------- 兑换码 ----------------

  addRedeemCode(code) {
    this.data.redeemCodes[code.codeHash] = code;
    this.scheduleSave();
    return code;
  }

  redeemCode(codeHash) {
    return this.data.redeemCodes[codeHash] || null;
  }

  // ---------------- 会话 ----------------

  addSession(record) {
    this.data.sessions.unshift(record);
    if (this.data.sessions.length > 500) this.data.sessions.length = 500;
    this.scheduleSave();
    return record;
  }

  updateSession(id, patch) {
    const s = this.data.sessions.find((x) => x.id === id);
    if (!s) return null;
    Object.assign(s, patch);
    this.scheduleSave();
    return s;
  }

  // ---------------- 流水（append-only）----------------

  appendLedger(entry) {
    const record = { ...entry, at: new Date().toISOString() };
    if (this._ledgerStream) {
      this._ledgerStream.write(`${JSON.stringify(record)}\n`);
    }
    return record;
  }

  /** 读取最近 n 条流水（用于 /admin/stats 与对账）。 */
  readLedgerTail(n = 5000) {
    if (!fs.existsSync(this.ledgerFile)) return [];
    const text = fs.readFileSync(this.ledgerFile, "utf8");
    const lines = text.split("\n").filter(Boolean);
    return lines.slice(-n).map((l) => {
      try {
        return JSON.parse(l);
      } catch {
        return null;
      }
    }).filter(Boolean);
  }
}

export const store = new Store();
