package com.lhht.xiaozhi.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import com.lhht.xiaozhi.activities.MainActivity;
import com.lhht.xiaozhi.settings.SettingsManager;
import com.lhht.xiaozhi.utils.LogUtils;

/**
 * 电脑端脚本的“打开 / 退出”控制入口。
 *
 * <p>盒子没有触摸屏，无法在设备上点击连接或退出；由电脑通过 adb 发送广播即可
 * 远程控制（见 tools/xiaozhi-device.ps1）。动作：
 * <ul>
 *   <li>{@link #ACTION_START}：打开自动模式并拉起主界面 → 自动连接、自动进入语音对话</li>
 *   <li>{@link #ACTION_STOP}：结束界面并退出进程 → 断开连接、释放麦克风（不改动开机自启）</li>
 *   <li>{@link #ACTION_BOOT_ON} / {@link #ACTION_BOOT_OFF}：单独设置盒子重启后是否自动打开应用</li>
 * </ul>
 *
 * <p>注意：该 receiver 必须 exported 才能接收 adb shell 发来的广播，因此用
 * {@code android:permission="android.permission.DUMP"} 限制发送方——该权限只有
 * shell/系统/特权应用持有，普通第三方应用无法控制本应用。</p>
 */
public class ControlReceiver extends BroadcastReceiver {

    public static final String ACTION_START = "com.lhht.xiaozhi.ai.action.START";
    public static final String ACTION_STOP = "com.lhht.xiaozhi.ai.action.STOP";
    public static final String ACTION_BOOT_ON = "com.lhht.xiaozhi.ai.action.BOOT_ON";
    public static final String ACTION_BOOT_OFF = "com.lhht.xiaozhi.ai.action.BOOT_OFF";

    private static final String TAG = "XiaoZhi-Control";
    /** 退出时给 ViewModel.onCleared() 释放录音/播放资源留出的时间，之后强制结束进程。 */
    private static final long EXIT_KILL_DELAY_MS = 5000;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;

        final Context appContext = context.getApplicationContext();
        final LogUtils log = LogUtils.getInstance();

        if (ACTION_BOOT_OFF.equals(action)) {
            new SettingsManager(appContext).setBootAutoStartEnabled(false);
            log.d(appContext, TAG, "开机自启已关闭");
            return;
        }

        if (ACTION_BOOT_ON.equals(action)) {
            new SettingsManager(appContext).setBootAutoStartEnabled(true);
            log.d(appContext, TAG, "开机自启已开启");
            return;
        }

        if (ACTION_START.equals(action)) {
            new SettingsManager(appContext).setAutoStartEnabled(true);
            log.d(appContext, TAG, "收到打开指令：开启自动连接并拉起主界面");
            Intent launch = new Intent(appContext, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            launch.putExtra(MainActivity.EXTRA_AUTO_START, true);
            try {
                appContext.startActivity(launch);
            } catch (Exception e) {
                log.e(appContext, TAG, "拉起主界面失败", e);
            }
            return;
        }

        if (ACTION_STOP.equals(action)) {
            // 只退出本次运行，不修改“开机自启”开关——那是一个独立开关
            log.d(appContext, TAG, "收到退出指令：停止自动连接，准备断开并释放麦克风");
            new Handler(Looper.getMainLooper()).post(() -> {
                MainActivity activity = MainActivity.getCurrent();
                if (activity != null) {
                    // 走正常生命周期：ViewModel.onCleared() 会断开 WebSocket、释放 AudioEngine
                    activity.shutdownForControl();
                } else {
                    // 界面不在（异常情况）：没有可释放的资源，直接结束进程
                    log.d(appContext, TAG, "没有正在运行的主界面，直接结束进程");
                    Process.killProcess(Process.myPid());
                }
            });
            // 兜底：释放流程可能卡在音频 HAL 上（主线程也会被卡住），
            // 因此用独立线程计时，到点无论如何都结束进程
            new Thread(() -> {
                try {
                    Thread.sleep(EXIT_KILL_DELAY_MS);
                } catch (InterruptedException ignored) {
                    // 仍需结束进程
                }
                log.d(appContext, TAG, "退出完成，结束进程");
                Process.killProcess(Process.myPid());
            }, "xiaozhi-exit").start();
        }
    }
}
