# 上线手册

从一台空服务器到用户能付费使用，按顺序走完即可。

---

## 0. 准备清单

| 需要什么 | 说明 |
|---|---|
| 一台 Linux 服务器 | 1C1G 起（网关只是转发，不吃 CPU），建议 2C2G |
| 一个域名 | 例如 `api.yourdomain.com`，必须能签 HTTPS 证书 |
| OpenAI API Key | 项目 Key，且账户已开通 `gpt-live-1` 权限 |
| Node.js ≥ 20.11 | 网关运行时 |
| 国内备案 | 若服务器在中国大陆，域名需完成 ICP 备案 |

---

## 1. 部署网关

```bash
# 安装 Node 22
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs git

# 拉代码（或直接 scp 上传 server/ 目录）
sudo mkdir -p /opt/speakmate && sudo chown $USER /opt/speakmate
git clone https://github.com/<you>/speakmate.git /opt/speakmate
cd /opt/speakmate/server

# 配置
cp .env.example .env
nano .env
```

`.env` 里**必须**改的三项：

```bash
OPENAI_API_KEY=sk-proj-...            # 真实 Key
ADMIN_TOKEN=$(openssl rand -hex 32)   # 后台管理口令
TOKEN_SALT=$(openssl rand -hex 32)    # 令牌哈希盐
```

> ⚠️ `TOKEN_SALT` 一旦上线就不要再改 —— 改了等于所有用户的令牌和未使用卡密全部作废。

安装依赖并试跑：

```bash
npm ci --omit=dev
npm start
# 另开一个终端
curl -s http://127.0.0.1:8787/health
```

### 交给 systemd 托管

```ini
# /etc/systemd/system/speakmate.service
[Unit]
Description=SpeakMate Gateway
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=speakmate
WorkingDirectory=/opt/speakmate/server
Environment=NODE_ENV=production
ExecStart=/usr/bin/node src/index.js
Restart=always
RestartSec=3
# 关停时给正在进行的会话留出结算时间
KillSignal=SIGTERM
TimeoutStopSec=20

# 加固
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ReadWritePaths=/opt/speakmate/server/data

[Install]
WantedBy=multi-user.target
```

```bash
sudo useradd -r -s /usr/sbin/nologin speakmate || true
sudo chown -R speakmate:speakmate /opt/speakmate/server
sudo systemctl daemon-reload
sudo systemctl enable --now speakmate
sudo systemctl status speakmate
```

>`KillSignal=SIGTERM` 很重要：网关收到 SIGTERM 会主动给每个进行中的会话发
>`session.close` 并完成结算，避免出现"用户明明在通话却被标记为未计费"。

---

## 2. 配置 nginx 与证书

```bash
sudo apt install -y nginx certbot python3-certbot-nginx

sudo cp /opt/speakmate/deploy/nginx/speakmate.conf \
        /etc/nginx/sites-available/speakmate
sudo nano /etc/nginx/sites-available/speakmate
#   把 api.speakmate.example 全部替换成你的域名
#   把 /admin/ 里的 allow 203.0.113.10 换成你自己的出口 IP
sudo ln -sf /etc/nginx/sites-available/speakmate /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default

sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d api.yourdomain.com --agree-tos -m you@example.com
sudo systemctl reload nginx
```

验证：

```bash
curl -s https://api.yourdomain.com/health   # 应被 403（只允许本机）
curl -sI https://api.yourdomain.com/        # 应返回 404
```

> `/health` 与 `/admin/` 在 nginx 层做了 IP 白名单。要从外面看健康状态，
> 登到服务器上 `curl 127.0.0.1:8787/health`。

---

## 3. 发卡与卖卡

```bash
cd /opt/speakmate/server
node scripts/gen-codes.js --count 200 --amount 30 --batch 2026-09-A --out /tmp/codes.txt
```

把 `/tmp/codes.txt` 导到你的卡密渠道（微店、有赞、私域发货机器人等），
导完**立刻删掉服务器上的明文**：

```bash
shred -u /tmp/codes.txt
```

> 库里只存 `sha256(盐 + 码)`，明文丢了就找不回来。生成时务必备份。

### 定价建议

