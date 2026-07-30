package com.tv.live;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道数据模型
 */
public class Channel {

    public int id;
    public String name;
    public String group;
    public String logo;
    public String url;            // 主源
    public List<String> sources;  // 所有源（含主源）
    public boolean healthy;

    // 运行时状态（不序列化）
    public int currentSourceIndex = 0;  // 当前播放的源索引
    public boolean isFavorite = false;  // 是否收藏

    public Channel() {
        this.sources = new ArrayList<>();
    }

    /**
     * 从 JSON 对象解析频道
     */
    public static Channel fromJson(JSONObject obj) {
        Channel ch = new Channel();
        ch.id = obj.optInt("id", 0);
        ch.name = obj.optString("name", "");
        ch.group = obj.optString("group", "其他");
        ch.logo = obj.optString("logo", "");
        ch.url = obj.optString("url", "");
        ch.healthy = obj.optBoolean("healthy", true);

        // 解析 sources 数组
        JSONArray srcArr = obj.optJSONArray("sources");
        if (srcArr != null && srcArr.length() > 0) {
            for (int i = 0; i < srcArr.length(); i++) {
                String src = srcArr.optString(i, "");
                if (!src.isEmpty()) {
                    ch.sources.add(src);
                }
            }
        }

        // 如果 sources 为空，用 url 作为唯一源
        if (ch.sources.isEmpty() && !ch.url.isEmpty()) {
            ch.sources.add(ch.url);
        }

        // 如果 url 为空但 sources 不为空，用第一个 source 作为 url
        if (ch.url.isEmpty() && !ch.sources.isEmpty()) {
            ch.url = ch.sources.get(0);
        }

        return ch;
    }

    /**
     * 获取当前播放源 URL
     */
    public String getCurrentSourceUrl() {
        if (sources.isEmpty()) return "";
        if (currentSourceIndex >= sources.size()) currentSourceIndex = 0;
        return sources.get(currentSourceIndex);
    }

    /**
     * 切换到下一个源，返回 true 表示还有下一个源，false 表示已经循环完
     */
    public boolean switchToNextSource() {
        if (sources.size() <= 1) return false;
        currentSourceIndex = (currentSourceIndex + 1) % sources.size();
        return currentSourceIndex != 0; // 如果回到第一个，说明循环完了
    }

    /**
     * 获取频道号（用于频道号跳转，从1开始）
     */
    public int channelNumber = 0;

    @Override
    public String toString() {
        return name + " [" + group + "]";
    }
}
