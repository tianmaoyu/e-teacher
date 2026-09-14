# SpeakMate 网关协议（App ↔ Gateway ↔ OpenAI）

版本：`1`
本文件是 App 与网关之间的**唯一接口契约**。任何一方改动必须先改这里。

实现方：Android `session/SessionHub.kt`、iOS `ios/Sources/Session/AppModel.swift`。
两端状态机、事件名、字幕切片与计费展示口径完全一致。

---

## 1. 传输

| 项 | 值 |
|---|---|
| 语音会话 | `wss://<gateway-host>/v1/live/sessions` |
| REST | `https://<gateway-host>/v1/*` |
| 鉴权 | `Authorization: Bearer <access_token>` |
| 设备标识 | `X-Device-Id: <安装级 UUID>` |
| 编码 | 文本帧为 UTF-8 JSON；音频以 base64 内嵌在 JSON 里 |

所有 `wss` 消息都是 **JSON 文本帧**，不使用二进制帧。

---

## 2. 握手时序

```
App                                    Gateway                        OpenAI
 │                                        │                              │
 │── WSS connect ────────────────────────▶│                              │
 │   Authorization: Bearer <token>        │                              │
 │   X-Device-Id: <uuid>                  │                              │
 │                                        │─ 校验令牌 / 余额 / 并发 ──    │
 │◀─ gateway.ready ───────────────────────│                              │
 │                                        │── WSS connect ──────────────▶│
 │                                        │   Bearer <OPENAI_API_KEY>    │
 │── session.start ──────────────────────▶│                              │
 │                                        │── session.start（覆写后）───▶│
 │                                        │◀─ session.started ───────────│
 │◀─ session.started ─────────────────────│                              │
 │── session.input_audio.append ─────────▶│─────────────────────────────▶│
 │◀─ session.output_audio.delta ──────────│◀─────────────────────────────│
 │◀─ session.output_transcript.delta ─────│◀─────────────────────────────│
 │◀─ gateway.usage（每 5s）────────────────│                              │
 │                                        │                              │
 │── session.close ──────────────────────▶│── session.close ────────────▶│
 │                                        │◀─ session.closed ────────────│
 │◀─ gateway.closed ──────────────────────│                              │
 │                                        │  （WS 关闭）                  │
```

---

## 3. 网关额外推送的事件

网关会在 OpenAI 事件流中**穿插注入**以下 `gateway.*` 事件。App 必须忽略
不认识的事件类型，以保证前向兼容。

### 3.1 `gateway.ready`

连接鉴权通过、即将建上游会话时立即推送。

```json
{
  "type": "gateway.ready",
  "protocolVersion": 1,
  "accountId": "acc_7f3a91",
  "voice": "marin",
  "audioRate": 24000,
  "balanceMicroUsd": 41500000,
  "remainingSeconds": 2766,
  "retailPerSecondUsd": 0.0025,
  "maxSessionSeconds": 1800,
  "serverTime": "2026-09-14T13:52:00.000Z"
}
```

### 3.2 `gateway.usage`

会话进行中每 **5 秒**推一次；`session.closed` 到达后推最后一次（`final: true`）。

```json
{
  "type": "gateway.usage",
  "elapsedSeconds": 137,
  "remainingSeconds": 823,
  "balanceMicroUsd": 41500000,
  "costMicroUsd": 34250,
  "final": false
}
```

`remainingSeconds` 是**零售口径**的剩余可聊秒数，UI 直接展示即可。
`costMicroUsd` 为本次会话累计零售扣费。

### 3.3 `gateway.notice`

非致命提示，不关闭连接。

```json
{ "type": "gateway.notice", "level": "warning", "code": "low_balance", "message": "剩余不足 1 分钟" }
```

| `code` | `level` | 含义 |
|---|---|---|
| `low_balance` | warning | 剩余 < 60 秒 |
| `translation_disabled` | info | 翻译额度不足，已自动关闭中文对照 |
| `unmetered_session` | warning | 上次会话未能对账，已按估算扣费 |

### 3.4 `gateway.session.terminated`

网关**主动**终结会话（不是用户挂断）。发完此事件后网关会替客户端发
`session.close` 并关闭连接。

```json
{ "type": "gateway.session.terminated", "reason": "insufficient_balance",
  "message": "余额不足，会话已结束" }
```

| `reason` | 含义 |
|---|---|
| `insufficient_balance` | 余额耗尽 |
| `max_session_seconds` | 达到单次会话时长上限 |
| `concurrent_limit` | 同账号并发超限（新连接被拒时也会发此事件） |
| `account_suspended` | 账号被停用 |
| `upstream_error` | 上游连接异常 |

### 3.5 `gateway.closed`

会话终结后的结算单。App 用它渲染结算页。**推完即关闭连接。**

```json
{
  "type": "gateway.closed",
  "voiceSeconds": 184,
  "costMicroUsd": 46000,
  "remainingSeconds": 2582,
  "balanceMicroUsd": 41254000,
  "closeReason": "close_requested",
  "unmetered": false
}
```

