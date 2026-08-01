# 灵犀 Android 客户端 — UI 与交互评审文档（v4）

> 本文档基于最新代码（2026-08-01，commit 7f2223b）重新梳理。
> v1-v3 的全部 P0/P1/P2、R-1~R-5、V3-1~V3-4 均已修复，本次供第四轮评审使用。

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
| 布局适配 | 手机（sw≥320dp）完整布局；极小屏/手表（sw<320dp）极简布局 |

---

## 二、布局资源结构

```
res/
├── layout/                   ← 默认（sw < 320dp，适配手表/极小屏）
│   └── activity_main.xml     ← 极简布局：FAB 居中，无 AppBar/消息/引导
├── layout-sw320dp/           ← sw >= 320dp（手机）
│   └── activity_main.xml     ← 完整布局：AppBar、消息历史、引导卡片
└── layout/
    └── activity_settings.xml ← 设置页（两种设备共用）
```

MainActivity 通过 `if (view != null)` 全局判空兼容两套布局，不需要双份 Activity。

---

## 三、主界面 — 手机布局（layout-sw320dp）

### 3.1 整体布局

```
┌──────────────────────────────────────────┐
│ TopBar: 灵犀      ● 已连接  [⚙]         │  ← AppBar
├──────────────────────────────────────────┤
│       [ 连接 / 断开连接 / 重新连接 ]      │  ← 连接按钮（含完整状态机）
│   ┌────────────────────────────────┐     │
│   │    通话中…  ~~~Aurora波纹~~~    │     │  ← voiceContainer（通话时显示）
│   └────────────────────────────────┘     │
│         ┌──────────────────┐             │
│         │  大圆形通话按钮   │             │  ← 120dp FAB（麦克风/停止图标）
│         └──────────────────┘             │
│          点击开始聊天 / 结束通话           │  ← voiceHintText（动态）
│   ┌────────────────────────────────┐     │
│   │  🚀 操作引导卡片（首次使用）    │     │
│   └────────────────────────────────┘     │
│   ┌────────────────────────────────┐     │
│   │  💬 聊天记录  [🔴]  [展开 ▼]  │     │  ← 折叠时可见红点提示
│   └────────────────────────────────┘     │
└──────────────────────────────────────────┘
```

---

### 3.2 连接按钮完整状态机

| 状态 | 文字 | 可点击 | 触发 |
|---|---|---|---|
| 未连接空闲 | 「连接」 | ✅ | 进入连接流程，立即禁用 |
| 连接中 | 「连接」 | ❌ 禁用 | — |
| 已连接 | 「断开连接」 | ✅ | 断开 WebSocket |
| 出现错误 | 「重新连接」 | ✅ | 重置状态 + 重新连接 |
| 放弃绑定后 | 「连接」 | ✅ | 显式恢复可用（R-2 已修复）|

---

### 3.3 大圆形通话按钮

| 状态 | 颜色 | 图标 | 说明文字 | contentDescription |
|---|---|---|---|---|
| 待机 | 亮蓝 #1D7FFF | 🎤 ic_mic | 「点击开始聊天」 | 「开始聊天」 |
| 通话中 | 深蓝 #1565C0 | ⏹ ic_stop | 「点击结束通话」 | 「结束通话」 |

**长按行为**：`HapticFeedbackConstants.LONG_PRESS` + 跳转 SettingsActivity（手表端唯一设置入口）

---

### 3.4 Aurora 极光波纹（WaveformView）

#### 渲染策略

| 设备 | API | 渲染方式 | 颜色来源 |
|---|---|---|---|
| 高端机 | API 26+（Android 8.0+） | 硬件加速 + saveLayer + SCREEN 混合 | `wave_l0/l1/l2_start/end` 资源色 |
| 低端机 | API 21-25 | 直接叠绘，无 saveLayer | `R.color.primary` × 3 alpha（0xCC/0x80/0x4D） |

