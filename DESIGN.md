# SpeakMate · 设计文档

> AI 英语口语教练。Android 原生 App + 自建计费网关，面向付费用户分发。
> 语音层基于 OpenAI **GPT-Live-1**（全双工语音模型，2026-09-10 开放 API）。

---

## 1. 产品定位

| 项 | 内容 |
|---|---|
| 产品名 | SpeakMate |
| 包名 | `com.tianmaoyu.speakmate` |
| 形态 | Android 原生 App（Kotlin + Jetpack Compose） |
| 商业模式 | 用户从运营方**购买接入额度**（兑换码 / 在线充值），App 内使用；语音时长按秒计费 |
| 首版范围 | ① 实时全双工语音对话 ② 中英双语实时字幕 |
| 技术底座 | GPT-Live-1 全双工语音 + 后端计费网关 |

### 为什么首版只做这两件事

GPT-Live-1 最大的差异点是**全双工**——它边听边说，能处理打断、停顿、附和（backchannel）。
OpenAI 公布的第三方数据：语言学习平台 Speak 接入后，系统对学习者的**打断率下降近 80%**。
口语练习最怕的就是"对讲机式"的一问一答，这一点必须在 v1 就打透。

纠错报告、场景扮演等能力在 v2 叠加，不影响本期的架构（见 §9 演进路线）。

---

## 2. 为什么必须有网关（而不是 App 直连 OpenAI）

这是本项目最关键的设计决策。

1. **成本安全**。`gpt-live-1` 语音层按 **$0.05/分钟**计费，且会话一开始就在烧钱。若把
   `OPENAI_API_KEY` 打进 App，任何人反编译即可拿到，一晚可刷出上千美元账单。
2. **模型与指令可控**。官方文档明确：会话的 `model`、`audio.format`、`delegation` 在
   `session.start` 时确定，**不可中途更换**。若客户端能自由填写，用户就能把它当通用
   语音助手白嫖。网关必须**强制覆写**这些字段（见 §5.3）。
3. **计费与对账**。官方 `session.closed` 事件返回 `usage.seconds`，这是唯一的权威用量
   来源。只有网关能看到它。
4. **可运营**。发卡、充值、限流、封禁、毛利分析都需要一个服务端。

> 官方文档也明确建议：`Keep the project API key on that trusted server.`
> 对 browser / mobile 应用推荐 WebRTC + 后端签发会话。本期用
> **WebSocket + 后端反向代理**，因为 GPT-Live 的 primary WebSocket 本身就能承载
> 音频与全量控制事件，链路更短、延迟更低，也省掉 WebRTC 的 SDP 交换与 TURN 成本。

---

## 3. 总体架构

```
┌──────────────────────────────────────────────┐
│              SpeakMate Android App           │
│  • 不持有任何 OpenAI 凭证                     │
│  • 只持有运营方签发的 access_token            │
│  • AudioRecord 24k PCM16 采集 / AudioTrack 播放│
│  • 断线重连、余额倒计时、实时字幕              │
└───────────────────────┬──────────────────────┘
                        │  WSS  /v1/live/sessions
                        │  Authorization: Bearer <access_token>
                        │  X-Device-Id: <uuid>
                        ▼
┌──────────────────────────────────────────────┐
│                  nginx                        │
│  • TLS 终止（Let's Encrypt）                  │
│  • Upgrade/Connection 头透传给网关            │
│  • limit_conn / limit_req 抗滥用              │
│  • access_log → 审计与排查                    │
└───────────────────────┬──────────────────────┘
                        ▼
┌──────────────────────────────────────────────┐
│           speakmate-gateway (Node.js)         │
│  ┌────────┬────────┬────────┬──────────────┐ │
│  │ auth   │ wallet │ relay  │ meter        │ │
│  │令牌校验│预扣/计费│透明转发│与 usage 对账 │ │
│  └────────┴────────┴────────┴──────────────┘ │
│  • 字段白名单 + 强制覆写 session.start         │
│  • 余额耗尽自动 session.close                  │
│  • ledger 流水（不可变追加）                    │
│  • REST: /v1/redeem /v1/translate /admin/*    │
└───────────────────────┬──────────────────────┘
                        │  WSS  wss://api.openai.com/v1/live/sessions
                        │  Authorization: Bearer <OPENAI_API_KEY>
                        ▼
                   OpenAI GPT-Live-1
```

