package com.lhht.xiaozhi.websocket;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.lhht.xiaozhi.utils.LogUtils;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

public class WebSocketManager {
    private static final String TAG = "WebSocketManager";
    // 300ms内且无任何服务端消息，才判定为格式/鉴权错误，停止重连
    private static final long AUTH_FAIL_THRESHOLD_MS = 300;
    // 指数退避重连：每次失败延迟递增，封顶 30 秒（对齐 py-xiaozhi min(attempts*2, 30)s）
    private static final long RECONNECT_BASE_DELAY_MS = 2000;
    private static final long RECONNECT_MAX_DELAY_MS = 30000;
    // 连续重连失败达到此次数后停止，避免无限空转耗电
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    // 连接健康检查：超过此时长未收到任何服务端消息，视为连接静默死亡，主动重连
    private static final long HEALTH_CHECK_INTERVAL_MS = 15000;
    private static final long HEALTH_CHECK_SILENCE_THRESHOLD_MS = 30000;
    // WebSocket 库内置心跳超时（秒）：超时未收到 pong 主动断开，触发 onClose 走重连逻辑
    private static final int CONNECTION_LOST_TIMEOUT_SEC = 30;

    private WebSocketClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Context context;
    private final String deviceId;
    private final String clientId;
    private WebSocketListener listener;
    private String serverUrl;
    private String token;
    private boolean enableToken;

    // 用 Runnable 引用替代 isReconnecting 布尔值，防止多个重连定时器并发
    private Runnable pendingReconnect;
    // 记录连接建立时间，用于检测鉴权失败（极短时间内关闭）
    private long connectOpenTime = 0;
    // 连续重连失败次数，连接成功后清零
    private int reconnectAttempts = 0;
    // 最近一次收到服务端任意消息（文本/二进制）的时间，用于健康检查
    private volatile long lastMessageTime = 0;
    private final Runnable healthCheckRunnable = this::checkConnectionHealth;
    private boolean healthCheckScheduled = false;

    public interface WebSocketListener {
        void onConnected();
        void onDisconnected();
        void onError(String error);
        void onMessage(String message);
        void onBinaryMessage(byte[] data);
    }

    public WebSocketManager(Context context, String deviceId, String clientId) {
        this.context = context.getApplicationContext();
        this.deviceId = deviceId;
        this.clientId = clientId;
    }

    public void setListener(WebSocketListener listener) {
        this.listener = listener;
    }

    public void connect(String url, String token, boolean enableToken) {
        this.serverUrl = url;
        this.token = token;
        this.enableToken = enableToken;

        LogUtils.getInstance().d(context, TAG, "准备连接: " + url
                + "  deviceId=" + deviceId + "  clientId=" + clientId);

        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Device-Id", deviceId);
            headers.put("Protocol-Version", "1");
            headers.put("Client-Id", clientId);
            // 参考项目（xiaozhi-android）的 ChatViewModel 中固定使用 "test-token"
            // 官方服务器通过 device-id + 绑定状态（OTA 流程完成后）鉴权
            // 局域网模式：用用户配置的 token
            if (enableToken && token != null && !token.isEmpty()) {
                headers.put("Authorization", "Bearer " + token);
                LogUtils.getInstance().d(context, TAG, "携带用户配置 Token");
            } else {
                // 官方模式：始终发送 test-token（与参考项目一致）
                headers.put("Authorization", "Bearer test-token");
                LogUtils.getInstance().d(context, TAG, "携带 test-token（官方模式默认）");
            }
            if (enableToken && token != null && !token.isEmpty()) {
                headers.put("Authorization", "Bearer " + token);
                LogUtils.getInstance().d(context, TAG, "携带 Authorization header");
            } else {
                LogUtils.getInstance().d(context, TAG, "未携带 Authorization header（Token 未启用或为空）");
            }

            URI uri = URI.create(url);
            client = new WebSocketClient(uri, headers) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    connectOpenTime = System.currentTimeMillis();
                    lastMessageTime = connectOpenTime;
                    reconnectAttempts = 0; // 连接成功，重置重连计数
                    LogUtils.getInstance().d(context, TAG,
                            "WebSocket 已连接，HTTP状态: " + handshakedata.getHttpStatus());
                    // ★ 在 WebSocket 线程直接发 hello，不经 mainHandler
                    // 避免：onClose 先于 mainHandler 回调执行，导致 hello 在关闭后才发
                    sendHelloMessage();
                    mainHandler.post(() -> {
                        if (listener != null) listener.onConnected();
                        scheduleHealthCheck();
                    });
                }

