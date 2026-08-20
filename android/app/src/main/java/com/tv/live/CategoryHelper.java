package com.tv.live;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 将原始 m3u 分组映射为 TV 直播 App 常见的智能分类（央视/卫视/地方/港澳台等）。
 */
public final class CategoryHelper {

    public static final String ALL = "all";
    public static final String FAV = "fav";
    public static final String HISTORY = "history";
    public static final String LOCAL = "local";
    public static final String HKTW = "hktw";

    public static class Category {
        public final String id;
        public final String name;
        public final String icon;

        public Category(String id, String name, String icon) {
            this.id = id;
            this.name = name;
            this.icon = icon;
        }
    }

    private static final Category[] SMART_CATEGORIES = {
            new Category("cctv", "央视", "📡"),
            new Category("satellite", "卫视", "📺"),
            new Category("local", "地方", "🏙"),
            new Category("hktw", "港澳台", "🌏"),
            new Category("sports", "体育", "⚽"),
            new Category("movie", "影视", "🎬"),
            new Category("news", "新闻", "📰"),
            new Category("kids", "少儿", "🧸"),
            new Category("intl", "国外", "🌍"),
    };

    private CategoryHelper() {}

    public static List<Category> getNavCategories() {
        List<Category> list = new ArrayList<>();
        list.add(new Category(ALL, "全部", "📋"));
        list.add(new Category(FAV, "收藏", "⭐"));
        list.add(new Category(HISTORY, "历史", "🕘"));
        for (Category c : SMART_CATEGORIES) {
            list.add(c);
        }
        return list;
    }

    public static String smartCategoryId(ChannelOptimized ch) {
        String name = safe(ch.name);
        String group = safe(ch.group);
        String combined = name + " " + group;

        if (containsAny(combined, "CCTV", "央视", "CGTN", "中央")) return "cctv";
        if (containsAny(combined, "卫视") && !containsAny(combined, "凤凰")) return "satellite";
        if (containsAny(combined,
                "香港", "澳门", "台湾", "港台", "港澳", "TVB", "翡翠", "明珠", "凤凰",
                "无线", "ViuTV", "RTHK", "东森", "中天", "三立", "民视", "中视", "台视", "澳视")) {
            return "hktw";
        }
        if (containsAny(combined, "体育", "足球", "Sport", "Sports", "NBA", "ESPN")) return "sports";
        if (containsAny(combined, "电影", "影视", "电视剧", "Movie", "Movies", "Cinema", "剧场")) return "movie";
        if (containsAny(combined, "新闻", "News", "资讯")) return "news";
        if (containsAny(combined, "少儿", "儿童", "Kids", "Cartoon", "动画")) return "kids";

        if (isInternational(group, name)) return "intl";

        if (containsAny(group, "地方", "省内", "市级") || isLocalProvince(combined)) return "local";
        if (containsAny(combined, "频道") && containsCjk(group)) return "local";

        return "local";
    }

    public static Map<String, List<ChannelOptimized>> buildSmartBuckets(List<ChannelOptimized> channels) {
        Map<String, List<ChannelOptimized>> buckets = new LinkedHashMap<>();
        for (Category c : SMART_CATEGORIES) {
            buckets.put(c.id, new ArrayList<>());
        }
        for (ChannelOptimized ch : channels) {
            String id = smartCategoryId(ch);
            List<ChannelOptimized> bucket = buckets.get(id);
            if (bucket != null) bucket.add(ch);
        }
        return buckets;
    }

    public static List<ChannelOptimized> filter(List<ChannelOptimized> all, String categoryId, boolean favoritesOnly) {
        return filter(all, categoryId, favoritesOnly, null);
    }

