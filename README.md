# SpeakMate · AI 英语口语教练

> 一个能卖的英语口语 App。Android + iOS 双端原生 + 自建计费网关，语音层跑 OpenAI **GPT-Live-1** 全双工模型。

```
SpeakMate App ──wss──▶ nginx ──▶ speakmate-gateway ──▶ wss://api.openai.com/v1/live/sessions
                                  ├─ 鉴权（你签发的令牌）
                                  ├─ 计费（按秒扣余额 / 余额尽自动断线）
                                  └─ 记账（ledger 流水 + usage 秒数对账）
```

---

## 为什么不能把 API Key 放进 App

1. **成本安全**。`gpt-live-1` 按 **$0.05/分钟**烧钱，会话一开始就计费。Key 打进 APK，
   任何人反编译即可拿走，一晚能刷出四位数美元账单。
2. **模型可控**。GPT-Live 的 `model` / `delegation` / `audio.format` 在 `session.start`
   时锁定。若客户端能自由填写，用户直接把它当通用语音助手白嫖。网关必须**强制覆写**。
3. **可对账**。官方 `session.closed` 返回的 `usage.seconds` 是唯一权威用量，只有服务端看得到。

> OpenAI 官方文档原话：`Keep the project API key on that trusted server.`

---

## 目录结构

```
├── DESIGN.md                 设计文档（架构 / 协议 / 计费 / 风控 / 演进路线）
├── docs/
│   ├── PROTOCOL.md           App ↔ 网关 ↔ OpenAI 的完整接口契约
│   └── DEPLOY.md             从零上线：服务器、证书、发卡、打包
├── android/                  Android App（Kotlin + Compose + OkHttp）
│   └── app/src/main/java/com/tianmaoyu/speakmate/
│       ├── audio/AudioEngine.kt        24kHz 采集 / 播放 / AEC
│       ├── session/SessionHub.kt       会话中枢（状态机 + 字幕 + 计费展示）
│       ├── session/LiveSessionClient.kt OkHttp WebSocket
│       ├── data/GatewayApi.kt          兑换码 / 账户 / 翻译
│       └── ui/                         激活页 / 通话页 / 设置页
├── ios/                      iOS App（SwiftUI + AVAudioEngine）
│   ├── project.yml           XcodeGen 工程描述（不提交 .xcodeproj）
│   └── Sources/
│       ├── Audio/VoiceAudioEngine.swift 采集 / 重采样 / 播放 / Voice Processing 回声消除
│       ├── Session/AppModel.swift       会话中枢（与 Android 一一对应）
│       ├── Session/LiveSocket.swift     URLSessionWebSocketTask
│       ├── Core/GatewayAPI.swift        兑换码 / 账户 / 翻译
│       └── UI/                          激活页 / 通话页 / 设置页
├── server/                   计费网关（Node.js，唯一依赖 ws）
│   ├── src/relay.js          核心：白名单 + 强制覆写 + 按秒计费 + 对账
│   ├── src/wallet.js         整数微美元钱包与 append-only 流水
│   └── test/smoke.mjs        端到端冒烟（自带假上游，40 项断言）
└── deploy/nginx/             反代配置（含 WSS 长连接与限流）
```

---

## 快速开始

### 1. 起网关

```bash
cd server
cp .env.example .env
#  必填：OPENAI_API_KEY
#  上线前务必改：ADMIN_TOKEN、TOKEN_SALT（openssl rand -hex 32）
npm install
npm start
```

启动后会打印零售价与数据目录。默认 `MARKUP=3.0`，折合 **¥1.08/分钟**。

### 2. 发卡

```bash
cd server
node scripts/gen-codes.js --count 100 --amount 30 --batch 2026-09-A --out codes.txt
```

明文只在生成时输出一次，库里只存 `sha256(盐 + 码)`。把 `codes.txt` 导到卡密系统后删除。

### 3. 自测整条链路

```bash
cd server && npm run smoke
```

不需要真实 API Key —— 测试会起一个假的 GPT-Live 上游，覆盖鉴权、配置覆写、
事件白名单、音频校验、按秒计费、`usage.seconds` 对账、兑换码与后台接口。

### 4. 打包 App

```bash
cd android
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 注入你的网关域名
./gradlew assembleRelease -Pspeakmate.gateway=https://api.yourdomain.com
```

装到手机上，输入兑换码激活即可开始对话。也可以直接推上 GitHub，由 Actions 出包。

### 5. 打包 iOS App

