package com.lhht.xiaozhi.websocket;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.lhht.xiaozhi.utils.LogUtils;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

public class WebSocketManager {
    private static final String TAG = "WebSocketManager";
    // 300ms内且未收到 hello 就关闭，才判定为格式/鉴权错误，停止重连
    private static final long AUTH_FAIL_THRESHOLD_MS = 300;
    // 指数退避重连：每次失败延迟递增，封顶 30 秒（对齐 py-xiaozhi min(attempts*2, 30)s）
    private static final long RECONNECT_BASE_DELAY_MS = 2000;
    private static final long RECONNECT_MAX_DELAY_MS = 30000;
    // 连续重连失败达到此次数后停止，避免无限空转耗电
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    // 连接健康检查：超过此时长未收到任何服务端消息/心跳响应，视为连接静默死亡，主动重连。
    // 必须大于库内置 ping 间隔（30s），否则空闲连接会被自己误杀。
    private static final long HEALTH_CHECK_INTERVAL_MS = 15000;
    private static final long HEALTH_CHECK_SILENCE_THRESHOLD_MS = 60000;
    // WebSocket 库内置心跳超时（秒）：超时未收到 pong 主动断开，触发 onClose 走重连逻辑
    private static final int CONNECTION_LOST_TIMEOUT_SEC = 30;

    private volatile WebSocketClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Context context;
    private final String deviceId;
    private final String clientId;
    private volatile WebSocketListener listener;
    private String serverUrl;
    private String token;
    private boolean enableToken;
    private boolean allowInsecureTls;

    /** 当前连接代次：每次新建 client 递增，旧 client 的迟到回调全部丢弃。 */
    private volatile int generation = 0;

    // 用 Runnable 引用替代 isReconnecting 布尔值，防止多个重连定时器并发
    private Runnable pendingReconnect;
    // 用户主动断开：onClose 不再自动重连
    private volatile boolean manualDisconnect = false;
    // 服务端 hello 是否已收到，用于更准确地识别握手失败
    private volatile boolean helloReceived = false;
    // 连续重连失败次数，连接成功后清零
    private int reconnectAttempts = 0;
    // 最近一次收到服务端任意消息 / pong 的时间，用于健康检查
    private volatile long lastMessageTime = 0;
    private final Runnable healthCheckRunnable = this::checkConnectionHealth;
    private volatile boolean healthCheckScheduled = false;
    private volatile int healthCheckGeneration = -1;

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

    /** 用户/业务触发的连接：重置重连计数并清理旧连接。 */
    public void connect(String url, String token, boolean enableToken, boolean allowInsecureTls) {
        connectInternal(url, token, enableToken, allowInsecureTls, true);
    }

    private synchronized void connectInternal(String url, String token, boolean enableToken,
                                              boolean allowInsecureTls, boolean resetAttempts) {
        if (client != null && client.isOpen()) {
            LogUtils.getInstance().d(context, TAG, "已有活动连接，忽略重复 connect()");
            return;
        }
        if (resetAttempts) {
            reconnectAttempts = 0;
        }
        manualDisconnect = false;
        cancelPendingReconnect();
        cancelHealthCheck();

        this.serverUrl = url;
        this.token = token;
        this.enableToken = enableToken;
        this.allowInsecureTls = allowInsecureTls;

        LogUtils.getInstance().d(context, TAG, "准备连接: " + url
                + "  deviceId=" + deviceId + "  clientId=" + clientId);

        // 先递增代次再关闭旧 client，旧 client 的 onClose/onError/onMessage 都会被当作过期回调忽略
        final int myGeneration = ++generation;
        closeClientQuietly();
        helloReceived = false;

        // API < 24 的 WSS 预建 socket 参数（见下方 wss 分支）
        SSLSocketFactory legacyTlsFactory = null;
        String legacyTlsHost = null;
        int legacyTlsPort = 443;

        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Device-Id", deviceId);
            headers.put("Protocol-Version", "1");
            headers.put("Client-Id", clientId);
            // 官方模式默认 token 为 test-token；自建模式未启用 Token 时不发送该头
            if (enableToken && token != null && !token.isEmpty()) {
                headers.put("Authorization", "Bearer " + token);
                LogUtils.getInstance().d(context, TAG, "携带 Authorization header");
            } else {
                LogUtils.getInstance().d(context, TAG, "未携带 Authorization header（Token 未启用或为空）");
            }

            URI uri = URI.create(url);
            final long[] openTime = {0L};
            WebSocketClient newClient = new WebSocketClient(uri, headers) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    if (myGeneration != generation) {
                        LogUtils.getInstance().d(context, TAG, "过期连接的 onOpen 回调，忽略");
                        close();
                        return;
                    }
                    openTime[0] = System.currentTimeMillis();
                    lastMessageTime = openTime[0];
                    reconnectAttempts = 0; // 连接成功，重置重连计数
                    LogUtils.getInstance().d(context, TAG,
                            "WebSocket 已连接，HTTP状态: " + handshakedata.getHttpStatus());
                    // ★ 在 WebSocket 线程直接发 hello，不经 mainHandler
                    // 避免：onClose 先于 mainHandler 回调执行，导致 hello 在关闭后才发
                    sendHelloMessage(this);
                    mainHandler.post(() -> {
                        if (myGeneration != generation) return;
                        WebSocketListener l = listener;
                        if (l != null) l.onConnected();
                        scheduleHealthCheck(myGeneration);
                    });
                }

