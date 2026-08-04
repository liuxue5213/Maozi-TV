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

    public static String smartCategoryId(Channel ch) {
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

    public static Map<String, List<Channel>> buildSmartBuckets(List<Channel> channels) {
        Map<String, List<Channel>> buckets = new LinkedHashMap<>();
        for (Category c : SMART_CATEGORIES) {
            buckets.put(c.id, new ArrayList<>());
        }
        for (Channel ch : channels) {
            String id = smartCategoryId(ch);
            List<Channel> bucket = buckets.get(id);
            if (bucket != null) bucket.add(ch);
        }
        return buckets;
    }

    public static List<Channel> filter(List<Channel> all, String categoryId, boolean favoritesOnly) {
        List<Channel> result = new ArrayList<>();
        if (FAV.equals(categoryId) || favoritesOnly) {
            for (Channel ch : all) {
                if (ch.isFavorite) result.add(ch);
            }
            return result;
        }
        if (ALL.equals(categoryId)) return new ArrayList<>(all);
        // 历史分类：由 MainActivity 在 refreshChannelGrid 中根据 playHistory 重排序
        if (HISTORY.equals(categoryId)) return new ArrayList<>(all);

        Map<String, List<Channel>> buckets = buildSmartBuckets(all);
        List<Channel> bucket = buckets.get(categoryId);
        return bucket != null ? new ArrayList<>(bucket) : result;
    }

    public static List<Channel> search(List<Channel> channels, String query) {
        if (query == null || query.trim().isEmpty()) return channels;
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Channel> result = new ArrayList<>();
        for (Channel ch : channels) {
            if (safe(ch.name).toLowerCase(Locale.ROOT).contains(q)
                    || safe(ch.group).toLowerCase(Locale.ROOT).contains(q)) {
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
        for (String p : PROVINCES) {
            if (text.contains(p)) return true;
        }
        return false;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }
}
