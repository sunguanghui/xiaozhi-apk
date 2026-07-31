package com.lhht.xiaozhi.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 轻量日志工具：写入 App 私有目录 + 支持 SAF 导出。
 * 所有 I/O 操作均在后台单线程池执行，绝不阻塞主线程。
 */
public class LogUtils {

    public static final int EXPORT_LOG_REQUEST_CODE = 1001;

    private static final String LOG_FILE_NAME = "app_debug.log";
    private static final long MAX_LOG_SIZE = 5 * 1024 * 1024L; // 5 MB
    private static final SimpleDateFormat SDF =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());

    private static volatile LogUtils instance;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private LogUtils() {}

    public static LogUtils getInstance() {
        if (instance == null) {
            synchronized (LogUtils.class) {
                if (instance == null) instance = new LogUtils();
            }
        }
        return instance;
    }

    // ── 写日志 ────────────────────────────────────────────────────────────────

    /** 返回日志文件对象，供外部分享使用 */
    public File getLogFile(Context context) {
        if (context == null) return null;
        return new File(context.getApplicationContext().getFilesDir() + "/logs", LOG_FILE_NAME);
    }

    public void d(Context context, String tag, String msg) {
        Log.d(tag, msg);
        writeToFile(context, "D", tag, msg, null);
    }

    public void e(Context context, String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
        writeToFile(context, "E", tag, msg, t);
    }

    private void writeToFile(Context context, String level, String tag, String msg, Throwable t) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext();
        ioExecutor.execute(() -> {
            try {
                File logDir = new File(appCtx.getFilesDir(), "logs");
                if (!logDir.exists()) logDir.mkdirs();
                File logFile = new File(logDir, LOG_FILE_NAME);

                // 超过 5 MB 先清空
                if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                    new FileWriter(logFile, false).close(); // truncate
                }

                String timestamp = SDF.format(new Date());
                StringBuilder sb = new StringBuilder();
                sb.append(timestamp).append(" ").append(level)
                  .append("/").append(tag).append(": ").append(msg);

                if (t != null) {
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    sb.append("\n").append(sw);
                }
                sb.append("\n");

                try (FileWriter fw = new FileWriter(logFile, true)) {
                    fw.write(sb.toString());
                }
            } catch (Exception ignored) {
                // 日志模块异常不能影响主业务
            }
        });
    }

    // ── SAF 导出 ─────────────────────────────────────────────────────────────

    /** 弹出系统文件选择器，让用户指定导出路径 */
    public void startExportLog(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "xiaozhi_debug.log");
        activity.startActivityForResult(intent, EXPORT_LOG_REQUEST_CODE);
    }

    /** 在 onActivityResult 中调用，将日志写入用户选定的 URI */
    public void handleExportResult(Context context, Uri targetUri) {
        if (context == null || targetUri == null) return;
        final Context appCtx = context.getApplicationContext();
        ioExecutor.execute(() -> {
            boolean success = false;
            try {
                File logFile = new File(appCtx.getFilesDir() + "/logs", LOG_FILE_NAME);
                if (!logFile.exists()) {
                    showToast(appCtx, "暂无日志文件");
                    return;
                }
                try (BufferedReader br = new BufferedReader(new FileReader(logFile));
                     OutputStream os = appCtx.getContentResolver().openOutputStream(targetUri)) {
                    if (os == null) throw new Exception("无法打开目标文件");
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    os.write(sb.toString().getBytes("UTF-8"));
                    success = true;
                }
            } catch (Exception e) {
                Log.e("LogUtils", "导出日志失败", e);
            }
            final boolean result = success;
            showToast(appCtx, result ? "日志导出成功" : "日志导出失败");
        });
    }

    private void showToast(Context ctx, String msg) {
        mainHandler.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
    }
}