| 面额 | MARKUP=3（¥1.08/分钟） | MARKUP=4.2（¥1.51/分钟） |
|---|---|---|
| ¥9.9 | 约 9 分钟 | 约 6.5 分钟 |
| ¥30 | 约 27 分钟 | 约 20 分钟 |
| ¥99 | 约 92 分钟 | 约 65 分钟 |

先用 ¥9.9 的小额卡试水，看真实平均会话时长（`/admin/stats` 的
`avgSessionSeconds`）再调 MARKUP。

---

## 4. 打包 App

### 方式 A：本地

```bash
cd android
./gradlew assembleRelease -Pspeakmate.gateway=https://api.yourdomain.com
```

### 方式 B：GitHub Actions（推荐）

推代码到 GitHub，Actions 自动出包。想产出**已签名**的正式包，先在本地造密钥：

```bash
keytool -genkeypair -v -keystore release.jks -alias speakmate \
  -keyalg RSA -keysize 2048 -validity 10950
base64 -i release.jks | pbcopy     # macOS；Linux 用 base64 -w0 release.jks
```

仓库 → Settings → Secrets and variables → Actions，加四个：

```
KEYSTORE_BASE64       上一步的 base64 串
KEYSTORE_PASSWORD
KEY_ALIAS             speakmate
KEY_PASSWORD
```

打 tag 出正式版：

```bash
git tag v1.0.0 && git push origin v1.0.0
```

Release 页面会自动挂着 APK。

> 不配 Secrets 也能构建，只是产出未签名包 —— 这是故意的，
> 好让仓库可以保持 public 而不必担心密钥泄露。

---

## 5. 上线后必做的三件事

### 5.1 成本熔断

在 OpenAI 后台给项目 Key 设置**月度硬上限**。网关本身有余额熔断，但那是
"每个用户"的；这个上限是防"万一网关被绕过"的兜底。

### 5.2 每日对账

```bash
curl -H "X-Admin-Token: $ADMIN_TOKEN" \
  "https://api.yourdomain.com/admin/stats?days=1"
```

重点看：

- `grossMarginRatio` 是否和预期一致（MARKUP=3 时理论值 0.667）
- `avgSessionSeconds` 是否异常（过长可能是挂机刷时长）
- `unmetered` 的会话数（在 `data/store.json` 里搜 `"unmetered": true`）——
  出现说明有会话没拿到 `usage.seconds`，以墙钟估算入账，需要人工复核

### 5.3 备份

```bash
# 建议放 crontab，每天 3 点
tar czf /var/backups/speakmate-$(date +%F).tar.gz -C /opt/speakmate/server data
```

只需要备份 `data/` 两个文件（`store.json` 和 `ledger.jsonl`）。
`TOKEN_SALT` 也要一起备份到密码管理器 —— 没有它，备份里的令牌索引就是废纸。

---

## 6. 常见故障

| 症状 | 原因 | 处理 |
|---|---|---|
| App 连上就断，提示"余额不足" | 账号没充值或卡密没核销 | `GET /admin/accounts` 查余额 |
| 连上就断，日志有 `invalid_token` | 客户端令牌与服务端 `TOKEN_SALT` 不匹配 | 检查换过盐没有；换过只能让用户重新兑换 |
| 建立会话后 30 秒内必断 | 上游 Key 没有 `gpt-live-1` 权限 | 看网关日志里的上游 error |
| 声音变速变调 | 客户端采样率与 `audio.format.rate` 不一致 | 检查 App 日志里的 `音频引擎就绪 rate=` |
| 模型自我打断、说半句就停 | 回声消除没生效 | 确认用的是 `VOICE_COMMUNICATION` 音源；让用户戴耳机验证 |
| 会话一小时后静默断开 | 中间设备掐长连接 | 已配 `proxy_read_timeout 3600s`；客户端心跳 20s，检查云厂商 SLB 超时设置 |
| 用户投诉"时长不对" | 对账用了估算值 | 在 `ledger.jsonl` 里查该 `refId`（会话 ID）的 `meta.authoritative` |

排查时优先看这三个地方：

```bash
sudo journalctl -u speakmate -n 200 -f          # 网关日志
sudo tail -f /var/log/nginx/speakmate.error.log # 反代日志
cat /opt/speakmate/server/data/ledger.jsonl | tail -20   # 资金流水
```
