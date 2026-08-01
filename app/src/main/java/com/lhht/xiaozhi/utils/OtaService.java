package com.lhht.xiaozhi.utils;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import com.lhht.xiaozhi.settings.SettingsManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 官方小智 OTA HTTP 服务。
 *
 * 流程（与 py-xiaozhi 完全对齐）：
 * 1. POST https://api.tenclass.net/xiaozhi/ota/   → 获取 websocket.url / websocket.token / activation
 * 2. 若响应含 activation → 显示6位验证码，同时每5秒轮询 /ota/activate 直到成功
 * 3. 激活成功后保存 token / url → 建立 WebSocket
 */
public class OtaService {

    public interface OtaCallback {
        void onActivationRequired(String code, String message);
        void onAlreadyActivated();
        void onError(String error);
    }

    private static final String OTA_URL      = "https://api.tenclass.net/xiaozhi/ota/";
    private static final String ACTIVATE_URL = "https://api.tenclass.net/xiaozhi/ota/activate";
    private static final String APP_NAME     = "xiaozhi-android";
    private static final String APP_VERSION  = "1.0.0";
    private static final String BOARD_TYPE   = "bread-compact-wifi"; // 与 py-xiaozhi 保持一致

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 轮询控制标志位（volatile 保证线程间可见性）
    private static volatile boolean isPolling = false;

    public static void checkActivation(Context context, SettingsManager sm, OtaCallback callback) {
        String macAddress = sm.getFormattedDeviceId(context);
        String clientId   = sm.getClientId();
        String serialNum  = sm.getSerialNumber(context);
        String hmacKey    = sm.getHmacKey(context);

        executor.execute(() -> {
            try {
                String localIp = getLocalIpAddress();
                String body = buildOtaPayload(macAddress, localIp);

                LogUtils.getInstance().d(context, "OtaService",
                        "OTA 请求 → " + OTA_URL
                        + "\n  Device-Id=" + macAddress
                        + "\n  Client-Id=" + clientId
                        + "\n  Body=" + body);

                String responseStr = postJson(OTA_URL, body,
                        macAddress, clientId, null);

                LogUtils.getInstance().d(context, "OtaService", "OTA 响应: " + responseStr);

                JSONObject json = new JSONObject(responseStr);

                // 解析 websocket.url 和 websocket.token，保存到 SettingsManager
                JSONObject ws = json.optJSONObject("websocket");
                if (ws != null) {
                    String wsUrl = ws.optString("url", "");
                    String wsToken = ws.optString("token", "test-token");
                    if (wsToken.isEmpty()) wsToken = "test-token";
                    if (!wsUrl.isEmpty()) {
                        sm.saveOtaWsUrl(wsUrl);
                        LogUtils.getInstance().d(context, "OtaService", "WS URL 已保存: " + wsUrl);
                    }
                    sm.saveOtaToken(wsToken);
                    LogUtils.getInstance().d(context, "OtaService", "WS Token 已保存");
                }

                JSONObject activation = json.optJSONObject("activation");
                if (activation != null) {
                    String code      = activation.optString("code", "");
                    String message   = activation.optString("message", "");
                    String challenge = activation.optString("challenge", "");

                    LogUtils.getInstance().d(context, "OtaService",
                            "需要激活，code=" + code + " challenge=" + challenge);

                    // 主线程显示验证码弹窗，后台同时开始轮询激活接口
                    mainHandler.post(() -> callback.onActivationRequired(code, message));

                    // 后台轮询激活
                    pollActivation(context, sm, challenge, code, serialNum, hmacKey, callback);
                } else {
                    LogUtils.getInstance().d(context, "OtaService", "设备已激活，可直接连接");
                    mainHandler.post(callback::onAlreadyActivated);
                }

            } catch (Exception e) {
                LogUtils.getInstance().e(context, "OtaService", "OTA 请求失败", e);
                mainHandler.post(() -> callback.onError("OTA 失败: " + e.getMessage()));
            }
        });
    }

    /** 停止激活轮询 */
    public static void stopPolling() {
        isPolling = false;
        LogUtils.getInstance().d(null, "OtaService", "用户主动停止激活轮询");
    }

