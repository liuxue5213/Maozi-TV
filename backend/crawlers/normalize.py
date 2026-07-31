"""Channel name normalisation.

Different m3u sources name the same channel very differently
(e.g. "CCTV-1", "CCTV1", "CCTV-1 综合", "央视一套"). We collapse these to a
single canonical key so the source manager merges their stream URLs together.
"""

import re
import unicodedata
from typing import Dict

# 繁体→简体 转换表（仅覆盖频道名常用字，避免引入 OpenCC 重依赖）。
# 出现频次较高的港澳台频道用字。
_T2S_TABLE = str.maketrans({
    "視": "视", "綜": "综", "藝": "艺", "聞": "闻", "東": "东",
    "電": "电", "華": "华", "鳳": "凤", "衛": "卫", "臺": "台", "灣": "湾",
    "資": "资", "訊": "讯", "網": "网", "節": "节", "體": "体", "運": "运",
    "動": "动", "經": "经", "濟": "济", "財": "财", "緯": "纬", "紀": "纪",
    "錢": "钱", "綫": "线", "線": "线", "號": "号", "碼": "码", "門": "门",
    "無": "无", "國": "国", "學": "学", "樂": "乐", "電": "电", "環": "环",
    "球": "球", "紀": "纪", "錄": "录", "紅": "红", "點": "点", "動": "动",
    "藍": "蓝", "光": "光", "線": "线", "網": "网", "訊": "讯", "訊": "讯",
    "導": "导", "嶼": "屿", "區": "区", "員": "员", "務": "务", "館": "馆",
})


def _t2s(text: str) -> str:
    """繁体→简体（仅覆盖频道常用字）。保守实现：未覆盖的字原样保留。"""
    return text.translate(_T2S_TABLE)


# Alias map: maps a cleaned/normalised name to a canonical display name.
# Keys are matched case-insensitively after basic cleaning.
CHANNEL_ALIASES: Dict[str, str] = {
    # CCTV
    "cctv1": "CCTV-1",
    "cctv2": "CCTV-2",
    "cctv3": "CCTV-3",
    "cctv4": "CCTV-4",
    "cctv5": "CCTV-5",
    "cctv6": "CCTV-6",
    "cctv7": "CCTV-7",
    "cctv8": "CCTV-8",
    "cctv9": "CCTV-9",
    "cctv10": "CCTV-10",
    "cctv11": "CCTV-11",
    "cctv12": "CCTV-12",
    "cctv13": "CCTV-13",
    "cctv14": "CCTV-14",
    "cctv15": "CCTV-15",
    "cctv16": "CCTV-16",
    "cctv17": "CCTV-17",
    "央视一套": "CCTV-1",
    "央视二套": "CCTV-2",
    "央视三套": "CCTV-3",
    "央视四套": "CCTV-4",
    "央视五套": "CCTV-5",
    "央视新闻": "CCTV-13",
    "央视少儿": "CCTV-14",
    "中央电视台综合频道": "CCTV-1",
    "中央电视台财经频道": "CCTV-2",
    # Hunan
    "湖南卫视": "湖南卫视",
    "芒果tv": "湖南卫视",
    # Beijing
    "北京卫视": "北京卫视",
    "btv": "北京卫视",
    # ── 香港（TVB / ViuTV / 港台）──────────────────────────
    "翡翠台": "翡翠台",
    "tvbjade": "翡翠台",
    "tvbjade2": "翡翠台",
    "jade": "翡翠台",
    "明珠台": "明珠台",
    "tvbpearl": "明珠台",
    "pearl": "明珠台",
    "tvbnews": "无线新闻台",
    "无线新闻台": "无线新闻台",
    "tvbfinance": "无线财经资讯台",
    "无线财经资讯台": "无线财经资讯台",
    "viutv": "ViuTV",
    "viutvsix": "ViuTVsix",
    "港台电视31": "RTHK 31",
    "rthk31": "RTHK 31",
    "港台电视32": "RTHK 32",
    "rthk32": "RTHK 32",
    "凤凰香港": "凤凰香港台",
    "凤凰卫视香港台": "凤凰香港台",
    # ── 澳门 ────────────────────────────────────────────────
    "澳视澳门": "澳视澳门",
    "澳視澳門": "澳视澳门",
    "tdm": "澳视澳门",
    "澳視綜藝": "澳视综艺",
    "澳视综艺": "澳视综艺",
    "澳視葡文": "澳视葡文",
    "澳视葡文": "澳视葡文",
    # ── 台湾（三立/中视/台视/民视等常用台）─────────────────
    "中视": "中视",
    "中視": "中视",
    "台视": "台视",
    "台視": "台视",
    "民视": "民视",
    "民視": "民视",
    "三立": "三立",
    "东森新闻": "东森新闻",
    "東森新聞": "东森新闻",
}