---

## 4. GPT-Live-1 协议要点（实现依据）

来源：`developers.openai.com/api/docs/guides/{live,live-conversations,live-migration,voice-websockets}`

### 4.1 连接与握手

| 项 | 值 |
|---|---|
| 端点 | `wss://api.openai.com/v1/live/sessions`（无 query 参数） |
| 鉴权 | Header `Authorization: Bearer $OPENAI_API_KEY` |
| 可选头 | `OpenAI-Safety-Identifier` |
| 首帧 | 客户端发 `session.start`，配置全放在 `session` 对象里 |
| 就绪 | 收到 `session.started`（含 `session.id`）后才能发音频 |
| 关闭 | 发 `session.close` → 等 `session.closed`（含 `usage.seconds`、`reason`） |

> ⚠️ GPT-Live **不兼容** `v1/realtime` 端点，也不兼容 Chat Completions / Responses 端点。

### 4.2 会话配置

```json
{
  "type": "session.start",
  "event_id": "event_start",
  "session": {
    "model": "gpt-live-1",
    "instructions": "…会话风格与何时委派，上限 16384 tokens…",
    "audio": {
      "format": { "type": "audio/pcm", "rate": 24000 },
      "output": { "voice": "marin" }
    },
    "delegation": { "type": "responses", "responses": { "model": "…", "tools": [] } },
    "store": false
  }
}
```

- `model` / `audio.format` / `audio.output.voice` / `delegation` 启动时确定，**中途不可改**
  （改需要新会话）。`session.update` 只能改同 delegation 模式内的设置，返回 `session.updated`。
- 支持的音频格式：
  - `{"type":"audio/pcm","rate":24000}` — 单声道有符号 16-bit LE，**默认**
  - `{"type":"audio/pcm","rate":16000}`
  - `{"type":"audio/pcmu","rate":8000}` G.711 μ-law ／ `{"type":"audio/pcma","rate":8000}` A-law

### 4.3 事件表（本 App 实际使用的子集）

**客户端 → 服务端**

| 事件 | 关键字段 | 说明 |
|---|---|---|
| `session.start` | `event_id`, `session` | 首帧，配置会话 |
| `session.input_audio.append` | `audio` (base64 原始 PCM) | 音频上行，无 ACK；PCM 字节数必须为**偶数** |
| `session.instructions.append` | `content` | 中途追加指令 |
| `session.input_audio.mute` / `.unmute` | — | 静音麦克风（不中断后端工作） |
| `session.update` | `session` | 同模式内改设置 |
| `session.close` | — | 请求优雅关闭 |

**服务端 → 客户端**

| 事件 | 关键字段 | 说明 |
|---|---|---|
| `session.started` | `session.id`, `session` | 会话就绪 |
| `session.output_audio.delta` | `delta` (base64) | 模型语音，按序入播放队列 |
| `session.input_transcript.delta` | `delta`, `start_ms`, `end_ms` | 用户语音转写片段 |
| `session.output_transcript.delta` | `delta`, `start_ms`, `end_ms` | 模型语音转写片段 |
| `session.instructions.appended` | — | 追加指令成功 |
| `session.updated` | `session` | 更新成功 |
| `session.closed` | `usage.seconds`, `reason` | **计费权威来源**；`reason` ∈ `close_requested`/`expired`/`content`/`remote_hangup`/`connection_lost` |
| `response.event` | 内嵌 Responses 事件 | Responses 委派的后端事件信封 |
| `error` | `error.client_event_id` | 命令被拒 |

### 4.4 三个容易踩的坑

1. **没有"说完了"事件**。GPT-Live 不发 `output_audio.done`，也没有 turn-completed 事件。
   播放进度必须由客户端自己跟踪（本文档 §7.4 的播放队列）。
