package com.lhht.xiaozhi.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.security.MessageDigest;
import java.util.UUID;

public class SettingsManager {
    private static final String PREF_NAME = "xiaozhi_settings";
    private static final String KEY_WS_URL = "ws_url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_ENABLE_TOKEN = "enable_token";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_USE_OFFICIAL = "use_official_server";
    private static final String KEY_CLIENT_ID = "client_id";
    private static final String KEY_SERIAL_NUMBER = "serial_number";
    // OTA 动态下发的 WebSocket URL 和 token
    private static final String KEY_OTA_WS_URL   = "ota_ws_url";
    private static final String KEY_OTA_TOKEN     = "ota_token";

    public static final String OFFICIAL_OTA_URL  = "https://api.tenclass.net/xiaozhi/ota/";
    public static final String OFFICIAL_WS_URL   = "wss://api.tenclass.net/xiaozhi/v1/"; // fallback

    private final SharedPreferences preferences;

    public SettingsManager(Context context) {
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ── 局域网服务器配置 ─────────────────────────────────────────────────────

    public void saveSettings(String wsUrl, String token, boolean enableToken) {
        preferences.edit()
                .putString(KEY_WS_URL, wsUrl)
                .putString(KEY_TOKEN, token)
                .putBoolean(KEY_ENABLE_TOKEN, enableToken)
                .apply();
    }

    public String getWsUrl() {
        return preferences.getString(KEY_WS_URL, "ws://localhost:9005");
    }

    public String getToken() {
        return preferences.getString(KEY_TOKEN, "");
    }

    public boolean isTokenEnabled() {
        return preferences.getBoolean(KEY_ENABLE_TOKEN, true);
    }

    // ── 官方平台开关 ─────────────────────────────────────────────────────────

    public boolean isUseOfficialServer() {
        // 默认官方模式：新用户开箱即用，无需手动配置
        return preferences.getBoolean(KEY_USE_OFFICIAL, true);
    }

    public void setUseOfficialServer(boolean useOfficial) {
        preferences.edit().putBoolean(KEY_USE_OFFICIAL, useOfficial).apply();
    }

    // ── OTA 动态 URL / Token（来自 OTA 响应的 websocket.url / websocket.token）

    public void saveOtaWsUrl(String url) {
        preferences.edit().putString(KEY_OTA_WS_URL, url).apply();
    }

    public String getOtaWsUrl() {
        return preferences.getString(KEY_OTA_WS_URL, null);
    }

    public void saveOtaToken(String token) {
        preferences.edit().putString(KEY_OTA_TOKEN, token).apply();
    }

    /** 优先用 OTA 返回的 token，缺省用 test-token（与 py-xiaozhi 一致） */
    public String getOtaToken() {
        String t = preferences.getString(KEY_OTA_TOKEN, null);
        return (t != null && !t.isEmpty()) ? t : "test-token";
    }

    /**
     * 实际连接用的 WebSocket URL：
     * 官方模式下优先用 OTA 返回的动态 URL，回退到硬编码地址；
     * 局域网模式用用户配置的 URL。
     */
    public String getEffectiveWsUrl() {
        if (isUseOfficialServer()) {
            String otaUrl = getOtaWsUrl();
            return (otaUrl != null && !otaUrl.isEmpty()) ? otaUrl : OFFICIAL_WS_URL;
        }
        return getWsUrl();
    }

    // ── 设备 ID / Client-Id / Serial Number ─────────────────────────────────

    public void saveDeviceId(String deviceId) {
        preferences.edit().putString(KEY_DEVICE_ID, deviceId).apply();
    }

    public String getDeviceId(Context context) {
        String saved = preferences.getString(KEY_DEVICE_ID, null);
        if (saved == null) {
            saved = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            saveDeviceId(saved);
        }
        return saved;
    }

    /** 小写带冒号 MAC 格式，与 ESP32 固件及 py-xiaozhi 完全一致：94:b1:c0:76:84:09 */
    public String getFormattedDeviceId(Context context) {
        String rawId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (rawId == null) rawId = "";
        String hex = rawId.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
        while (hex.length() < 12) hex = hex + "0";
        hex = hex.substring(0, 12);
        StringBuilder mac = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (mac.length() > 0) mac.append(":");
            mac.append(hex, i, i + 2);
        }
        return mac.toString();
    }

    public String getClientId() {
        String clientId = preferences.getString(KEY_CLIENT_ID, null);
        if (clientId == null) {
            clientId = UUID.randomUUID().toString();
            preferences.edit().putString(KEY_CLIENT_ID, clientId).apply();
        }
        return clientId;
    }

    /**
     * 生成并持久化 Serial Number，格式与 py-xiaozhi 一致：
     * SN-{MD5(mac_no_colons)前8位大写}-{mac_no_colons小写}
     * 例：SN-A1B2C3D4-94b1c0768409
     */
    public String getSerialNumber(Context context) {
        String sn = preferences.getString(KEY_SERIAL_NUMBER, null);
        if (sn != null) return sn;

        String mac = getFormattedDeviceId(context);
        String macClean = mac.replace(":", "").toLowerCase();
        String shortHash = md5(macClean).substring(0, 8).toUpperCase();
        sn = "SN-" + shortHash + "-" + macClean;
        preferences.edit().putString(KEY_SERIAL_NUMBER, sn).apply();
        return sn;
    }

    /** 生成 HMAC key：SHA-256(mac_no_colons) */
    public String getHmacKey(Context context) {
        String mac = getFormattedDeviceId(context);
        String macClean = mac.replace(":", "").toLowerCase();
        return sha256(macClean);
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "00000000";
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "0000000000000000000000000000000000000000000000000000000000000000";
        }
    }
}
