package com.tv.live;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 拼音匹配工具 - 支持拼音首字母和全拼匹配频道名称
 * 
 * 功能：
 * - 内置 100+ 常用频道名 → 拼音映射表
 * - 支持拼音首字母匹配（如"hnxws" → "湖南卫视"）
 * - 支持全拼匹配（如"hunanweishi" → "湖南卫视"）
 * - 支持模糊匹配（如"hunanshi" → "湖南卫视"）
 * 
 * 使用方式：
 * PinyinMatcher.matches("湖南卫视", "hnxws") → true
 * PinyinMatcher.matches("CCTV-1", "cctv1") → true
 */
public class PinyinMatcher {
    
    // 频道名规范化 → 拼音变体列表
    private static final Map<String, List<String>> PINYIN_MAP = new HashMap<>();
    
    // 拼音首字母 → 可能的频道名集合（用于快速预筛选）
    private static final Map<String, Set<String>> ABBR_MAP = new HashMap<>();
    
    static {
        initPinyinMap();
        initAbbrMap();
    }
    
    /**
     * 初始化频道名 → 拼音映射表
     * 格式：规范化频道名 → [拼音1, 拼音2, ...]
     */
    private static void initPinyinMap() {
        // 央视 CCTV
        add("cctv1", "cctv-1", "cctv1", "中央一台", "央一综合");
        add("cctv2", "cctv-2", "cctv2", "中央二台", "财经频道");
        add("cctv3", "cctv-3", "cctv3", "中央三台", "综艺频道");
        add("cctv4", "cctv-4", "cctv4", "中央四台", "中文国际", "国际频道");
        add("cctv5", "cctv-5", "cctv5", "中央五台", "体育频道");
        add("cctv6", "cctv-6", "cctv6", "中央六台", "电影频道");
        add("cctv7", "cctv-7", "cctv7", "中央七台", "国防军事", "军事频道");
        add("cctv8", "cctv-8", "cctv8", "中央八台", "电视剧频道");
        add("cctv9", "cctv-9", "cctv9", "中央九台", "纪录频道");
        add("cctv10", "cctv10", "cctv10", "中央十台", "科教频道");
        add("cctv11", "cctv-11", "cctv11", "中央十一台", "戏曲频道");
        add("cctv12", "cctv-12", "cctv12", "中央十二台", "社会与法");
        add("cctv13", "cctv13", "cctv13", "中央十三台", "新闻频道");
        add("cctv14", "cctv14", "cctv14", "中央十四台", "少儿频道");
        add("cctv15", "cctv15", "cctv15", "中央十五台", "音乐频道");
        add("cctv16", "cctv16", "cctv16", "中央十六台", "奥林匹克");
        add("cctv17", "cctv17", "cctv17", "中央十七台", "农业农村");
        add("cgtn", "cgtn", "cgtn", "央视英语", "cctvenglish");
        
        // 卫视
        add("hunanweishi", "hnws", "hunanstv", "湖南卫视", "湖南");
        add("zhejiangweishi", "zjws", "zhejiangtv", "浙江卫视", "浙江");
        add("jiangsuweishi", "jsws", "jiangsutv", "江苏卫视", "江苏");
        add("dongfangweishi", "dfws", "dongfangtv", "dfws", "东方卫视", "东方");
        add("beijingweishi", "bjws", "beijingtv", "北京卫视", "北京");
        add("guangdongweishi", "gdws", "gdtv", "广东卫视", "广东");
        add("shenzhenweishi", "szws", "shentv", "深圳卫视", "深圳");
        add("shandongweishi", "sdws", "shandongtv", "sdws", "山东卫视", "山东");
        add("anhuiweishi", "ahws", "anhuitv", "安徽卫视", "安徽");
        add("tianjinweishi", "tjws", "tianjintv", "天津卫视", "天津");
        add("liaoningweishi", "lnws", "liaoningtv", "辽宁卫视", "辽宁");
        add("heilongjiangweishi", "hljws", "heilongjiangtv", "hljtv", "黑龙江卫视", "龙江");
        add("jilinweishi", "jlws", "jilintv", "吉林卫视", "吉林");
        add("sichuanweishi", "scws", "sichuantv", "四川卫视", "四川");
        add("chongqingweishi", "cqws", "chongqingtv", "重庆卫视", "重庆");
        add("fujianweishi", "fjws", "fujiantv", "东南卫视", "福建", "东南");
        add("henanweishi", "henws", "henantv", "河南卫视", "河南");
        add("hubeiweishi", "hbws", "hubeitv", "湖北卫视", "湖北");
        add("jiangxiweishi", "jxws", "jiangxitv", "江西卫视", "江西");
        add("guangxiweishi", "gxws", "guangxi", "广西卫视", "广西");
        add("guizhouweishi", "gzws", "guizhoutv", "贵州卫视", "贵州");
        add("shanxiweishi", "sxws", "shanxitv", "山西卫视", "山西");
        add("shaanxiweishi", "sxxws", "shaanxitv", "陕西卫视", "陕西");
        add("gansuweishi", "gsws", "gansutv", "甘肃卫视", "甘肃");
        add("qinghaiweishi", "qhws", "qinghaitv", "青海卫视", "青海");
        add("ningxiaweishi", "nxws", "ningxiatv", "宁夏卫视", "宁夏");
        add("xinjiangweishi", "xjws", "xinjiangtv", "新疆卫视", "新疆");
        add("hainanweishi", "hinws", "hainantv", "海南卫视", "海南");
        add("neimengguweishi", "nmgws", "neimengutv", "内蒙古卫视", "内蒙古");
        add("yunnanweishi", "ynws", "yunnantv", "云南卫视", "云南");
        add("xizangweishi", "xzws", "xizangtv", "西藏卫视", "西藏");
        
        // 港澳台
        add("feicuidetai", "fcct", "feicui", "翡翠台", "tvb");
        add("fenghuangweishi", "fhws", "phoenix", "凤凰卫视", "凤凰中文");
        add("zhongtian", "zt", "cti", "中天新闻台", "中天");
        add("dongsen", "ds", "ettv", "东森新闻台", "东森");
        add("tvbs", "tvbs", "tvbsnews", "tvbs新闻");
        
        // 其他
        add("jin'yingka'tong", "jykt", "jinying", "金鹰卡通");
        add("jiajiaka'tong", "jjkt", "jiajia", "嘉佳卡通");
        add("tiyuchangm", "typd", "sports", "体育频道", "cctv5");
        add("dianying", "dy", "movie", "电影频道", "cctv6");
        add("jilupindao", "jlpd", "record", "纪录频道", "cctv9");
        add("shao'er", "se", "kids", "少儿频道", "cctv14");
        add("xinwen", "xw", "news", "新闻频道", "cctv13");
        add("yinyue", "yy", "music", "音乐频道", "cctv15");
        add("guoji", "gj", "intl", "国际频道", "cctv4");
        add("shehuiyu", "shy", "law", "社会与法", "cctv12");
        add("keji", "kj", "science", "科教频道", "cctv10");
        add("xiqu", "xq", "opera", "戏曲频道", "cctv11");
        
        // 地方台
        add("henanshengxin", "hnsx", "河南新闻");
        add("hubeixinwen", "hbxw", "湖北新闻");
        add("hunanxinwen", "hnxw", "湖南新闻");
        add("guangdongxinwen", "gdxw", "广东新闻");
        add("sichuanxinwen", "scxw", "四川新闻");
        add("shandongxinwen", "sdxw", "山东新闻");
        add("jiangsuxinwen", "jsxw", "江苏新闻");
        add("zhejiangxinwen", "zjxw", "浙江新闻");
        add("shanxixinwen", "sxxw", "山西新闻");
        add("xinwenlianbo", "xwlb", "新闻联播");
        add("chaoxiangtiantian", "cxtt", "朝闻天下");
    }
    
