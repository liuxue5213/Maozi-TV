package com.tv.live;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 多源频道聚合器。
 *
 * 流程：
 * 1. 拉取 source-list.json 获取信号源清单
 * 2. 并行拉取所有启用的 channels.json
 * 3. 按标准化频道名称合并去重（同名频道 sources 合并）
 * 4. 过滤无源频道，重新编号
 *
 * 容错：
 * - source-list.json 拉取失败 → 回退到硬编码默认 URLs
 * - 单个源拉取失败 → 跳过，不影响其他源
 * - 所有源失败 → 返回 null，调用方回退到缓存
 */
public class MultiSourceFetcher {

    private static final String TAG = "MultiSourceFetcher";

    // ── 默认信号源清单（source-list.json 拉取失败时回退）────────
    private static final String[] DEFAULT_CHANNEL_URLS = {
            "https://gitee.com/liuxue5213/maozi-tv/raw/main/channels.json",
            "https://cdn.jsdelivr.net/gh/liuxue5213/Maozi-TV@main/channels.json",
            "https://raw.githubusercontent.com/liuxue5213/Maozi-TV/main/channels.json",
    };

    private static final String[] SOURCE_LIST_URLS = {
            "https://gitee.com/liuxue5213/maozi-tv/raw/main/source-list.json",
            "https://cdn.jsdelivr.net/gh/liuxue5213/Maozi-TV@main/source-list.json",
            "https://raw.githubusercontent.com/liuxue5213/Maozi-TV/main/source-list.json",
    };

    // 网络超时
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;
    // 单源最大等待时间
    private static final long PER_SOURCE_TIMEOUT_MS = 20000;

    public interface FetchCallback {
        void onResult(FetchResult result);
    }

    /** 聚合结果 */
    public static class FetchResult {
        public List<ChannelOptimized> channels;
        public String version;          // channels.json 版本号
        public long generatedAtTs;      // 生成时间戳
        public String generatedAt;      // 生成时间字符串
        public int totalCount;          // 总频道数（过滤前）
        public int mergedCount;         // 合并去重后
        public int sourceCount;         // 成功拉取的源数量;
        public List<String> errors;     // 各源错误信息

        public FetchResult() {
            channels = new ArrayList<>();
            errors = new ArrayList<>();
        }
    }

    // ── 信号源配置 ────────────────────────────────────────
    private static class SourceConfig {
        String name;
        String url;
        int priority;
        boolean enabled;
    }

    // ── 自定义源（用户手动添加）──────────────────────────
    private final List<String> customSourceUrls = new ArrayList<>();

    /** 添加用户自定义的源 URL（m3u / json / 接口地址） */
    public void addCustomSource(String url) {
        if (url != null && !url.trim().isEmpty() && !customSourceUrls.contains(url.trim())) {
            customSourceUrls.add(url.trim());
        }
    }

