# SpeakMate 下载与安装

## 下载

| 平台 | 文件 | 安装方式 |
|---|---|---|
| Android | `speakmate-<sha>-release.apk` | 直接安装（未配置签名密钥时为未签名包，部分机型需先卸载旧版本） |
| Android | `speakmate-<sha>-debug.apk` | 调试包，可直接安装 |
| iOS | `SpeakMate-unsigned.ipa` | 用 AltStore / Sideloadly / Xcode 自签后安装 |
| iOS | `SpeakMate-simulator.zip` | 解压后拖入 iOS 模拟器运行 |

## 首次使用

1. 安装后打开 App。
2. 输入运营方发放的兑换码激活（无需注册账号）。
3. 点「开始对话」，与 AI 老师实时练口语；说的和它说的都会实时显示双语字幕。

## 计费

按实际对话秒数计费，界面上「剩余」实时可见；余额耗尽会自动结束会话并给出结算单。

## 自建服务器

App 不持有任何 OpenAI 密钥，语音会话经自建网关转发。
在 App 的「设置 → 服务器」里填入你自己的网关地址即可（也可在构建时用构建参数注入默认值）。

- Android：`./gradlew assembleRelease -Pspeakmate.gateway=https://api.yourdomain.com`
- iOS：`xcodebuild ... SPEAKMATE_GATEWAY=wss://api.yourdomain.com`

部署步骤见仓库 `docs/DEPLOY.md`。

## 安全说明

- 所有构建产物均由 GitHub Actions 从本仓库源码构建，可自行比对 commit。
- iOS 包一律**未签名**（仓库不持有任何开发者证书），需要你用自己的证书重签。
- 仓库内不含任何 API Key、令牌或签名密钥。
