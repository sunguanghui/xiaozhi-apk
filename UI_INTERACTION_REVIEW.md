# 灵犀 Android 客户端 — UI 与交互评审文档（v3）

> 本文档基于最新代码（2026-08-01）重新梳理，v2 评审的全部 R-1~R-5 问题均已修复，供第三轮评审使用。

---

## 一、整体概览

| 属性 | 说明 |
|---|---|
| 项目名称 | 灵犀（Lingxi）Android 客户端 |
| 定位 | 基于小智平台（xiaozhi.me）的 AI 语音助手客户端 |
| 核心功能 | 实时语音对话、文字聊天、官方平台接入、自建服务器支持 |
| UI 框架 | Material Design 3 |
| 开发语言 | Java |
| 最低版本 | Android 5.0（API 21） |
| 目标版本 | Android 15（API 35） |
| 支持架构 | armeabi-v7a、arm64-v8a、x86、x86_64 |

---

## 二、主界面（MainActivity）

### 2.1 整体布局

```
┌──────────────────────────────────────────┐
│ TopBar: 灵犀      ● 已连接  [⚙]         │  ← AppBar
├──────────────────────────────────────────┤
│                                          │
│       [ 连接 / 断开连接 / 重新连接 ]      │  ← 顶部连接按钮（含完整状态机）
│                                          │
│   ┌────────────────────────────────┐     │
│   │    通话中…（仅通话时显示）      │     │  ← 通话状态文字
│   │    ~~~~ Aurora 极光波纹 ~~~~   │     │  ← WaveformView（可见性感知动画）
│   └────────────────────────────────┘     │
│                                          │
│         ┌──────────────────┐             │
│         │  大圆形通话按钮   │             │  ← 120dp FAB
│         └──────────────────┘             │
│          点击开始聊天 / 结束通话           │  ← 动态说明文字（voiceHintText）
│                                          │
│   ┌────────────────────────────────┐     │
│   │  🚀 操作引导卡片（首次使用）    │     │  ← 首次使用引导（minHeight 48dp）
│   └────────────────────────────────┘     │
│                                          │
│   ┌────────────────────────────────┐     │
│   │  💬 聊天记录        [展开 ▼]  │     │  ← 可折叠消息区
│   │  (展开后：消息列表 + 文字输入)  │     │
│   └────────────────────────────────┘     │
└──────────────────────────────────────────┘
```

---

### 2.2 TopBar（顶部应用栏）

**组件**：MaterialToolbar，背景色白色（#FFFFFF）

| 元素 | 位置 | 说明 |
|---|---|---|
| 应用名称"灵犀" | 左侧 | 静态文字 |
| 状态指示点 | 右侧 | 8dp 圆点，颜色动态变化 |
| 连接状态文字 | 状态点右侧 | 动态文字 |
| 设置按钮 ⚙ | 最右侧 | 跳转设置页 |

**状态点颜色映射**：

| 状态 | 颜色 | 色值 |
|---|---|---|
| 已连接 | 绿色 | #22C55E |
| 未连接/连接中 | 灰色 | #9CA3AF |
| 错误 | 红色 | #EF4444 |

---

### 2.3 连接按钮完整状态机

**组件**：MaterialButton（圆角 24dp + ic_link 图标）

| 状态 | 按钮文字 | 是否可点击 | 点击行为 |
|---|---|---|---|
| 未连接（空闲） | 「连接」 | ✅ | 进入连接流程，按钮立即禁用 |
| 连接中（OTA/握手） | 「连接」 | ❌ 禁用 | — |
| 已连接 | 「断开连接」 | ✅ | 断开 WebSocket |
| 错误 | 「重新连接」 | ✅ | 重置状态 + 重新发起连接 |
| 放弃绑定后 | 「连接」 | ✅ | 恢复正常（R-2 已修复） |

> 关键：`toggleConnection()` 入口统一重置按钮文字并 `setEnabled(false)`，所有终止态（connected/disconnected/error/放弃绑定）均显式调用 `setEnabled(true)`，状态机闭环完整。

---

### 2.4 大圆形通话按钮

**组件**：FloatingActionButton（120dp），elevation 6dp

| 通话状态 | 背景色 | 图标 | 说明文字 | contentDescription |
|---|---|---|---|---|
| 待机 | 亮蓝色 #1D7FFF | 🎤 ic_mic | 「点击开始聊天」 | 「开始聊天」 |
| 通话中 | 深蓝色 #1565C0 | ⏹ ic_stop | 「点击结束通话」 | 「结束通话」 |

