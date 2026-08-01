"""频道国家分类：国内 / 港澳台 / 韩国 / 日本 / 国外。

用于导出和入库时国内优先排序，确保 APK/网页端的频道列表以国内频道为主。
"""

from typing import Literal

# 国内频道特征（名称或分组含这些关键字 → 国内）
DOMESTIC_NAME_KW = (
    # 央视
    "CCTV", "央视", "中央", "CCTV+", "CGTN",
    # 卫视（各省）
    "卫视", "北京", "上海", "天津", "重庆",
    "湖南", "浙江", "江苏", "广东", "山东", "河南", "四川", "安徽", "湖北",
    "福建", "东南", "深圳", "黑龙江", "辽宁", "吉林", "河北", "山西", "陕西",
    "江西", "云南", "贵州", "广西", "内蒙", "新疆", "西藏", "宁夏", "青海",
    "甘肃", "海南", "三沙", "厦门", "兵团", "农林",
    # 地方台
    "新闻", "体育", "少儿", "公共", "都市", "生活", "科教", "法制", "经济",
    "戏曲", "音乐", "影视", "农科", "党建", "教育",
    # 中文特征
    "频道", "台", "卫视",
)

DOMESTIC_GROUP_KW = (
    "央视", "卫视", "地方台", "未分类", "央视频道", "卫视频道",
    "地方频道", "港澳台", "国内",
)

# 港澳台特征
HKMT_NAME_KW = (
    "翡翠台", "明珠台", "TVB", "ViuTV", "RTHK", "港台电视",
    "凤凰", "澳视", "澳視", "TDM", "澳门",
    "东森", "中天", "三立", "民视", "台視", "中視", "華視",
    "寰宇", "台视", "香港", "台湾", "澳门",
)

# 韩国频道特征
# 注意：不用裸的 "KBS"/"MBC"/"SBS"（会误匹配 WSBS-DT1、KBShinya、MBC Brasil 等），
# 改用具体词 + 韩文字符检测（见 classify_channel 中的韩文判断）
KOREA_NAME_KW = (
    # 韩国有线台（具体词，可信）
    "tvN", "JTBC", "ENA", "MBN",
    "Channel A", "TV Chosun", "TV조선",
    "CGN", "CTS", "EBS",
    # KBS/MBC/SBS 的具体节目/海外版（避免裸短词误匹配）
    "KBS World", "KBS LiveCam", "KBS1", "KBS2", "KBS N Sports",
    "MBC Drama", "MBC America", "MBC Movie", "MBC Every1",
    "MBC 충북", "MBC 강원", "MBC Gangwon", "MBC Chungbuk", "MBC Daejeon",
    "SBS International", "SBS Korea",
    # 中文标识
    "韩国",
)
# 韩国 group 特征（iptv-org 的 kr.m3u 通常用 "Korea" 或 "한국"）
KOREA_GROUP_KW = (
    "Korea", "Korean", "한국", "韩国",
)

# 日本频道特征
# 注意："NHK" 是强特征（不会和其他词混淆），可以用裸词
JAPAN_NAME_KW = (
    # 日本五大台 + 卫星频道
    "NHK", "Fuji TV", "Fujisankei", "TV Tokyo", "TV Asahi",
    "Nippon TV", "Tokyo MX", "TVK", "SUN TV", "BS11",
    "WOWOW", "TV Osaka", "Mainichi", "Yomiuri",
    # 海外版
    "NHK World", "NHK World Japan",
    # 中文标识
    "日本",
)
# 日本 group 特征（iptv-org 的 jp.m3u 通常用 "Japan" 或 "日本"）
JAPAN_GROUP_KW = (
    "Japan", "Japanese", "日本", "日本語",
)

# 明确是国外的特征（命中则直接判国外）
FOREIGN_NAME_KW = (
    # 英文频道名特征
    "BBC", "CNN", "NBC", "ABC", "CBS", "FOX", "HBO", "MTV", "ESPN",
    "Discovery", "National Geographic", "History", "Animal Planet",
    "Cartoon Network", "Disney", "Nickelodeon", "Cinemax", "Showtime",
    "Bloomberg", "Al Jazeera", "Deutsche", "RT News",
    # 注意：CGTN 是央视国际，属于国内，不放这里（在 DOMESTIC_NAME_KW）
    # 注意：News/Music/Sports 等泛化词不放进 FOREIGN，避免误判港澳台频道
)

