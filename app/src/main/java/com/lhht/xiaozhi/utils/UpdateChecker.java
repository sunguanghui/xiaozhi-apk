package com.lhht.xiaozhi.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 版本更新检查工具。
 * 优先请求 Gitee（国内可访问），失败后兜底请求 GitHub。
 * 自动检查：每24小时最多执行一次。
 * 手动检查：设置页按钮触发，始终执行。
 */
public class UpdateChecker {
    private static final String TAG = "UpdateChecker";
    private static final String PREFS = "update_prefs";
    private static final String KEY_LAST_CHECK = "last_check_time";
    private static final String KEY_SKIPPED = "skipped_version";
    private static final long CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L; // 24h

    private static final String GITEE_API =
            "https://gitee.com/api/v5/repos/sunguanghui1989/xiaozhi-apk/releases/latest";
    private static final String GITHUB_API =
            "https://api.github.com/repos/sunguanghui/xiaozhi-apk/releases/latest";
    private static final String RELEASE_PAGE =
            "https://gitee.com/sunguanghui1989/xiaozhi-apk/releases";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── 公开入口 ──────────────────────────────────────────────────────────────

    /** 启动时自动检查（24h内只执行一次） */
    public static void checkOnStartup(Activity activity) {
        if (!shouldCheck(activity)) return;
        doCheck(activity, false);
    }

    /** 手动触发检查（设置页按钮），始终执行并给出 Toast 反馈 */
    public static void checkManually(Activity activity) {
        Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show();
        doCheck(activity, true);
    }

    // ── 核心逻辑 ──────────────────────────────────────────────────────────────

    private static void doCheck(Activity activity, boolean manual) {
        executor.execute(() -> {
            saveLastCheckTime(activity);
            ReleaseInfo info = fetchRelease(GITEE_API, 5000);
            if (info == null) {
                Log.d(TAG, "Gitee 失败，尝试 GitHub");
                info = fetchRelease(GITHUB_API, 8000);
            }
            final ReleaseInfo result = info;
            mainHandler.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (result == null) {
                    if (manual) Toast.makeText(activity, "检查失败，请检查网络", Toast.LENGTH_SHORT).show();
                    return;
                }
                handleResult(activity, result, manual);
            });
        });
    }

    private static void handleResult(Activity activity, ReleaseInfo info, boolean manual) {
        String current = getVersionName(activity);
        if (!isNewer(info.version, current)) {
            if (manual) Toast.makeText(activity, "已是最新版本 v" + current, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!manual && info.version.equals(getSkipped(activity))) return;
        showDialog(activity, info);
    }

    private static void showDialog(Activity activity, ReleaseInfo info) {
        String current = getVersionName(activity);
        String msg = "当前版本：v" + current + "\n最新版本：v" + info.version;
        if (info.changelog != null && !info.changelog.isEmpty()) {
            String log = info.changelog.length() > 200
                    ? info.changelog.substring(0, 200) + "…" : info.changelog;
            msg += "\n\n更新内容：\n" + log;
        }
        final String finalMsg = msg;
        new MaterialAlertDialogBuilder(activity)
                .setTitle("🎉 发现新版本 v" + info.version)
                .setMessage(finalMsg)
                .setPositiveButton("立即更新", (d, w) -> {
                    String url = (info.downloadUrl != null && !info.downloadUrl.isEmpty())
                            ? info.downloadUrl : RELEASE_PAGE;
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                })
                .setNegativeButton("跳过此版本", (d, w) -> saveSkipped(activity, info.version))
                .setNeutralButton("稍后提醒", null)
                .show();
    }

    // ── HTTP 请求 ─────────────────────────────────────────────────────────────

    private static ReleaseInfo fetchRelease(String apiUrl, int timeoutMs) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            if (conn.getResponseCode() != 200) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject json = new JSONObject(sb.toString());
            String tag = json.optString("tag_name", "").replaceAll("[^0-9.]", "");
            if (tag.isEmpty()) return null;

            String body = json.optString("body", "");
            String dlUrl = RELEASE_PAGE;
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    String u = assets.getJSONObject(i).optString("browser_download_url", "");
                    if (u.endsWith(".apk")) { dlUrl = u; break; }
                }
            }
            return new ReleaseInfo(tag, body, dlUrl);
        } catch (Exception e) {
            Log.d(TAG, "fetchRelease 失败(" + apiUrl + "): " + e.getMessage());
            return null;
        }
    }

    // ── 版本号比较（语义化版本：1.2.3 > 1.2.0）────────────────────────────────

    /** 从 PackageManager 读取当前安装版本号，不依赖 BuildConfig */
    private static String getVersionName(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0)
                    .versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    static boolean isNewer(String latest, String current) {
        try {
            int[] l = parse(latest), c = parse(current);
            for (int i = 0; i < Math.max(l.length, c.length); i++) {
                int lv = i < l.length ? l[i] : 0, cv = i < c.length ? c[i] : 0;
                if (lv > cv) return true;
                if (lv < cv) return false;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static int[] parse(String v) {
        String[] p = v.replaceAll("[^0-9.]", "").split("\\.");
        int[] n = new int[p.length];
        for (int i = 0; i < p.length; i++) n[i] = p[i].isEmpty() ? 0 : Integer.parseInt(p[i]);
        return n;
    }

    // ── SharedPreferences ─────────────────────────────────────────────────────

    private static boolean shouldCheck(Context ctx) {
        return System.currentTimeMillis() - prefs(ctx).getLong(KEY_LAST_CHECK, 0) > CHECK_INTERVAL_MS;
    }
    private static void saveLastCheckTime(Context ctx) {
        prefs(ctx).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
    }
    private static String getSkipped(Context ctx) { return prefs(ctx).getString(KEY_SKIPPED, ""); }
    private static void saveSkipped(Context ctx, String v) {
        prefs(ctx).edit().putString(KEY_SKIPPED, v).apply();
    }
    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ── 数据类 ────────────────────────────────────────────────────────────────

    private static class ReleaseInfo {
        final String version, changelog, downloadUrl;
        ReleaseInfo(String v, String c, String d) { version = v; changelog = c; downloadUrl = d; }
    }
}
