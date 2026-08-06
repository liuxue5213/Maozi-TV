package com.tv.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 轻量埋点 + 云同步客户端。
 *
 * 埋点：启动/换台/收藏/崩溃 → POST 到后端 /api/events
 * 云同步：收藏/历史 通过 client_id 备份到后端 /api/sync/*
 *
 * 全部静默失败（后端不可用不打扰用户），网络请求在后台线程。
 */
public class CloudSync {

    private static final String TAG = "CloudSync";
    private static final String PREFS_NAME = "maozi_tv_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_CLIENT_ID = "client_id";
    private static final String DEFAULT_SERVER_URL = "http://192.168.1.100:8000";

    // 共享线程池：避免每次调用 new Thread（换台/收藏是高频操作）
    private static final java.util.concurrent.ExecutorService NET_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(2);

    // ── 匿名设备标识 ──────────────────────────────────────
    public static String getClientId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_CLIENT_ID, "");
        if (id.isEmpty()) {
            id = "tv_" + android.os.Build.SERIAL + "_" + (System.currentTimeMillis() & 0xFFFFF);
            prefs.edit().putString(KEY_CLIENT_ID, id).apply();
        }
        return id;
    }

    private static String getServerUrl(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
    }

    // ── 埋点上报 ──────────────────────────────────────────
    public static void track(final Context context, final String eventType,
                             final Integer channelId, final String channelName,
                             final String extra) {
        NET_EXECUTOR.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("event_type", eventType);
                body.put("channel_id", channelId != null ? channelId : JSONObject.NULL);
                body.put("channel_name", channelName != null ? channelName : "");
                body.put("client_id", getClientId(context));
                body.put("extra", extra != null ? extra : "");

                String url = getServerUrl(context) + "/api/events";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                conn.disconnect();
                if (code != 200) {
                    Log.d(TAG, "埋点失败 HTTP " + code + " (" + eventType + ")");
                }
            } catch (Exception e) {
                // 静默失败，不打扰用户
            }
        });
    }

    // ── 云同步：保存 ──────────────────────────────────────
    public static void save(final Context context, final List<Integer> favorites,
                            final List<Integer> history) {
        NET_EXECUTOR.execute(() -> {
            try {
                JSONArray favArr = new JSONArray();
                for (int id : favorites) favArr.put(id);
                JSONArray histArr = new JSONArray();
                for (int id : history) histArr.put(id);

                JSONObject body = new JSONObject();
                body.put("client_id", getClientId(context));
                body.put("favorites", favArr);
                body.put("history", histArr);

                String url = getServerUrl(context) + "/api/sync/save";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                conn.disconnect();
                Log.d(TAG, "云同步保存 HTTP " + code);
            } catch (Exception e) {
                Log.d(TAG, "云同步保存失败: " + e.getMessage());
            }
        });
    }

    // ── 云同步：加载 ──────────────────────────────────────
    public interface SyncLoadCallback {
        void onResult(List<Integer> favorites, List<Integer> history);
    }

    public static void load(final Context context, final SyncLoadCallback callback) {
        NET_EXECUTOR.execute(() -> {
            try {
                String url = getServerUrl(context) + "/api/sync/load?client_id="
                        + java.net.URLEncoder.encode(getClientId(context), "UTF-8");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setRequestProperty("User-Agent", "MaoziTV-Sync/2.0");

                int code = conn.getResponseCode();
                if (code != 200) {
                    conn.disconnect();
                    postEmpty(callback);
                    return;
                }

                StringBuilder sb = new StringBuilder();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                JSONObject obj = new JSONObject(sb.toString());
                List<Integer> favorites = parseIdArray(obj.optJSONArray("favorites"));
                List<Integer> history = parseIdArray(obj.optJSONArray("history"));

                new Handler(Looper.getMainLooper()).post(() ->
                        callback.onResult(favorites, history));
            } catch (Exception e) {
                Log.d(TAG, "云同步加载失败: " + e.getMessage());
                postEmpty(callback);
            }
        });
    }

    private static void postEmpty(final SyncLoadCallback callback) {
        new Handler(Looper.getMainLooper()).post(() ->
                callback.onResult(new ArrayList<>(), new ArrayList<>()));
    }

    private static List<Integer> parseIdArray(JSONArray arr) {
        List<Integer> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            list.add(arr.optInt(i));
        }
        return list;
    }
}