#### 颜色资源（支持深色模式独立覆盖）

| Token | 亮色模式 | 深色模式（values-night） |
|---|---|---|
| wave_l0_start / end | #00CFFF → #0055FF（青蓝） | #1A5C8C → #0D2E6E（暗蓝） |
| wave_l1_start / end | #FF6EC7 → #A855F7（粉紫） | #7A2A5C → #42166A（暗紫） |
| wave_l2_start / end | #00FFA3 → #00D4FF（极光绿）| #0A5A3A → #0A3A5A（暗绿青）|

#### 动画生命周期

| 事件 | 行为 |
|---|---|
| `onAttachedToWindow` | 启动 ValueAnimator |
| `onDetachedFromWindow` | 立即 cancel |
| `onVisibilityChanged → GONE/INVISIBLE` | 立即 cancel，释放 CPU/GPU |
| `onVisibilityChanged → VISIBLE` | 重新 start |

振幅驱动：麦克风 `setAmplitude(rms)` + AI 播放 `setPlayingAmplitude(rms)`（PCM-RMS）

---

### 3.5 消息历史区（可折叠）

**折叠时新消息提示（V3-3 已修复）：**
- 标题栏右侧 8dp 红点（`messageBadge`，#EF4444）
- 后续消息到达且消息区折叠时：显示红点 + 标题栏左右抖动（ObjectAnimator，400ms，±8dp，3次往返）
- 用户展开时：红点自动隐藏

**展开行为：**
- 首条消息自动展开（itemCount 0→1 触发）
- 展开动画结束后 `smoothScrollToPosition(lastIndex)` 滚动到最新消息（R-5）
- `setNestedScrollingEnabled(false)` 消除与外层 NestedScrollView 的嵌套滚动冲突（V3-4）
- 高度：`wrap_content` + 代码限高 240dp，内容少时无留白

---

## 四、主界面 — 手表/极小屏布局（layout，sw<320dp）

```
┌─────────────────────────┐   （圆形屏幕约 180-280dp）
│  ● 未连接  [连接]       │   ← 状态点 + 连接按钮（TextButton，右上角）
│                         │
│         ╔═════╗          │
│         ║通话中║          │   ← callStatusText（通话时显示，上方）
│         ╠═════╣          │
│         ║  🎤 ║          │   ← voiceButton（150dp FAB，屏幕中心）
│         ╚═════╝          │
│       点击开始聊天        │   ← voiceHintText（下方提示）
│                         │
└─────────────────────────┘
```

**设计原则：**
- 移除 AppBar、操作引导卡片、消息历史卡片、Aurora 波纹（手表无空间）
- FAB 放大至 150dp，icon 64dp，适合盲按
- 长按 FAB → 进入设置（唯一设置入口，配合 HapticFeedback）
- 保留全部核心 ID，MainActivity 通过判空兼容，无需双份 Activity

---

## 五、官方平台绑定流程（弹窗）

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

- `setCancelable(false)` 防止误触
- 「复制验证码」写入剪贴板，**不关闭对话框**
- 「放弃绑定」：`stopPolling()` + 显式 `connectButton.setEnabled(true)` + 恢复文字
- 绑定成功后轮询自动关闭弹窗并建立 WebSocket

---

## 六、设置页面（SettingsActivity）

Token 保存判断（R-1 已修复）：
```java
boolean unchanged = inputToken.isEmpty()
    || inputToken.equals(maskToken(actualToken));
String token = unchanged ? actualToken : inputToken;
```

---

## 七、完整交互状态机

### 7.1 连接状态机

