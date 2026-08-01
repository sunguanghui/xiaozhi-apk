# 灵犀 · Android 客户端

> **灵犀**（Lingxi）—— 心有灵犀一点通的 AI 语音助手。  
> 基于官方小智平台（xiaozhi.me）的 Android 语音对话客户端，支持实时语音通话和文字交互。同时兼容私有部署的自建服务器。

[![Android](https://img.shields.io/badge/Android-10%2B-green)](https://developer.android.com)
[![Java](https://img.shields.io/badge/Java-11-blue)](https://www.java.com)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)
[![Release](https://img.shields.io/github/v/release/sunguanghui/xiaozhi-apk)](https://github.com/sunguanghui/xiaozhi-apk/releases)

---

## 功能特性

| 功能 | 说明 |
|---|---|
| 🎤 实时语音通话 | Opus 编解码，16kHz 单声道，60ms 帧，低延迟 |
| 💬 文字消息 | 支持文字输入与 AI 对话，消息历史可展开查看 |
| 🔊 AI语音回复 | TTS 自动播放，波形动画实时显示 |
| 🌐 官方平台接入 | 对接 xiaozhi.me，扫码绑定，开箱即用 |
| 🏠 自建服务器 | 支持局域网私有部署，完全可控 |
| 🔄 自动重连 | 断线后自动重连，5 秒间隔，稳定可靠 |
| 🌙 深色/浅色主题 | 跟随系统自动切换 |
| 🔐 回声消除 | TTS 播放期间自动抑制录音发送，防止回声 |

---

## 快速开始

### 下载安装

从 [GitHub Releases](https://github.com/sunguanghui/xiaozhi-apk/releases) 下载最新 APK，安装到 Android 10+ 设备。

> 安装时需允许"安装未知应用"权限。

---

## 操作指南

### 一、首次启动

安装后首次打开 App，若未曾连接过服务器，主界面会显示 **操作引导卡片**，清晰呈现3步操作流程：

| 步骤 | 内容 |
|---|---|
| ① | 点击右上角「连接」按钮 |
| ② | 等待状态点变为绿色「已连接」 |
| ③ | 按下方蓝色大按钮，开始语音聊天！ |

首次成功连接服务器后，引导卡片自动隐藏，不再显示。也可点击「知道了」手动关闭。

---

### 二、接入官方小智平台（推荐）

#### 前提
- 在 [xiaozhi.me](https://xiaozhi.me) 注册账号

#### 操作步骤

**第1步：开启官方模式**

打开 App → 右上角 **⚙ 设置** → 开启"接入官方小智平台(xiaozhi.me)"开关 → 点击**保存**。

> 切换到官方模式时，自建服务器配置区域会自动隐藏；Token 字段会自动清空，防止旧数据干扰绑定流程。

**第2步：连接并获取绑定验证码**

返回主界面 → 点击右上角**连接**按钮。

App 会先请求官方 OTA 接口获取激活状态：
- 若设备已绑定 → 直接建立连接，可正常使用
- 若设备未绑定 → 弹出**绑定验证码对话框**，显示 6 位数字验证码

```
┌─────────────────────────────────────┐
│           设备未绑定                 │
│                                     │
│  请在浏览器打开 https://xiaozhi.me  │
│  进入控制台添加设备，输入验证码：    │
│                                     │
│         【 123456 】                 │
│                                     │
│  绑定完成后对话框将自动关闭…        │
│                      [取消]          │
└─────────────────────────────────────┘
```

**第3步：在官网完成绑定**

1. 打开浏览器，访问 [xiaozhi.me](https://xiaozhi.me) 并登录
2. 进入控制台 → 添加设备
3. 在输入框填入 App 弹窗显示的 6 位验证码
4. 点击确认完成绑定

> 绑定成功后，App 的对话框会**自动关闭**并建立连接，无需手动操作。

**第4步：开始对话**

连接成功后，状态栏显示绿色圆点和"已连接"。
点击主界面大圆形按钮即可开始语音通话。

---

### 三、接入自建服务器

适合已自行部署 [xiaozhi-esp32-server](https://github.com/xinnan-tech/xiaozhi-esp32-server) 的用户。

**操作步骤**

1. 打开 App → **⚙ 设置**
2. 确保"接入官方小智平台"开关**关闭**
3. 填写 **WebSocket 地址**，格式示例：
   - 局域网：`ws://192.168.1.100:9005`
   - 公网（TLS）：`wss://your-domain.com/ws`
4. 如服务器需要 Token：开启"启用 Token 认证"→ 填写 Token
5. 点击**保存**
6. 返回主界面 → 点击**连接**

> 连接局域网服务器时，如提示网络错误，请在 `network_security_config.xml` 中添加服务器 IP 白名单（参见开发文档）。

---

### 四、语音通话

**开始通话**

点击主界面中央大圆形**通话按钮**（蓝色麦克风图标）→ 状态变为"通话中…"，波形动画开始显示。

**说话**

直接对着手机说话，App 实时将语音编码发送至服务器，AI 处理后自动语音回复。

**AI 回复时**

- 扬声器自动播放 TTS 语音
- 波形动画显示播放状态
- 此时麦克风**自动静音**（防回声），AI 说完后自动恢复监听

**结束通话**

再次点击通话按钮即可结束，或等待对话自然结束。

> **打断说话**：AI 正在说话时直接说话，服务端会自动检测并停止 TTS 播放。

---

### 五、文字消息

点击主界面"聊天记录"卡片 → 展开消息历史和文字输入区 → 在输入框输入内容 → 点击发送。

- 消息记录会显示用户消息（右侧蓝色气泡）和 AI 回复（左侧灰色气泡）
- 文字消息与语音通话共用同一个会话，均显示在同一列表中

---

### 六、设置说明

点击右上角 **⚙ 设置图标** 进入设置页面。

| 设置项 | 说明 |
|---|---|
| 接入官方小智平台 | 开/关选择服务器模式，两种模式的配置区域互斥显示 |
| WebSocket 地址 | 自建服务器地址（仅在自建模式下显示） |
| Token | 服务器认证令牌（仅在自建模式下显示） |
| 启用 Token 认证 | 是否在连接时携带 Bearer Token |
| 保存 | 保存所有设置，自动返回主界面 |
| 导出调试日志 | 右上角菜单 → 导出日志文件，用于排查问题 |

---

### 七、调试与排障

**导出日志**

设置页 → 右上角三点菜单 → "导出调试日志" → 选择保存位置。

日志文件包含 WebSocket 连接记录、OTA 请求响应、音频状态等详细信息，方便定位问题。

**常见问题**

| 现象 | 原因 | 解决 |
|---|---|---|
| 连接按钮点击无反应 | Token 配置错误或网络不通 | 导出日志查看具体错误 |
| 通话无声音 | 麦克风权限未授予 | 系统设置 → 应用权限 → 开启麦克风 |
| AI 回复重复之前说的话 | 回声（扬声器声音被麦克风录入）| 使用耳机或降低音量 |
| 第二轮对话不响应 | 网络抖动导致连接断开 | 断开后重新连接 |
| 官方平台验证码弹窗不出现 | 旧版本遗留的无效 token | 进设置关掉再开启官方模式开关（自动清空 token）|
| 局域网地址连接失败 | 明文 HTTP 被拦截 | 将设备 IP 添加至 `network_security_config.xml` |

---

## 编译构建

### 环境要求

- Android Studio Hedgehog 或更新版本
- JDK 17+
- Android SDK API 35
- NDK 27.0（自动下载）

### 本地构建

```bash
# 克隆仓库
git clone https://github.com/sunguanghui/xiaozhi-apk.git
cd xiaozhi-apk

# Debug 包
./gradlew assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk

# Release 包（需配置签名）
./gradlew assembleRelease
# 输出：app/build/outputs/apk/release/app-release.apk
```

### CI/CD 自动构建

每次推送到 `main` 分支，GitHub Actions 自动构建 Debug + Release APK。

发布新版本时，在 GitHub 创建 Release，Actions 自动构建签名 Release APK 并上传。

**所需 Secrets**（在 GitHub 仓库 Settings → Secrets 中配置）：

| Secret | 说明 |
|---|---|
| `KEYSTORE_BASE64` | Keystore 文件的 Base64 编码 |
| `KEYSTORE_PASSWORD` | Keystore 密码 |
| `KEY_ALIAS` | Key 别名 |
| `KEY_PASSWORD` | Key 密码 |

---

## 技术架构

```
com.lhht.xiaozhi/
├── activities/
│   ├── MainActivity.java       # 主界面：通话、消息、连接控制
│   ├── SettingsActivity.java   # 设置页面
│   └── OnboardingActivity.java # 新手引导（新手引导 ViewPager2）
├── audio/
│   └── OpusUtils.java          # Opus JNI 编解码单例
├── adapters/
│   └── MessageAdapter.java     # 消息气泡列表适配器
├── models/
│   └── Message.java            # 消息数据模型
├── settings/
│   └── SettingsManager.java    # 配置持久化（SharedPreferences）
├── utils/
│   ├── LogUtils.java           # 日志工具（文件写入 + SAF 导出）
│   └── OtaService.java         # OTA HTTP 接口（设备激活/绑定）
├── views/
│   └── WaveformView.java       # 自定义波形动画 View
└── websocket/
    └── WebSocketManager.java   # WebSocket 连接管理（含 SSL/重连）
```

**核心依赖**

| 库 | 版本 | 用途 |
|---|---|---|
| Java-WebSocket | 1.5.4 | WebSocket 客户端 |
| Material Components | 1.11.0 | Material3 UI 组件 |
| Opus（预编译 .so） | — | 音频编解码 |
| AndroidX AppCompat | 1.6.1 | 兼容层 |

---

## 协议说明

客户端与服务端通过 WebSocket 通信，遵循小智 ESP32 协议（v1）：

```json
// 握手
{"type":"hello","version":1,"transport":"websocket",
 "audio_params":{"format":"opus","sample_rate":16000,"channels":1,"frame_duration":60}}

// 开始监听
{"type":"listen","state":"start","mode":"auto","session_id":"xxxxxxxx"}

// 停止监听
{"type":"listen","state":"stop","session_id":"xxxxxxxx"}

// 文字输入
{"type":"listen","state":"detect","text":"你好","source":"text","session_id":"xxxxxxxx"}
```

音频数据以 Opus 编码的**二进制帧**传输（16kHz，CBR 32kbps）。

---

## 致谢

本项目开发过程中参考了以下开源项目，在此表示诚挚感谢：

| 项目 | 贡献 |
|---|---|
| [douo/xiaozhi-android](https://github.com/douo/xiaozhi-android) | WebSocket 协议实现、设备信息格式、音频通道设计 |
| [huangjunsen0406/py-xiaozhi](https://github.com/huangjunsen0406/py-xiaozhi) | OTA 激活流程、HMAC 签名、clear_audio_queue 回声消除策略 |
| [niceqwer55555/xiaozhi-apk](https://github.com/niceqwer55555/xiaozhi-apk) | Android 客户端实现参考 |

---

## 相关资源

- 官方小智平台：[xiaozhi.me](https://xiaozhi.me)
- 自建服务器：[xiaozhi-esp32-server](https://github.com/xinnan-tech/xiaozhi-esp32-server)
- ESP32 协议文档：[xiaozhi-esp32](https://github.com/78/xiaozhi-esp32)

---

## License

MIT License — 详见 [LICENSE](LICENSE) 文件。