                @Override
                public void onMessage(ByteBuffer bytes) {
                    lastMessageTime = System.currentTimeMillis();
                    byte[] data = new byte[bytes.remaining()];
                    bytes.get(data);
                    mainHandler.post(() -> {
                        if (listener != null) listener.onBinaryMessage(data);
                    });
                }

                @Override
                public void onMessage(String message) {
                    lastMessageTime = System.currentTimeMillis();
                    // 所有服务端文本消息写入日志文件，便于远程调试
                    LogUtils.getInstance().d(context, TAG, "收到文本消息: " + message);
                    mainHandler.post(() -> {
                        if (listener != null) listener.onMessage(message);
                    });
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    long duration = System.currentTimeMillis() - connectOpenTime;
                    String msg = String.format(
                            "WebSocket 关闭 - code:%d reason:\"%s\" remote:%b 连接持续:%dms",
                            code, reason, remote, duration);
                    LogUtils.getInstance().d(context, TAG, msg);

                    mainHandler.post(() -> {
                        cancelHealthCheck();
                        if (listener != null) listener.onDisconnected();

                        // 检测连接建立后极短时间（<300ms）被服务端关闭：协议握手失败
                        // 常见原因：hello version 不匹配、device-id 格式错误等
                        // 此时无限重连无意义，停止并提示用户
                        if (remote && code == 1000 && duration < AUTH_FAIL_THRESHOLD_MS) {
                            String authErr = "服务器拒绝握手（" + duration + "ms 内关闭），"
                                    + "请检查设置是否正确，或重新安装 App 后再试。";
                            LogUtils.getInstance().d(context, TAG, "握手失败，停止重连: " + authErr);
                            if (listener != null) listener.onError(authErr);
                            return;
                        }

                        scheduleReconnect();
                    });
                }