2. **转写时间戳不是墙钟**。`start_ms`/`end_ms` 是会话时间轴上的区间，用来排序和分段，
   不能当作"这句话已播完"的依据。
3. **打断不取消后端任务**。语音被打断 ≠ 委派的 Responses 任务被取消，两者生命周期独立。

---

## 5. 网关设计

### 5.1 目录

```
server/
  src/
    index.js          HTTP + WS 入口
    config.js         环境变量与定价配置
    store.js          持久化（JSON 文件驱动，接口可换 Postgres）
    auth.js           令牌签发 / 校验（只存哈希）
    wallet.js         余额、预扣、结算、ledger
    relay.js          WS 代理核心（白名单 + 覆写 + 计量）
    routes/
      redeem.js       兑换码核销
      translate.js    双语字幕翻译
      admin.js        运营后台 API
      health.js
  package.json
```

### 5.2 钱包：为什么用「整数微美元」

浮点数在计费上是事故源头。统一用 **`micro_usd`（1e-6 USD，整数）**：

```
语音成本        = 时长(秒) × VOICE_COST_PER_SEC_USD
VOICE_COST_PER_SEC_USD = 0.05 / 60 = 0.000833333…
零售每秒        = 语音成本 × MARKUP
充值时          = 人民币金额 / USD_CNY × 1e6 → micro_usd
展示剩余分钟    = balance_micro_usd / 1e6 / 零售每秒 / 60
```

配置项（`server/.env`）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `VOICE_COST_PER_MIN_USD` | `0.05` | OpenAI 语音层成本 |
| `BACKEND_COST_PER_1K_TOK_USD` | `0.0008` | 后端文本模型成本（翻译等） |
| `MARKUP` | `3.0` | 零售倍率，毛利 = (MARKUP-1)/MARKUP |
| `USD_CNY` | `7.2` | 折算汇率 |
| `MIN_START_BALANCE_SEC` | `30` | 低于此秒数不允许开会话 |
| `MAX_SESSION_SEC` | `1800` | 单次会话硬上限，防挂机 |
| `MAX_CONCURRENT_PER_ACCOUNT` | `1` | 单令牌并发会话数 |

**毛利测算**（MARKUP=3.0）：每分钟成本 $0.05 → 零售 $0.15 ≈ ¥1.08/分钟。
用户 ¥30 可聊约 27 分钟。若售价定为 ¥1.5/分钟，markup≈4.2，毛利 ≈76%。

### 5.3 安全核心：字段白名单 + 强制覆写

网关**不信任**客户端发来的 `session.start`，逐字段处理：

| 字段 | 处理方式 |
|---|---|
| `session.model` | **强制覆写** 为 `gpt-live-1`，客户端提供值一律丢弃 |
| `session.delegation` | **强制覆写** 为服务端配置（含后端模型与工具授权） |
| `session.audio.format` | 只接受 `rate` ∈ `{16000, 24000}`，其余值拒连 |
| `session.audio.output.voice` | 白名单校验（见 §6.4），非法值回落默认音色 |
| `session.store` | **强制 `false`**（隐私 + 不产生可 fork 的存储） |
| `session.instructions` | 放行，但**长度截断**到 `MAX_CLIENT_INSTRUCTIONS`（默认 2000 字符） |

**上行事件白名单**（其余一律回 `error` 且不计费）：

```
session.start
session.input_audio.append
session.input_audio.mute
session.input_audio.unmute
session.instructions.append
session.close
```

> 特别地：`session.update` 被**禁止**，因为它是客户端尝试改模型的唯一路径。

### 5.4 会话生命周期与计费状态机

