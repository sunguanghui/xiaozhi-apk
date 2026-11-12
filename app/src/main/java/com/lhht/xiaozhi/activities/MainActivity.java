package com.lhht.xiaozhi.activities;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.lhht.xiaozhi.utils.OtaService;
import com.lhht.xiaozhi.utils.LogUtils;
import com.lhht.xiaozhi.utils.ImeInsetsHelper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lhht.xiaozhi.R;
import com.lhht.xiaozhi.adapters.MessageAdapter;
import com.lhht.xiaozhi.models.Message;
import com.lhht.xiaozhi.session.SessionViewModel;
import com.lhht.xiaozhi.settings.SettingsManager;
import com.lhht.xiaozhi.views.WaveformView;
import com.lhht.xiaozhi.websocket.WebSocketManager;
import com.lhht.xiaozhi.audio.AudioEngine;
import com.lhht.xiaozhi.audio.BindCodeAnnouncer;

import org.json.JSONObject;
import org.json.JSONException;

import java.lang.ref.WeakReference;

public class MainActivity extends AppCompatActivity
        implements WebSocketManager.WebSocketListener, AudioEngine.Listener {
    private static final int PERMISSION_REQUEST_CODE = 1;
    /** BootReceiver 传入：本次启动由开机自启触发 */
    public static final String EXTRA_AUTO_START = "auto_start";

    // 电脑端脚本通过 ControlReceiver 发广播控制“打开/退出”，需要找到当前界面
    private static WeakReference<MainActivity> currentRef;

    // 对话中说出/输入“退出小智”后自动退出应用（无触摸屏盒子不方便操作）
    private static final String EXIT_KEYWORD = "退出小智";
    /** 识别到关键词后最多等这么久，避免服务端没有回复时一直不退出 */
    private static final long EXIT_KEYWORD_MAX_WAIT_MS = 8000;
    /** tts.stop 之后留给播放缓冲排空的时间 */
    private static final long EXIT_AFTER_SPEECH_DELAY_MS = 1500;
    /** 界面结束后强制结束进程的兜底延时 */
    private static final long EXIT_KILL_DELAY_MS = 5000;

    /** 当前存活的界面实例（没有则为 null），供 ControlReceiver 使用。 */
    public static MainActivity getCurrent() {
        return currentRef == null ? null : currentRef.get();
    }

    private boolean autoStartEnabled;       // 设置项：开机自启 + 自动连接 + 自动对话
    private boolean autoStartCallPending;   // 连接成功后自动进入语音对话
    private boolean resumeCallOnReconnect;  // 断线重连后自动恢复语音对话
    private boolean userDisconnected;       // 用户主动点“断开”，不自动恢复
    private boolean connectInFlight;        // 一次“连接”流程正在进行（OTA/WebSocket）
    // 自动模式下的连接重试：开机时网络/DNS 往往要过十几秒才就绪，
    // 首次 OTA 失败后必须自动重试，否则无人操作的盒子会一直停在“连接错误”。
    private static final long AUTO_CONNECT_RETRY_DELAY_MS = 8000;
    private final Handler autoRetryHandler = new Handler(Looper.getMainLooper());
    private boolean autoRetryScheduled;
    // 关键词退出：主线程回调（WebSocket 消息已经 post 到 mainHandler）
    private final Handler exitHandler = new Handler(Looper.getMainLooper());
    private final Runnable exitRunnable = this::exitByKeyword;
    private boolean exitRequested;
    private final Runnable autoRetryRunnable = new Runnable() {
        @Override
        public void run() {
            autoRetryScheduled = false;
            if (!autoStartEnabled || userDisconnected || isFinishing()) return;
            if (webSocketManager != null && webSocketManager.isConnected()) return;
            if (connectInFlight) return;
            LogUtils.getInstance().d(MainActivity.this, "XiaoZhi-Auto", "自动连接重试");
            toggleConnection();
        }
    };

    // 开机后被厂商界面盖住时自动回到前台（见 onStop/reclaimForeground）
    private boolean bootLaunched;
    private boolean activityResumed;
    private boolean settingsOpen;
    private long bootLaunchElapsedMs;
    private long suppressAutoForegroundUntilMs;
    private int foregroundReclaims;
    private static final long FOREGROUND_RECLAIM_TICK_MS = 3000;
    private static final long FOREGROUND_RECLAIM_WINDOW_MS = 120000;
    private static final int MAX_FOREGROUND_RECLAIMS = 6;
    private static final int REQ_SETTINGS = 100;
    private final Handler foregroundHandler = new Handler(Looper.getMainLooper());

    private WebSocketManager webSocketManager;
    private SettingsManager settingsManager;
    private AudioEngine audioEngine;
    private SessionViewModel sessionViewModel;
    private TextView connectionStatus;
    private Button connectButton;
    private ImageButton sendButton;
    private EditText messageInput;
    private TextView callStatusText;
    private WaveformView waveformView;
    private View voiceContainer;
    private View statusDot;
    private volatile String sessionId = ""; // 服务器 hello 里返回的 session_id（音频线程也会读取）
    private MessageAdapter messageAdapter;
    private RecyclerView messagesRecyclerView;
    // 消息历史折叠/展开
    private View messageHeaderLayout;
    private View messageContentLayout;
    private ImageView messageExpandIcon;
    private boolean isMessageExpanded = false;
    private androidx.appcompat.app.AlertDialog bindingDialog;
    // 无屏音箱/盒子看不到绑定码，用预置语音朗读（系统里没有 TTS 引擎）
    private BindCodeAnnouncer bindCodeAnnouncer;
    private View guideCard; // 操作引导卡片
    private FloatingActionButton voiceButton; // 语音通话按钮
    private TextView voiceHintText; // 通话按钮下方说明文字
    private View messageBadge; // 折叠态新消息红点
    private ObjectAnimator shakeAnimator; // 标题栏抖动动画（节流复用）

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i("MainActivity", "应用启动");
        currentRef = new WeakReference<>(this);
        setContentView(R.layout.activity_main);

        // 开机自启路径：亮屏、直接显示在锁屏之上，并保持常亮，
        // 保证无触摸屏的音箱/盒子开机后无需任何操作即可使用
        boolean launchedByBoot = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_AUTO_START, false);
        bootLaunched = launchedByBoot;
        bootLaunchElapsedMs = SystemClock.elapsedRealtime();
        if (launchedByBoot) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // 初始化视图
        callStatusText = findViewById(R.id.callStatusText);
        waveformView = findViewById(R.id.waveformView);
        voiceContainer = findViewById(R.id.voiceContainer);
        statusDot = findViewById(R.id.statusDot);
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView);
        messageHeaderLayout = findViewById(R.id.messageHeaderLayout);
        messageContentLayout = findViewById(R.id.messageContentLayout);
        messageExpandIcon = findViewById(R.id.messageExpandIcon);

        // 设置RecyclerView
        messageAdapter = new MessageAdapter();
        messagesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        messagesRecyclerView.setAdapter(messageAdapter);
        // 限制消息列表最大高度（wrap_content 下避免撑开过高）
        final int maxRecyclerHeight = (int) (240 * getResources().getDisplayMetrics().density);
        messagesRecyclerView.addOnLayoutChangeListener(
            (v, l, t, r, b, ol, ot, or, ob) -> {
                if (v.getHeight() > maxRecyclerHeight) {
                    v.getLayoutParams().height = maxRecyclerHeight;
                    v.requestLayout();
                }
            });
        // 禁用 RecyclerView 嵌套滚动，避免与外层 NestedScrollView 冲突（V3-4）
        messagesRecyclerView.setNestedScrollingEnabled(false);

        // 消息历史折叠/展开（手表布局无此控件，判空兜底）
        if (messageHeaderLayout != null)
            messageHeaderLayout.setOnClickListener(v -> toggleMessageExpansion());

        // 初始化
        settingsManager = new SettingsManager(this);
        autoStartEnabled = settingsManager.isAutoStartEnabled();
        // 使用小写带冒号 MAC 格式（与 ESP32 固件及参考项目 xiaozhi-android 完全一致）
        String deviceId = settingsManager.getFormattedDeviceId(this);
        String clientId = settingsManager.getClientId();
        Log.i("MainActivity", "设备ID: " + deviceId + "  ClientId: " + clientId);

        // 连接与音频会话放在 ViewModel 中：旋转屏幕 / 系统重建 Activity 时不会断线或结束通话
        sessionViewModel = new ViewModelProvider(this).get(SessionViewModel.class);
        sessionViewModel.init(this, deviceId, clientId);
        webSocketManager = sessionViewModel.getWebSocketManager();
        audioEngine = sessionViewModel.getAudioEngine();
        bindCodeAnnouncer = new BindCodeAnnouncer(this);

        // 初始化视图
        connectionStatus = findViewById(R.id.connectionStatus);
        connectButton = findViewById(R.id.connectButton);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);

        // 绑定新布局按钮
        voiceButton = findViewById(R.id.voiceButton);
        voiceHintText = findViewById(R.id.voiceHintText);
        messageBadge = findViewById(R.id.messageBadge);
        ImageButton settingsButton = findViewById(R.id.settingsButton);

        // 设置按钮点击事件
        if (connectButton != null) connectButton.setOnClickListener(v -> toggleConnection());
        if (voiceButton != null) {
            voiceButton.setOnClickListener(v -> toggleRecording());
            // 长按 FAB 进入设置（手表端无顶部设置按钮时的唯一入口）
            // 通话中忽略长按，防止按住说话时误触跳转（Task 2）
            voiceButton.setOnLongClickListener(v -> {
                if (sessionViewModel.isCallActive()) return false;
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                openSettings();
                return true;
            });
        }
        if (sendButton != null) sendButton.setOnClickListener(v -> sendMessage());
        if (settingsButton != null) settingsButton.setOnClickListener(v -> openSettings());

        // 检查并请求权限
        checkPermissions();

        // 自动模式：尽早抢占麦克风。该音箱固件里厂商语音助手会一直直接占用麦克风阵列，
        // 如果等连接成功后再打开，音频 HAL 会在 openInput 处阻塞并连带卡死播放。
        // 先于建连打开并一直持有，等价于"谁先打开谁占用"。
        if (autoStartEnabled
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
            LogUtils.getInstance().d(this, "XiaoZhi-Voice",
                    "自动模式：连接前抢先打开麦克风（防止厂商语音助手占用）");
            audioEngine.setTransmitEnabled(false);
            audioEngine.startRecording();
        }

        // 操作引导：未连接过时显示引导卡片
        guideCard = findViewById(R.id.guideCard);
        View guideDismissButton = findViewById(R.id.guideDismissButton);
        boolean hasConnectedBefore = getSharedPreferences("guide_prefs", MODE_PRIVATE)
                .getBoolean("has_connected", false);
        if (!hasConnectedBefore && guideCard != null) {
            guideCard.setVisibility(View.VISIBLE);
        }
        if (guideDismissButton != null) {
            guideDismissButton.setOnClickListener(v -> hideGuide());
        }

        // 所有视图就绪后再绑定会话回调；Activity 重建时同步已有连接/通话状态
        webSocketManager.setListener(this);
        audioEngine.setListener(this);
        syncSessionUi();

        // 处理软键盘遮挡：targetSdk 35 在 Android 15 上不再自动 resize 窗口，
        // 需要按 IME inset 收缩内容区，否则输入框和聊天记录会被键盘盖住
        ImeInsetsHelper.apply(this, messageInput);

        // 自动连接 + 自动进入对话：开机自启（无触摸屏的音箱/盒子）或普通冷启动时，
        // 自动点击一次“连接”；ViewModel 存活期间（旋转等重建）不重复触发。
        if (autoStartEnabled && !webSocketManager.isConnected()
                && !sessionViewModel.isAutoConnectAttempted() && !isFinishing()) {
            sessionViewModel.markAutoConnectAttempted();
            autoStartCallPending = true;
            // 等首帧绘制完成再触发，保证按钮/状态栏走与手动点击完全相同的流程
            getWindow().getDecorView().post(this::toggleConnection);
        }

        // 开机窗口内盯住前台 Activity：被厂商界面盖住时自动回来
        if (bootLaunched && autoStartEnabled) {
            foregroundHandler.postDelayed(foregroundTick, FOREGROUND_RECLAIM_TICK_MS);
        }
    }

    /** Activity 重建（旋转等）后，把 ViewModel 中仍然存活的会话状态同步回 UI。 */
    private void syncSessionUi() {
        setConnectionUi(webSocketManager.isConnected());
        setCallUiActive(sessionViewModel.isCallActive());
    }

    private void toggleMessageExpansion() {
        isMessageExpanded = !isMessageExpanded;
        if (messageContentLayout != null)
            messageContentLayout.setVisibility(isMessageExpanded ? View.VISIBLE : View.GONE);
        // 展开时隐藏红点（V3-3）
        if (isMessageExpanded && messageBadge != null)
            messageBadge.setVisibility(View.GONE);
        if (messageExpandIcon == null) return;
        ViewPropertyAnimator anim = messageExpandIcon.animate()
                .rotation(isMessageExpanded ? 180 : 0)
                .setDuration(200);
        if (isMessageExpanded) {
            anim.withEndAction(() -> {
                if (messagesRecyclerView == null) return;
                int last = messageAdapter.getItemCount() - 1;
                if (last >= 0) messagesRecyclerView.smoothScrollToPosition(last);
            });
        }
        anim.start();
    }

    /** 标题栏左右抖动，提示有新消息（V3-3）。节流：动画进行中不重复启动。 */
    private void shakeMessageHeader() {
        if (messageHeaderLayout == null) return;
        if (shakeAnimator != null && shakeAnimator.isRunning()) return; // 节流：正在抖动则跳过
        shakeAnimator = ObjectAnimator.ofFloat(messageHeaderLayout, "translationX",
                0f, -8f, 8f, -6f, 6f, -4f, 4f, 0f);
        shakeAnimator.setDuration(400);
        shakeAnimator.start();
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST_CODE);
        }
    }

    private void toggleConnection() {
        if (!webSocketManager.isConnected()) {
            userDisconnected = false;
            // 自建模式 + URL 未配置（空或默认 localhost）→ 引导去设置，不发起连接
            if (!settingsManager.isUseOfficialServer()) {
                String wsUrl = settingsManager.getWsUrl();
                boolean isUnconfigured = wsUrl.isEmpty()
                        || wsUrl.contains("localhost")
                        || wsUrl.contains("127.0.0.1");
                if (isUnconfigured) {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("还未配置服务器")
                            .setMessage("您当前使用自建模式，但还未填写服务器地址。\n\n请前往设置填写 WebSocket 地址，或切换到官方小智平台直接使用。")
                            .setPositiveButton("去设置", (d, w) -> openSettings())
                            .setNegativeButton("取消", null)
                            .show();
                    return; // 提前返回，按钮状态不变
                }
            }
            // 配置合法，进入连接流程
            connectInFlight = true;
            connectButton.setText(R.string.connect);
            connectButton.setEnabled(false); // 连接过程中禁用，防止重复点击
            connectionStatus.setText("正在连接…");
            updateStatusDot(R.color.status_disconnected);
            if (settingsManager.isUseOfficialServer()) {
                // 官方模式：先调 OTA API 获取激活码或确认已绑定，再连 WebSocket
                connectOfficialServer();
            } else {
                // 局域网模式：直接连接
                String wsUrl = settingsManager.getWsUrl();
                String token = settingsManager.getToken();
                boolean enableToken = settingsManager.isTokenEnabled();
                webSocketManager.connect(wsUrl, token, enableToken, settingsManager.isAllowInsecureTls());
            }
        } else {
            userDisconnected = true;
            connectInFlight = false;
            autoRetryHandler.removeCallbacks(autoRetryRunnable);
            autoRetryScheduled = false;
            webSocketManager.disconnect();
        }
    }

    /** 自动模式下延迟重试连接（网络/DNS 未就绪时）。 */
    private void scheduleAutoConnectRetry() {
        if (!autoStartEnabled || userDisconnected || autoRetryScheduled) return;
        autoRetryScheduled = true;
        autoRetryHandler.postDelayed(autoRetryRunnable, AUTO_CONNECT_RETRY_DELAY_MS);
    }

    /** 官方平台连接流程：OTA → 激活码弹窗（后台轮询）或 直接连 WebSocket */
    private void connectOfficialServer() {
        connectionStatus.setText("正在获取激活状态…");

        OtaService.checkActivation(this, settingsManager, new OtaService.OtaCallback() {
            @Override
            public void onActivationRequired(String code, String message) {
                connectInFlight = false;
                // 超时重试会下发新验证码，先关掉上一个弹窗，避免对话框叠加
                if (bindingDialog != null && bindingDialog.isShowing()) {
                    bindingDialog.dismiss();
                }
                String dialogMsg = "请在浏览器打开 https://xiaozhi.me\n"
                        + "进入控制台添加设备，输入以下验证码：\n\n"
                        + "【 " + code + " 】\n\n"
                        + "绑定完成后对话框将自动关闭…";
                // 保存引用，激活成功后可从 onAlreadyActivated 里关闭
                bindingDialog = new MaterialAlertDialogBuilder(MainActivity.this)
                    .setTitle("设备未绑定")
                    .setMessage(dialogMsg)
                    .setCancelable(false)  // 防止误触关闭，避免用户看不到验证码
                    .setPositiveButton("复制验证码", (dialog, which) -> {
                        // 复制验证码到剪贴板
                        android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        android.content.ClipData clip = android.content.ClipData.newPlainText("验证码", code);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(MainActivity.this, "验证码已复制", Toast.LENGTH_SHORT).show();
                        // 不关闭对话框，让用户继续查看
                    })
                    .setNegativeButton("放弃绑定", (dialog, which) -> {
                        OtaService.stopPolling();  // 停止后台轮询
                        bindCodeAnnouncer.stop();
                        dialog.dismiss();
                        bindingDialog = null;
                        // R-2: 确保连接按钮恢复可用，防止状态机卡死
                        connectButton.setEnabled(true);
                        connectButton.setText(R.string.connect);
                        connectionStatus.setText(getString(R.string.status_disconnected));
                        updateStatusDot(R.color.status_disconnected);
                        Toast.makeText(MainActivity.this, "已取消绑定", Toast.LENGTH_SHORT).show();
                    })
                    .show();
                // 音箱/盒子没有触摸屏，也通常看不到界面：用语音把 6 位绑定码念出来，
                // 之后每 20 秒重播一轮，直到绑定完成或用户放弃
                bindCodeAnnouncer.announce(code);
            }

            @Override
            public void onAlreadyActivated() {
                // 关闭绑定弹窗（如果还在显示）
                if (bindingDialog != null && bindingDialog.isShowing()) {
                    bindingDialog.dismiss();
                    bindingDialog = null;
                }
                bindCodeAnnouncer.announceSuccess();
                doConnectWebSocket();
            }

            @Override
            public void onError(String error) {
                bindCodeAnnouncer.stop();
                if (bindingDialog != null && bindingDialog.isShowing()) {
                    bindingDialog.dismiss();
                }
                bindingDialog = null;
                connectInFlight = false;
                // 恢复按钮，避免失败后停留在禁用状态（手动模式可再次点击连接）
                connectButton.setEnabled(true);
                connectButton.setText(R.string.connect);
                if (autoStartEnabled && !userDisconnected) {
                    // 开机瞬间网络/DNS 往往还没就绪：自动重试，不反复弹 Toast
                    LogUtils.getInstance().d(MainActivity.this, "XiaoZhi-Auto",
                            "OTA 失败，稍后自动重试: " + error);
                    connectionStatus.setText("网络未就绪，正在重试…");
                    updateStatusDot(R.color.status_disconnected);
                    scheduleAutoConnectRetry();
                    return;
                }
                Toast.makeText(MainActivity.this, "OTA 错误: " + error, Toast.LENGTH_LONG).show();
                connectionStatus.setText(getString(R.string.status_error));
                updateStatusDot(R.color.status_error);
            }
        });
    }

    private void doConnectWebSocket() {        // 官方模式：使用 OTA 返回的 URL 和 token（默认 test-token）
        // 局域网模式：使用用户配置的 URL 和 token
        String wsUrl  = settingsManager.getEffectiveWsUrl();
        String token  = settingsManager.isUseOfficialServer()
                ? settingsManager.getOtaToken()
                : settingsManager.getToken();
        boolean useToken = !token.isEmpty();
        webSocketManager.connect(wsUrl, token, useToken, settingsManager.isAllowInsecureTls());
    }

    private void toggleRecording() {
        if (!sessionViewModel.isCallActive()) {
            startCall();
        } else {
            endCall();
        }
    }

    /**
     * 自动模式：连接成功后自动进入语音对话（等价于用户点击“开始聊天”）。
     * 无触摸屏的音箱/盒子设备依赖该逻辑开箱即用。
     */
    private void maybeStartCallAutomatically() {
        if (!autoStartEnabled) return;
        if (!autoStartCallPending && !resumeCallOnReconnect) return;
        // 尚未连上服务器时保留待办，等 onConnected 再处理
        if (webSocketManager == null || !webSocketManager.isConnected()) return;
        if (audioEngine == null || sessionViewModel.isCallActive()) {
            autoStartCallPending = false;
            resumeCallOnReconnect = false;
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            // 权限未授予：保留待办，授权成功后再进入对话
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST_CODE);
            return;
        }
        autoStartCallPending = false;
        resumeCallOnReconnect = false;
        LogUtils.getInstance().d(this, "XiaoZhi-Voice", "自动开始语音对话");
        startCall();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_CODE) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            maybeStartCallAutomatically();
        } else {
            // 用户拒绝授权，放弃自动进入对话，避免反复弹窗
            autoStartCallPending = false;
            resumeCallOnReconnect = false;
        }
    }

    private void startCall() {
        if (!webSocketManager.isConnected()) {            // 未连接时弹引导 Dialog，提供"去连接"快捷入口
            new MaterialAlertDialogBuilder(this)
                .setTitle("尚未连接服务器")
                .setMessage("需要先连接服务器才能开始语音聊天。")
                .setPositiveButton("去连接", (d, w) -> toggleConnection())
                .setNegativeButton("取消", null)
                .show();
            return;
        }

        // 发送开始通话消息
        try {
            JSONObject startMessage = new JSONObject();
            startMessage.put("type", "listen");
            startMessage.put("state", "start");
            startMessage.put("mode", "auto");
            if (!sessionId.isEmpty()) startMessage.put("session_id", sessionId);
            webSocketManager.sendMessage(startMessage.toString());
        } catch (JSONException e) {
            LogUtils.getInstance().e(this, "XiaoZhi-Voice", "发送开始通话消息失败", e);
            return;
        }

        sessionViewModel.setCallActive(true);
        runOnUiThread(() -> setCallUiActive(true));

        if (!audioEngine.isPlaybackAvailable()) {
            Toast.makeText(this, "音频播放初始化失败，请重启应用", Toast.LENGTH_LONG).show();
        }
        // 自动模式下麦克风在建连前就已打开，这里只打开发送开关；非自动模式则顺带开始录音
        audioEngine.setTransmitEnabled(true);
        audioEngine.startRecording();
    }

    private void endCall() {
        boolean wasActive = sessionViewModel != null && sessionViewModel.isCallActive();
        sessionViewModel.setCallActive(false);
        if (autoStartEnabled) {
            // 自动模式（无触摸的音箱/盒子）：只停止播放与音频发送，继续持有麦克风。
            // 一旦释放，厂商语音助手会重新打开麦克风阵列，之后再抢占就会卡死音频 HAL。
            audioEngine.cancelPlayback();
        } else {
            audioEngine.stopRecording();
        }
        runOnUiThread(() -> setCallUiActive(false));

        if (!wasActive) return;

        // 发送停止通话消息
        try {
            JSONObject stopMessage = new JSONObject();
            stopMessage.put("type", "listen");
            stopMessage.put("state", "stop");
            if (!sessionId.isEmpty()) stopMessage.put("session_id", sessionId);
            webSocketManager.sendMessage(stopMessage.toString());
        } catch (JSONException e) {
            LogUtils.getInstance().e(this, "XiaoZhi-Voice", "发送停止通话消息失败", e);
        }
    }

    /** 通话态 UI 切换，startCall/endCall/Activity 重建共用。 */
    private void setCallUiActive(boolean active) {
        if (voiceContainer != null) {
            voiceContainer.setVisibility(active ? View.VISIBLE : View.GONE);
        }
        if (callStatusText != null) {
            callStatusText.setVisibility(active ? View.VISIBLE : View.GONE);
            if (active) callStatusText.setText(R.string.calling);
        }
        if (waveformView != null && !active) {
            waveformView.setAmplitude(0);
        }
        if (voiceButton != null) {
            voiceButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, active ? R.color.primary_dark : R.color.primary)));
            voiceButton.setImageResource(active ? R.drawable.ic_stop : R.drawable.ic_mic);
            voiceButton.setContentDescription(active ? "结束通话" : "开始聊天"); // R-4
        }
        if (voiceHintText != null) {
            voiceHintText.setText(active ? "点击结束通话" : "点击开始聊天");
        }
    }

    private void sendMessage() {
        if (messageInput == null) return; // 手表布局无输入框，直接返回
        String message = messageInput.getText().toString().trim();
        if (message.isEmpty()) return;
        if (!webSocketManager.isConnected()) {
            // 未连接时弹引导 Dialog，提供"去连接"快捷入口
            new MaterialAlertDialogBuilder(this)
                .setTitle("尚未连接服务器")
                .setMessage("需要先连接服务器才能发送消息。")
                .setPositiveButton("去连接", (d, w) -> toggleConnection())
                .setNegativeButton("取消", null)
                .show();
            return;
        }
        try {
            JSONObject jsonMessage = new JSONObject();
            jsonMessage.put("type", "listen");
            jsonMessage.put("state", "detect");
            jsonMessage.put("text", message);
            jsonMessage.put("source", "text");
            if (!sessionId.isEmpty()) jsonMessage.put("session_id", sessionId);
            webSocketManager.sendMessage(jsonMessage.toString());
            // 不立即添加消息，等待服务器 stt 回显以避免重复
            messageInput.setText("");
        } catch (Exception e) {
            LogUtils.getInstance().e(this, "XiaoZhi-Text", "发送文字消息失败", e);
        }
    }

    private void openSettings() {
        // 打开设置页期间不要触发“开机窗口内抢回前台”，否则会把用户从设置里拽回来
        settingsOpen = true;
        suppressAutoForegroundUntilMs = SystemClock.elapsedRealtime() + 5000;
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivityForResult(intent, REQ_SETTINGS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SETTINGS) {
            settingsOpen = false;
        }
    }

    /**
     * 物理键盘快捷键支持（Task 4）：
     *   Space  → 切换通话（对讲机模式，需已连接且非文字输入状态）
     *   Enter  → 发送消息（输入框获焦时，非 Shift+Enter）
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();

            // Space：切换语音通话（PTT 快捷唤醒 / 挂断）
            if (code == KeyEvent.KEYCODE_SPACE) {
                boolean inputFocused = messageInput != null && messageInput.isFocused();
                if (!inputFocused && webSocketManager != null && webSocketManager.isConnected()) {
                    if (voiceButton != null) voiceButton.performClick();
                    return true;
                }
            }

            // Enter：发送文字消息（输入框获焦且非 Shift+Enter）
            if (code == KeyEvent.KEYCODE_ENTER && !event.isShiftPressed()) {
                if (messageInput != null && messageInput.isFocused()) {
                    sendMessage();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onConnected() {
        addLog("WebSocket", "已连接");
        runOnUiThread(() -> {
            connectInFlight = false;
            autoRetryHandler.removeCallbacks(autoRetryRunnable);
            autoRetryScheduled = false;
            setConnectionUi(true);
            userDisconnected = false;
            // 第一次连接成功时自动关闭操作引导
            hideGuide();
            // 等服务器 hello（带 session_id）到达后再进入对话；
            // 若 hello 迟到，最多等 600ms 后仍按已连接处理，避免自动流程卡住
            if (autoStartCallPending || resumeCallOnReconnect) {
                getWindow().getDecorView().postDelayed(this::maybeStartCallAutomatically, 600);
            }
        });
    }

    @Override
    public void onDisconnected() {
        addLog("WebSocket", "已断开");
        final boolean wasActive = audioEngine != null
                && (audioEngine.isRecording() || audioEngine.isPlaying());
        runOnUiThread(() -> {
            setConnectionUi(false);
            connectInFlight = false;
            // 自动模式：非用户主动断开且当时正在通话 → 重连成功后自动恢复对话
            if (autoStartEnabled && wasActive && !userDisconnected) {
                resumeCallOnReconnect = true;
            }
            endCall();
        });
    }

    @Override
    public void onError(String error) {
        addLog("Error", error);
        runOnUiThread(() -> {
            // 错误可能发生在通话中：先结束通话，避免麦克风/播放状态卡死
            if (audioEngine != null && (audioEngine.isRecording() || audioEngine.isPlaying())) {
                endCall();
            }
            connectInFlight = false;
            connectButton.setText("重新连接");
            connectButton.setEnabled(true);
            updateStatusDot(R.color.status_error);
            if (autoStartEnabled && !userDisconnected) {
                // 自动模式：无人值守，安静重试而不是反复弹 Toast
                connectionStatus.setText("连接失败，正在重试…");
                scheduleAutoConnectRetry();
                return;
            }
            connectionStatus.setText(R.string.status_error);
            Toast.makeText(this, "错误: " + error, Toast.LENGTH_SHORT).show();
        });
    }

    /** 连接态 UI 切换，onConnected/onDisconnected/Activity 重建共用。 */
    private void setConnectionUi(boolean connected) {
        if (connectionStatus != null) {
            connectionStatus.setText(connected ? R.string.status_connected : R.string.status_disconnected);
        }
        if (connectButton != null) {
            connectButton.setText(connected ? R.string.disconnect : R.string.connect);
            connectButton.setEnabled(true);
        }
        updateStatusDot(connected ? R.color.status_connected : R.color.status_disconnected);
    }

    private void updateStatusDot(int colorRes) {
        if (statusDot != null) {
            statusDot.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, colorRes)));
        }
    }

    // ── 服务端消息分发（P2-8）：按 type 拆到独立 handler，不再是一长串 if/else ──

    @Override
    public void onMessage(String message) {
        addLog("Message", message);
        try {
            JSONObject jsonMessage = new JSONObject(message);
            String type = jsonMessage.getString("type");
            switch (type) {
                case "hello":
                    handleHello(jsonMessage);
                    break;
                case "bind":
                    handleBind(jsonMessage);
                    break;
                case "tts":
                    handleTts(jsonMessage);
                    break;
                case "stt":
                    handleStt(jsonMessage);
                    break;
                default:
                    break; // llm 等其他类型：仅日志记录，不需要额外处理
            }
        } catch (JSONException e) {
            LogUtils.getInstance().e(this, "XiaoZhi-Message", "解析消息失败", e);
        }
    }

    /** 服务器 hello 消息：保存 session_id，后续所有消息都需要携带 */
    private void handleHello(JSONObject jsonMessage) {
        String sid = jsonMessage.optString("session_id", "");
        if (!sid.isEmpty()) {
            sessionId = sid;
            Log.i("XiaoZhi", "已收到 session_id: " + sessionId);
        }
        // 自动模式：拿到 session_id 后立即进入对话（listen.start 需要带上它）
        maybeStartCallAutomatically();
    }

    /** 官方平台返回绑定验证码，弹出引导弹窗 */
    private void handleBind(JSONObject jsonMessage) {
        String code = jsonMessage.optString("code", "");
        String msg = getString(R.string.bind_dialog_message, code);
        runOnUiThread(() -> {
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.bind_dialog_title)
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton(R.string.bind_dialog_confirm, (dialog, which) -> {
                    dialog.dismiss();
                    bindCodeAnnouncer.stop();
                    webSocketManager.reconnect();
                })
                .show();
            // 无屏设备：语音播报绑定码
            bindCodeAnnouncer.announce(code);
        });
    }

    /**
     * tts.start/sentence_start → 开始播放；tts.stop → 排空后重新开启麦克风。
     * sentence_start 附带文字时，同时显示到聊天气泡（AI 回复只显示一次，sentence_end 同文字不重复）。
     */
    private void handleTts(JSONObject jsonMessage) throws JSONException {
        String state = jsonMessage.optString("state");
        if ("start".equals(state)) {
            // 新一轮回复：清空上一轮残余音频并重置延迟埋点
            addLog("Audio", "准备播放音频（新回合）");
            audioEngine.beginPlayback(true);
        } else if ("sentence_start".equals(state)) {
            // 同一轮回复的下一句：保留 FIFO 中尚未播完的上一句音频
            addLog("Audio", "准备播放音频（下一句）");
            audioEngine.beginPlayback(false);
        } else if ("stop".equals(state)) {
            addLog("Audio", "停止接收新的音频数据，等待播放缓冲区排空");
            audioEngine.endPlayback();
            // 用户说过“退出小智”：让这句告别播完再退出
            if (exitRequested) {
                scheduleExitAfterSpeech();
            }
        }

        if ("sentence_start".equals(state) && jsonMessage.has("text")) {
            displayMessage(jsonMessage.getString("text"), true);
        }
    }

    /** stt → 显示用户说的话；说“退出小智”时自动结束本次运行 */
    private void handleStt(JSONObject jsonMessage) throws JSONException {
        if (jsonMessage.has("text")) {
            String text = jsonMessage.getString("text");
            displayMessage(text, false);
            if (containsExitKeyword(text)) {
                requestKeywordExit();
            }
        }
    }

    /** 去掉空白和常见标点后再匹配，避免“退出 小智。”这类识别结果漏掉。 */
    private static boolean containsExitKeyword(String text) {
        if (text == null) return false;
        String normalized = text.replaceAll("[\\s，。、,.!！?？~～]", "");
        return normalized.contains(EXIT_KEYWORD);
    }

    /**
     * 用户说了“退出小智”：先正常结束语音对话，让助手把告别语播完再退出。
     * 服务端没有回复时用 8 秒兜底，避免一直停在等待状态。
     */
    private void requestKeywordExit() {
        if (exitRequested) return;
        exitRequested = true;
        LogUtils.getInstance().d(this, "XiaoZhi-Control", "识别到“退出小智”，准备退出");
        exitHandler.removeCallbacks(exitRunnable);
        exitHandler.postDelayed(exitRunnable, EXIT_KEYWORD_MAX_WAIT_MS);
    }

    private void scheduleExitAfterSpeech() {
        exitHandler.removeCallbacks(exitRunnable);
        exitHandler.postDelayed(exitRunnable, EXIT_AFTER_SPEECH_DELAY_MS);
    }

    /** 走与电脑端 stop 相同的正常退出流程，并保留强杀兜底。 */
    private void exitByKeyword() {
        if (isFinishing() || isDestroyed()) return;
        exitHandler.removeCallbacks(exitRunnable);
        LogUtils.getInstance().d(this, "XiaoZhi-Control", "按关键词退出：结束界面并释放麦克风");
        shutdownForControl();
        // 释放流程可能卡在音频 HAL 上，到点直接结束进程（下次 start 前应用会重新拉起）
        new Thread(() -> {
            try {
                Thread.sleep(EXIT_KILL_DELAY_MS);
            } catch (InterruptedException ignored) {
                // 仍需结束进程
            }
            Process.killProcess(Process.myPid());
        }, "xiaozhi-keyword-exit").start();
    }

    /** 文字显示规则（参考 py-xiaozhi / xiaozhi-android）：首条消息自动展开，折叠态显示红点+抖动提示 */
    private void displayMessage(String text, boolean isFromServer) {
        runOnUiThread(() -> {
            if (!isMessageExpanded && messageAdapter.getItemCount() == 0) {
                toggleMessageExpansion(); // 首条消息：自动展开
            } else if (!isMessageExpanded) {
                if (messageBadge != null) messageBadge.setVisibility(View.VISIBLE);
                shakeMessageHeader();
            }
            messageAdapter.addMessage(new Message(text, isFromServer));
            if (messagesRecyclerView != null)
                messagesRecyclerView.smoothScrollToPosition(messageAdapter.getItemCount() - 1);
            // 输入中收到新消息会撑高列表、把输入框挤到键盘下面，这里保持输入框可见
            keepInputVisibleIfTyping();
        });
    }

    /** 键盘展开且输入框有焦点时，保证输入框始终位于键盘上方。 */
    private void keepInputVisibleIfTyping() {
        if (messageInput == null || !messageInput.isShown() || !messageInput.isFocused()) return;
        final View input = messageInput;
        final View content = findViewById(android.R.id.content);
        if (content == null) return;
        content.post(() -> {
            if (input.getWidth() <= 0) return;
            int extra = Math.round(16 * getResources().getDisplayMetrics().density);
            input.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, input.getWidth(), input.getHeight() + extra),
                    true);
        });
    }

    @Override
    public void onBinaryMessage(byte[] data) {
        audioEngine.feedEncodedAudio(data);
    }

    // ── AudioEngine.Listener：音频子系统回调，桥接到 UI / WebSocket ──

    @Override
    public void onRecordingAmplitude(float amplitude) {
        runOnUiThread(() -> { if (waveformView != null) waveformView.setAmplitude(amplitude); });
    }

    @Override
    public void onPlaybackAmplitude(float rms) {
        runOnUiThread(() -> { if (waveformView != null) waveformView.setPlayingAmplitude(rms); });
    }

    @Override
    public void onEncodedAudio(byte[] data) {
        webSocketManager.sendBinaryMessage(data);
    }

    @Override
    public void onPlaybackDrained() {
        addLog("Audio", "播放缓冲排空，麦克风已重新开启");
        // 自动重发 listen.start（与 py-xiaozhi 一致）：告知服务端客户端已准备好接收下一轮语音
        if (sessionViewModel.isCallActive()) {
            try {
                JSONObject listenMsg = new JSONObject();
                listenMsg.put("type", "listen");
                listenMsg.put("state", "start");
                listenMsg.put("mode", "auto");
                if (!sessionId.isEmpty()) listenMsg.put("session_id", sessionId);
                webSocketManager.sendMessage(listenMsg.toString());
                addLog("Audio", "已自动重发 listen.start");
            } catch (JSONException e) {
                LogUtils.getInstance().e(this, "XiaoZhi-Voice", "重发 listen.start 失败", e);
            }
        }
    }

    @Override
    public void onLatencySummary(String summary) {
        addLog("Timing", summary);
    }

    @Override
    public void onAudioError(String message) {
        addLog("AudioError", message);
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            if (audioEngine != null && audioEngine.isRecording()) {
                endCall();
            } else {
                setCallUiActive(false);
            }
        });
    }

    /** 隐藏操作引导卡片并记录"已了解"，之后不再展示 */
    private void hideGuide() {
        if (guideCard != null && guideCard.getVisibility() == View.VISIBLE) {
            guideCard.setVisibility(View.GONE);
        }
        getSharedPreferences("guide_prefs", MODE_PRIVATE)
                .edit().putBoolean("has_connected", true).apply();
    }

    private void addLog(String tag, String message) {
        String fullTag = "XiaoZhi-" + tag;
        Log.i(fullTag, message);
        LogUtils.getInstance().d(this, fullTag, message);
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        activityResumed = false;
    }

    /**
     * 这台音箱的厂商语音助手开机后会启动自己的界面（“Pandora 测试用例界面”）把我们的
     * 界面盖住。盒子没有触摸，无法手动切回来，因此开机后两分钟内定时检查：一旦发现自己
     * 不再是前台 Activity，就把自己拉回前台。次数有上限，避免和厂商应用无限互相抢占。
     */
    private final Runnable foregroundTick = new Runnable() {
        @Override
        public void run() {
            if (!bootLaunched || !autoStartEnabled || isFinishing()) return;
            long now = SystemClock.elapsedRealtime();
            if (now - bootLaunchElapsedMs > FOREGROUND_RECLAIM_WINDOW_MS) return;
            if (!activityResumed && !settingsOpen && isScreenOn()
                    && now >= suppressAutoForegroundUntilMs
                    && foregroundReclaims < MAX_FOREGROUND_RECLAIMS) {
                reclaimForeground();
            }
            foregroundHandler.postDelayed(this, FOREGROUND_RECLAIM_TICK_MS);
        }
    };

    private boolean isScreenOn() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm == null || pm.isScreenOn();
        } catch (Exception e) {
            return true;
        }
    }

    private void reclaimForeground() {
        foregroundReclaims++;
        LogUtils.getInstance().d(this, "XiaoZhi-Auto",
                "开机后被其他界面覆盖，自动回到前台（第 " + foregroundReclaims + " 次）");
        // 先尝试直接把自己所在的任务移到前台。实测在这台设备上，从后台 Activity
        // startActivity(REORDER_TO_FRONT) 不会真正切换前台，moveTaskToFront 才有效。
        boolean moved = false;
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            // API 22 上没有 REAL_GET_TASKS 时只返回本应用自己的任务，正好够用
            java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks =
                    am.getRunningTasks(10);
            int taskId = -1;
            for (android.app.ActivityManager.RunningTaskInfo info : tasks) {
                if (info.baseActivity != null
                        && getPackageName().equals(info.baseActivity.getPackageName())) {
                    taskId = info.id;
                    break;
                }
            }
            if (taskId != -1) {
                am.moveTaskToFront(taskId, 0);
                moved = true;
            }
        } catch (Exception e) {
            LogUtils.getInstance().e(this, "XiaoZhi-Auto", "moveTaskToFront 失败", e);
        }

        if (moved) return;
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(EXTRA_AUTO_START, true);
        try {
            startActivity(intent);
        } catch (Exception e) {
            LogUtils.getInstance().e(this, "XiaoZhi-Auto", "回到前台失败", e);
        }
    }

    /**
     * 电脑端脚本“退出”入口（ControlReceiver 调用）：结束界面，让 ViewModel.onCleared()
     * 断开连接并释放音频资源。进程结束由 ControlReceiver 的兜底线程负责。
     */
    public void shutdownForControl() {
        LogUtils.getInstance().d(this, "XiaoZhi-Control", "退出：停止语音对话并释放麦克风");
        userDisconnected = true;
        autoStartEnabled = false;
        autoRetryHandler.removeCallbacks(autoRetryRunnable);
        autoRetryScheduled = false;
        foregroundHandler.removeCallbacksAndMessages(null);
        if (bindCodeAnnouncer != null) {
            bindCodeAnnouncer.stop();
        }
        finishAndRemoveTask();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentRef != null && currentRef.get() == this) {
            currentRef = null;
        }
        if (bindCodeAnnouncer != null) {
            bindCodeAnnouncer.stop();
        }
        foregroundHandler.removeCallbacksAndMessages(null);
        autoRetryHandler.removeCallbacks(autoRetryRunnable);
        autoRetryScheduled = false;
        exitHandler.removeCallbacks(exitRunnable);
        // 连接与音频资源由 SessionViewModel 持有：旋转/配置变更时继续存活，
        // 仅在 Activity 真正 finish 后由 ViewModel.onCleared() 释放。
        // Listener 必须在每次销毁时解绑，否则旧 Activity 会被回调（并泄漏），
        // 新 Activity 在 onCreate 中会重新绑定。
        if (webSocketManager != null) {
            webSocketManager.setListener(null);
        }
        if (audioEngine != null) {
            audioEngine.setListener(null);
        }
        if (isFinishing()) {
            // 用户真正离开页面：结束仍在显示的绑定弹窗并停止 OTA 轮询，
            // 避免轮询线程继续持有已销毁的 Activity 长达 5 分钟。
            if (bindingDialog != null && bindingDialog.isShowing()) {
                bindingDialog.dismiss();
            }
            bindingDialog = null;
            OtaService.stopPolling();
        }
    }
}
