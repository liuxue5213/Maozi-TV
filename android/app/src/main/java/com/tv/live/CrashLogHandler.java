package com.tv.live;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃日志文件记录器。
 *
 * 作用：未捕获异常时把完整堆栈写入可访问的文件，
 * 方便在无法连接 adb 的电视盒子上排查闪退原因。
 *
 * 日志位置（写两个地方，任一可访问即可）：
 * 1. /sdcard/Android/data/{包名}/files/crash.log  (应用专属外部目录，无需权限)
 * 2. /sdcard/MaoziTV/crash.log                   (公共目录，需要 WRITE 权限时尝试)
 *
 * 调用方式：
 *   CrashLogHandler.init(this);   // Application.onCreate 中调用一次
 */
public class CrashLogHandler {

    private static final String TAG = "CrashLog";
    private static final String FILE_NAME = "crash.log";

    private static Context appContext;

    /** 初始化：设置全局未捕获异常处理器 */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
        Thread.UncaughtExceptionHandler original =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            // 写入崩溃日志
            writeCrashLog(thread, throwable);
            // 交给系统默认处理器（结束进程）
            if (original != null) {
                original.uncaughtException(thread, throwable);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(1);
            }
        });
        Log.i(TAG, "崩溃日志记录器已启用");
    }

    /** 手动写入一条崩溃日志（供其他异常路径调用） */
    public static void writeCrashLog(Thread thread, Throwable throwable) {
        if (appContext == null) return;
        try {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()).format(new Date());
            StringBuilder sb = new StringBuilder();
            sb.append("========================================\n");
            sb.append("时间: ").append(timestamp).append('\n');
            sb.append("线程: ").append(thread != null ? thread.getName() : "unknown").append('\n');
            sb.append("异常: ").append(throwable != null ? throwable.toString() : "null").append('\n');
            sb.append("----------------------------------------\n");
            if (throwable != null) {
                java.io.StringWriter sw = new java.io.StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                sb.append(sw).append('\n');
            }
            sb.append("========================================\n\n");

            String logContent = sb.toString();
            writeToFile(getExternalLogFile(), logContent);
            writeToFile(getPublicLogFile(), logContent);

            // 自动上报崩溃日志到云端
            try {
                CloudSync.track(appContext, "crash", 0, "App崩溃", logContent);
            } catch (Exception e) {
                Log.e(TAG, "上报崩溃日志失败: " + e.getMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "写入崩溃日志失败: " + e.getMessage());
        }
    }

    /** 获取外部专属目录日志文件（无需权限） */
    private static File getExternalLogFile() {
        try {
            File dir = appContext.getExternalFilesDir(null);
            if (dir == null) return null;
            return new File(dir, FILE_NAME);
        } catch (Exception e) {
            return null;
        }
    }

    /** 获取公共目录日志文件（可能需要权限，失败静默） */
    private static File getPublicLogFile() {
        try {
            File dir = new File(android.os.Environment.getExternalStorageDirectory(), "MaoziTV");
            return new File(dir, FILE_NAME);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeToFile(File file, String content) {
        if (file == null) return;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            // 追加模式，保留多条崩溃记录；超过 200KB 则清空重写
            FileWriter writer = new FileWriter(file, file.length() < 200 * 1024);
            writer.write(content);
            writer.close();
        } catch (Exception e) {
            Log.e(TAG, "写文件失败 " + file.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    /** 读取崩溃日志内容（供 App 内展示） */
    public static String readCrashLog() {
        if (appContext == null) return null;
        File file = getExternalLogFile();
        if (file == null || !file.exists()) {
            file = getPublicLogFile();
        }
        if (file == null || !file.exists()) return null;
        try {
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new java.io.FileInputStream(file), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 清除崩溃日志 */
    public static void clearCrashLog() {
        if (appContext == null) return;
        File file = getExternalLogFile();
        if (file != null) file.delete();
        file = getPublicLogFile();
        if (file != null) file.delete();
    }
}
