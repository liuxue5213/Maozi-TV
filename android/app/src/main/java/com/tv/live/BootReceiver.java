package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启：系统启动完成后，可选直接拉起主界面进入上次播放的频道。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";
    private static final String PREFS_NAME = "maozi_tv_prefs";
    private static final String KEY_BOOT_LAUNCH = "boot_launch_enabled"; // 是否开机自启
    private static final String KEY_MODE = "mode";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }

        Log.i(TAG, "系统启动完成，检查是否开机自启");

        boolean enabled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_BOOT_LAUNCH, false);
        if (!enabled) {
            Log.d(TAG, "开机自启已关闭，跳过");
            return;
        }

        // 直接拉起主界面（上次播放频道会在 MainActivity 中自动恢复）
        try {
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(launch);
            Log.i(TAG, "已拉起 MainActivity");
        } catch (Exception e) {
            Log.e(TAG, "拉起失败: " + e.getMessage());
        }
    }
}
