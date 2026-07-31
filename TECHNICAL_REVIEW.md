# 灵犀 Android 客户端 · 技术评审文档

> 文档版本：2026-07-31　　仓库：https://github.com/sunguanghui/xiaozhi-apk

---

## 一、项目基本信息

| 项 | 值 |
|---|---|
| 项目名称 | 灵犀 Android 客户端 |
| 包名 | `com.lhht.xiaozhi.ai` |
| 代码命名空间 | `com.lhht.xiaozhi` |
| 仓库地址 | https://github.com/sunguanghui/xiaozhi-apk |
| 主分支 | `main` |
| 当前版本 | 1.0（versionCode 1） |
| 最低支持 Android | API 29（Android 10） |
| 目标 SDK | API 35（Android 15） |
| 开发语言 | Java（主体）+ C++（JNI） |

---

## 二、技术栈

| 层面 | 组件 / 版本 | 说明 |
|---|---|---|
| 构建工具 | AGP 8.8.0 + Gradle 8.10.2 | Kotlin DSL 构建脚本 |
| 编译 JDK | Java 11（源/目标兼容级别） | CI 使用 Java 17 |
| UI 框架 | AndroidX AppCompat 1.6.1 | NoActionBar 主题 |
| 设计系统 | Material Components 1.11.0 | Material3 Light/Dark theme |
| WebSocket | Java-WebSocket 1.5.4（`org.java-websocket`） | 全双工双向通信 |
| 音频编解码 | Opus（预编译 `.so` + JNI） | 16kHz 单声道语音 |
| Native 构建 | CMake 3.22.1 + NDK 27.0 | 编译 JNI 封装层 |
| CI/CD | GitHub Actions（`ubuntu-latest`） | push main 自动构建 debug + release APK |

---

## 三、工程结构

```
xiaozhi-apk/
├── .github/workflows/android.yml     # CI/CD 流水线
├── app/
│   ├── CMakeLists.txt                # Native 库构建配置
│   ├── build.gradle.kts              # 模块级构建脚本（含签名配置）
│   ├── proguard-rules.pro            # Release 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/
│       │   ├── opus-lib.cpp          # JNI 函数实现
│       │   └── opus/include/         # Opus C 头文件
│       ├── java/com/lhht/xiaozhi/
│       │   ├── activities/
│       │   │   ├── MainActivity.java         # 主界面 + 音频处理
│       │   │   └── SettingsActivity.java     # 设置页
│       │   ├── adapters/
│       │   │   └── MessageAdapter.java       # 聊天气泡列表
│       │   ├── audio/
│       │   │   └── OpusUtils.java            # JNI Opus 编解码器单例
│       │   ├── models/
│       │   │   └── Message.java              # 消息数据模型
│       │   ├── settings/
│       │   │   └── SettingsManager.java      # SharedPreferences 封装
│       │   ├── views/
│       │   │   └── WaveformView.java         # 自定义波形动画 View
│       │   └── websocket/
│       │       └── WebSocketManager.java     # WebSocket 连接管理
│       ├── jniLibs/                          # 预编译 Opus .so
│       │   ├── arm64-v8a/libopus.so
│       │   ├── armeabi-v7a/libopus.so
│       │   ├── x86/libopus.so
│       │   └── x86_64/libopus.so
│       └── res/
│           ├── drawable/    # 所有自定义背景/图标 drawable
│           ├── layout/
│           │   ├── activity_main.xml
│           │   ├── activity_settings.xml
│           │   └── item_message.xml
│           ├── values/              # 颜色、字符串、样式（浅色）
│           └── values-night/        # 颜色、样式（深色覆盖）
```

---

## 四、核心模块详解

### 4.1 WebSocketManager

**职责**：管理 WebSocket 连接的完整生命周期。

**关键特性：**
- 连接时通过 HTTP Header 传递认证信息：
  ```
  device-id: <Android_ID>
  Authorization: Bearer <token>（启用时）
  ```
- 连接成功后立即发送握手消息（`hello`），声明客户端能力
- 断线后 **3 秒自动重连**，通过 `isReconnecting` 标志位防止重复重连
- 支持文本帧（JSON 控制消息）和二进制帧（Opus 音频数据）
- 所有回调通过 `Handler(Looper.getMainLooper())` 切换到主线程

**握手协议：**
```json
{
  "type": "hello",
  "version": 3,
  "transport": "websocket",
  "audio_params": {
    "format": "opus",
    "sample_rate": 16000,
    "channels": 1,
    "frame_duration": 60
  }
}
```

**接口定义：**
```java
interface WebSocketListener {
    void onConnected();
    void onDisconnected();
    void onError(String error);
    void onMessage(String message);
    void onBinaryMessage(byte[] data);
}
```

---

### 4.2 音频管道（MainActivity）

#### 录音 → 上行链路