                /**
                 * Java-WebSocket 1.5.x 的默认实现会调用 SSLParameters.setEndpointIdentificationAlgorithm()，
                 * 该方法是 Android 7.0（API 24）才加入的。在 Android 5.x/6.x 上会抛 NoSuchMethodError，
                 * 且运行在 WebSocket 读线程里无人捕获，会直接把整个进程带崩（表现：点击“连接”立即退出）。
                 * 这里按系统版本降级：API 24+ 保留 HTTPS 主机名校验，旧系统跳过。
                 */
                @Override
                protected void onSetSSLParameters(SSLParameters sslParameters) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
                    }
                }

                @Override
                public void onMessage(ByteBuffer bytes) {
                    if (myGeneration != generation) return;
                    lastMessageTime = System.currentTimeMillis();
                    byte[] data = new byte[bytes.remaining()];
                    bytes.get(data);
                    mainHandler.post(() -> {
                        if (myGeneration != generation) return;
                        WebSocketListener l = listener;
                        if (l != null) l.onBinaryMessage(data);
                    });
                }

                @Override
                public void onMessage(String message) {
                    if (myGeneration != generation) return;
                    lastMessageTime = System.currentTimeMillis();
                    if (isHelloMessage(message)) {
                        helloReceived = true;
                    }
                    // 所有服务端文本消息写入日志文件，便于远程调试
                    LogUtils.getInstance().d(context, TAG, "收到文本消息: " + message);
                    mainHandler.post(() -> {
                        if (myGeneration != generation) return;
                        WebSocketListener l = listener;
                        if (l != null) l.onMessage(message);
                    });
                }

                @Override
                public void onWebsocketPong(org.java_websocket.WebSocket conn, Framedata f) {
                    super.onWebsocketPong(conn, f);
                    // 心跳响应也算“连接活跃”，否则空闲连接会被健康检查误杀
                    if (myGeneration == generation) {
                        lastMessageTime = System.currentTimeMillis();
                    }
                }

                @Override
                public void onWebsocketPing(org.java_websocket.WebSocket conn, Framedata f) {
                    super.onWebsocketPing(conn, f);
                    if (myGeneration == generation) {
                        lastMessageTime = System.currentTimeMillis();
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    long duration = openTime[0] == 0 ? 0 : System.currentTimeMillis() - openTime[0];
                    String msg = String.format(
                            "WebSocket 关闭 - code:%d reason:\"%s\" remote:%b 连接持续:%dms",
                            code, reason, remote, duration);
                    LogUtils.getInstance().d(context, TAG, msg);

                    mainHandler.post(() -> {
                        if (myGeneration != generation) {
                            LogUtils.getInstance().d(context, TAG, "过期连接的 onClose 回调，忽略");
                            return;
                        }
                        cancelHealthCheck();
                        WebSocketListener l = listener;
                        if (l != null) l.onDisconnected();

                        if (manualDisconnect) {
                            LogUtils.getInstance().d(context, TAG, "用户主动断开，不自动重连");
                            return;
                        }

                        // 检测连接建立后极短时间（<300ms）被服务端关闭且未收到 hello：
                        // 协议握手失败，无限重连无意义，停止并提示用户
                        if (remote && code == 1000 && duration < AUTH_FAIL_THRESHOLD_MS && !helloReceived) {
                            String authErr = "服务器拒绝握手（" + duration + "ms 内关闭），"
                                    + "请检查设置是否正确，或重新安装 App 后再试。";
                            LogUtils.getInstance().d(context, TAG, "握手失败，停止重连: " + authErr);
                            WebSocketListener callback = listener;
                            if (callback != null) callback.onError(authErr);
                            return;
                        }

                        scheduleReconnect();
                    });
                }

                @Override
                public void onError(Exception ex) {
                    if (myGeneration != generation) return;
                    String errMsg = "WebSocket 错误: " + ex.getMessage();
                    LogUtils.getInstance().e(context, TAG, errMsg, ex);
                    // 不在 onError 里排重连：Java-WebSocket 随后一定会回调 onClose，
                    // 两处都排会导致重连次数翻倍、旧定时器被意外取消。
                    mainHandler.post(() -> {
                        if (myGeneration != generation) return;
                        WebSocketListener l = listener;
                        if (l != null) l.onError(ex.getMessage());
                    });
                }
            };
            // 启用库内置心跳，超时未收到 pong 视为连接丢失并触发 onClose（P1-5）
            newClient.setConnectionLostTimeout(CONNECTION_LOST_TIMEOUT_SEC);

            // WSS：默认使用系统证书校验；仅当用户显式开启“允许自签名证书”时才放宽
            if ("wss".equalsIgnoreCase(uri.getScheme())) {
                if (allowInsecureTls) {
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
                        newClient.setSocketFactory(sslContext.getSocketFactory());
                        LogUtils.getInstance().d(context, TAG,
                                "WSS 已禁用证书校验（用户开启了“允许自签名证书”，存在中间人风险）");
                    } catch (Exception sslEx) {
                        LogUtils.getInstance().e(context, TAG, "WSS SSL 初始化失败", sslEx);
                        WebSocketListener l = listener;
                        if (l != null) l.onError("SSL 配置失败: " + sslEx.getMessage());
                        return;
                    }
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    // API 24 以下有两个坑：
                    // 1) SSLSocket 默认不校验证书主机名，而 Java-WebSocket 依赖的
                    //    SSLParameters.setEndpointIdentificationAlgorithm() 在旧系统上不存在（会崩进程）——
                    //    用自定义 TrustManager 在握手阶段补齐证书链 + 主机名校验；
                    // 2) Java-WebSocket 会先建一个无主机名的裸 socket 再分层封装，旧系统 Conscrypt
                    //    在这种路径下不发送 SNI；官方网关按 SNI 选证书，缺失时返回默认证书
                    //    （实测 api.tenclass.net 会拿到 mqtt.tenclass.net 的证书）——
                    //    这里改为直接创建一个带主机名的 SSLSocket 交给客户端连接，确保 ClientHello 带 SNI。
                    // 注意：createSocket(host, port) 会做 DNS 解析，主线程调用会抛
                    // NetworkOnMainThreadException，所以真正的建连放到下面的后台线程里做。
                    try {
                        legacyTlsHost = uri.getHost();
                        legacyTlsPort = uri.getPort() != -1 ? uri.getPort() : 443;
                        legacyTlsFactory = TlsCompat.createHostnameVerifyingSocketFactory(legacyTlsHost);
                        LogUtils.getInstance().d(context, TAG,
                                "API " + Build.VERSION.SDK_INT + "：启用 SNI + TrustManager 主机名校验");
                    } catch (Exception tlsEx) {
                        LogUtils.getInstance().e(context, TAG, "TLS 主机名校验初始化失败", tlsEx);
                        WebSocketListener l = listener;
                        if (l != null) l.onError("TLS 配置失败: " + tlsEx.getMessage());
                        return;
                    }
                }
            }

            client = newClient;
            if (legacyTlsFactory != null) {
                connectWithPreparedTlsSocket(newClient, legacyTlsFactory, legacyTlsHost,
                        legacyTlsPort, myGeneration);
            } else {
                newClient.connect();
                LogUtils.getInstance().d(context, TAG, "connect() 已调用（非阻塞）");
            }

        } catch (Exception e) {
            LogUtils.getInstance().e(context, TAG, "创建 WebSocket 失败", e);
            WebSocketListener l = listener;
            if (l != null) l.onError(e.getMessage());
        }
    }

    /**
     * API &lt; 24 的 WSS 建连：先在后台线程创建带 SNI 的 SSLSocket，再交给 Java-WebSocket 连接。
     *
     * <p>不能在主线程做：{@code SSLSocketFactory.createSocket(host, port)} 会做 DNS 解析，
     * 在 StrictMode 下抛 {@link android.os.NetworkOnMainThreadException}。
     * 建连失败时在主线程回调 onError 并走常规重连退避。
     */
    private void connectWithPreparedTlsSocket(final WebSocketClient newClient,
                                              final SSLSocketFactory factory,
                                              final String host, final int port,
                                              final int myGeneration) {
        Thread t = new Thread(() -> {
            Socket tlsSocket = null;
            try {
                tlsSocket = factory.createSocket(host, port);
                if (myGeneration != generation) {
                    LogUtils.getInstance().d(context, TAG, "连接代次已变化，放弃本轮 TLS 建连");
                    closeQuietly(tlsSocket);
                    return;
                }
                newClient.setSocket(tlsSocket);
                newClient.connect();
                LogUtils.getInstance().d(context, TAG,
                        "connect() 已调用（API < 24：预建 SNI SSLSocket -> " + host + ":" + port + "）");
            } catch (Exception e) {
                closeQuietly(tlsSocket);
                final String message = e.getMessage() != null ? e.getMessage() : e.toString();
                LogUtils.getInstance().e(context, TAG, "TLS 预建 socket 失败: " + message, e);
                mainHandler.post(() -> {
                    if (myGeneration != generation) return;
                    WebSocketListener l = listener;
                    if (l != null) l.onError("TLS 连接失败: " + message);
                    scheduleReconnect();
                });
            }
        }, "WsTlsConnect");
        t.setDaemon(true);
        t.start();
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * 安全调度重连：指数退避（2s, 4s, 8s... 封顶 30s），
     * 连续失败达到 MAX_RECONNECT_ATTEMPTS 次后停止并通知 UI（P1-4）。
     */
    private void scheduleReconnect() {
        cancelPendingReconnect();
        if (manualDisconnect) {
            LogUtils.getInstance().d(context, TAG, "主动断开状态，跳过重连");
            return;
        }
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            String err = "已连续重连失败 " + reconnectAttempts + " 次，请检查网络或服务器地址后手动重试。";
            LogUtils.getInstance().d(context, TAG, "达到最大重连次数，停止重连: " + err);
            WebSocketListener l = listener;
            if (l != null) l.onError(err);
            return;
        }
        reconnectAttempts++;
        long delay = Math.min(RECONNECT_BASE_DELAY_MS * (1L << (reconnectAttempts - 1)), RECONNECT_MAX_DELAY_MS);
        LogUtils.getInstance().d(context, TAG,
                "第 " + reconnectAttempts + " 次重连，" + delay / 1000 + "秒后尝试...");
        pendingReconnect = () -> {
            pendingReconnect = null;
            if (manualDisconnect) return;
            connectInternal(serverUrl, token, enableToken, allowInsecureTls, false);
        };
        mainHandler.postDelayed(pendingReconnect, delay);
    }

    private void cancelPendingReconnect() {
        Runnable pending = pendingReconnect;
        if (pending != null) {
            mainHandler.removeCallbacks(pending);
            pendingReconnect = null;
        }
    }

    /**
     * 连接健康主动探测（P1-5）：某些网络环境下 TCP 连接会静默死亡而不触发 onClose，
     * 若超过 HEALTH_CHECK_SILENCE_THRESHOLD_MS 未收到任何服务端消息 / pong，主动断开重连。
     */
    private void checkConnectionHealth() {
        if (healthCheckGeneration != generation) {
            healthCheckScheduled = false;
            return;
        }
        WebSocketClient current = client;
        if (current == null || !current.isOpen() || manualDisconnect) {
            healthCheckScheduled = false;
            return;
        }
        long silence = System.currentTimeMillis() - lastMessageTime;
        if (silence > HEALTH_CHECK_SILENCE_THRESHOLD_MS) {
            LogUtils.getInstance().d(context, TAG,
                    "连接静默 " + silence + "ms 无响应，判定连接已死，主动重连");
            healthCheckScheduled = false;
            current.close();
            return;
        }
        mainHandler.postDelayed(healthCheckRunnable, HEALTH_CHECK_INTERVAL_MS);
    }

    private void scheduleHealthCheck(int forGeneration) {
        if (healthCheckScheduled && healthCheckGeneration == forGeneration) return;
        healthCheckScheduled = true;
        healthCheckGeneration = forGeneration;
        mainHandler.postDelayed(healthCheckRunnable, HEALTH_CHECK_INTERVAL_MS);
    }

    private void cancelHealthCheck() {
        healthCheckScheduled = false;
        healthCheckGeneration = -1;
        mainHandler.removeCallbacks(healthCheckRunnable);
    }

    /** 绑定完成后重新鉴权：关闭旧连接并建立新连接（旧连接回调通过代次失效）。 */
    public synchronized void reconnect() {
        LogUtils.getInstance().d(context, TAG, "主动调用 reconnect()");
        manualDisconnect = false;
        cancelPendingReconnect();
        cancelHealthCheck();
        reconnectAttempts = 0;
        final int myGeneration = ++generation;
        closeClientQuietly();
        mainHandler.postDelayed(() -> {
            if (myGeneration != generation || manualDisconnect) return;
            connectInternal(serverUrl, token, enableToken, allowInsecureTls, false);
        }, 500);
    }

    /** 主动断开，不触发自动重连 */
    public synchronized void disconnect() {
        LogUtils.getInstance().d(context, TAG, "主动断开连接");
        manualDisconnect = true;
        cancelPendingReconnect();
        cancelHealthCheck();
        reconnectAttempts = 0;
        WebSocketClient current = client;
        if (current != null && !current.isClosed()) {
            try {
                current.close();
            } catch (Exception e) {
                LogUtils.getInstance().e(context, TAG, "关闭 WebSocket 失败", e);
            }
        } else {
            // 没有可关闭的连接时也要让 UI 回到未连接态
            WebSocketListener l = listener;
            if (l != null) l.onDisconnected();
        }
    }

    private void closeClientQuietly() {
        WebSocketClient old = client;
        client = null;
        if (old != null && !old.isClosed()) {
            try {
                old.close();
            } catch (Exception e) {
                LogUtils.getInstance().e(context, TAG, "关闭旧 WebSocket 失败", e);
            }
        }
    }

    private void sendHelloMessage(WebSocketClient target) {
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
            target.send(hello.toString());
            LogUtils.getInstance().d(context, TAG, "已发送 hello 握手消息");
        } catch (JSONException e) {
            LogUtils.getInstance().e(context, TAG, "构造 hello 消息失败", e);
        }
    }

    private static boolean isHelloMessage(String message) {
        try {
            return "hello".equals(new JSONObject(message).optString("type"));
        } catch (JSONException e) {
            return false;
        }
    }

    public boolean isConnected() {
        WebSocketClient current = client;
        return current != null && current.isOpen();
    }

    public void sendMessage(String message) {
        WebSocketClient current = client;
        if (current != null && current.isOpen()) {
            current.send(message);
        }
    }

    public void sendBinaryMessage(byte[] data) {
        WebSocketClient current = client;
        if (current != null && current.isOpen()) {
            current.send(data);
        }
    }
}
