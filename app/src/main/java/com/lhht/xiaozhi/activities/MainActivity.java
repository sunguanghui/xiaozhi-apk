package com.lhht.xiaozhi.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.lhht.xiaozhi.utils.OtaService;
import com.lhht.xiaozhi.utils.UpdateChecker;

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
import com.lhht.xiaozhi.audio.OpusUtils;

import org.json.JSONObject;
import org.json.JSONException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements WebSocketManager.WebSocketListener {
    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
    private static final int PLAY_BUFFER_SIZE = BUFFER_SIZE * 4;  // 播放缓冲区设置为录音缓冲区的4倍
    private static final int OPUS_FRAME_SIZE = 960; // 60ms at 16kHz

    private WebSocketManager webSocketManager;
    private SettingsManager settingsManager;
    private TextView connectionStatus;
    private Button connectButton;
    private ImageButton sendButton;
    private EditText messageInput;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private boolean isRecording = false;
    private ExecutorService executorService;
    private boolean isPlaying = false;
    private byte[] audioBuffer;
    private OpusUtils opusUtils;
    private long encoderHandle;
    private long decoderHandle;
    private short[] decodedBuffer;
    private short[] recordBuffer;
    private TextView callStatusText;
    private WaveformView waveformView;
    private View voiceContainer;
    private View statusDot;
    private ExecutorService audioExecutor;  // 音频处理线程池
    private String sessionId = ""; // 服务器 hello 里返回的 session_id
    // TTS 停止后清空回声帧的截止时间（对齐 py-xiaozhi clear_audio_queue）
    private volatile long flushUntilMs = 0;
    private MessageAdapter messageAdapter;
    private RecyclerView messagesRecyclerView;
    // 消息历史折叠/展开
    private View messageHeaderLayout;
    private View messageContentLayout;
    private ImageView messageExpandIcon;
    private boolean isMessageExpanded = false;
    private androidx.appcompat.app.AlertDialog bindingDialog; // 绑定验证码弹窗，激活成功后自动关闭

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

        // 消息历史折叠/展开
        messageHeaderLayout.setOnClickListener(v -> toggleMessageExpansion());
        
        Log.i("MainActivity", "应用启动");

        // 初始化
        settingsManager = new SettingsManager(this);
        // 使用小写带冒号 MAC 格式（与 ESP32 固件及参考项目 xiaozhi-android 完全一致）
        String deviceId = settingsManager.getFormattedDeviceId(this);
        String clientId = settingsManager.getClientId();
        Log.i("MainActivity", "设备ID: " + deviceId + "  ClientId: " + clientId);
        webSocketManager = new WebSocketManager(this, deviceId, clientId);
        webSocketManager.setListener(this);
        executorService = Executors.newSingleThreadExecutor();
        audioExecutor = Executors.newSingleThreadExecutor();

        // 初始化音频播放器
        int minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AUDIO_FORMAT
        );
        Log.i("MainActivity", "AudioTrack最小缓冲区: " + minBufferSize + " 字节");
        
        try {
            audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(Math.max(minBufferSize * 8, 32768))  // 增大缓冲区
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();
            
            int state = audioTrack.getState();
            if (state == AudioTrack.STATE_INITIALIZED) {
                Log.i("MainActivity", "AudioTrack初始化成功");
                audioTrack.play();
            } else {
                Log.e("MainActivity", "AudioTrack初始化失败: " + state);
            }
        } catch (Exception e) {
            Log.e("MainActivity", "创建AudioTrack失败", e);
        }

        // 初始化 Opus 编解码器
        opusUtils = OpusUtils.getInstance();
        encoderHandle = opusUtils.createEncoder(SAMPLE_RATE, 1, 10);
        decoderHandle = opusUtils.createDecoder(SAMPLE_RATE, 1);
        decodedBuffer = new short[OPUS_FRAME_SIZE];
        recordBuffer = new short[OPUS_FRAME_SIZE];

        // 初始化视图
        connectionStatus = findViewById(R.id.connectionStatus);
        connectButton = findViewById(R.id.connectButton);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);

        // 绑定新布局按钮
        ImageButton voiceButton = findViewById(R.id.voiceButton);
        ImageButton settingsButton = findViewById(R.id.settingsButton);

        // 设置按钮点击事件
        if (connectButton != null) connectButton.setOnClickListener(v -> toggleConnection());
        if (voiceButton != null) voiceButton.setOnClickListener(v -> toggleRecording());
        if (sendButton != null) sendButton.setOnClickListener(v -> sendMessage());
        if (settingsButton != null) settingsButton.setOnClickListener(v -> openSettings());

        // 检查并请求权限
        checkPermissions();

        // 聊天记录输入框获焦时，滚动 NestedScrollView 确保键盘不遮挡输入框
        androidx.core.widget.NestedScrollView scrollView = findViewById(R.id.mainScrollView);
        if (scrollView != null && messageInput != null) {
            messageInput.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    // 等键盘动画完成后滚动到最底部，确保输入框可见
                    messageInput.postDelayed(() -> scrollView.fullScroll(View.FOCUS_DOWN), 200);
                }
            });
        }

        // 启动时静默检查版本更新
        UpdateChecker.checkOnStartup(this);
    }

    private void toggleMessageExpansion() {
        isMessageExpanded = !isMessageExpanded;
        messageContentLayout.setVisibility(isMessageExpanded ? View.VISIBLE : View.GONE);
        // 旋转展开图标
        messageExpandIcon.animate()
                .rotation(isMessageExpanded ? 180 : 0)
                .setDuration(200)
                .start();
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
                    .setCancelable(true)
                    .setNegativeButton("取消", (dialog, which) -> {
                        dialog.dismiss();
                        bindingDialog = null;
                        connectionStatus.setText(getString(R.string.status_disconnected));
                        updateStatusDot(R.color.status_disconnected);
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
        if (!isRecording) {
            startCall();
        } else {
            endCall();
        }
    }

    private void startCall() {
        if (!webSocketManager.isConnected()) {
            Toast.makeText(this, "请先连接服务器", Toast.LENGTH_SHORT).show();
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
            Log.e("XiaoZhi-Voice", "发送开始通话消息失败: " + e.getMessage());
            return;
        }

        if (audioRecord == null) {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE);
        }

        isRecording = true;
        runOnUiThread(() -> {
            voiceContainer.setVisibility(View.VISIBLE);
            callStatusText.setVisibility(View.VISIBLE);
            callStatusText.setText(R.string.calling);
        });
        
        executorService.execute(() -> {
            audioRecord.startRecording();
            short[] buffer = new short[OPUS_FRAME_SIZE]; // 960 samples
            byte[] encodedBuffer = new byte[1024]; // Opus编码后的缓冲区
            long lastAudioTime = System.currentTimeMillis();
            
            while (isRecording) {
                int samplesRead = audioRecord.read(buffer, 0, OPUS_FRAME_SIZE);
                if (samplesRead > 0) {
                    // 检测音量并更新波形
                    float amplitude = 0;
                    for (int i = 0; i < samplesRead; i++) {
                        amplitude = Math.max(amplitude, Math.abs(buffer[i]) / 32768.0f);
                    }
                    final float finalAmplitude = amplitude;
                    runOnUiThread(() -> waveformView.setAmplitude(finalAmplitude));
                    
                    // 检测静音
                    boolean isSilent = amplitude < 0.02f; // 2%的阈值
                    
                    // 更新最后一次有声音的时间
                    if (!isSilent) {
                        lastAudioTime = System.currentTimeMillis();
                    }
                    
                    // 编码为 Opus
                    // 参考 xiaozhi-android-client audio_util.dart：
                    // 当 samplesRead < 960 时补零，避免 Opus 编码器因帧不足而失败
                    if (samplesRead < OPUS_FRAME_SIZE) {
                        for (int i = samplesRead; i < OPUS_FRAME_SIZE; i++) {
                            buffer[i] = 0;
                        }
                    }
                    int encodedBytes = opusUtils.encode(encoderHandle, buffer, 0, encodedBuffer);
                    if (encodedBytes > 0) {
                        long now = System.currentTimeMillis();
                        // isPlaying=true：AI 正在说话，跳过发送（防止实时回声）
                        // flushUntilMs：AudioTrack 刚排空，清空 AudioRecord 里积压的回声帧
                        // 对齐 py-xiaozhi clear_audio_queue / xiaozhi-android waitForPlaybackCompletion
                        if (!isPlaying && now >= flushUntilMs) {
                            byte[] encodedData = new byte[encodedBytes];
                            System.arraycopy(encodedBuffer, 0, encodedData, 0, encodedBytes);
                            webSocketManager.sendBinaryMessage(encodedData);
                        }
                    } else {
                        Log.e("XiaoZhi-Voice", "Opus编码失败: " + encodedBytes);
                    }

                    // 静音超时：只在非播放且缓冲已清空时发送静音帧
                    if (!isPlaying && System.currentTimeMillis() >= flushUntilMs
                            && System.currentTimeMillis() - lastAudioTime > 1000) {
                        // 发送静音帧
                        short[] silenceFrame = new short[OPUS_FRAME_SIZE];
                        int silenceBytes = opusUtils.encode(encoderHandle, silenceFrame, 0, encodedBuffer);
                        if (silenceBytes > 0) {
                            byte[] silenceData = new byte[silenceBytes];
                            System.arraycopy(encodedBuffer, 0, silenceData, 0, silenceBytes);
                            webSocketManager.sendBinaryMessage(silenceData);
                        }
                        runOnUiThread(() -> waveformView.setAmplitude(0));
                    }
                }
            }
        });
    }

    private void endCall() {
        isRecording = false;
        isPlaying = false; // 重置播放状态，防止下次通话被屏蔽
        runOnUiThread(() -> {
            voiceContainer.setVisibility(View.GONE);
            callStatusText.setVisibility(View.GONE);
            waveformView.setAmplitude(0);
        });

        // 释放 AudioRecord，下次通话重新创建，避免复用已停止的实例导致第二次通话失效
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }

        // 发送停止通话消息
        try {
            JSONObject stopMessage = new JSONObject();
            stopMessage.put("type", "listen");
            stopMessage.put("state", "stop");
            if (!sessionId.isEmpty()) stopMessage.put("session_id", sessionId);
            webSocketManager.sendMessage(stopMessage.toString());
        } catch (JSONException e) {
            Log.e("XiaoZhi-Voice", "发送停止通话消息失败: " + e.getMessage());
        }
    }

    private void sendMessage() {
        String message = messageInput.getText().toString().trim();
        if (!message.isEmpty() && webSocketManager.isConnected()) {
            try {
                JSONObject jsonMessage = new JSONObject();
                jsonMessage.put("type", "listen");
                jsonMessage.put("state", "detect");
                jsonMessage.put("text", message);
                jsonMessage.put("source", "text");
                if (!sessionId.isEmpty()) jsonMessage.put("session_id", sessionId);
                webSocketManager.sendMessage(jsonMessage.toString());
                messageAdapter.addMessage(new Message(message, false));  // 添加用户消息
                messageInput.setText("");
                messagesRecyclerView.smoothScrollToPosition(messageAdapter.getItemCount() - 1);  // 滚动到底部
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    @Override
    public void onConnected() {
        addLog("WebSocket", "已连接");
        runOnUiThread(() -> {
            connectionStatus.setText(R.string.status_connected);
            connectButton.setText(R.string.disconnect);
            updateStatusDot(R.color.status_connected);
        });
    }

    @Override
    public void onDisconnected() {
        addLog("WebSocket", "已断开");
        runOnUiThread(() -> {
            connectionStatus.setText(R.string.status_disconnected);
            connectButton.setText(R.string.connect);
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

    @Override
    public void onMessage(String message) {
        addLog("Message", message);
        try {
            JSONObject jsonMessage = new JSONObject(message);
            String type = jsonMessage.getString("type");
            String state = jsonMessage.optString("state");
            
            // 服务器 hello 消息：保存 session_id，后续所有消息都需要携带
            if ("hello".equals(type)) {
                String sid = jsonMessage.optString("session_id", "");
                if (!sid.isEmpty()) {
                    sessionId = sid;
                    Log.i("XiaoZhi", "已收到 session_id: " + sessionId);
                }
                return;
            }

            if ("bind".equals(type)) {
                // 官方平台返回绑定验证码，弹出引导弹窗
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
                return;
            }

            if ("tts".equals(type)) {
                if ("start".equals(state) || "sentence_start".equals(state)) {
                    addLog("Audio", "准备播放音频");
                    // 开始播放音频
                    isPlaying = true;
                    audioExecutor.execute(() -> {
                        if (audioTrack != null) {
                            try {
                                if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                                    addLog("Audio", "重新初始化AudioTrack");
                                    // 重新初始化AudioTrack
                                    int minBufferSize = AudioTrack.getMinBufferSize(
                                        SAMPLE_RATE,
                                        AudioFormat.CHANNEL_OUT_MONO,
                                        AUDIO_FORMAT
                                    );
                                    audioTrack = new AudioTrack.Builder()
                                        .setAudioAttributes(new AudioAttributes.Builder()
                                            .setUsage(AudioAttributes.USAGE_MEDIA)
                                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                            .build())
                                        .setAudioFormat(new AudioFormat.Builder()
                                            .setEncoding(AUDIO_FORMAT)
                                            .setSampleRate(SAMPLE_RATE)
                                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                            .build())
                                        .setBufferSizeInBytes(Math.max(minBufferSize * 8, 32768))
                                        .setTransferMode(AudioTrack.MODE_STREAM)
                                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                                        .build();
                                }
                                
                                if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                                    audioTrack.play();
                                    addLog("Audio", "AudioTrack开始播放");
                                }
                            } catch (Exception e) {
                                addLog("Audio", "AudioTrack初始化/播放失败: " + e.getMessage());
                            }
                        }
                    });
                } else if ("stop".equals(state)) {
                    addLog("Audio", "停止接收新的音频数据，等待播放缓冲区排空");
                    // 不立即设 isPlaying=false：AudioTrack 缓冲区里还有音频待播
                    // 提交到 audioExecutor 队列末尾，等所有写入任务完成后再轮询排空
                    audioExecutor.execute(() -> {
                        waitForAudioTrackDrain();
                        isPlaying = false;
                        addLog("Audio", "AudioTrack 缓冲排空，麦克风已重新开启");
                        // 自动重发 listen.start（与 py-xiaozhi 一致）
                        // 告知服务端客户端已准备好接收下一轮语音
                        if (isRecording) {
                            try {
                                JSONObject listenMsg = new JSONObject();
                                listenMsg.put("type", "listen");
                                listenMsg.put("state", "start");
                                listenMsg.put("mode", "auto");
                                if (!sessionId.isEmpty()) listenMsg.put("session_id", sessionId);
                                webSocketManager.sendMessage(listenMsg.toString());
                                addLog("Audio", "已自动重发 listen.start");
                            } catch (JSONException e) {
                                Log.e("XiaoZhi-Voice", "重发 listen.start 失败: " + e.getMessage());
                            }
                        }
                    });
                }
            }
            
            // 文字显示规则（参考 py-xiaozhi / xiaozhi-android）：
            // - tts.sentence_start → 显示 AI 回复（只显示一次，sentence_end 同文字不再重复）
            // - stt              → 显示用户说的话（isFromServer=false）
            // - llm / 其他       → 只含 emotion/emoji，不加入聊天气泡
            if ("tts".equals(type) && "sentence_start".equals(state) && jsonMessage.has("text")) {
                String text = jsonMessage.getString("text");
                runOnUiThread(() -> {
                    messageAdapter.addMessage(new Message(text, true));
                    messagesRecyclerView.smoothScrollToPosition(messageAdapter.getItemCount() - 1);
                });
            } else if ("stt".equals(type) && jsonMessage.has("text")) {
                String text = jsonMessage.getString("text");
                runOnUiThread(() -> {
                    messageAdapter.addMessage(new Message(text, false)); // 用户的话
                    messagesRecyclerView.smoothScrollToPosition(messageAdapter.getItemCount() - 1);
                });
            }
        } catch (JSONException e) {
            addLog("Error", "解析消息失败: " + e.getMessage());
        }
    }

    @Override
    public void onBinaryMessage(byte[] data) {
        Log.i("XiaoZhi-Audio", String.format("收到音频数据: %d 字节, isPlaying=%b", data.length, isPlaying));
        
        if (!isPlaying) {
            Log.i("XiaoZhi-Audio", "当前不在接收状态，忽略音频数据");
            return;
        }

        // 复制数据，避免被修改
        final byte[] audioData = data.clone();
        
        // 在主线程中检查状态
        if (audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e("XiaoZhi-Audio", "错误: AudioTrack未初始化或状态错误");
            return;
        }

        if (decoderHandle == 0) {
            Log.e("XiaoZhi-Audio", "错误: Opus解码器未初始化");
            return;
        }

        // 在音频线程中处理
        audioExecutor.execute(() -> {
            try {
                // 解码 Opus 数据
                int decodedSamples = opusUtils.decode(decoderHandle, audioData, decodedBuffer);
                
                if (decodedSamples < 0) {
                    Log.e("XiaoZhi-Audio", String.format("Opus解码失败: %d", decodedSamples));
                    return;
                }
                
                if (decodedSamples == 0) {
                    return;
                }

                // 将 short[] 转换为 byte[]
                byte[] pcmData = new byte[decodedSamples * 2];
                for (int i = 0; i < decodedSamples; i++) {
                    short sample = decodedBuffer[i];
                    // 使用小端序（同Web端）
                    pcmData[i * 2] = (byte) (sample & 0xff);
                    pcmData[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
                }
                
                // 使用阻塞模式写入，确保数据完整性
                int written = 0;
                int retryCount = 0;
                while (written < pcmData.length && retryCount < 3) {  // 移除 isPlaying 检查，让数据继续写入
                    if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack.play();
                        Log.i("XiaoZhi-Audio", "重新开始播放");
                    }
                    
                    int remaining = pcmData.length - written;
                    int result = audioTrack.write(pcmData, written, remaining, AudioTrack.WRITE_BLOCKING);
                    if (result < 0) {
                        Log.e("XiaoZhi-Audio", String.format("写入音频数据失败: %d", result));
                        retryCount++;
                        continue;
                    } else if (result == 0) {
                        break;
                    }
                    written += result;
                }
                
                Log.i("XiaoZhi-Audio", String.format("成功写入 %d/%d 字节", written, pcmData.length));
            } catch (Exception e) {
                Log.e("XiaoZhi-Audio", "播放音频失败: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void addLog(String tag, String message) {
        Log.i("XiaoZhi-" + tag, message);
    }

    /**
     * 等待 AudioTrack 缓冲区排空，然后设置 flushUntilMs。
     * 对齐 py-xiaozhi clear_audio_queue + xiaozhi-android waitForPlaybackCompletion：
     * 排空后额外丢弃 300ms 的录音帧，清除 AudioRecord 缓冲区里的 TTS 回声。
     */
    private void waitForAudioTrackDrain() {
        if (audioTrack == null) return;
        try {
            int stableCount = 0;
            int lastPos = audioTrack.getPlaybackHeadPosition();
            while (stableCount < 5) {
                Thread.sleep(100);
                int currentPos = audioTrack.getPlaybackHeadPosition();
                if (currentPos == lastPos) {
                    stableCount++;
                } else {
                    stableCount = 0;
                    lastPos = currentPos;
                }
            }
        } catch (InterruptedException ignored) {}
        // AudioTrack 排空后，再丢弃 300ms 录音帧（清除 AudioRecord 里积压的回声帧）
        flushUntilMs = System.currentTimeMillis() + 300;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        webSocketManager.disconnect();
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }
        if (encoderHandle != 0) {
            opusUtils.destroyEncoder(encoderHandle);
            encoderHandle = 0;
        }
        if (decoderHandle != 0) {
            opusUtils.destroyDecoder(decoderHandle);
            decoderHandle = 0;
        }
        executorService.shutdown();
        audioExecutor.shutdown();
    }
} 