```
 客户端连接
    │
    ├─ 无/错令牌 ──────────────────▶ 401 关闭
    ├─ 账号被封 ─────────────────┬─▶ gateway.notice{reason:account_suspended} → 关闭
    ├─ 余额 < MIN_START_BALANCE ─┴─▶ gateway.notice{reason:insufficient_balance} → 关闭
    ├─ 已有并发会话 ────────────────▶ gateway.notice{reason:concurrent_limit} → 关闭
    │
    ▼
 建上游 WS（带运营方 OPENAI_API_KEY）
    │
    ▼
 收 session.started
    ├─ 记录 openai_session_id
    ├─ 启动计量定时器（每 5s 扣一次帐，写入内存态）
    └─ 推送 gateway.ready / gateway.usage
    │
    ▼  ┌─────────────── 每秒检测 ───────────────┐
    │  │ 已用秒数 ≥ MAX_SESSION_SEC  → 主动 close │
    │  │ 余额 ≤ 0                    → 主动 close │
    │  └────────────────────────────────────────┘
    ▼
 收 session.closed
    ├─ 以 usage.seconds 为权威值**对账**（覆盖定时器估算）
    ├─ 退还预扣与实耗的差额
    ├─ 写 ledger：voice_usage
    └─ 推 gateway.closed 给客户端
```

**预扣策略**：连接建立时按 `MIN_START_BALANCE_SEC` 冻结，避免"聊到一半余额变负"。
实际结算以 `usage.seconds` 为准，多退少补。

**对账优先级**：`session.closed.usage.seconds` > 本地墙钟估算 > 0。
若连接异常断开拿不到 `session.closed`，退化为墙钟估算并标记 `unmetered`，运营侧人工复核。

### 5.5 REST 接口

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| `POST` | `/v1/redeem` | 兑换码 | 核销兑换码；首次为设备建账号并返回 `access_token` |
| `GET` | `/v1/me` | access_token | 查余额、已用时长、剩余分钟 |
| `POST` | `/v1/translate` | access_token | 整句翻译，用于中文对照字幕；按 token 计费 |
| `POST` | `/admin/redeem-codes` | `X-Admin-Token` | 批量生成兑换码 |
| `GET` | `/admin/accounts` | `X-Admin-Token` | 账号列表 |
| `POST` | `/admin/accounts/:id/topup` | `X-Admin-Token` | 手工充值 / 退款 |
| `GET` | `/admin/stats` | `X-Admin-Token` | 营收、成本、毛利、活跃会话 |

**为什么用兑换码而不是内嵌支付**：微信/支付宝支付需要企业主体与回调验签，属于运营
范畴而非技术范畴。兑换码把支付环节解耦——运营方可以在任何渠道（微店、淘宝、私域）
卖卡，App 只负责核销。二期接入支付时，`POST /admin/.../topup` 换成支付回调即可。

### 5.6 为什么翻译走独立 HTTP 端点

双语字幕需要中文对照，但**不能**让 GPT-Live 输出中文——那会污染英语口语环境
（模型可能开始用中文回话）。因此：

```
GPT-Live 输出英文转写片段 ──▶ App 攒批（~1.2s 或句末标点）
                              ──▶ POST /v1/translate
                              ──▶ 网关记账（文本 token 成本 × MARKUP）
                              ──▶ 回中文，App 贴在英文下方
```

好处：① 英语环境干净；② 翻译成本可独立计量与定价；③ 用户可关闭翻译省钱。

---

## 6. Android App 设计

### 6.1 分层

```
ui/            Compose 界面（激活页 / 通话页 / 设置页）
vm/            ViewModel —— 把 SessionHub 的状态映射成 UI 状态，转发用户动作
session/       SessionHub（单例状态总线）
               ├─ LiveSessionClient  OkHttp WebSocket，事件收发
               ├─ AudioEngine        AudioRecord 采集 + AudioTrack 播放
               └─ CaptionAssembler   转写片段拼装 + 翻译攒批
data/          GatewayApi（REST）、Prefs（EncryptedSharedPreferences）
service/       LiveSessionService（前台服务，锁屏不断）
```

`SessionHub` 是唯一持有实时资源的地方，前台服务负责它的生命周期，
UI 只读 `StateFlow`。这样锁屏、切后台都不影响会话。

### 6.2 音频链路