- 背景色、图标、说明文字（`voiceHintText`）、`contentDescription` 四项同步更新
- 未连接时点击 → `MaterialAlertDialog`（[去连接] / [取消]），不再静默

---

### 2.5 Aurora 极光波纹（WaveformView）

**组件**：自定义 View（280×120dp），位于 `voiceContainer` 内（通话时可见）

#### 渲染策略（按设备 API 级别自动切换）

| 设备等级 | API | 渲染方式 | 混合模式 |
|---|---|---|---|
| 高端机 | API 26+（Android 8.0+） | 硬件加速 + `canvas.saveLayer` 离屏合成 | `PorterDuff.SCREEN`（极光微光交融） |
| 低端机降级 | API 21-25 | 直接绘制，无 saveLayer | 各层半透明叠绘（alpha 200/160/120） |

#### 视觉规格

| 特性 | 实现 |
|---|---|
| 层数 | 3 层独立正弦波，相位差各 120°（2π/3） |
| 颜色 | 层0：青蓝 #00CFFF→#0055FF；层1：粉紫 #FF6EC7→#A855F7；层2：极光绿 #00FFA3→#00D4FF |
| 边缘衰减 | 抛物线 `edgeFactor = 1-(2t-1)²`，两端振幅收拢为零 |
| 呼吸动画 | `ValueAnimator` 3s/周期，基础振幅 0.07×ViewHeight，**波纹绝不静止** |
| 振幅响应 | Lerp（系数 0.12）平滑跟随，满振幅 0.38×ViewHeight |
| 驱动来源 | 麦克风：`setAmplitude(rms)`；AI 播放：`setPlayingAmplitude(rms)`（PCM-RMS） |

#### 动画生命周期管理（R-3 已修复）

| 事件 | 行为 |
|---|---|
| `onAttachedToWindow` | 启动 ValueAnimator |
| `onDetachedFromWindow` | 立即 cancel，释放资源 |
| `onVisibilityChanged → GONE/INVISIBLE` | 立即 cancel，**停止 CPU/GPU 消耗** |
| `onVisibilityChanged → VISIBLE` | 重新 start |

> voiceContainer 设为 GONE 时，子 View WaveformView 的 `onVisibilityChanged` 会被自动触发，动画随即暂停。通话重新开始时 voiceContainer 恢复 VISIBLE，动画自动重启。

---

### 2.6 操作引导卡片（首次使用）

- 触发条件：SharedPreferences `has_connected = false`
- 关闭：首次成功连接自动关闭，或点击「知道了」（`minHeight="48dp"`）手动关闭

```
🚀 如何开始使用
 ① 点击右上角⚙，配置服务器并保存
 ② 点击蓝色连接按钮，等待状态点变绿「已连接」
 ③ 按下方蓝色大按钮，开始语音聊天！
                              [知道了]
```

---

### 2.7 消息历史区（可折叠）

**默认状态**：折叠，仅显示标题栏

**触发展开的条件**：
1. 用户手动点击标题栏
2. **首条消息到达时**（messageAdapter.getItemCount() == 0 → 1），若处于折叠状态自动展开

**展开动画完成后**（R-5 已修复）：
- `withEndAction` 在 200ms 动画结束时触发 `smoothScrollToPosition(lastIndex)`，用户立即看到最新消息

**消息列表**：
- 高度：`wrap_content`，代码限制最大高度 240dp，内容少时无留白
- 消息气泡：用户（右，蓝色）/ AI（左，浅灰）

**文字输入**：未连接时发送 → 引导弹窗（[去连接] / [取消]），不再静默失败

---

## 三、官方平台绑定流程

### 3.1 已绑定设备

直接建立 WebSocket，无弹窗。

### 3.2 未绑定设备（验证码弹窗）

```
┌──────────────────────────────────────┐
│         设备未绑定                    │
│  请在浏览器打开 https://xiaozhi.me   │
│  进入控制台添加设备，输入验证码：      │
│           【 123456 】               │
│  绑定完成后对话框将自动关闭…          │
│   [复制验证码]          [放弃绑定]   │
└──────────────────────────────────────┘
```

| 设计点 | 实现 |
|---|---|
| 不可误触关闭 | `setCancelable(false)` |
| 复制验证码 | 写入剪贴板 + Toast，**不关闭对话框** |
| 放弃绑定 | `stopPolling()` + 显式恢复连接按钮（R-2） |
| 自动关闭 | 轮询成功后 dismiss + 直连 WebSocket |
| 超时（5分钟）| Toast 提示 + 按钮变「重新连接」 |

---

## 四、设置页面（SettingsActivity）