    /** 轮询 /ota/activate，最多60次（每次间隔5秒） */
    private static void pollActivation(Context context, SettingsManager sm,
                                       String challenge, String code,
                                       String serialNum, String hmacKey,
                                       OtaCallback callback) {
        isPolling = true; // 开始轮询
        executor.execute(() -> {
            try {
                String hmacSignature = hmacSha256(hmacKey, challenge);
                String macAddress    = sm.getFormattedDeviceId(context);
                String clientId      = sm.getClientId();

                JSONObject payload = new JSONObject();
                JSONObject inner = new JSONObject();
                inner.put("algorithm", "hmac-sha256");
                inner.put("serial_number", serialNum);
                inner.put("challenge", challenge);
                inner.put("hmac", hmacSignature);
                payload.put("Payload", inner);
                String body = payload.toString();

                LogUtils.getInstance().d(context, "OtaService",
                        "开始激活轮询 serial=" + serialNum);

                for (int attempt = 0; attempt < 60 && isPolling; attempt++) {
                    try {
                        String resp = postJson(ACTIVATE_URL, body,
                                macAddress, clientId, "2");

                        // HTTP 200 → 激活成功
                        LogUtils.getInstance().d(context, "OtaService",
                                "激活轮询 " + (attempt + 1) + "/60 成功");
                        isPolling = false;
                        mainHandler.post(callback::onAlreadyActivated);
                        return;

                    } catch (ActivationPendingException e) {
                        // HTTP 202 → 用户还未在网页上输入验证码，继续等待
                        LogUtils.getInstance().d(context, "OtaService",
                                "激活轮询 " + (attempt + 1) + "/60 等待中...");
                        Thread.sleep(5000);
                    } catch (Exception e) {
                        LogUtils.getInstance().d(context, "OtaService",
                                "激活轮询异常: " + e.getMessage() + "，5秒后重试");
                        Thread.sleep(5000);
                    }
                }

                // 循环结束后检查是否被手动停止
                if (isPolling) {
                    // 未被手动停止，说明是超时退出
                    LogUtils.getInstance().d(context, "OtaService", "激活超时（5分钟）");
                    mainHandler.post(() -> callback.onError("激活超时，请重新尝试"));
                } else {
                    // 被手动停止
                    LogUtils.getInstance().d(context, "OtaService", "激活轮询已被用户停止");
                }
                isPolling = false;

            } catch (Exception e) {
                LogUtils.getInstance().e(context, "OtaService", "激活轮询失败", e);
                mainHandler.post(() -> callback.onError("激活失败: " + e.getMessage()));
            }
        });
    }

    // ── 构造 OTA 请求体（与 py-xiaozhi._build_ota_payload 格式一致）────────────

    private static String buildOtaPayload(String macAddress, String localIp) throws Exception {
        JSONObject root = new JSONObject();

        JSONObject app = new JSONObject();
        app.put("version", APP_VERSION);
        app.put("elf_sha256", "0000000000000000000000000000000000000000000000000000000000000000");
        root.put("application", app);

        JSONObject board = new JSONObject();
        board.put("type", BOARD_TYPE);
        board.put("name", APP_NAME);
        board.put("ip", localIp);
        board.put("mac", macAddress);
        root.put("board", board);

        return root.toString();
    }

    // ── HTTP POST ──────────────────────────────────────────────────────────────

    /**
     * @param activationVersion 激活接口传 "2"；OTA 接口传 null
     * @throws ActivationPendingException 当 HTTP 202 时抛出（激活接口等待用户输入）
     */
    private static String postJson(String urlStr, String body,
                                   String deviceId, String clientId,
                                   String activationVersion) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Device-Id", deviceId);
        conn.setRequestProperty("Client-Id", clientId);
        conn.setRequestProperty("User-Agent", BOARD_TYPE + "/" + APP_NAME + "-" + APP_VERSION);
        conn.setRequestProperty("Accept-Language", "zh-CN");
        if (activationVersion != null) {
            conn.setRequestProperty("Activation-Version", activationVersion);
        }
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes("UTF-8"));
        }

        int code = conn.getResponseCode();
        if (code == 202) throw new ActivationPendingException();
        if (code != 200) throw new Exception("HTTP " + code);

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    // ── HMAC-SHA256 签名 ───────────────────────────────────────────────────────

    private static String hmacSha256(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));
        byte[] bytes = mac.doFinal(data.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ── 获取本机 IP ──────────────────────────────────────────────────────────

    private static String getLocalIpAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr.getHostAddress().indexOf(':') < 0) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private static class ActivationPendingException extends Exception {}
}