    /**
     * 拉取并聚合所有信号源（后台线程安全，回调在后台线程，勿直接改 UI）。
     */
    public void fetchAll(FetchCallback callback) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            FetchResult result = doFetch();
            if (callback != null) {
                callback.onResult(result);
            }
        });
        executor.shutdown();
    }

    // ── 核心拉取逻辑 ──────────────────────────────────────
    private FetchResult doFetch() {
        FetchResult result = new FetchResult();

        // 1. 获取信号源清单
        List<SourceConfig> configs = fetchSourceList();

        // 1.5 追加用户自定义源（优先拉取）
        for (String url : customSourceUrls) {
            SourceConfig sc = new SourceConfig();
            sc.name = "自定义源";
            sc.url = url;
            sc.priority = 0;
            sc.enabled = true;
            configs.add(sc);
        }

        // 2. 并行拉取所有 channels.json
        Map<String, List<ChannelOptimized>> sourceResults = fetchAllSources(configs, result);

        result.sourceCount = sourceResults.size();

        if (sourceResults.isEmpty()) {
            result.errors.add("所有信号源均拉取失败");
            return result;
        }

        // 3. 合并去重
        List<ChannelOptimized> merged = mergeChannels(sourceResults, result);

        // 4. 过滤无源频道 + 重新编号
        List<ChannelOptimized> filtered = filterAndNumber(merged);

        result.channels = filtered;
        result.mergedCount = filtered.size();

        Log.i(TAG, "聚合完成: " + result.sourceCount + " 个源, "
                + result.totalCount + " → " + result.mergedCount + " 频道");
        return result;
    }

    // ── 1. 获取信号源清单 ─────────────────────────────────
    private List<SourceConfig> fetchSourceList() {
        for (String url : SOURCE_LIST_URLS) {
            try {
                String json = downloadString(url);
                if (json != null) {
                    List<SourceConfig> configs = parseSourceList(json);
                    if (!configs.isEmpty()) {
                        Log.i(TAG, "信号源清单加载成功: " + url + " (" + configs.size() + " 个源)");
                        return configs;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "拉取 source-list.json 失败: " + url + " — " + e.getMessage());
            }
        }

        // 回退到默认 URLs
        Log.w(TAG, "所有 source-list.json 源失败，回退到硬编码默认 URLs");
        List<SourceConfig> defaults = new ArrayList<>();
        for (int i = 0; i < DEFAULT_CHANNEL_URLS.length; i++) {
            SourceConfig sc = new SourceConfig();
            sc.name = "默认源" + (i + 1);
            sc.url = DEFAULT_CHANNEL_URLS[i];
            sc.priority = i + 1;
            sc.enabled = true;
            defaults.add(sc);
        }
        return defaults;
    }

    private List<SourceConfig> parseSourceList(String json) {
        List<SourceConfig> list = new ArrayList<>();
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray arr = obj.optJSONArray("sources");
            if (arr == null) return list;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject src = arr.getJSONObject(i);
                if (!src.optBoolean("enabled", true)) continue;

                SourceConfig sc = new SourceConfig();
                sc.name = src.optString("name", "未命名源");
                sc.url = src.optString("url", "");
                sc.priority = src.optInt("priority", 99);
                sc.enabled = true;

                if (!sc.url.isEmpty()) {
                    list.add(sc);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "解析 source-list.json 失败: " + e.getMessage());
        }
        return list;
    }

    // ── 2. 并行拉取所有 channels.json ────────────────────
    private Map<String, List<ChannelOptimized>> fetchAllSources(
            List<SourceConfig> configs, FetchResult result) {

        Map<String, List<ChannelOptimized>> results = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(configs.size(), 4)); // 最多 4 并发

        CountDownLatch latch = new CountDownLatch(configs.size());

        for (SourceConfig sc : configs) {
            pool.execute(() -> {
                try {
                    List<ChannelOptimized> channels = fetchSingleSource(sc.url, result);
                    if (channels != null && !channels.isEmpty()) {
                        synchronized (results) {
                            results.put(sc.name, channels);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(PER_SOURCE_TIMEOUT_MS * configs.size() / 4, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        pool.shutdownNow();
        return results;
    }

    /** 拉取单个 channels.json 并解析 */
    private List<ChannelOptimized> fetchSingleSource(String urlStr, FetchResult result) {
        try {
            String json = downloadString(urlStr);
            if (json == null) return null;

            JSONObject obj = new JSONObject(json);

            // 记录元数据（取第一个成功源的）
            if (result.version == null) {
                result.version = String.valueOf(obj.optInt("version", 0));
                result.generatedAtTs = obj.optLong("generated_at_ts", 0);
                result.generatedAt = obj.optString("generated_at", "");
            }

            JSONArray chArr = obj.optJSONArray("channels");
            if (chArr == null) {
                result.errors.add(urlStr + ": 无 channels 数组");
                return null;
            }

            List<ChannelOptimized> list = new ArrayList<>();
            for (int i = 0; i < chArr.length(); i++) {
                try {
                    ChannelOptimized ch = ChannelOptimized.fromJson(chArr.getJSONObject(i));
                    list.add(ch);
                } catch (Exception e) {
                    // 单个频道解析失败不影响整体
                }
            }

            result.totalCount += list.size();
            Log.d(TAG, "源 " + urlStr + " 解析 " + list.size() + " 频道");
            return list;

        } catch (Exception e) {
            result.errors.add(urlStr + ": " + e.getMessage());
            Log.w(TAG, "拉取源失败: " + urlStr + " — " + e.getMessage());
            return null;
        }
    }

    // ── 3. 合并去重 ──────────────────────────────────────
    private List<ChannelOptimized> mergeChannels(
            Map<String, List<ChannelOptimized>> sourceResults, FetchResult result) {

        // key = 标准化频道名称, value = 合并后的频道
        Map<String, ChannelOptimized> merged = new LinkedHashMap<>();

        for (Map.Entry<String, List<ChannelOptimized>> entry : sourceResults.entrySet()) {
            for (ChannelOptimized ch : entry.getValue()) {
                if (ch == null || ch.name == null || ch.name.trim().isEmpty()) continue;

                String key = normalizeChannelName(ch.name);

                ChannelOptimized existing = merged.get(key);
                if (existing != null) {
                    // 合并 sources（去重）
                    mergeSources(existing, ch);
                } else {
                    // 新频道（深拷贝，避免引用污染）
                    ChannelOptimized copy = new ChannelOptimized();
                    copy.id = ch.id;
                    copy.name = ch.name;
                    copy.group = ch.group;
                    copy.logo = ch.logo;
                    copy.url = ch.url;
                    copy.healthy = ch.healthy;
                    copy.region = ch.region;
                    copy.sources = new ArrayList<>(ch.sources);
                    copy.currentSourceIndex = 0;
                    merged.put(key, copy);
                }
            }
        }

        return new ArrayList<>(merged.values());
    }

    /** 将 from 的 sources 合并到 to（去重） */
    private void mergeSources(ChannelOptimized to, ChannelOptimized from) {
        if (from.sources == null) return;
        for (String src : from.sources) {
            if (src != null && !src.isEmpty() && !to.sources.contains(src)) {
                to.sources.add(src);
            }
        }
        // 如果 to 的 url 为空但 from 有，补上
        if ((to.url == null || to.url.isEmpty()) && from.url != null && !from.url.isEmpty()) {
            to.url = from.url;
        }
    }

    // ── 4. 过滤 + 编号 ──────────────────────────────────
    private List<ChannelOptimized> filterAndNumber(List<ChannelOptimized> list) {
        List<ChannelOptimized> result = new ArrayList<>();
        int num = 0;
        for (ChannelOptimized ch : list) {
            // 过滤无源频道
            if (ch.sources == null || ch.sources.isEmpty()) continue;

            // 确保 url 与 sources 一致
            if ((ch.url == null || ch.url.isEmpty()) && !ch.sources.isEmpty()) {
                ch.url = ch.sources.get(0);
            }

            ch.channelNumber = ++num;
            ch.currentSourceIndex = 0;
            result.add(ch);
        }
        return result;
    }

    // ── 工具方法 ─────────────────────────────────────────

    /** 频道名称标准化（用于去重匹配） */
    static String normalizeChannelName(String name) {
        if (name == null) return "";
        // 移除括号内容、空格、特殊符号，转大写
        return name
                .replaceAll("[（(][^）)]*[）)]", "")  // 移除 (1080p) （高清）等
                .replaceAll("\\s+", "")               // 移除空格
                .replaceAll("[_\\-·•]+", "")           // 移除分隔符
                .replaceAll("[【\\[][^\\]】]*[\\]】]", "") // 移除 【】[]
                .toUpperCase()
                .trim();
    }

    /** HTTP GET 下载字符串 */
    private String downloadString(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36");

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
}