### 4.1 整体布局

```
┌──────────────────────────────────────┐
│ ← 设置                           ⋮  │  ← 右上角：导出调试日志
├──────────────────────────────────────┤
│  接入官方小智平台(xiaozhi.me)    [●]  │  ← SwitchMaterial
│  ─────────────────────────────────── │
│  设备 ID（官方模式时显示）            │
│  WebSocket 地址（自建模式时显示）     │
│  Token（自建模式时显示，脱敏）        │
│  启用 Token 认证            [Switch] │
│  ─────────────────────────────────── │
│            [       保存       ]       │
└──────────────────────────────────────┘
```

### 4.2 Token 脱敏逻辑（R-1 已修复）

- 加载：`前8位明文 + ****`
- 获焦：清空，提示「输入新 Token（留空则保持原值不变）」
- 保存判断：
  ```java
  // 精确比对脱敏值，彻底消除边界误判
  boolean unchanged = inputToken.isEmpty()
      || inputToken.equals(maskToken(actualToken));
  String token = unchanged ? actualToken : inputToken;
  ```

---

## 五、完整交互状态机

### 5.1 连接状态机（闭环完整）

```
[未连接]  ←──────────────────────────────────┐
    │ 点击「连接」/ 「重新连接」                 │
    │ → 按钮立即禁用                           │
    ▼                                         │
[连接中：按钮禁用，显示"正在连接…"]            │
    │                                         │
    ├─ 官方模式 → OTA 请求                     │
    │    ├─ 已绑定 ──→ WebSocket 握手          │
    │    ├─ 未绑定 ──→ 验证码弹窗              │
    │    │    ├─ 轮询成功 → 自动关闭 → 握手    │
    │    │    ├─ 放弃绑定 → 按钮恢复 ──────────┘
    │    │    └─ 超时 → "重新连接" ────────────┘
    │    └─ 网络错误 → "重新连接" ─────────────┘
    └─ 自建模式 → 直接 WebSocket 握手
            │
    [已连接：按钮恢复"断开连接"]
            │ 点击「断开连接」
            └──→ [未连接：按钮恢复"连接"]（同时触发 endCall）
```

### 5.2 语音通话状态机

```
[待机：亮蓝🎤 + "点击开始聊天" + contentDescription="开始聊天"]
    │ 点击（已连接）
    ▼
[通话中：深蓝⏹ + "点击结束通话" + contentDescription="结束通话" + Aurora 波纹]
    │ AI 说话（isPlaying=true）→ 麦克风暂停，波形响应 AI 播放 RMS
    │ AudioTrack 排空 → 麦克风恢复，重发 listen.start
    │ 点击结束 / WebSocket 断开
    └──→ [待机：亮蓝🎤]
```

---

## 六、问题清单

### ✅ v1 → v2 已修复（共 9 项）

| 编号 | 问题描述 |
|---|---|
| P0-1 | 未连接点通话仅 Toast → MaterialAlertDialog + [去连接] |
| P1-2 | 按钮下方文字不随状态变化 → voiceHintText 强绑定 |
| P1-3 | 首条消息不自动展开 → onMessage 检测首条触发展开 |
| P1-4 | 发送按钮未连接静默失败 → MaterialAlertDialog + [去连接] |
| P1-5 | OTA 超时无重试入口 → 按钮变「重新连接」 |
| P2-6 | 连接中可重复点击 → setEnabled(false) |
| P2-8 | AI 说话时波形静止 → onBinaryMessage 计算 PCM-RMS |
| P2-9 | RecyclerView 固定 240dp 留白 → wrap_content + 代码限高 |
| P2-10 | 引导按钮触控区偏小 → minHeight 48dp |

### ✅ v2 → v3 已修复（共 5 项）

| 编号 | 问题描述 | 修复方案 |
|---|---|---|
| R-1 | Token 脱敏判断用 `****` 结尾，存在误判风险 | 改为与 `maskToken(actualToken)` 精确比对 |
| R-2 | 放弃绑定后连接按钮仍处于禁用状态 | 放弃绑定时显式调用 `setEnabled(true)` + 恢复文字 |
| R-3 | WaveformView GONE 时 ValueAnimator 持续运行浪费资源 | 重写 `onVisibilityChanged` + `onDetachedFromWindow`，可见性感知动画暂停/恢复 |
| R-4 | FAB contentDescription 不随通话状态更新 | startCall/endCall 中同步更新 contentDescription |
| R-5 | 消息区展开后未滚动到最新消息 | `withEndAction` 在展开动画结束后 `smoothScrollToPosition` |

### ⚠️ 仍需关注的潜在问题