```
麦克风
  AudioRecord(src=VOICE_COMMUNICATION, 24000Hz, MONO, PCM16)
        │  100ms 一帧 = 4800 字节
        │  AcousticEchoCanceler + NoiseSuppressor（设备支持时启用）
        ▼
  偶数对齐（PCM16 必须整样本）→ base64 → session.input_audio.append
        │
        ▼  WSS
  session.output_audio.delta (base64)
        │
        ▼
  PlaybackQueue（jitter buffer，目标 120ms 预缓冲）
        │
  AudioTrack(MODE_STREAM, 24000Hz, MONO, PCM16) — 阻塞写
```

**采样率降级**：`AudioRecord.getMinBufferSize(24000,…)` 报错时回落到 16000Hz，
并把这个值随 `session.start` 一起上报网关；网关按 §5.3 校验后写进 `audio.format`。
两端必须一致，否则声音会变速变调。

**回声消除**：必须用 `VOICE_COMMUNICATION` 音源并开启 AEC，否则外放时模型会听到
自己说话并自我打断——这是全双工语音 App 最常见的翻车点。

### 6.3 全双工下的 UI 状态

| 状态 | 触发 | 界面 |
|---|---|---|
| `Idle` | 未连接 | 开始按钮 |
| `Connecting` | WS 已建，等 `session.started` | 转圈 + "正在接通…" |
| `Listening` | 已就绪，模型没在说话 | 呼吸圆环（小振幅）+ 麦克风电平 |
| `Speaking` | 收到 `output_audio.delta` 后，播放队列非空 | 圆环随播放能量波动 |
| `Muted` | 用户点静音 | 麦克风划线 |
| `Ended` | `session.closed` / 余额耗尽 | 结算页：时长、花费、剩余 |

**"模型在说话"一律由播放队列状态推导**，不能用收到 delta 判定（官方明确没有
播放完成事件）。

### 6.4 音色

GPT-Live-1 提供 12 个实时音色，默认 `marin`。App 内置可选清单：

```
marin(默认) quartz ripple vesper willow stone gleam
meridian bossa tempo beacon delta cinder
```

网关侧持有白名单，非法值回落默认，防止用户传入未授权音色。

### 6.5 教学指令（instructions）分层

会话指令只写**风格与委派时机**，业务规则放后端（官方推荐做法）：

```
会话层（GPT-Live）：你是 Emma，一位耐心的英语口语陪练。用自然语速的英语对话，
                  一次最多问一个问题，不要纠正语法，不要翻译，不要切换到中文。
                  遇到需要查资料的问题交给后端。

后端层（delegation.responses）：负责资料检索与纵深问答。
```

v1 不写纠错指令——纠错靠"打断感"实现（用户说错时，模型用更自然的说法复述一遍），
这比弹出红字纠正更符合全双工语音的体验。显式纠错留到 v2。

---

## 7. 关键实现细节

### 7.1 断线重连

GPT-Live 会话不可恢复（改配置需新会话）。策略：

| 场景 | 处理 |
|---|---|
| 网络抖动，`session.started` 前断开 | 指数退避重连（1s/2s/4s，最多 3 次），**不扣费** |
| 会话中网络断开 | 本地立即结算已用时长并提示；重连即新会话 |
| 网关主动关闭（余额/超时） | 展示 `gateway.notice.reason`，不回连 |
| 服务端 `connection_lost` | 提示网络问题，允许手动重试 |

### 7.2 余额心跳

网关每 5 秒推 `gateway.usage`：

```json
{ "type": "gateway.usage",
  "elapsedSeconds": 137,
  "remainingSeconds": 823,
  "balanceMicroUsd": 1234567,
  "costMicroUsd": 34250 }
```

App 顶部常驻"剩余 13:43"。剩余 < 60 秒时转橙色并提示。

### 7.3 令牌存储

`androidx.security:security-crypto` 的 `EncryptedSharedPreferences`（AES256-GCM，
密钥由 Android Keystore 托管）。**不写日志、不进备份**：
`android:allowBackup="false"`、`android:dataExtractionRules` 排除。

### 7.4 播放队列