    /**
     * 带缓存 buckets 的过滤方法，避免重复计算分类。
     * @param cachedBuckets 预计算的分类桶，为 null 时回退到实时计算
     */
    public static List<ChannelOptimized> filter(List<ChannelOptimized> all, String categoryId, boolean favoritesOnly,
                                                 Map<String, List<ChannelOptimized>> cachedBuckets) {
        List<ChannelOptimized> result = new ArrayList<>();
        if (FAV.equals(categoryId) || favoritesOnly) {
            for (ChannelOptimized ch : all) {
                if (ch.isFavorite) result.add(ch);
            }
            return result;
        }
        if (ALL.equals(categoryId)) return new ArrayList<>(all);
        // 历史分类：由 MainActivity 在 refreshChannelGrid 中根据 playHistory 重排序
        if (HISTORY.equals(categoryId)) return new ArrayList<>(all);

        // 优先使用缓存的 buckets，避免每次点击都遍历全部频道做字符串匹配
        Map<String, List<ChannelOptimized>> buckets = cachedBuckets != null ? cachedBuckets : buildSmartBuckets(all);
        List<ChannelOptimized> bucket = buckets.get(categoryId);
        return bucket != null ? new ArrayList<>(bucket) : result;
    }

    public static List<ChannelOptimized> search(List<ChannelOptimized> channels, String query) {
        if (query == null || query.trim().isEmpty()) return channels;
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<ChannelOptimized> result = new ArrayList<>();
        
        for (ChannelOptimized ch : channels) {
            // 原有匹配：频道名/分组包含查询字符串
            boolean nameMatch = safe(ch.name).toLowerCase(Locale.ROOT).contains(q);
            boolean groupMatch = safe(ch.group).toLowerCase(Locale.ROOT).contains(q);
            
            // 拼音匹配：使用 PinyinMatcher
            boolean pinyinMatch = PinyinMatcher.matches(ch.name, q);
            
            if (nameMatch || groupMatch || pinyinMatch) {
                result.add(ch);
            }
        }
        return result;
    }

    private static boolean isInternational(String group, String name) {
        if (containsCjk(group) || containsCjk(name)) {
            if (containsAny(group + name, "CCTV", "央视", "卫视")) return false;
            if (containsAny(group + name, "香港", "澳门", "台湾", "TVB")) return false;
        }
        if (!containsCjk(group) && group.matches("(?i)[a-z].*")) return true;
        String[] intlGroups = {
                "General", "News", "Movies", "Sports", "Kids", "Music", "Documentary",
                "Entertainment", "USA", "UK", "Europe", "Asia", "Latin"
        };
        for (String g : intlGroups) {
            if (group.equalsIgnoreCase(g)) return true;
        }
        return false;
    }

    private static final String[] PROVINCES = {
            "北京", "上海", "天津", "重庆", "河北", "山西", "辽宁", "吉林", "黑龙江",
            "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南", "湖北", "湖南",
            "广东", "海南", "四川", "贵州", "云南", "陕西", "甘肃", "青海", "内蒙古",
            "广西", "西藏", "宁夏", "新疆", "深圳", "大连", "青岛", "厦门", "宁波"
    };

    private static boolean isLocalProvince(String text) {
        return extractProvince(text) != null;
    }

    /**
     * 从频道名称/分组中提取省份（用于二级分组）。
     * 返回 null 表示无法识别省份。
     */
    public static String extractProvince(String text) {
        if (text == null) return null;
        for (String p : PROVINCES) {
            if (text.contains(p)) return p;
        }
        return null;
    }

    /**
     * 获取频道的二级分组 key（省份维度，仅对地方分类有意义）。
     * 返回 null 表示无二级分组。
     */
    public static String subGroupId(ChannelOptimized ch) {
        String combined = safe(ch.name) + " " + safe(ch.group);
        String province = extractProvince(combined);
        if (province != null) return "province_" + province;
        // 港澳台二级
        if (containsAny(combined, "香港", "TVB", "翡翠")) return "region_hk";
        if (containsAny(combined, "澳门", "澳视")) return "region_mo";
        if (containsAny(combined, "台湾", "东森", "中天", "三立", "民视", "中视", "台视")) return "region_tw";
        return null;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    /**
     * 检测是否包含中文字符。
     * 不用 Character.UnicodeScript（在 coreLibraryDesugaring 开启后，
     * Android 6.0 上会 ClassNotFoundException 崩溃），
     * 改用简单的 CJK 统一表意文字范围判断。
     */
    private static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // CJK Unified Ideographs 基本区 + 扩展A区
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }
}
