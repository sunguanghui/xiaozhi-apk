package com.lhht.xiaozhi.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.lhht.xiaozhi.activities.MainActivity;
import com.lhht.xiaozhi.settings.SettingsManager;
import com.lhht.xiaozhi.utils.LogUtils;

/**
 * 开机自启：系统启动完成后直接拉起主界面。
 *
 * <p>音箱/盒子类设备通常没有触摸屏，无法手动点“连接”和“开始聊天”。主界面启动后
 * 会按设置自动连接服务器并进入语音对话（见 MainActivity 中的自动连接逻辑）。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    // 部分电视盒子/手机厂商的“快速开机”广播，标准 BOOT_COMPLETED 之外补充兼容
    private static final String ACTION_QUICKBOOT =
            "android.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_HTC_QUICKBOOT =
            "com.htc.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        boolean isBoot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || ACTION_QUICKBOOT.equals(action)
                || ACTION_HTC_QUICKBOOT.equals(action);
        if (!isBoot) return;

        if (!new SettingsManager(context).isBootAutoStartEnabled()) {
            LogUtils.getInstance().d(context, TAG, "开机自启已关闭，忽略本次广播");
            return;
        }

        try {
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launch.putExtra(MainActivity.EXTRA_AUTO_START, true);
            context.startActivity(launch);
            LogUtils.getInstance().d(context, TAG,
                    "开机自启：已拉起主界面（开机 "
                            + android.os.SystemClock.elapsedRealtime() / 1000
                            + "s），等待自动连接并抢占麦克风");
        } catch (Exception e) {
            // 新系统（Android 10+）对后台启动 Activity 有限制，失败时记录原因，不影响手动打开
            LogUtils.getInstance().e(context, TAG, "开机自启拉起主界面失败", e);
        }
    }
}