```
麦克风（AudioRecord）
  ↓ 16kHz / Mono / PCM_16BIT
  ↓ 960 samples（= 60ms / 帧）
Opus 编码（JNI）
  ↓ 压缩后约 40~80 字节/帧（CBR 32kbps）
WebSocket 二进制帧
  ↓
服务端
```

**静音检测**：计算每帧最大振幅，低于 2% 阈值判定为静音；连续静音超过 **1 秒**，发送静音帧通知服务端。

**并发模型**：录音循环在 `executorService`（单线程池）中运行，音频播放在 `audioExecutor`（单线程池）中运行，两者互不阻塞。

#### 下行链路 → 播放

```
服务端
  ↓
WebSocket 二进制帧
  ↓
Opus 解码（JNI）→ PCM_16BIT
  ↓
AudioTrack（低延迟模式 PERFORMANCE_MODE_LOW_LATENCY）
  ↓
扬声器
```

**播放状态机**：由 TTS JSON 消息控制——收到 `{"type":"tts","state":"start"}` 或 `"sentence_start"` 时开始接收音频，收到 `"stop"` 时停止接收新数据但继续播放缓冲中的内容（避免截断）。

---

### 4.3 OpusUtils（JNI 封装）

**单例模式**（线程安全双重检查）。加载 Native 库 `libopusJni.so`，封装以下 Native 方法：

| 方法 | 说明 |
|---|---|
| `createEncoder(rate, channels, complexity)` | 创建编码器，返回句柄 |
| `createDecoder(rate, channels)` | 创建解码器，返回句柄 |
| `encode(handle, pcm[], offset, out[])` | PCM → Opus，返回编码字节数 |
| `decode(handle, opus[], pcm[])` | Opus → PCM，返回样本数 |
| `destroyEncoder/destroyDecoder(handle)` | 释放 Native 资源 |

**编码参数**（`opus-lib.cpp`）：
- 模式：`OPUS_APPLICATION_RESTRICTED_LOWDELAY`（低延迟语音）
- 码率：CBR 32000 bps
- 复杂度：10（调用方传入）
- DTX / FEC：均关闭

**支持 ABI**：`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`

---

### 4.4 SettingsManager

基于 `SharedPreferences` 的轻量配置存储，文件名 `xiaozhi_settings`。

| 键 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `ws_url` | String | `ws://localhost:9005` | WebSocket 服务端地址 |
| `token` | String | `test-token` | Bearer 认证令牌 |
| `enable_token` | Boolean | `true` | 是否启用 Token 认证 |
| `device_id` | String | Android ID | 设备唯一标识，首次使用自动读取并持久化 |

---

### 4.5 WaveformView

自定义 `View`，在通话过程中实时渲染正弦波动画。

- 通过 `setAmplitude(float)` 接收麦克风振幅（0.0~1.0），映射到波形高度
- 每帧计算正弦路径，通过 `invalidate()` 自驱动动画
- 波形颜色：`@color/wave_color`（`#40C4FF`）
- 频率 1.2Hz，相位速度 0.2 rad/帧

> **⚠️ 已知问题**：`onDraw` 末尾无条件调用 `invalidate()`，即使通话未开始（amplitude=0）也持续重绘，存在低电耗问题，待优化。

---

### 4.6 MessageAdapter

RecyclerView 适配器，支持两种消息类型：

| 类型 | 对齐 | 气泡背景 | 文字颜色 |
|---|---|---|---|
| 用户消息（`isFromServer=false`） | 右对齐 | 蓝色 `#1D7FFF`，右上角小圆角 | 白色 |
| AI 回复（`isFromServer=true`） | 左对齐 | 浅灰/深灰（深色模式自适应），左上角小圆角 | 深色/浅色自适应 |

---

## 五、通信协议

### 5.1 完整消息类型

| 方向 | type | state / 其他字段 | 说明 |
|---|---|---|---|
| C→S | `hello` | `version=3, transport, audio_params` | 连接握手 |
| C→S | `listen` | `state=start, mode=auto` | 开始通话 |
| C→S | `listen` | `state=stop, mode=auto` | 结束通话 |
| C→S | `listen` | `state=detect, text, source=text` | 文字输入 |
| C→S | Binary | — | Opus 编码音频帧（60ms/帧） |
| S→C | `tts` | `state=start\|sentence_start\|stop` | TTS 播放控制 |
| S→C | `*` | `text=...` | 服务端文字消息（显示在聊天列表） |
| S→C | Binary | — | Opus 编码 TTS 音频帧 |

### 5.2 连接参数

- 默认地址：`ws://localhost:9005`
- 协议：WebSocket（ws:// 或 wss://）
- 音频格式：Opus，16kHz，单声道，60ms 帧长

---

## 六、UI/UX 设计

### 6.1 主界面布局结构

