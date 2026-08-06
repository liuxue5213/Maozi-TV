package com.tv.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * WorkManager 后台周期任务：检测 channels.json 是否有更新。
 *
 * 策略：
 * - 每 6 小时检查一次（只拉轻量 version.json，~200B）
 * - 发现更新后静默下载完整 channels.json 并缓存
 * - 下次启动时 App 会优先使用新缓存
 */
public class ChannelUpdateWorker extends Worker {

    private static final String TAG = "ChannelUpdateWorker";

    // WorkManager 任务名
    public static final String WORK_NAME = "channel_update_check";

    // channels.json 地址（多源兜底，直接下载比对生成时间戳）
    private static final String[] CHANNEL_URLS = {
            "https://gitee.com/liuxue5213/maozi-tv/raw/main/channels.json",
            "https://cdn.jsdelivr.net/gh/liuxue5213/Maozi-TV@main/channels.json",
            "https://raw.githubusercontent.com/liuxue5213/Maozi-TV/main/channels.json",
    };

    private static final String PREFS_NAME = "maozi_tv_prefs";
    private static final String KEY_CACHED_VERSION = "cached_version";
    private static final String KEY_CACHED_JSON = "cached_channels_json";
    private static final String KEY_CACHED_TS = "cached_generated_ts";

    public ChannelUpdateWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "后台检查频道更新...");
        Context context = getApplicationContext();

        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            int cachedVersion = prefs.getInt(KEY_CACHED_VERSION, 0);
            long cachedTs = prefs.getLong(KEY_CACHED_TS, 0);

            // 1. 下载完整 channels.json（含真实生成时间戳）
            // 注意：不能依赖 version.json 的 versionCode，那是 APK 版本号，
            //       与 channels.json 的 version(频道数据版本) 不是同一套号段，
            //       直接比较会导致每 6 小时无条件下载。
            String channelsJson = fetchChannelsJson();
            if (channelsJson == null || channelsJson.isEmpty()) {
                Log.w(TAG, "下载 channels.json 失败");
                return Result.retry();
            }

            // 2. 验证 JSON 有效性并读取版本信息
            JSONObject remote = new JSONObject(channelsJson);
            if (remote.optJSONArray("channels") == null) {
                Log.w(TAG, "channels.json 格式无效");
                return Result.retry();
            }

            int remoteVersion = remote.optInt("version", 0);
            long remoteTs = remote.optLong("generated_at_ts", 0);

            Log.d(TAG, "版本对比: 本地 v" + cachedVersion + " ts=" + cachedTs
                    + " → 远程 v" + remoteVersion + " ts=" + remoteTs);

            // 3. 如果没变化（时间戳和版本都不更新），直接返回
            if (remoteTs <= cachedTs && remoteVersion <= cachedVersion) {
                Log.d(TAG, "频道数据未变化，跳过");
                return Result.success();
            }

            // 4. 有更新 → 缓存新数据
            prefs.edit()
                    .putString(KEY_CACHED_JSON, channelsJson)
                    .putInt(KEY_CACHED_VERSION, remoteVersion)
                    .putLong(KEY_CACHED_TS, remoteTs)
                    .apply();

            Log.i(TAG, "频道数据已静默更新到 v" + remoteVersion + " ts=" + remoteTs);
            return Result.success();

        } catch (Exception e) {
            Log.w(TAG, "后台检查更新异常: " + e.getMessage());
            return Result.retry();
        }
    }

    // ── 完整 channels.json 拉取 ──────────────────────────
    private String fetchChannelsJson() {
        for (String url : CHANNEL_URLS) {
            try {
                String json = downloadString(url, 8000, 15000);
                if (json != null && json.contains("\"channels\"")) {
                    return json;
                }
            } catch (Exception e) {
                Log.d(TAG, "拉取 channels 失败: " + url);
            }
        }
        return null;
    }

    // ── 工具方法 ─────────────────────────────────────────
    private String downloadString(String urlStr, int connectTimeout, int readTimeout) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setRequestProperty("User-Agent", "MaoziTV-BackgroundCheck/2.0");

            int code = conn.getResponseCode();
            if (code != 200) return null;

            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ── 静态 API ─────────────────────────────────────────

    /**
     * 注册周期检查任务（应在 Application 或 MainActivity onCreate 中调用一次）。
     */
    public static void schedule(Context context) {
        // 约束：有网络连接，不限制电池状态
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(false)
                .build();

        // 周期：6 小时（最短 15 分钟是 WorkManager 的限制）
        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(ChannelUpdateWorker.class, 6, TimeUnit.HOURS)
                        .setConstraints(constraints)
                        .setInitialDelay(1, TimeUnit.MINUTES) // 首次延迟 1 分钟
                        .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // 已存在则保留，不重复注册
                request
        );

        Log.i(TAG, "后台频道检查任务已注册 (每 6 小时)");
    }

    /**
     * 立即执行一次检查（用于启动时）。
     */
    public static void checkNow(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(ChannelUpdateWorker.class, 1, TimeUnit.HOURS)
                        .setConstraints(constraints)
                        .build();

        WorkManager.getInstance(context).enqueue(request);
    }

    /**
     * 取消后台检查。
     */
    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }
}
