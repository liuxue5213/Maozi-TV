package com.tv.live;

import android.app.Application;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 应用入口。
 *
 * 职责：
 * 1. 初始化 Bugly 崩溃上报（需在官方平台注册后填入 APP_ID）
 * 2. 统一初始化 WorkManager 后台任务
 *
 * ⚠️ Bugly 接入说明：
 *   1. 到 bugly.qq.com 注册应用，获得 APP_ID
 *   2. 将下方 BUGLY_APP_ID 替换为真实 APP_ID
 *   3. 在 app/build.gradle.kts 添加依赖：
 *        implementation("com.tencent.bugly:crashreport:latest.release")
 *   4. 本类通过反射调用 Bugly，未加依赖时自动跳过，不影响编译运行
 */
public class App extends Application {

    private static final String TAG = "App";
    private static final String BUGLY_APP_ID = ""; // TODO: 填入你的 Bugly APP_ID

    @Override
    public void onCreate() {
        super.onCreate();

        initBugly();

        // 注册后台频道源周期检查（每 6 小时）
        // App 启动和 MainActivity 都会调用 schedule()，
        // 内部用 ExistingPeriodicWorkPolicy.KEEP 去重，不会重复注册
        try {
            ChannelUpdateWorker.schedule(this);
        } catch (Exception e) {
            Log.e(TAG, "注册后台任务失败: " + e.getMessage());
        }
    }

    /**
     * 初始化 Bugly 崩溃上报（反射调用，避免编译期强依赖）。
     */
    private void initBugly() {
        if (BUGLY_APP_ID == null || BUGLY_APP_ID.trim().isEmpty()) {
            Log.i(TAG, "Bugly APP_ID 未配置，崩溃上报已禁用（不影响运行）");
            return;
        }
        try {
            // 反射加载 CrashReport，未引入依赖时 ClassNotFoundException 被捕获
            Class<?> crashReportClass = Class.forName("com.tencent.bugly.crashreport.CrashReport");
            Method initMethod = crashReportClass.getMethod("initCrashReport",
                    android.content.Context.class, String.class, boolean.class);
            initMethod.invoke(null, getApplicationContext(), BUGLY_APP_ID, false);
            Log.i(TAG, "Bugly 崩溃上报已启用");
        } catch (Throwable t) {
            Log.w(TAG, "Bugly 初始化失败（未引入依赖?）: " + t.getMessage());
        }
    }
}
