package com.lhht.xiaozhi.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.UUID;

public class SettingsManager {
    private static final String PREF_NAME = "xiaozhi_settings";
    private static final String KEY_WS_URL = "ws_url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_ENABLE_TOKEN = "enable_token";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_USE_OFFICIAL = "use_official_server";
    private static final String KEY_CLIENT_ID = "client_id";

    public static final String OFFICIAL_WS_URL  = "wss://api.tenclass.net/xiaozhi/v1/";
    public static final String OFFICIAL_OTA_URL = "https://api.tenclass.net/xiaozhi/ota/";

    private final SharedPreferences preferences;

    public SettingsManager(Context context) {
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ── 服务器 / Token ───────────────────────────────────────────────────────

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

    public String getEffectiveWsUrl() {
        return isUseOfficialServer() ? OFFICIAL_WS_URL : getWsUrl();
    }

    public String getToken() {
        return preferences.getString(KEY_TOKEN, "");
    }

    public boolean isTokenEnabled() {
        return preferences.getBoolean(KEY_ENABLE_TOKEN, true);
    }

    // ── 官方平台开关 ─────────────────────────────────────────────────────────

    public boolean isUseOfficialServer() {
        return preferences.getBoolean(KEY_USE_OFFICIAL, false);
    }

    public void setUseOfficialServer(boolean useOfficial) {
        preferences.edit().putBoolean(KEY_USE_OFFICIAL, useOfficial).apply();
    }

    // ── 设备 ID ──────────────────────────────────────────────────────────────

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

    public String getClientId() {
        String clientId = preferences.getString(KEY_CLIENT_ID, null);
        if (clientId == null) {
            clientId = UUID.randomUUID().toString();
            preferences.edit().putString(KEY_CLIENT_ID, clientId).apply();
        }
        return clientId;
    }

    /**
     * 返回小写带冒号 MAC 格式，与 ESP32 固件及参考项目完全一致。
     * 格式：94:b1:c0:76:84:09
     * 用于 Device-Id header、OTA 请求和设置页显示。
     */
    public String getFormattedDeviceId(Context context) {
        String rawId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (rawId == null) rawId = "";

        // 取纯十六进制字符，补齐至 12 位，小写
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
}