```
收到 delta → 解 base64 → 进 ConcurrentLinkedQueue<ByteArray>
播放线程 → 攒够 120ms 才起播（抗抖动）
         → AudioTrack.write(...,WRITE_BLOCKING)
         → atPlaybackHeadPosition 用于"是否在说话"判定
```

队列空且 `playbackHeadPosition` 追平写入位置 → 切回 `Listening`。

---

## 8. 构建与发布

### 8.1 本地构建

需要 JDK 17+、Android SDK（platform 36 / build-tools 36.1.0）。

```bash
cd android
./gradlew assembleDebug        # 产出 app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # 需 keystore（见下）
```

### 8.2 CI（GitHub Actions）

`.github/workflows/android.yml`：

| 触发 | 动作 |
|---|---|
| `push` 到 `main` 且改动 `android/**` | 构建 debug + release APK，上传 artifact |
| 打 `v*` tag | 额外把 APK 发布到 GitHub Release |
| `pull_request` | 只构建 debug，做编译门禁 |

Release 签名走仓库 Secrets：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、
`KEY_ALIAS`、`KEY_PASSWORD`。**未配置时自动跳过签名**，只产出未签名包，
保证 fork 和 PR 不会因缺密钥而红。

`server/**` 改动由 `.github/workflows/server.yml` 做语法检查与冒烟测试。

### 8.3 nginx

见 `deploy/nginx/speakmate.conf`。关键点：

```nginx
location /v1/live/sessions {
    proxy_pass http://127.0.0.1:8787;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 3600s;      # 长连接不能被默认 60s 掐断
    proxy_buffering off;           # 音频不能进缓冲
    limit_conn speakmate 2;        # 单 IP 并发
}
```

---

## 9. 演进路线

| 版本 | 内容 | 依赖本期架构的什么 |
|---|---|---|
| v1（本期） | 全双工对话 + 双语字幕 + 计费 | — |
| v1.1 | 课后报告（错误清单 / 生词 / CEFR 评估） | 用已攒下的双轨转写，纯客户端 + `/v1/translate` 同源后端模型 |
| v1.2 | 场景角色扮演 + 难度分级 | 换 `session.instructions`，无需改协议 |
| v2.0 | 显式纠错（`session.commentary.append` 插播点评） | 走 `delegation.type: "client"`，网关侧加一条委派处理链路 |
| v2.1 | 在线支付、订阅制 | `POST /admin/accounts/:id/topup` 换成支付回调 |
| v3.0 | iOS / Web 端 | 网关协议不变，仅换客户端；Web 端建议改走 WebRTC |

---

## 10. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 用户反编译提取令牌共享 | 盗刷 | 令牌可吊销、单令牌并发 1、设备指纹绑定、异常用量告警 |
| 挂机刷时长 | 成本失控 | `MAX_SESSION_SEC` 硬上限 + 静音超时自动关闭（v1.1） |
| 上游 OpenAI 涨价或限流 | 毛利倒挂 | 成本与倍率全部配置化，可热调 |
| 拿不到 `session.closed` | 对账缺口 | 墙钟兜底 + `unmetered` 标记 + 人工复核队列 |
| 弱机型采样率不支持 | 无法通话 | 24000 → 16000 自动降级，两端协议对齐 |
| 外放啸叫 / 自我打断 | 体验崩坏 | 强制 `VOICE_COMMUNICATION` + AEC/NS；UI 引导戴耳机 |

---

## 11. 成本与定价示例

| 项 | 数值 |
|---|---|
| 语音层成本 | $0.05 / 分钟 |
| 后端模型（翻译等） | 约 $0.0008 / 1K tokens |
| 折合成本（含翻译） | 约 $0.052 / 分钟 |
| 建议零售（MARKUP=3） | ¥1.08 / 分钟 |
| 建议零售（MARKUP=4.2） | ¥1.51 / 分钟 |
| 单用户 ¥30 可聊 | 约 20–27 分钟 |

实际毛利还受平均会话时长、翻译开启率影响，`/admin/stats` 提供按天成本/营收曲线。
