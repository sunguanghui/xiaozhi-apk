package com.lhht.xiaozhi.activities;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.content.pm.PackageManager;
import android.os.Bundle;
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

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lhht.xiaozhi.R;
import com.lhht.xiaozhi.adapters.MessageAdapter;
import com.lhht.xiaozhi.models.Message;
import com.lhht.xiaozhi.settings.SettingsManager;
import com.lhht.xiaozhi.views.WaveformView;
import com.lhht.xiaozhi.websocket.WebSocketManager;
import com.lhht.xiaozhi.audio.AudioEngine;

import org.json.JSONObject;
import org.json.JSONException;

public class MainActivity extends AppCompatActivity
        implements WebSocketManager.WebSocketListener, AudioEngine.Listener {
    private static final int PERMISSION_REQUEST_CODE = 1;

    private WebSocketManager webSocketManager;
    private SettingsManager settingsManager;
    private AudioEngine audioEngine;
    private TextView connectionStatus;
    private Button connectButton;
    private ImageButton sendButton;
    private EditText messageInput;
    private TextView callStatusText;
    private WaveformView waveformView;
    private View voiceContainer;
    private View statusDot;
    private String sessionId = ""; // 服务器 hello 里返回的 session_id
    private MessageAdapter messageAdapter;
    private RecyclerView messagesRecyclerView;
    // 消息历史折叠/展开
    private View messageHeaderLayout;
    private View messageContentLayout;
    private ImageView messageExpandIcon;
    private boolean isMessageExpanded = false;
    private androidx.appcompat.app.AlertDialog bindingDialog;
    private View guideCard; // 操作引导卡片
    private FloatingActionButton voiceButton; // 语音通话按钮
    private TextView voiceHintText; // 通话按钮下方说明文字
    private View messageBadge; // 折叠态新消息红点
    private ObjectAnimator shakeAnimator; // 标题栏抖动动画（节流复用）

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i("MainActivity", "应用启动");
        setContentView(R.layout.activity_main);

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
        // 使用小写带冒号 MAC 格式（与 ESP32 固件及参考项目 xiaozhi-android 完全一致）
        String deviceId = settingsManager.getFormattedDeviceId(this);
        String clientId = settingsManager.getClientId();
        Log.i("MainActivity", "设备ID: " + deviceId + "  ClientId: " + clientId);
        webSocketManager = new WebSocketManager(this, deviceId, clientId);
        webSocketManager.setListener(this);

        // 音频子系统：录音/播放/Opus 编解码/应用层播放缓冲，全部封装在 AudioEngine 里
        audioEngine = new AudioEngine(this, this);
        audioEngine.init();

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
                if (audioEngine.isRecording()) return false;
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                openSettings();
                return true;
            });
        }
        if (sendButton != null) sendButton.setOnClickListener(v -> sendMessage());
        if (settingsButton != null) settingsButton.setOnClickListener(v -> openSettings());

        // 检查并请求权限
        checkPermissions();

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
                webSocketManager.connect(wsUrl, token, enableToken);
            }
        } else {
            webSocketManager.disconnect();
        }
    }

    /** 官方平台连接流程：OTA → 激活码弹窗（后台轮询）或 直接连 WebSocket */
    private void connectOfficialServer() {
        connectionStatus.setText("正在获取激活状态…");

        OtaService.checkActivation(this, settingsManager, new OtaService.OtaCallback() {
            @Override
            public void onActivationRequired(String code, String message) {
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
            }

            @Override
            public void onAlreadyActivated() {
                // 关闭绑定弹窗（如果还在显示）
                if (bindingDialog != null && bindingDialog.isShowing()) {
                    bindingDialog.dismiss();
                    bindingDialog = null;
                }
                doConnectWebSocket();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(MainActivity.this, "OTA 错误: " + error, Toast.LENGTH_LONG).show();
                connectionStatus.setText(getString(R.string.status_error));
                updateStatusDot(R.color.status_error);
            }
        });
    }

    private void doConnectWebSocket() {
        // 官方模式：使用 OTA 返回的 URL 和 token（默认 test-token）
        // 局域网模式：使用用户配置的 URL 和 token
        String wsUrl  = settingsManager.getEffectiveWsUrl();
        String token  = settingsManager.isUseOfficialServer()
                ? settingsManager.getOtaToken()
                : settingsManager.getToken();
        boolean useToken = !token.isEmpty();
        webSocketManager.connect(wsUrl, token, useToken);
    }

    private void toggleRecording() {
        if (!audioEngine.isRecording()) {
            startCall();
        } else {
            endCall();
        }
    }

    private void startCall() {
        if (!webSocketManager.isConnected()) {
            // 未连接时弹引导 Dialog，提供"去连接"快捷入口
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

        runOnUiThread(() -> {
            if (voiceContainer != null) voiceContainer.setVisibility(View.VISIBLE);
            if (callStatusText != null) {
                callStatusText.setVisibility(View.VISIBLE);
                callStatusText.setText(R.string.calling);
            }
            // 通话中：深蓝色 + 停止图标 + 文案更新
            if (voiceButton != null) {
                voiceButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.primary_dark)));
                voiceButton.setImageResource(R.drawable.ic_stop);
                voiceButton.setContentDescription("结束通话"); // R-4
            }
            if (voiceHintText != null) voiceHintText.setText("点击结束通话");
        });

        audioEngine.startRecording();
    }

    private void endCall() {
        audioEngine.stopRecording();
        runOnUiThread(() -> {
            if (voiceContainer != null) voiceContainer.setVisibility(View.GONE);
            if (callStatusText != null) callStatusText.setVisibility(View.GONE);
            if (waveformView != null) waveformView.setAmplitude(0);
            // 恢复待机态：亮蓝色 + 麦克风图标 + 文案恢复
            if (voiceButton != null) {
                voiceButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.primary)));
                voiceButton.setImageResource(R.drawable.ic_mic);
                voiceButton.setContentDescription("开始聊天"); // R-4
            }
            if (voiceHintText != null) voiceHintText.setText("点击开始聊天");
        });

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
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
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
            connectionStatus.setText(R.string.status_connected);
            connectButton.setText(R.string.disconnect);
            connectButton.setEnabled(true);
            updateStatusDot(R.color.status_connected);
            // 第一次连接成功时自动关闭操作引导
            hideGuide();
        });
    }

    @Override
    public void onDisconnected() {
        addLog("WebSocket", "已断开");
        runOnUiThread(() -> {
            connectionStatus.setText(R.string.status_disconnected);
            connectButton.setText(R.string.connect);
            connectButton.setEnabled(true);
            updateStatusDot(R.color.status_disconnected);
            endCall();
        });
    }

    @Override
    public void onError(String error) {
        addLog("Error", error);
        runOnUiThread(() -> {
            connectionStatus.setText(R.string.status_error);
            updateStatusDot(R.color.status_error);
            connectButton.setText("重新连接");
            connectButton.setEnabled(true);
            Toast.makeText(this, "错误: " + error, Toast.LENGTH_SHORT).show();
        });
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
    }

    /** 官方平台返回绑定验证码，弹出引导弹窗 */
    private void handleBind(JSONObject jsonMessage) {
        String code = jsonMessage.optString("code", "");
        String msg = getString(R.string.bind_dialog_message, code);
        runOnUiThread(() ->
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.bind_dialog_title)
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton(R.string.bind_dialog_confirm, (dialog, which) -> {
                    dialog.dismiss();
                    webSocketManager.reconnect();
                })
                .show()
        );
    }

    /**
     * tts.start/sentence_start → 开始播放；tts.stop → 排空后重新开启麦克风。
     * sentence_start 附带文字时，同时显示到聊天气泡（AI 回复只显示一次，sentence_end 同文字不重复）。
     */
    private void handleTts(JSONObject jsonMessage) throws JSONException {
        String state = jsonMessage.optString("state");
        if ("start".equals(state) || "sentence_start".equals(state)) {
            addLog("Audio", "准备播放音频");
            audioEngine.beginPlayback();
        } else if ("stop".equals(state)) {
            addLog("Audio", "停止接收新的音频数据，等待播放缓冲区排空");
            audioEngine.endPlayback();
        }

        if ("sentence_start".equals(state) && jsonMessage.has("text")) {
            displayMessage(jsonMessage.getString("text"), true);
        }
    }

    /** stt → 显示用户说的话 */
    private void handleStt(JSONObject jsonMessage) throws JSONException {
        if (jsonMessage.has("text")) {
            displayMessage(jsonMessage.getString("text"), false);
        }
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
        if (audioEngine.isRecording()) {
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
    protected void onDestroy() {
        super.onDestroy();
        webSocketManager.disconnect();
        audioEngine.release();
    }
}
