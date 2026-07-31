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
    private WebSocketClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Context context;      // 用于 LogUtils
    private String deviceId;            // MAC 格式（AA:BB:CC:DD:EE:FF）
    private WebSocketListener listener;
    private String serverUrl;
    private String token;
    private boolean enableToken;
    private boolean isReconnecting = false;
    private static final int RECONNECT_DELAY = 3000; // 3秒后重连

    public interface WebSocketListener {
        void onConnected();
        void onDisconnected();
        void onError(String error);
        void onMessage(String message);
        void onBinaryMessage(byte[] data);
    }

    /**
     * @param context 用于 LogUtils 日志记录
     * @param deviceId MAC 格式设备 ID（由 SettingsManager.getFormattedDeviceId() 提供）
     */
    public WebSocketManager(Context context, String deviceId) {
        this.context = context.getApplicationContext();
        this.deviceId = deviceId;
    }

    public void setListener(WebSocketListener listener) {
        this.listener = listener;
    }

    public void connect(String url, String token, boolean enableToken) {
        this.serverUrl = url;
        this.token = token;
        this.enableToken = enableToken;

        LogUtils.getInstance().d(context, TAG, "准备连接: " + url);

        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("device-id", deviceId);
            if (enableToken && token != null && !token.isEmpty()) {
                headers.put("Authorization", "Bearer " + token);
            }

            URI uri = URI.create(url);
            client = new WebSocketClient(uri, headers) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.d(TAG, "WebSocket Connected");
                    LogUtils.getInstance().d(context, TAG,
                            "WebSocket 已连接，HTTP状态: " + handshakedata.getHttpStatus());
                    mainHandler.post(() -> {
                        if (listener != null) listener.onConnected();
                        sendHelloMessage();
                    });
                }

                @Override
                public void onMessage(ByteBuffer bytes) {
                    Log.d(TAG, "Received binary message: " + bytes.remaining() + " bytes");
                    byte[] data = new byte[bytes.remaining()];
                    bytes.get(data);
                    mainHandler.post(() -> {
                        if (listener != null) listener.onBinaryMessage(data);
                    });
                }

                @Override
                public void onMessage(String message) {
                    Log.d(TAG, "Received message: " + message);
                    mainHandler.post(() -> {
                        if (listener != null) listener.onMessage(message);
                    });
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    String msg = String.format("WebSocket 关闭 - code:%d, reason:%s, remote:%b",
                            code, reason, remote);
                    Log.d(TAG, msg);
                    LogUtils.getInstance().d(context, TAG, msg);

                    mainHandler.post(() -> {
                        if (listener != null) listener.onDisconnected();
                        if (!isReconnecting) {
                            isReconnecting = true;
                            LogUtils.getInstance().d(context, TAG,
                                    "3秒后尝试自动重连...");
                            mainHandler.postDelayed(() -> {
                                isReconnecting = false;
                                WebSocketManager.this.connect(serverUrl, token, enableToken);
                            }, RECONNECT_DELAY);
                        }
                    });
                }

                @Override
                public void onError(Exception ex) {
                    String errMsg = "WebSocket 错误: " + ex.getMessage();
                    Log.e(TAG, errMsg, ex);
                    LogUtils.getInstance().e(context, TAG, errMsg, ex);

                    mainHandler.post(() -> {
                        if (listener != null) listener.onError(ex.getMessage());
                    });
                }
            };

            // WSS：配置 TLS，否则官方服务器会拒绝连接
            if ("wss".equalsIgnoreCase(uri.getScheme())) {
                try {
                    SSLContext sslContext = SSLContext.getInstance("TLS");
                    sslContext.init(null, null, null); // 使用系统默认信任链
                    client.setSocketFactory(sslContext.getSocketFactory());
                    LogUtils.getInstance().d(context, TAG, "WSS SSL 配置成功");
                } catch (Exception sslEx) {
                    LogUtils.getInstance().e(context, TAG,
                            "WSS SSL 初始化失败", sslEx);
                    if (listener != null) listener.onError("SSL 配置失败: " + sslEx.getMessage());
                    return;
                }
            }

            // 使用非阻塞 connect()，避免在主线程抛出 NetworkOnMainThreadException
            client.connect();
            LogUtils.getInstance().d(context, TAG, "connect() 已调用（非阻塞）");

        } catch (Exception e) {
            String errMsg = "创建 WebSocket 失败";
            Log.e(TAG, errMsg, e);
            LogUtils.getInstance().e(context, TAG, errMsg, e);
            if (listener != null) {
                listener.onError(e.getMessage());
            }
        }
    }

    /**
     * 断开当前连接并立即重新发起握手（用于设备绑定完成后重新鉴权）。
     */
    public void reconnect() {
        LogUtils.getInstance().d(context, TAG, "主动调用 reconnect()");
        isReconnecting = false;
        if (client != null) {
            client.close();
        }
        connect(serverUrl, token, enableToken);
    }

    private void sendHelloMessage() {
        try {
            JSONObject hello = new JSONObject();
            hello.put("type", "hello");
            hello.put("version", 3);
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
            Log.e(TAG, "Error creating hello message", e);
            LogUtils.getInstance().e(context, TAG, "构造 hello 消息失败", e);
        }
    }

    public void disconnect() {
        isReconnecting = true; // 主动断开时不触发自动重连
        LogUtils.getInstance().d(context, TAG, "主动断开连接");
        if (client != null && client.isOpen()) {
            client.close();
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