```
[未连接] ←──────────────────────────────────────────┐
    │ 点击「连接」/「重新连接」→ 按钮禁用              │
    ▼                                                │
[连接中]                                              │
    ├─ 官方模式 → OTA                                 │
    │    ├─ 已绑定 → WebSocket 握手 → [已连接]         │
    │    ├─ 未绑定 → 验证码弹窗                        │
    │    │    ├─ 轮询成功 → 自动关闭 → [已连接]         │
    │    │    ├─ 放弃 → 按钮恢复 ──────────────────────┘
    │    │    └─ 超时 → 「重新连接」 ──────────────────┘
    │    └─ 网络错误 → 「重新连接」 ──────────────────┘
    └─ 自建模式 → 直连 → [已连接]
[已连接] → 点击「断开」→ [未连接]（同时 endCall）
```

### 7.2 通话状态机

```
[待机: 亮蓝🎤 "点击开始聊天" contentDescription="开始聊天"]
    │ 点击（已连接）
    ▼
[通话中: 深蓝⏹ "点击结束通话" contentDescription="结束通话" + Aurora 波纹]
    │ AI说话 → isPlaying=true，麦克风暂停，波形响应AI音量
    │ AudioTrack排空 → 麦克风恢复，重发 listen.start
    │ 点击结束 / WebSocket断开
    └──→ [待机]
```

---

## 八、问题修复全记录

### ✅ v1 → v2（P0/P1/P2，共 9 项）

| 编号 | 问题 | 修复 |
|---|---|---|
| P0-1 | 未连接点通话仅 Toast | MaterialAlertDialog + [去连接] |
| P1-2 | 按钮下方文字不更新 | voiceHintText 强绑定 |
| P1-3 | 首条消息不自动展开 | onMessage 检测首条触发 |
| P1-4 | 发送按钮未连接静默失败 | MaterialAlertDialog + [去连接] |
| P1-5 | OTA 超时无重试入口 | 按钮变「重新连接」 |
| P2-6 | 连接中可重复点击 | setEnabled(false) |
| P2-8 | AI 说话时波形静止 | PCM-RMS 驱动 setPlayingAmplitude |
| P2-9 | RecyclerView 固定 240dp 留白 | wrap_content + 代码限高 |
| P2-10 | 引导按钮触控区偏小 | minHeight 48dp |

### ✅ v2 → v3（R-1~R-5，共 5 项）

| 编号 | 问题 | 修复 |
|---|---|---|
| R-1 | Token 脱敏判断用 `****` 结尾，存在误判 | 精确比对 `maskToken(actualToken)` |
| R-2 | 放弃绑定后连接按钮仍禁用 | 显式 `setEnabled(true)` + 恢复文字 |
| R-3 | WaveformView GONE 时动画持续运行 | `onVisibilityChanged` 感知暂停/恢复 |
| R-4 | FAB contentDescription 不随状态更新 | startCall/endCall 同步更新 |
| R-5 | 消息区展开后未滚动到底部 | `withEndAction → smoothScrollToPosition` |

### ✅ v3 → v4（V3-1~V3-4 + 新增，共 7 项）

| 编号 | 问题 | 修复 |
|---|---|---|
| V3-1 | Aurora Wave 深色模式刺眼 | 颜色资源化，values-night 覆盖低饱和暗色 |
| V3-2 | 低端机多色叠加出脏色 | API<26 降级为 primary 蓝单色三 alpha，纯净叠绘 |
| V3-3 | 消息区折叠后新消息无提示 | 红点（messageBadge）+ 标题栏抖动动画 |
| V3-4 | NestedScrollView 嵌套滚动冲突 | `setNestedScrollingEnabled(false)` |
| 新增 | 手表/极小屏无专用布局 | layout-sw320dp 拆分，默认 layout 为极简手表版 |
| 新增 | 手表端无设置入口 | FAB 长按 → settings + HapticFeedback |
| 新增 | 手表布局缺失 View 导致潜在 NPE | MainActivity 8 处全面 null 判断兜底 |

---

## 九、颜色与视觉规范

### 主色系

