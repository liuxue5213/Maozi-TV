package com.tv.live;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 优化的频道数据模型
 * 主要改进：
 * 1. 源优先级智能排序
 * 2. 健康状态检查
 * 3. 源质量评分
 */
public class ChannelOptimized {

    public int id;
    public String name;
    public String group;
    public String logo;
    public String url;
    public List<String> sources;
    public boolean healthy;
    public String region;
    public String epg;  // EPG identifier for program guide

    // 运行时状态
    public int currentSourceIndex = 0;
    public boolean isFavorite = false;
    public int channelNumber = 0;

    // 源质量评分（内部使用）
    private List<SourceQuality> sourceQualities = new ArrayList<>();

    private static final class SourceQuality {
        String url;
        int score;  // 0-100，越高越好

        SourceQuality(String url, int score) {
            this.url = url;
            this.score = score;
        }
    }

    public ChannelOptimized() {
        this.sources = new ArrayList<>();
    }

    /**
     * 从 JSON 解析并自动优化源顺序
     */
    public static ChannelOptimized fromJson(JSONObject obj) {
        ChannelOptimized ch = new ChannelOptimized();
        ch.id = obj.optInt("id", 0);
        ch.name = obj.optString("name", "");
        ch.group = obj.optString("group", "其他");
        ch.logo = obj.optString("logo", "");
        ch.url = obj.optString("url", "");
        ch.healthy = obj.optBoolean("healthy", true);
        ch.region = obj.optString("region", "domestic");
        ch.epg = obj.optString("epg", "");

        // 解析源列表
        JSONArray srcArr = obj.optJSONArray("sources");
        if (srcArr != null && srcArr.length() > 0) {
            for (int i = 0; i < srcArr.length(); i++) {
                String src = srcArr.optString(i, "");
                if (!src.isEmpty()) {
                    ch.sources.add(src);
                }
            }
        }

        // 确保 url 和 sources 一致
        if (ch.sources.isEmpty() && !ch.url.isEmpty()) {
            ch.sources.add(ch.url);
        }
        if (ch.url.isEmpty() && !ch.sources.isEmpty()) {
            ch.url = ch.sources.get(0);
        }

        // 🔥 核心：智能排序源
        ch.optimizeSourceOrder();

        return ch;
    }

    /**
     * 智能源排序：根据 URL 特征评估质量并排序
     * 评分规则：
     * - HTTPS 优先 +20分
     * - 知名CDN/运营商 +30分
     * - .m3u8 直播流 +15分
     * - 无端口号 +10分
     * - 短URL +10分
     * - 非IP地址 +15分
     */
    private void optimizeSourceOrder() {
        if (sources.isEmpty()) return;

        sourceQualities.clear();
        for (String src : sources) {
            int score = calculateSourceScore(src);
            sourceQualities.add(new SourceQuality(src, score));
        }

        // 按评分降序排序
        Collections.sort(sourceQualities, (a, b) -> b.score - a.score);

        // 重新构建 sources 列表
        sources.clear();
        for (SourceQuality sq : sourceQualities) {
            sources.add(sq.url);
        }

        // 更新主URL为最高质量的源
        if (!sources.isEmpty()) {
            url = sources.get(0);
        }
    }

    /**
     * 计算单个源的评分
     */
    private int calculateSourceScore(String url) {
        int score = 0;

        try {
            // 协议检查
            if (url.startsWith("https://")) {
                score += 20;
            } else if (url.startsWith("http://")) {
                score += 10;
            }

            // CDN/运营商域名加分
            if (url.contains(".alicdn.com") || url.contains(".aliyun.com") ||
                url.contains(".tencentcdn.com") || url.contains(".qcdn.com") ||
                url.contains(".hwcdn.com") || url.contains(".cdn163.com")) {
                score += 30;
            }

            // 直播流格式
            if (url.contains(".m3u8")) {
                score += 15;
            } else if (url.contains(".flv")) {
                score += 10;
            }

            // 无端口号（标准服务）
            if (!url.contains(":8080") && !url.contains(":80") && !url.contains(":443")) {
                // 检查是否有非标准端口
                if (!Pattern.compile(":(\\d+)").matcher(url).find()) {
                    score += 10;
                }
            }

            // URL长度（短URL通常更稳定）
            if (url.length() < 100) {
                score += 10;
            } else if (url.length() < 150) {
                score += 5;
            }

            // 非IP地址（域名通常更稳定）
            if (!Pattern.compile("^https?://\\d+\\.\\d+\\.\\d+\\.\\d+").matcher(url).find()) {
                score += 15;
            }

            // 常见可靠域名后缀
            if (url.contains(".cn") || url.contains(".com") || url.contains(".net")) {
                score += 5;
            }

            // 减分项：明显的临时/测试源
            if (url.contains("test") || url.contains("temp") || url.contains("localhost")) {
                score -= 20;
            }

        } catch (Exception e) {
            // 解析异常给最低分
            score = 0;
        }

        return Math.max(0, score);  // 确保非负
    }

    /**
     * 获取当前播放源
     */
    public String getCurrentSourceUrl() {
        if (sources.isEmpty()) return "";
        if (currentSourceIndex >= sources.size()) currentSourceIndex = 0;
        return sources.get(currentSourceIndex);
    }

    /**
     * 切换到下一个源
     */
    public boolean switchToNextSource() {
        if (sources.size() <= 1) return false;
        currentSourceIndex = (currentSourceIndex + 1) % sources.size();
        return currentSourceIndex != 0;
    }

    /**
     * 获取当前源的质量评分
     */
    public int getCurrentSourceScore() {
        if (currentSourceIndex >= sourceQualities.size()) return 0;
        return sourceQualities.get(currentSourceIndex).score;
    }

    /**
     * 重置到最高质量源
     */
    public void resetToBestSource() {
        currentSourceIndex = 0;
    }

    @Override
    public String toString() {
        return name + " [" + group + "] (源数量: " + sources.size() + ", 当前评分: " + getCurrentSourceScore() + ")";
    }
}