```bash
brew install xcodegen
cd ios && xcodegen generate

# 模拟器包（本机直接跑）
xcodebuild -project SpeakMate.xcodeproj -scheme SpeakMate \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build-sim CODE_SIGNING_ALLOWED=NO build

# 注入网关域名并出未签名 IPA
xcodebuild -project SpeakMate.xcodeproj -scheme SpeakMate \
  -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
  -archivePath build-device/SpeakMate.xcarchive \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" \
  SPEAKMATE_GATEWAY=wss://api.yourdomain.com archive
```

未签名 IPA 用 AltStore / Sideloadly / Xcode 自签后安装。要上架 App Store
则需替换 `DEVELOPMENT_TEAM` 并配置签名证书。

---

## GitHub Actions

| 触发 | 行为 |
|---|---|
| push 到 main / PR | 构建 debug + release，上传 artifact |
| 打 `v*` tag | 额外把 APK 发到 GitHub Release |
| 手动触发 | 可临时注入网关地址 |

两个工作流：

| 工作流 | 产物 |
|---|---|
| `android.yml` | `*-debug.apk`、`*-release.apk` |
| `ios.yml` | `SpeakMate-unsigned.ipa`、`SpeakMate-simulator.zip` |

iOS 侧不需要任何证书或 Secrets —— 出的是未签名包，公库也能安全构建。

启用签名（可选）：仓库 Settings → Secrets 添加

```
KEYSTORE_BASE64      base64 -i release.jks | pbcopy
KEYSTORE_PASSWORD
KEY_ALIAS
KEY_PASSWORD
```

**不配置也能构建** —— 会自动跳过签名只出未签名包，fork 与 PR 不会红，所以这个仓库可以放心公开。

---

## 定价怎么算

所有金额都是**整数微美元**（1e-6 USD），杜绝浮点累计误差。

| 配置项 | 默认 | 说明 |
|---|---|---|
| `VOICE_COST_PER_MIN_USD` | 0.05 | GPT-Live-1 语音层官方成本 |
| `BACKEND_COST_PER_1K_TOK_USD` | 0.0008 | 后端文本模型（中文字幕翻译） |
| `MARKUP` | 3.0 | 零售倍率，毛利率 = (MARKUP−1)/MARKUP |
| `USD_CNY` | 7.2 | 折算汇率，由服务端下发，调价不用发版 |

| MARKUP | 零售价 | 毛利 | ¥30 可聊 |
|---|---|---|---|
| 3.0 | ¥1.08/分钟 | 67% | 约 27 分钟 |
| 4.2 | ¥1.51/分钟 | 76% | 约 20 分钟 |

`GET /admin/stats?days=7` 返回按天营收、成本、毛利、会话数与平均时长。

---

## 运营接口

```bash
# 生成兑换码
curl -X POST https://api.yourdomain.com/admin/redeem-codes \
  -H "X-Admin-Token: $ADMIN_TOKEN" -H 'content-type: application/json' \
  -d '{"count":100,"amountCny":30,"batch":"2026-09-A"}'

# 账号列表 / 手工充值 / 停用
curl -H "X-Admin-Token: $ADMIN_TOKEN" https://api.yourdomain.com/admin/accounts
curl -X POST https://api.yourdomain.com/admin/accounts/acc_000001/topup \
  -H "X-Admin-Token: $ADMIN_TOKEN" -H 'content-type: application/json' \
  -d '{"amountCny":30,"note":"微信收款"}'

# 经营看板
curl -H "X-Admin-Token: $ADMIN_TOKEN" "https://api.yourdomain.com/admin/stats?days=7"
```

---

## 防盗刷

- 令牌只存哈希，库被拖走也无法直接冒用
- 单账号并发会话数限制（默认 1）
- 单次会话硬上限（默认 30 分钟）+ 余额耗尽自动断线
- 客户端上行事件**白名单**，`session.update` 直接拒绝
- 上行 `session.start` 的 `model` / `delegation` / `store` 一律强制覆写
- 音频 base64 与字节对齐校验、上行速率限制
- nginx 层按 IP 限制并发连接与请求速率

---

## 已知取舍

| 项 | 现状 | 后续 |
|---|---|---|
| 持久化 | 单机 JSON 文件 + JSONL 流水 | 量上来换 Postgres 驱动，`store.js` 接口已隔离 |
| 支付 | 兑换码（支付环节解耦到任何渠道） | 接支付回调替换 `POST /admin/accounts/:id/topup` |
| 限流 | 单进程内存滑动窗口 | 多实例换 Redis |
| 会话恢复 | GPT-Live 不支持中途改配置，断线即新会话 | 客户端退避重连 |
| 纠错功能 | v1 无显式纠错，靠"更地道地复述" | v2 走 client delegation + `session.commentary.append` |

---

## 许可

MIT