                @Override
                public void onError(Exception ex) {
                    String errMsg = "WebSocket 错误: " + ex.getMessage();
                    LogUtils.getInstance().e(context, TAG, errMsg, ex);
                    mainHandler.post(() -> {
                        cancelHealthCheck();
                        if (listener != null) listener.onError(ex.getMessage());
                        scheduleReconnect();
                    });
                }
            };
            // 启用库内置心跳，超时未收到 pong 视为连接丢失并触发 onClose（P1-5）
            client.setConnectionLostTimeout(CONNECTION_LOST_TIMEOUT_SEC);

            // WSS：禁用证书验证（与 py-xiaozhi ssl._create_unverified_context() 一致）
            if ("wss".equalsIgnoreCase(uri.getScheme())) {
                try {
                    TrustManager[] trustAll = new TrustManager[]{
                        new X509TrustManager() {
                            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        }
                    };
                    SSLContext sslContext = SSLContext.getInstance("TLS");
                    sslContext.init(null, trustAll, new java.security.SecureRandom());
                    client.setSocketFactory(sslContext.getSocketFactory());
                    LogUtils.getInstance().d(context, TAG, "WSS SSL 配置成功（禁用证书验证）");
                } catch (Exception sslEx) {
                    LogUtils.getInstance().e(context, TAG, "WSS SSL 初始化失败", sslEx);
                    if (listener != null) listener.onError("SSL 配置失败: " + sslEx.getMessage());
                    return;
                }
            }

            client.connect();
            LogUtils.getInstance().d(context, TAG, "connect() 已调用（非阻塞）");

        } catch (Exception e) {
            LogUtils.getInstance().e(context, TAG, "创建 WebSocket 失败", e);
            if (listener != null) listener.onError(e.getMessage());
        }
    }

    /**
     * 安全调度重连：指数退避（2s, 4s, 8s... 封顶 30s），
     * 连续失败达到 MAX_RECONNECT_ATTEMPTS 次后停止并通知 UI（P1-4）。
     */
    private void scheduleReconnect() {
        cancelPendingReconnect();
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            String err = "已连续重连失败 " + reconnectAttempts + " 次，请检查网络或服务器地址后手动重试。";
            LogUtils.getInstance().d(context, TAG, "达到最大重连次数，停止重连: " + err);
            if (listener != null) listener.onError(err);
            return;
        }
        reconnectAttempts++;
        long delay = Math.min(RECONNECT_BASE_DELAY_MS * (1L << (reconnectAttempts - 1)), RECONNECT_MAX_DELAY_MS);
        LogUtils.getInstance().d(context, TAG,
                "第 " + reconnectAttempts + " 次重连，" + delay / 1000 + "秒后尝试...");
        pendingReconnect = () -> {
            pendingReconnect = null;
            connect(serverUrl, token, enableToken);
        };
        mainHandler.postDelayed(pendingReconnect, delay);
    }

    private void cancelPendingReconnect() {
        if (pendingReconnect != null) {
            mainHandler.removeCallbacks(pendingReconnect);
            pendingReconnect = null;
        }
    }

    /**
     * 连接健康主动探测（P1-5）：某些网络环境下 TCP 连接会静默死亡而不触发 onClose，
     * 若超过 HEALTH_CHECK_SILENCE_THRESHOLD_MS 未收到任何服务端消息，主动断开重连。
     */
    private void checkConnectionHealth() {
        if (client == null || !client.isOpen()) {
            healthCheckScheduled = false;
            return;
        }
        long silence = System.currentTimeMillis() - lastMessageTime;
        if (silence > HEALTH_CHECK_SILENCE_THRESHOLD_MS) {
            LogUtils.getInstance().d(context, TAG,
                    "连接静默 " + silence + "ms 无响应，判定连接已死，主动重连");
            healthCheckScheduled = false;
            client.close();
            return;
        }
        mainHandler.postDelayed(healthCheckRunnable, HEALTH_CHECK_INTERVAL_MS);
    }

    private void scheduleHealthCheck() {
        if (healthCheckScheduled) return;
        healthCheckScheduled = true;
        mainHandler.postDelayed(healthCheckRunnable, HEALTH_CHECK_INTERVAL_MS);
    }

    private void cancelHealthCheck() {
        healthCheckScheduled = false;
        mainHandler.removeCallbacks(healthCheckRunnable);
    }

    /** 绑定完成后重新鉴权 */
    public void reconnect() {
        LogUtils.getInstance().d(context, TAG, "主动调用 reconnect()");
        cancelPendingReconnect();
        cancelHealthCheck();
        if (client != null && !client.isClosed()) {
            client.close();
        }
        mainHandler.postDelayed(() -> connect(serverUrl, token, enableToken), 500);
    }

    /** 主动断开，不触发自动重连 */
    public void disconnect() {
        LogUtils.getInstance().d(context, TAG, "主动断开连接");
        cancelPendingReconnect();
        cancelHealthCheck();
        if (client != null && client.isOpen()) {
            client.close();
        }
    }

    private void sendHelloMessage() {
        try {
            JSONObject hello = new JSONObject();
            hello.put("type", "hello");
            hello.put("version", 1);  // 官方协议版本号为 1，与 Protocol-Version header 保持一致
            hello.put("transport", "websocket");
            JSONObject audioParams = new JSONObject();
            audioParams.put("format", "opus");
            audioParams.put("sample_rate", 16000);
            audioParams.put("channels", 1);
            audioParams.put("frame_duration", 60);
            hello.put("audio_params", audioParams);
            sendMessage(hello.toString());
            LogUtils.getInstance().d(context, TAG, "已发送 hello 握手消息");
        } catch (JSONException e) {
            LogUtils.getInstance().e(context, TAG, "构造 hello 消息失败", e);
        }
    }

    public boolean isConnected() {
        return client != null && client.isOpen();
    }

    public void sendMessage(String message) {
        if (client != null && client.isOpen()) {
            client.send(message);
        }
    }

    public void sendBinaryMessage(byte[] data) {
        if (client != null && client.isOpen()) {
            client.send(data);
        }
    }
}
