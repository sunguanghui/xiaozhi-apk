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

public class WebSocketManager {
    private static final String TAG = "WebSocketManager";
    private static final int RECONNECT_DELAY = 5000;
    // 300ms内且无任何服务端消息，才判定为格式/鉴权错误，停止重连
    private static final long AUTH_FAIL_THRESHOLD_MS = 300;

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
            headers.put("device-id", deviceId);
            headers.put("Protocol-Version", "1");
            headers.put("Client-Id", clientId);
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
                    LogUtils.getInstance().d(context, TAG,
                            "WebSocket 已连接，HTTP状态: " + handshakedata.getHttpStatus());
                    // ★ 在 WebSocket 线程直接发 hello，不经 mainHandler
                    // 避免：onClose 先于 mainHandler 回调执行，导致 hello 在关闭后才发
                    sendHelloMessage();
                    mainHandler.post(() -> {
                        if (listener != null) listener.onConnected();
                    });
                }

                @Override
                public void onMessage(ByteBuffer bytes) {
                    byte[] data = new byte[bytes.remaining()];
                    bytes.get(data);
                    mainHandler.post(() -> {
                        if (listener != null) listener.onBinaryMessage(data);
                    });
                }

                @Override
                public void onMessage(String message) {
                    Log.d(TAG, "Received: " + message);
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
                        if (listener != null) listener.onDisconnected();

                        // ★ 检测鉴权失败：连接建立后不到 800ms 就被服务端关闭（code:1000）
                        // 此时无限重连无意义，应停止并提示用户检查 Token
                        if (remote && code == 1000 && duration < AUTH_FAIL_THRESHOLD_MS) {
                            String authErr = "服务器拒绝连接（" + duration + "ms 内关闭），"
                                    + "请检查官方平台 Token 是否正确填写。"
                                    + "前往 https://xiaozhi.me 控制台获取设备 OTA Token。";
                            LogUtils.getInstance().d(context, TAG, "判定为鉴权失败，停止重连: " + authErr);
                            if (listener != null) listener.onError(authErr);
                            return; // 不再自动重连
                        }

                        scheduleReconnect();
                    });
                }

                @Override
                public void onError(Exception ex) {
                    String errMsg = "WebSocket 错误: " + ex.getMessage();
                    LogUtils.getInstance().e(context, TAG, errMsg, ex);
                    mainHandler.post(() -> {
                        if (listener != null) listener.onError(ex.getMessage());
                        scheduleReconnect();
                    });
                }
            };

            // WSS：配置 TLS
            if ("wss".equalsIgnoreCase(uri.getScheme())) {
                try {
                    SSLContext sslContext = SSLContext.getInstance("TLS");
                    sslContext.init(null, null, null);
                    client.setSocketFactory(sslContext.getSocketFactory());
                    LogUtils.getInstance().d(context, TAG, "WSS SSL 配置成功");
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

    /** 安全调度重连：取消已有定时器后重新排队，杜绝重连风暴 */
    private void scheduleReconnect() {
        cancelPendingReconnect();
        LogUtils.getInstance().d(context, TAG, RECONNECT_DELAY / 1000 + "秒后尝试自动重连...");
        pendingReconnect = () -> {
            pendingReconnect = null;
            connect(serverUrl, token, enableToken);
        };
        mainHandler.postDelayed(pendingReconnect, RECONNECT_DELAY);
    }

    private void cancelPendingReconnect() {
        if (pendingReconnect != null) {
            mainHandler.removeCallbacks(pendingReconnect);
            pendingReconnect = null;
        }
    }

    /** 绑定完成后重新鉴权 */
    public void reconnect() {
        LogUtils.getInstance().d(context, TAG, "主动调用 reconnect()");
        cancelPendingReconnect();
        if (client != null && !client.isClosed()) {
            client.close();
        }
        mainHandler.postDelayed(() -> connect(serverUrl, token, enableToken), 500);
    }

    /** 主动断开，不触发自动重连 */
    public void disconnect() {
        LogUtils.getInstance().d(context, TAG, "主动断开连接");
        cancelPendingReconnect();
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