| 编号 | 优先级 | 描述 | 位置 |
|---|---|---|---|
| V3-1 | P2 | 深色模式（`values-night`）下 Aurora Wave 极光色（高饱和青蓝/粉紫/绿）与深色背景的视觉协调性未经验证 | WaveformView / colors-night |
| V3-2 | P2 | 低端机降级渲染（API 21-25）使用半透明叠绘，三层相近色叠加后是否足够美观需真机验证 | WaveformView |
| V3-3 | P3 | 通话中用户手动折叠消息区后，AI 新消息到达时不会再次自动展开（`getItemCount() == 0` 仅首条触发） | MainActivity |
| V3-4 | P3 | `NestedScrollView` 内部的 `RecyclerView` 在内容过多时可能出现嵌套滚动冲突 | activity_main.xml |

---

## 七、颜色与视觉规范

### 主色系

| Token | 色值 | 用途 |
|---|---|---|
| primary | #1D7FFF | 通话按钮（待机）、连接按钮、消息气泡（用户）|
| primary_dark | #1565C0 | 通话按钮（通话中） |

### Aurora Wave 色系

| 层 | 渐变起点 | 渐变终点 | 风格 |
|---|---|---|---|
| 层 0 | #00CFFF | #0055FF | 青蓝 |
| 层 1 | #FF6EC7 | #A855F7 | 粉紫 |
| 层 2 | #00FFA3 | #00D4FF | 极光绿→青 |

### 状态色

| Token | 色值 | 用途 |
|---|---|---|
| status_connected | #22C55E | 状态点（已连接） |
| status_disconnected | #9CA3AF | 状态点（未连接） |
| status_error | #EF4444 | 状态点（错误） |

---

## 八、关键 UI 组件清单

| 组件 | 位置 | 关键特性 |
|---|---|---|
| MaterialToolbar | 两个页面 | AppBar |
| FloatingActionButton | 主界面 | 120dp，图标/色/文字/contentDescription 四项同步 |
| MaterialButton | 主界面、设置页 | 连接按钮含完整状态机 |
| MaterialCardView | 主界面、设置页 | 引导卡片、消息区、配置区 |
| SwitchMaterial | 设置页 | 官方/自建模式切换 |
| RecyclerView | 主界面 | wrap_content + 最大高度 240dp |
| WaveformView | 主界面 | Aurora 极光波纹，可见性感知，API 自适应渲染 |
| MaterialAlertDialogBuilder | 主界面 | 绑定验证码、未连接引导、bind 消息弹窗 |
| ViewPropertyAnimator | 主界面 | 展开图标旋转 + withEndAction 滚动 |

---

## 九、无障碍与可用性

| 项目 | 现状 | 评估 |
|---|---|---|
| FAB contentDescription | 随通话状态动态更新（R-4 已修复） | ✅ |
| 触摸目标尺寸 | 通话按钮 120dp；引导按钮 minHeight 48dp | ✅ |
| 颜色对比度 | 蓝底白字约 4.8:1，满足 WCAG AA | ✅ |
| 深色模式 | values-night 基础支持，Aurora 色系未验证 | ⚠️ |
| 字体缩放 | 全部 sp 单位 | ✅ |
| 未连接操作引导 | Dialog + [去连接] 明确指引 | ✅ |
| Token 安全 | 脱敏显示 + 精确比对（R-1 修复后） | ✅ |
| 最低 Android 版本 | API 21（Android 5.0） | ✅ |

---

## 十、待评审重点建议

请重点关注以下方面：

1. **低端机视觉降级效果（V3-2）**：API 21-25 设备使用半透明叠绘替代 SCREEN 混合，三层极光色的最终视觉效果是否可接受？是否需要进一步调整 alpha 值或颜色？

2. **深色模式兼容性（V3-1）**：Aurora Wave 使用高饱和亮色渐变，在深色主题下是否协调？是否需要为 `values-night` 提供不同的波形颜色配置？

3. **嵌套滚动冲突（V3-4）**：`NestedScrollView` 包含可展开的 `RecyclerView`，当消息较多时手势是否流畅？是否存在外层滚动优先导致内层 RecyclerView 无法滚动的问题？

4. **新消息通知策略（V3-3）**：当前仅在首条消息时自动展开。用户通话中折叠了消息区后，后续 AI 回复是否需要某种提示（如标题栏 Badge 或轻微抖动）？

5. **连接状态机边界测试**：在弱网络环境下，OTA 请求超时 → 按钮恢复「重新连接」→ 再次点击 → 再次禁用的循环流程是否稳定？