| Token | 色值 | 用途 |
|---|---|---|
| primary | #1D7FFF | 通话按钮待机、连接按钮、消息气泡 |
| primary_dark | #1565C0 | 通话按钮通话中 |

### Aurora Wave

| Token | 亮色 | 深色（values-night） |
|---|---|---|
| wave_l0_start/end | 青蓝 #00CFFF→#0055FF | 暗蓝 #1A5C8C→#0D2E6E |
| wave_l1_start/end | 粉紫 #FF6EC7→#A855F7 | 暗紫 #7A2A5C→#42166A |
| wave_l2_start/end | 极光绿 #00FFA3→#00D4FF | 暗绿青 #0A5A3A→#0A3A5A |

### 状态色

| Token | 色值 | 用途 |
|---|---|---|
| status_connected | #22C55E | 状态点（已连接） |
| status_disconnected | #9CA3AF | 状态点（未连接） |
| status_error | #EF4444 | 状态点（错误）、消息红点 |

---

## 十、无障碍与可用性

| 项目 | 现状 | 评估 |
|---|---|---|
| FAB contentDescription | 随通话状态动态更新 | ✅ |
| 触摸目标尺寸 | 手机 120dp；手表 150dp；引导按钮 48dp | ✅ |
| 颜色对比度 | 蓝底白字 ~4.8:1，满足 WCAG AA | ✅ |
| 深色模式 Aurora | values-night 独立覆盖，低饱和暗色 | ✅（待真机验证） |
| 低端机降级 | primary 蓝单色叠绘，无脏色 | ✅（待真机验证） |
| 字体缩放 | 全部 sp 单位 | ✅ |
| 手表设置入口 | FAB 长按 + HapticFeedback | ✅ |
| 嵌套滚动 | setNestedScrollingEnabled(false) | ✅ |

---

## 十一、关键 UI 组件清单

| 组件 | 位置 | 关键特性 |
|---|---|---|
| MaterialToolbar | 手机主界面、设置页 | 手表布局中**不存在**（已判空） |
| FloatingActionButton | 两种布局均有 | 手机 120dp；手表 150dp；状态联动；长按进设置 |
| MaterialButton | 两种布局均有 | 连接按钮含完整状态机 |
| WaveformView | 仅手机布局 | Aurora 3层；双渲染策略；颜色资源化；可见性感知 |
| RecyclerView | 仅手机布局 | wrap_content；代码限高；nestedScrolling 关闭 |
| messageBadge (View) | 仅手机布局 | 8dp 红点，折叠时新消息提示 |
| MaterialAlertDialogBuilder | 两种布局 | 绑定验证码、未连接引导 |

---

## 十二、待评审重点建议

请重点关注以下方面：

1. **深色模式 Aurora 实际效果**：暗极光色（暗蓝/暗紫/暗绿青）在纯黑背景（#111318）上是否足够可辨，同时不刺眼？三层 SCREEN 混合后的实际颜色叠加是否协调？

2. **低端机降级视觉感受**：API 21-25 设备上三层 primary 蓝不同透明度叠绘，是否仍有足够的"流动感"？还是看起来像普通的蓝色背景？

3. **手表布局实用性**：
   - 通话中没有 Aurora 波纹，用户是否能感知到通话状态（仅靠 callStatusText 和图标变化）？
   - FAB 长按进设置的操作是否直觉？是否需要任何 Toast 提示（如「已进入设置」）？
   - 消息历史在手表端完全移除，如果用户想查看 AI 回复是否有替代方案？

4. **消息红点交互完整性**：
   - 当前红点只在折叠时新消息到来时显示；若用户**展开后再折叠**，老消息不会触发红点，是否符合预期？
   - 标题栏抖动动画（400ms）是否过于频繁？如果 AI 连续输出多句，是否会持续抖动？

5. **FAB 长按与短按的误触率**：在手表等小屏、低精度触控设备上，长按是否容易误触发？是否需要更长的 `longClickDuration` 或视觉反馈？