```
CoordinatorLayout (fitsSystemWindows)
├── AppBarLayout (fitsSystemWindows)
│   ├── MaterialToolbar       ← 标题 + 设置入口
│   └── 连接状态栏             ← 彩色状态点 + 状态文字 + 连接/断开按钮
├── 消息列表区（RecyclerView）
│   └── 空状态提示（无消息时显示）
├── 通话波形卡片（通话时显示，深色背景）
├── 分隔线
└── 底部输入栏
    ├── 文本输入框
    ├── 语音通话按钮（蓝色圆形，白色图标）
    └── 发送按钮（蓝色圆形，白色图标）
```

### 6.2 主题与颜色系统

- 主题框架：**Material3**（`Theme.Material3.Light/Dark.NoActionBar`）
- 主色调：`#1D7FFF`（亮蓝）
- **深色/浅色双模式完整支持**：`values/colors.xml`（浅色）+ `values-night/colors.xml`（深色覆盖）
- 跟随系统自动切换，无需用户手动操作

| 色彩 token | 浅色 | 深色 |
|---|---|---|
| `bg_page` | `#F5F7FA` | `#111318` |
| `bg_surface` | `#FFFFFF` | `#1C1E24` |
| `bg_status_bar` | `#EBF5FF` | `#1A2535` |
| `bg_input_field` | `#EFF2F5` | `#2A2D35` |
| `bubble_ai` | `#F3F4F6` | `#252830` |
| `text_secondary` | `#6B7280` | `#9AA0AE` |

---

## 七、安全与合规

| 项 | 现状 |
|---|---|
| 网络安全策略 | 自定义 `network_security_config.xml`，默认禁止明文；`localhost/127.0.0.1` 例外放行（局域网设备需手动添加 IP） |
| Token 认证 | 可选 Bearer Token，经 WebSocket Header 传输 |
| 设备标识 | `Settings.Secure.ANDROID_ID`（不可重置唯一标识） |
| Release 混淆 | 已启用 `isMinifyEnabled=true`，保留 WebSocket、Opus JNI 类 |
| Release 签名 | 通过 CI 环境变量（`KEYSTORE_BASE64/PASSWORD/ALIAS`）注入，本地构建退回 debug 签名 |
| 权限 | 仅申请 `INTERNET` + `RECORD_AUDIO`，最小权限原则 |

---

## 八、CI/CD 流程

```
push to main / workflow_dispatch
        │
        ├─ Checkout + Java 17
        ├─ assembleDebug  → 上传 debug APK (artifact)
        ├─ Decode keystore（仅配置了 KEYSTORE_BASE64 时执行）
        └─ assembleRelease → 上传 release APK (artifact)
```

**GitHub Secrets 依赖**（release 签名）：

| Secret | 说明 |
|---|---|
| `KEYSTORE_BASE64` | keystore 文件的 base64 编码 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | key 别名 |
| `KEY_PASSWORD` | key 密码 |

---

## 九、已知技术问题 / 待优化点

| # | 问题 | 严重度 | 说明 |
|---|---|---|---|
| 1 | `WaveformView` 无限 `invalidate()` | 中 | 未通话时仍持续重绘，耗电 |
| 2 | `connectBlocking()` 风险 | 高 | `WebSocketManager.connect()` 调用了 `connectBlocking()`，若在主线程触发将抛出 NetworkOnMainThreadException |
| 3 | 无消息持久化 | 低 | 聊天记录不跨 Session 保存，重启 App 后消失 |
| 4 | 局域网明文需手动配置 | 低 | 连接非 localhost 局域网设备需手动在 `network_security_config.xml` 添加 IP |
| 5 | Token 明文存储 | 中 | Token 存于 SharedPreferences 明文，建议改用 Android Keystore + EncryptedSharedPreferences |
| 6 | 无重连退避策略 | 低 | 断线后固定 3s 无限重试，建议改为指数退避 + 最大重试次数上限 |
| 7 | `PLAY_BUFFER_SIZE` 变量未使用 | 低 | 定义了但实际未参与 `AudioTrack` 初始化 |

---

## 十、可扩展新功能方向

基于当前架构，以下功能技术上可直接扩展：

1. **消息持久化**：引入 Room 数据库，以 `deviceId + 时间戳` 为索引存储聊天记录
2. **多服务器配置**：SettingsManager 扩展支持多个服务器地址，支持快速切换
3. **通话录音保存**：在 `executorService` 录音循环中同时写 PCM/Opus 文件
4. **VAD（语音活动检测）增强**：将当前振幅阈值检测替换为 WebRTC VAD 算法，降低误触发
5. **推送通知**：接入 FCM，服务端主动唤醒客户端
6. **MCP 工具调用**：服务端返回 `type=mcp` 消息时，客户端解析并执行设备本地操作（符合 xiaozhi-esp32 协议扩展方向）
7. **多语言 / 国际化**：现有字符串已集中于 `strings.xml`，可直接新增 `values-en/strings.xml`