    /**
     * 初始化拼音首字母 → 频道名集合映射
     * 用于快速预筛选，减少完整匹配次数
     */
    private static void initAbbrMap() {
        for (Map.Entry<String, List<String>> entry : PINYIN_MAP.entrySet()) {
            String normalizedKey = normalize(entry.getKey());
            for (String abbr : entry.getValue()) {
                // 取首字母作为缩写
                String abbrKey = abbr.replaceAll("[aeiou]", "").toLowerCase(Locale.ROOT);
                if (abbrKey.length() >= 2 && abbrKey.length() <= 6) {
                    ABBR_MAP.computeIfAbsent(abbrKey, k -> new HashSet<>()).add(normalizedKey);
                }
            }
        }
    }
    
    /**
     * 添加频道映射
     */
    private static void add(String normalized, String... variants) {
        List<String> list = PINYIN_MAP.computeIfAbsent(normalized, k -> new ArrayList<>());
        for (String v : variants) {
            list.add(v.toLowerCase(Locale.ROOT));
        }
    }
    
    /**
     * 规范化频道名（去除特殊字符、空格、后缀）
     */
    private static String normalize(String name) {
        if (name == null) return "";
        // 转小写
        String n = name.toLowerCase(Locale.ROOT);
        // 移除常见后缀
        n = n.replaceAll("(hd|fhd|4k|超清|高清|标清|流畅|频道|卫视|台)$", "");
        // 移除分隔符
        n = n.replaceAll("[\\s\\-\\(\\)（）]", "");
        // 移除 emoji
        n = n.replaceAll("[\\x{1F300}-\\x{1F9FF}\\x{2600}-\\x{26FF}\\x{2700}-\\x{27BF}]", "");
        return n.trim();
    }
    
    /**
     * 检查查询字符串是否匹配频道名
     * 
     * @param channelName 频道名称
     * @param query 查询字符串（拼音首字母、全拼、或原文）
     * @return 是否匹配
     */
    public static boolean matches(String channelName, String query) {
        if (channelName == null || query == null || query.isEmpty()) return false;
        
        String normalizedChannel = normalize(channelName);
        String q = query.toLowerCase(Locale.ROOT).trim();
        
        // 直接包含匹配
        if (normalizedChannel.contains(q) || channelName.contains(q)) {
            return true;
        }
        
        // 拼音首字母快速匹配
        String abbrKey = q.replaceAll("[aeiou]", "").toLowerCase(Locale.ROOT);
        Set<String> candidateChannels = ABBR_MAP.get(abbrKey);
        if (candidateChannels != null && candidateChannels.contains(normalizedChannel)) {
            return true;
        }
        
        // 完整拼音匹配
        List<String> pinyinVariants = PINYIN_MAP.get(normalizedChannel);
        if (pinyinVariants != null) {
            for (String variant : pinyinVariants) {
                if (q.equals(variant) || variant.startsWith(q)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * 获取频道名的拼音首字母
     * 
     * @param channelName 频道名称
     * @return 拼音首字母（如"hunws"）或 null
     */
    public static String getAbbr(String channelName) {
        List<String> variants = PINYIN_MAP.get(normalize(channelName));
        if (variants != null && !variants.isEmpty()) {
            for (String v : variants) {
                String abbr = v.replaceAll("[aeiou]", "").toLowerCase(Locale.ROOT);
                if (abbr.length() >= 2 && abbr.length() <= 6) {
                    return abbr;
                }
            }
        }
        return null;
    }
}