# 明确国外的分组（只用强特征词，避免 News/Sports 等泛化词误杀港澳台）
FOREIGN_GROUP_KW = (
    "Animation", "Documentary", "Lifestyle", "Cooking", "Travel",
    "Science", "Religious", "Undefined",
)


def classify_channel(name: str, group: str = "") -> Literal["cn", "hkmt", "kr", "jp", "foreign"]:
    """判断频道属于 国内(cn) / 港澳台(hkmt) / 韩国(kr) / 日本(jp) / 国外(foreign)。

    优先级：韩国/日本 group > 其他国外特征(BBC/CNN/FOX) > 韩国/日本 name > 港澳台 > 国内。
    （其他国外特征优先于韩国/日本 name，避免 "FOX 23 (KBSI-DT1)" 这类
     美国呼号被 KBS 误判为韩国；但 group 是 Korea/Japan 时优先级最高）
    """
    n = (name or "").strip()
    g = (group or "").strip()
    n_low = n.lower()
    g_low = g.lower()

    # 1. 韩国/日本 group 特征（最高优先级，源明确标记的国家分组最可靠）
    for kw in KOREA_GROUP_KW:
        if kw.lower() in g_low:
            return "kr"
    for kw in JAPAN_GROUP_KW:
        if kw.lower() in g_low:
            return "jp"

    # 2. 明确其他国外特征（BBC/CNN/FOX 等，优先于韩国/日本 name 判断，
    #    避免 "FOX 23 (KBSI-DT1)" 这类美国呼号被 KBS 误判为韩国）
    for kw in FOREIGN_NAME_KW:
        if kw.lower() in n_low:
            return "foreign"
    for kw in FOREIGN_GROUP_KW:
        if kw.lower() in g_low:
            return "foreign"

    # 3. 韩国/日本 name 特征（频道名含电视台标识）
    for kw in KOREA_NAME_KW:
        if kw.lower() in n_low:
            return "kr"
    for kw in JAPAN_NAME_KW:
        if kw.lower() in n_low:
            return "jp"

    # 3.5 韩文字符检测：频道名含韩文(가-힣) → 韩国
    # （补足 KBS/MBC/SBS 短词移除后的识别能力，如 "MBC 충북" 靠韩文识别）
    if any('\uAC00' <= ch <= '\uD7A3' for ch in n):
        return "kr"

    # 4. 港澳台特征
    for kw in HKMT_NAME_KW:
        if kw in n or kw in g:
            return "hkmt"

    # 5. 国内特征——频道名优先
    for kw in DOMESTIC_NAME_KW:
        if kw in n:
            return "cn"

    # 6. 含足够多中文字符 → 国内（要求至少2个中文字符，避免"(勿)(址)Movie"等误判）
    cjk_count = sum(1 for ch in n if '一' <= ch <= '鿿')
    if cjk_count >= 2:
        return "cn"

    # 7. 频道名是纯英文/无国内特征 → 即使 group 含中文也判国外
    #    （修复：cs3306 把国外频道归到"未分类"等中文分组，导致误判国内）
    #    只有 group 明确是强国内分组（央视/卫视/地方台）才信 group
    for kw in ("央视", "央视频道", "卫视", "卫视频道", "地方台", "地方频道"):
        if kw in g:
            # 但仍要求频道名有国内特征或中文，否则 group 可能是误归
            has_cn_name = any(k in n for k in DOMESTIC_NAME_KW) or cjk_count >= 1
            if has_cn_name:
                return "cn"
            # 纯英文名归到中文分组 → 国外频道误归，判 foreign
            return "foreign"

    # 8. group 含其他中文（如"未分类"）但频道名纯英文 → 国外
    if cjk_count == 0 and not any(k in n for k in DOMESTIC_NAME_KW):
        return "foreign"

    # 9. 默认国外（纯英文且无国内特征的）
    return "foreign"


def sort_key_domestic_first(name: str, group: str = "") -> tuple:
    """排序 key：国内(0) < 港澳台(1) < 韩国(2) < 日本(3) < 国外(4)，同类按名称排序。"""
    region = classify_channel(name, group)
    order = {"cn": 0, "hkmt": 1, "kr": 2, "jp": 3, "foreign": 4}
    return (order.get(region, 4), name)
