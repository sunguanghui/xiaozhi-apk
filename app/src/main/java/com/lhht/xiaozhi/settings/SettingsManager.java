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

    /**
     * 官方小智平台 WebSocket 地址（实测正确路径）
     * wss://api.xiaozhi.me/v1/ 返回 404；正确路径为 /xiaozhi/v1/
     */
    public static final String OFFICIAL_WS_URL = "wss://api.tenclass.net/xiaozhi/v1/";

    private final SharedPreferences preferences;

    public SettingsManager(Context context) {
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ── 服务器 / Token 设置 ──────────────────────────────────────────────────

    public void saveSettings(String wsUrl, String token, boolean enableToken) {
        preferences.edit()
                .putString(KEY_WS_URL, wsUrl)
                .putString(KEY_TOKEN, token)
                .putBoolean(KEY_ENABLE_TOKEN, enableToken)
                .apply();
    }

    /** 原始存储的局域网地址 */
    public String getWsUrl() {
        return preferences.getString(KEY_WS_URL, "ws://localhost:9005");
    }

    /**
     * 实际生效的 WebSocket 地址：
     * - 开启官方服务器时，固定返回 {@link #OFFICIAL_WS_URL}
     * - 否则返回用户手动配置的局域网地址
     */
    public String getEffectiveWsUrl() {
        return isUseOfficialServer() ? OFFICIAL_WS_URL : getWsUrl();
    }

    public String getToken() {
        return preferences.getString(KEY_TOKEN, ""); // 默认空，防止 test-token 误导官方服务器鉴权
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

    /** 返回用户自定义 ID（或首次自动读取的 Android ID）*/
    public String getDeviceId(Context context) {
        String savedDeviceId = preferences.getString(KEY_DEVICE_ID, null);
        if (savedDeviceId == null) {
            savedDeviceId = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            saveDeviceId(savedDeviceId);
        }
        return savedDeviceId;
    }

    /**
     * 返回持久化的 Client-Id（UUID 格式），首次调用时自动生成并保存。
     * 官方协议要求每个客户端实例有唯一的软件标识符。
     */
    public String getClientId() {
        String clientId = preferences.getString(KEY_CLIENT_ID, null);
        if (clientId == null) {
            clientId = UUID.randomUUID().toString();
            preferences.edit().putString(KEY_CLIENT_ID, clientId).apply();
        }
        return clientId;
    }

    /**
     * 返回官方服务器所需的 MAC 地址格式设备 ID（带冒号，用于显示）。
     * 取 Android ID 的前 12 位十六进制字符（不足则补 '0'），
     * 每两位用冒号分隔，转为大写，格式如：AA:BB:CC:DD:EE:FF。
     */
    public String getFormattedDeviceId(Context context) {
        String hex = getPlainDeviceId(context);
        // 格式化为 AA:BB:CC:DD:EE:FF
        StringBuilder mac = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (mac.length() > 0) mac.append(":");
            mac.append(hex, i, i + 2);
        }
        return mac.toString();
    }

    /**
     * 返回官方服务器 device-id header 所需的纯大写12位十六进制格式（无冒号）。
     * 格式如：94B1C076840A
     */
    public String getPlainDeviceId(Context context) {
        String rawId = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ANDROID_ID);
        if (rawId == null) rawId = "";

        // 取纯十六进制字符，截取或补齐至 12 位
        String hex = rawId.replaceAll("[^0-9a-fA-F]", "");
        while (hex.length() < 12) hex = hex + "0";
        return hex.substring(0, 12).toUpperCase();
    }
}
