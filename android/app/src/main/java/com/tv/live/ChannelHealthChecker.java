package com.tv.live;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 频道健康检查工具
 * 用于批量检查频道源的可用性
 */
public class ChannelHealthChecker {

    private static final String TAG = "ChannelHealthChecker";
    private static final int TIMEOUT_MS = 5000;  // 5秒超时

    /**
     * 检查单个 URL 的健康状态
     * @param url 要检查的URL
     * @return true表示可用，false表示不可用
     */
    public static boolean checkUrlHealth(String url) {
        HttpURLConnection conn = null;
        try {
            URL urlObj = new URL(url);
            conn = (HttpURLConnection) urlObj.openConnection();
            conn.setRequestMethod("HEAD");  // HEAD 请求更快
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "MaoziTV/2.0");

            int responseCode = conn.getResponseCode();
            
            // 对于直播流，2xx 和 3xx 都认为是健康的
            boolean isHealthy = (responseCode >= 200 && responseCode < 400);
            
            if (conn != null) conn.disconnect();
            return isHealthy;

        } catch (Exception e) {
            // 连接失败、超时等都认为不健康
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
            return false;
        }
    }

    /**
     * 获取URL的响应时间（毫秒）
     * @param url 要检查的URL
     * @return 响应时间，失败返回 -1
     */
    public static long getUrlResponseTime(String url) {
        HttpURLConnection conn = null;
        try {
            long startTime = System.currentTimeMillis();
            URL urlObj = new URL(url);
            conn = (HttpURLConnection) urlObj.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "MaoziTV/2.0");

            int responseCode = conn.getResponseCode();
            long endTime = System.currentTimeMillis();

            if (conn != null) conn.disconnect();
            
            return (responseCode >= 200 && responseCode < 400) ? (endTime - startTime) : -1;

        } catch (Exception e) {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
            return -1;
        }
    }

    /**
     * 验证 HLS 直播流的可用性（更准确的检查）
     * @param m3u8Url m3u8直播流URL
     * @return true表示可用，false表示不可用
     */
    public static boolean checkHlsStreamHealth(String m3u8Url) {
        HttpURLConnection conn = null;
        try {
            URL urlObj = new URL(m3u8Url);
            conn = (HttpURLConnection) urlObj.openConnection();
            conn.setRequestMethod("GET");  // HLS 需要GET请求
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "MaoziTV/2.0");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                if (conn != null) conn.disconnect();
                return false;
            }

            // 读取前几行内容验证是否是有效的 m3u8
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)
            );
            
            String line;
            boolean isValidM3u8 = false;
            int lineCount = 0;
            
            while ((line = reader.readLine()) != null && lineCount < 10) {
                lineCount++;
                if (line.contains("#EXTM3U") || line.contains("#EXTINF")) {
                    isValidM3u8 = true;
                    break;
                }
            }
            
            reader.close();
            if (conn != null) conn.disconnect();
            
            return isValidM3u8;

        } catch (Exception e) {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
            return false;
        }
    }

    /**
     * 批量检查频道健康状态
     * @param channels 频道列表
     * @param listener 进度回调
     */
    public static void batchCheckHealth(java.util.List<Channel> channels, HealthCheckListener listener) {
        if (channels == null || channels.isEmpty()) {
            if (listener != null) listener.onComplete(0, 0);
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(
            Math.min(10, channels.size())  // 最多10个并发
        );

        final int totalChannels = channels.size();
        final int[] checkedCount = {0};
        final int[] healthyCount = {0};

        for (final Channel channel : channels) {
            executor.execute(() -> {
                try {
                    // 检查所有源，只要有一个可用就认为频道健康
                    boolean isHealthy = false;
                    for (String source : channel.sources) {
                        if (source.contains(".m3u8")) {
                            isHealthy = checkHlsStreamHealth(source);
                        } else {
                            isHealthy = checkUrlHealth(source);
                        }
                        if (isHealthy) break;  // 找到一个可用源就停止
                    }

                    channel.healthy = isHealthy;
                    if (isHealthy) healthyCount[0]++;

                    checkedCount[0]++;
                    if (listener != null) {
                        listener.onProgress(channel, checkedCount[0], totalChannels, isHealthy);
                    }

                } catch (Exception e) {
                    // 单个频道检查失败不影响整体流程
                    checkedCount[0]++;
                    if (listener != null) {
                        listener.onError(channel, e.getMessage());
                    }
                }
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(60, TimeUnit.SECONDS);  // 最多等待60秒
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (listener != null) {
            listener.onComplete(totalChannels, healthyCount[0]);
        }
    }

    /**
     * 健康检查进度监听器
     */
    public interface HealthCheckListener {
        /**
         * 进度更新
         * @param channel 当前检查的频道
         * @param checked 已检查数量
         * @param total 总数量
         * @param isHealthy 当前频道是否健康
         */
        void onProgress(Channel channel, int checked, int total, boolean isHealthy);

        /**
         * 检查错误
         * @param channel 出错的频道
         * @param errorMsg 错误信息
         */
        void onError(Channel channel, String errorMsg);

        /**
         * 检查完成
         * @param total 总数量
         * @param healthy 健康数量
         */
        void onComplete(int total, int healthy);
    }

    /**
     * 快速检查单个频道的最佳源
     * @param channel 频道对象
     * @return 最佳源的索引，-1表示无可用源
     */
    public static int findBestSource(Channel channel) {
        if (channel == null || channel.sources.isEmpty()) return -1;

        long minResponseTime = Long.MAX_VALUE;
        int bestIndex = -1;

        for (int i = 0; i < channel.sources.size(); i++) {
            String source = channel.sources.get(i);
            long responseTime = getUrlResponseTime(source);
            
            if (responseTime >= 0 && responseTime < minResponseTime) {
                minResponseTime = responseTime;
                bestIndex = i;
            }
        }

        return bestIndex;
    }
}