def normalize_channel_name(name: str) -> str:
    """Return a canonical key for a channel name.

    The returned key is used ONLY for grouping/dedup; the original display
    name is preserved when creating the Channel row.
    """
    if not name:
        return ""
    # Full-width → half-width (e.g. ＣＣＴＶ１ → CCTV1, （ → (, ） → ))
    name = unicodedata.normalize("NFKC", name)
    # 去掉画质/地区前缀方括号: [HD] [BD] [SD] [IPv6] [Not 24/7] 等任意方括号内容
    # （卫视源常见 [IPv6]/[Not 24/7]/[Geo-blocked] 等变体后缀，统一清理以合并）
    name = re.sub(r"\[[^\]]*\]", "", name)
    # 去掉 geo-blocked / region 等英文标记（无方括号的情况）
    name = re.sub(r"\[?geo-?blocked\]?", "", name, flags=re.IGNORECASE)
    # 去掉 "Not 24/7" 等英文可用性标记
    name = re.sub(r"not\s*24/?7", "", name, flags=re.IGNORECASE)
    # 去掉泰文/越南语等东南亚频道前缀（epg.pw 港澳台源常见 ช่อง 前缀）
    name = re.sub(r"^[\u0E00-\u0E7F\s]+", "", name)  # 泰文范围
    # 去掉 "- 线路N（大陆线路）" / "- 蓝光N" / "- 超清N" 等: 同一台的多条线路即多个源, 应合并。
    # 先清理整段含括号注释的线路后缀, 再清理无括号的
    name = re.sub(r"[\s\-]*线[路][\d一二三四五六七八九十]*\s*[\(（].*?[\)）]", "", name)
    name = re.sub(r"[\s\-]*线[路][\d一二三四五六七八九十]*", "", name)
    # 去掉 "- 蓝光N" / "- 高清N" / "- 超清N" 后缀（港澳台源常用, 表示不同线路）
    name = re.sub(r"[\s\-]*(蓝光|超清|高清|流畅|原画)[\d一二三四五六七八九十]*", "", name)
    # Lowercase + trim
    cleaned = name.strip().lower()
    # Remove separators that vary between sources: spaces, - _ . ·
    cleaned = re.sub(r"[\s\-_\.·•・]+", "", cleaned)
    # Collapse parenthetical suffixes like "CCTV1(综合)" -> "cctv1"
    cleaned = re.sub(r"[\(（].*?[\)）]", "", cleaned)
    # Chinese synonyms for "HD" / "标清" — keep resolution out of the key so
    # "CCTV1高清" and "CCTV1HD" merge with "CCTV1".
    cleaned = re.sub(r"(高清|超清|hd|fhd|uhd|4k|8k|标清|sd)", "", cleaned)
    # 繁体 → 简体归一化（港澳台源常用繁体，国内用简体）
    cleaned = _t2s(cleaned)
    # Remove trailing "台"/"卫视" qualifier once for grouping is risky (would
    # merge 湖南卫视/湖南经视), so we leave those in.
    cleaned = cleaned.strip()

    # Apply alias table on the cleaned key (after繁简转换)
    if cleaned in CHANNEL_ALIASES:
        return CHANNEL_ALIASES[cleaned].lower()

    return cleaned


def display_name_for(name: str) -> str:
    """Pick a nice display name, preferring the canonical alias if known.

    Falls back to a lightly-cleaned version of the original (strips quality
    prefixes like [HD]/[BD], geo markers, and route suffixes) so the UI
    doesn't show noisy raw names.
    """
    if not name:
        return ""
    cleaned = name.strip()
    folded = normalize_channel_name(cleaned)
    for canonical in CHANNEL_ALIASES.values():
        if canonical.lower() == folded:
            return canonical
    # 轻量清理显示名：去任意方括号后缀、geo 标记、泰文前缀、线路/蓝光后缀、繁转简
    disp = unicodedata.normalize("NFKC", cleaned)
    disp = re.sub(r"\[[^\]]*\]", "", disp)
    disp = re.sub(r"\[?geo-?blocked\]?", "", disp, flags=re.IGNORECASE)
    disp = re.sub(r"not\s*24/?7", "", disp, flags=re.IGNORECASE)
    disp = re.sub(r"^[\u0E00-\u0E7F\s]+", "", disp)
    disp = re.sub(r"[\s\-]*线[路][\d一二三四五六七八九十]*\s*[\(（].*?[\)）]", "", disp)
    disp = re.sub(r"[\s\-]*线[路][\d一二三四五六七八九十]*", "", disp)
    disp = re.sub(r"[\s\-]*(蓝光|超清|高清|流畅|原画)[\d一二三四五六七八九十]*", "", disp)
    disp = _t2s(disp)
    disp = disp.strip(" -·•")
    return disp or cleaned
