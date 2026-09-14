import { config } from "./config.js";

export function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body),
    "access-control-allow-origin": config.allowedOrigin,
    "access-control-allow-headers": "authorization,content-type,x-admin-token,x-device-id",
    "access-control-allow-methods": "GET,POST,OPTIONS",
    "cache-control": "no-store",
  });
  res.end(body);
}

export function sendError(res, status, code, message) {
  sendJson(res, status, { error: code, message });
}

export async function readJson(req, limitBytes = 64 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > limitBytes) {
        reject(Object.assign(new Error("payload too large"), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => {
      const text = Buffer.concat(chunks).toString("utf8").trim();
      if (!text) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(text));
      } catch {
        reject(Object.assign(new Error("invalid json body"), { status: 400 }));
      }
    });
    req.on("error", reject);
  });
}

export function clientIp(req) {
  const fwd = req.headers["x-forwarded-for"];
  if (typeof fwd === "string" && fwd.length > 0) {
    return fwd.split(",")[0].trim();
  }
  return req.socket?.remoteAddress || null;
}

export function deviceIdOf(req) {
  const raw = req.headers["x-device-id"];
  if (typeof raw !== "string") return null;
  const cleaned = raw.trim().replace(/[^A-Za-z0-9_.:-]/g, "").slice(0, 80);
  return cleaned || null;
}

/** 极简内存限流：按 key 记录滑动窗口内的次数。单进程够用，多实例请换 Redis。 */
const buckets = new Map();

export function rateLimit(key, max, windowMs) {
  const now = Date.now();
  let bucket = buckets.get(key);
  if (!bucket || now - bucket.start >= windowMs) {
    bucket = { start: now, count: 0 };
    buckets.set(key, bucket);
  }
  bucket.count += 1;
  return bucket.count <= max;
}

// 定期清理空桶，避免内存无限增长
setInterval(() => {
  const now = Date.now();
  for (const [key, bucket] of buckets) {
    if (now - bucket.start > 10 * 60 * 1000) buckets.delete(key);
  }
}, 60 * 1000).unref?.();

export function publicMessage(err) {
  if (err && err.status && err.status < 500) return err.message;
  return "internal error";
}