`closeReason` 直接透传 OpenAI 的 `session.closed.reason`
（`close_requested` / `expired` / `content` / `remote_hangup` / `connection_lost`）。
`unmetered: true` 表示未取得 `usage.seconds`，用客户端墙钟估算入账。

---

## 4. App → 网关：允许发送的事件

**白名单之外的任何客户端事件都会被拒绝**（回 `error`，`error.client_event_id`
指回原消息），且不产生计费。

| 事件 | 网关处理 |
|---|---|
| `session.start` | 覆写 `model`/`delegation`/`store`/`audio.format.rate`，校验 `voice`，截断 `instructions` 后转发 |
| `session.input_audio.append` | 校验 base64 与偶数字节数后转发 |
| `session.input_audio.mute` / `.unmute` | 原样转发 |
| `session.instructions.append` | 截断后转发 |
| `session.close` | 转发并进入结算流程 |

### 4.1 `session.start` 客户端字段约束

```json
{
  "type": "session.start",
  "event_id": "evt_1",
  "session": {
    "instructions": "…≤ 2000 字符…",
    "audio": {
      "format": { "rate": 24000 },
      "output": { "voice": "marin" }
    }
  }
}
```

- `rate` ∈ `{16000, 24000}`，缺省 `24000`
- `voice` ∈ §6 白名单，非法值回落 `marin`（不报错，只回 `gateway.notice`）
- `instructions` 超长则截断，**不报错**
- 客户端提交的 `model`、`delegation`、`store` 一律被丢弃并覆写

---

## 5. REST 接口

### 5.1 `POST /v1/redeem` — 兑换码核销

无鉴权（凭兑换码本身）。同一 `deviceId` 首次核销即建号并下发长期令牌；
之后核销只充值。

请求：
```json
{ "code": "SM-4K7Q-9XZ2", "deviceId": "b2c1…", "deviceName": "Pixel 8" }
```

响应 `200`：
```json
{
  "accessToken": "sm_live_9f2c…",   // 仅首次或轮换时返回，之后为 null
  "accountId": "acc_7f3a91",
  "creditedCny": 30,
  "balanceMicroUsd": 41500000,
  "remainingSeconds": 2766
}
```

错误：

| HTTP | `error` | 含义 |
|---|---|---|
| 400 | `invalid_code` | 兑换码不存在 |
| 409 | `already_redeemed` | 已被其他设备核销 |
| 429 | `rate_limited` | 同 IP 尝试过频 |

### 5.2 `GET /v1/me`

响应：
```json
{
  "accountId": "acc_7f3a91",
  "status": "active",
  "balanceMicroUsd": 41500000,
  "remainingSeconds": 2766,
  "totalVoiceSeconds": 8421,
  "totalCostMicroUsd": 2105000
}
```

### 5.3 `POST /v1/translate` — 双语字幕翻译

请求：
```json
{ "texts": ["I'd like a flat white, please.", "Sure, anything else?"],
  "target": "zh-CN" }
```

响应：
```json
{ "translations": ["我想要一杯馥芮白，谢谢。", "好的，还需要别的吗？"],
  "costMicroUsd": 1200 }
```

约束：单次 ≤ 20 条、每条 ≤ 400 字符；超额返回 `413 payload_too_large`。
文本用量按后端模型 token 成本 × `MARKUP` 计入 `text_usage` 流水。

### 5.4 `POST /v1/token/rotate` — 令牌轮换

设备丢失时使用。用旧令牌换新令牌，旧令牌立即失效。

```json
{ "accessToken": "sm_live_8a71…" }
```

### 5.5 运营接口（`X-Admin-Token`）

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/admin/redeem-codes` | `{"count":100,"amountCny":30,"batch":"2026-09-A"}` → 明文卡密数组（**只返回一次**） |
| `GET` | `/admin/accounts?limit=50&cursor=` | 账号列表 |
| `POST` | `/admin/accounts/:id/topup` | `{"amountCny":30,"note":"微信收款"}` |
| `POST` | `/admin/accounts/:id/suspend` | `{"suspended":true}` |
| `GET` | `/admin/stats?days=7` | 营收 / 成本 / 毛利 / 会话数 / 平均时长 |
| `GET` | `/admin/sessions/live` | 当前在线会话 |

---

## 6. 音色白名单

```
marin（默认）  quartz  ripple  vesper  willow  stone  gleam
meridian       bossa   tempo   beacon  delta   cinder
```

## 7. 错误事件

网关拒绝客户端命令时：

```json
{ "type": "error", "error": { "type": "invalid_request_error",
  "code": "event_not_allowed", "message": "session.update is not permitted" },
  "client_event_id": "evt_9" }
```

| `code` | 触发 |
|---|---|
| `event_not_allowed` | 事件不在白名单 |
| `invalid_audio` | base64 非法或字节数为奇数 |
| `rate_not_supported` | `audio.format.rate` 不在 `{16000,24000}` |
| `balance_exhausted` | 会话中余额归零 |
| `upstream_unavailable` | 上游连接失败 |
