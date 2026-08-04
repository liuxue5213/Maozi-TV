package com.tv.live;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * 应用内自动更新。
 *
 * 流程：
 *   1. checkForUpdate() 拉取 GitHub/Gitee 上的 version.json
 *   2. 比较 versionCode，若远端更新则弹窗提示
 *   3. 用户确认后 downloadAndInstall() 后台下载 APK
 *   4. 下载完成调起系统安装器（需 REQUEST_INSTALL_PACKAGES 权限）
 *
 * version.json 字段：
 *   { versionCode, versionName, releaseNotes, apkUrl, apkUrlMirror, minSupportVersionCode }
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    /**
     * version.json 清单地址（多源兜底，按顺序尝试）。
     * jsdelivr 镜像国内访问更稳定，放第一位。
     */
    private static final String[] VERSION_JSON_URLS = {
            "https://cdn.jsdelivr.net/gh/liuxue5213/Maozi-TV@main/version.json",
            "https://raw.githubusercontent.com/liuxue5213/Maozi-TV/main/version.json",
            "https://gitee.com/liuxue5213/maozi-tv/raw/main/version.json",
    };

    private final Activity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public UpdateChecker(Activity activity) {
        this.activity = activity;
    }

    /** 当前已安装版本的 versionCode */
    private int currentVersionCode() {
        try {
            PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private String currentVersionName() {
        try {
            PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /**
     * 检查更新（后台线程网络请求，回调在主线程）。
     *
     * @param silent true=静默检查（无更新不弹窗，仅 Toast）；
     *              false=用户主动触发（各种情况都提示）
     */
    public void checkForUpdate(boolean silent) {
        new Thread(() -> {
            JSONObject json = null;
            final Exception[] lastErr = new Exception[1];
            for (String url : VERSION_JSON_URLS) {
                try {
                    json = fetchJson(url);
                    if (json != null) break;
                } catch (Exception e) {
                    lastErr[0] = e;
                    Log.w(TAG, "拉取 version.json 失败: " + url + " — " + e.getMessage());
                }
            }

            final JSONObject result = json;
            mainHandler.post(() -> handleResult(result, silent, lastErr[0]));
        }).start();
    }

    private JSONObject fetchJson(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "MaoziTV-UpdateChecker");
            if (conn.getResponseCode() != 200) return null;

            InputStream is = conn.getInputStream();
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            is.close();
            return new JSONObject(baos.toString());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void handleResult(JSONObject json, boolean silent, Exception lastErr) {
        if (json == null) {
            if (!silent) {
                Toast.makeText(activity, "检查更新失败：网络错误",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }

        try {
            int remoteCode = json.optInt("versionCode", 0);
            String remoteName = json.optString("versionName", "");
            String notes = json.optString("releaseNotes", "");
            String apkUrl = json.optString("apkUrl", "");
            String apkMirror = json.optString("apkUrlMirror", "");

            int current = currentVersionCode();
            if (remoteCode <= current) {
                if (!silent) {
                    Toast.makeText(activity,
                            "已是最新版本 " + currentVersionName(),
                            Toast.LENGTH_SHORT).show();
                }
                return;
            }

            // 有新版本 → 弹窗
            showUpdateDialog(remoteName, notes, apkUrl, apkMirror);
        } catch (Exception e) {
            Log.e(TAG, "解析 version.json 出错", e);
            if (!silent) {
                Toast.makeText(activity, "检查更新失败：数据异常",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showUpdateDialog(String newVersion, String notes,
                                  String apkUrl, String apkMirror) {
        String msg = String.format(Locale.getDefault(),
                "当前版本：v%s\n最新版本：v%s\n\n更新内容：\n%s",
                currentVersionName(), newVersion,
                notes.isEmpty() ? "（未提供）" : notes);

        String[] urls = apkMirror != null && !apkMirror.isEmpty()
                ? new String[]{apkMirror, apkUrl}
                : new String[]{apkUrl};

        new AlertDialog.Builder(activity)
                .setTitle("发现新版本")
                .setMessage(msg)
                .setPositiveButton("立即更新", (d, w) -> downloadAndInstall(urls))
                .setNegativeButton("稍后再说", null)
                .show();
    }

    /**
     * 后台下载 APK 并调起安装。多源兜底，逐个尝试。
     */
    private void downloadAndInstall(String[] urls) {
        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle("正在下载更新")
                .setMessage("请稍候…")
                .setCancelable(false)
                .show();

        new Thread(() -> {
            File apkFile = null;
            Exception lastErr = null;
            for (String url : urls) {
                try {
                    apkFile = downloadApk(url);
                    if (apkFile != null) break;
                } catch (Exception e) {
                    lastErr = e;
                    Log.w(TAG, "下载 APK 失败: " + url + " — " + e.getMessage());
                }
            }

            final File result = apkFile;
            final Exception err = lastErr;
            mainHandler.post(() -> {
                progress.dismiss();
                if (result != null) {
                    installApk(result);
                } else {
                    String tip = err != null ? err.getMessage() : "未知错误";
                    Toast.makeText(activity, "下载失败：" + tip,
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private File downloadApk(String urlStr) throws Exception {
        File dir = new File(activity.getExternalCacheDir(), "updates");
        if (!dir.exists() && !dir.mkdirs()) {
            dir = new File(activity.getCacheDir(), "updates");
            if (!dir.exists()) dir.mkdirs();
        }
        File apk = new File(dir, "MaoziTV-update.apk");
        if (apk.exists()) apk.delete();

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "MaoziTV-UpdateChecker");
            // GitHub release 下载会 302 跟随，HttpURLConnection 默认自动处理
            if (conn.getResponseCode() != 200) {
                throw new Exception("HTTP " + conn.getResponseCode());
            }

            InputStream is = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(apk);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
            fos.flush();
            fos.close();
            is.close();
            return apk;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void installApk(File apk) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        String mimeType = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension("apk");
        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            uri = Uri.fromFile(apk);
        }
        intent.setDataAndType(uri, mimeType != null ? mimeType : "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "调起安装器失败", e);
            Toast.makeText(activity, "无法打开安装器，请检查权限设置",
                    Toast.LENGTH_LONG).show();
        }
    }
}

