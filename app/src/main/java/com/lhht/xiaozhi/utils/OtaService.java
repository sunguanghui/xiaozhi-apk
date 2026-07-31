package com.lhht.xiaozhi.utils;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 调用官方小智 OTA HTTP API，获取设备激活码（6位验证码）或确认设备已绑定。
 *
 * 接口：POST https://api.tenclass.net/xiaozhi/ota/
 * Headers：Device-Id, Client-Id, Content-Type: application/json
 * Body：仿 ESP32 的设备信息 JSON
 *
 * 响应包含 activation.code 时 → 设备未绑定，需显示验证码
 * 响应不含 activation 时 → 设备已绑定，可直接连接 WebSocket
 */
public class OtaService {

    public interface OtaCallback {
        /** 设备未绑定，code = 6位验证码，message = 引导文字 */
        void onActivationRequired(String code, String message);
        /** 设备已绑定，可直接连接 WebSocket */
        void onAlreadyActivated();
        /** 请求失败 */
        void onError(String error);
    }

    private static final String OTA_URL = "https://api.tenclass.net/xiaozhi/ota/";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void checkActivation(Context context, String macAddress, String clientId,
                                       OtaCallback callback) {
        executor.execute(() -> {
            try {
                String body = buildDeviceInfoJson(macAddress, clientId);
                LogUtils.getInstance().d(context, "OtaService",
                        "OTA 请求 → " + OTA_URL + " Device-Id=" + macAddress);

                URL url = new URL(OTA_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Device-Id", macAddress);
                conn.setRequestProperty("Client-Id", clientId);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }

                int code = conn.getResponseCode();
                LogUtils.getInstance().d(context, "OtaService", "OTA 响应 HTTP " + code);

                if (code != 200) {
                    mainHandler.post(() -> callback.onError("OTA 接口返回 HTTP " + code));
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }

                String responseStr = sb.toString();
                LogUtils.getInstance().d(context, "OtaService", "OTA 响应体: " + responseStr);

                JSONObject json = new JSONObject(responseStr);
                JSONObject activation = json.optJSONObject("activation");

                if (activation != null) {
                    String activationCode = activation.optString("code", "");
                    String activationMsg  = activation.optString("message", "");
                    mainHandler.post(() -> callback.onActivationRequired(activationCode, activationMsg));
                } else {
                    mainHandler.post(callback::onAlreadyActivated);
                }

            } catch (Exception e) {
                LogUtils.getInstance().e(context, "OtaService", "OTA 请求失败", e);
                mainHandler.post(() -> callback.onError("网络请求失败: " + e.getMessage()));
            }
        });
    }

    /** 构造仿 ESP32 的设备信息 JSON（与参考项目 DeviceInfo.toJson() 格式一致） */
    private static String buildDeviceInfoJson(String macAddress, String clientId) throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", 2);
        root.put("flash_size", 8388608);
        root.put("psram_size", 4194304);
        root.put("minimum_free_heap_size", 262144);
        root.put("mac_address", macAddress);
        root.put("uuid", clientId);
        root.put("chip_model_name", "android");

        JSONObject chipInfo = new JSONObject();
        chipInfo.put("model", 0);
        chipInfo.put("cores", Runtime.getRuntime().availableProcessors());
        chipInfo.put("revision", 1);
        chipInfo.put("features", 0);
        root.put("chip_info", chipInfo);

        JSONObject app = new JSONObject();
        app.put("name", "xiaozhi-android");
        app.put("version", "1.0.0");
        app.put("compile_time", "2026-07-31T00:00:00Z");
        app.put("idf_version", "android-" + Build.VERSION.RELEASE);
        app.put("elf_sha256", "0000000000000000000000000000000000000000000000000000000000000000");
        root.put("application", app);

        root.put("partition_table", new JSONArray());

        JSONObject ota = new JSONObject();
        ota.put("label", "ota_0");
        root.put("ota", ota);

        JSONObject board = new JSONObject();
        board.put("name", Build.MODEL);
        board.put("revision", "1.0");
        board.put("features", new JSONArray());
        board.put("manufacturer", Build.MANUFACTURER);
        board.put("serial_number", macAddress.replace(":", ""));
        root.put("board", board);

        return root.toString();
    }
}
