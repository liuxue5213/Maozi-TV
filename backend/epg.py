"""EPG (Electronic Program Guide) data fetcher and cache.

Fetches TV program schedules from epg.pw's XMLTV API and serves them
to Android / Web clients via the /api/epg endpoint.

Features:
- Built-in channel-name to EPG ID mapping for ~100 major Chinese channels
- In-memory cache with 30-minute TTL to avoid hammering upstream
- Graceful degradation: returns placeholder when no EPG data available
- Thread-safe: uses threading.Lock for cache access
"""

import logging
import threading
import time
from typing import Dict, List, Optional
from xml.etree import ElementTree

import requests

logger = logging.getLogger(__name__)

# Channel name to EPG ID mapping
CHANNEL_EPG_MAP: Dict[str, str] = {
    "cctv-1": "CCTV-1", "cctv1": "CCTV-1", "cctv-2": "CCTV-2", "cctv2": "CCTV-2",
    "cctv-3": "CCTV-3", "cctv3": "CCTV-3", "cctv-4": "CCTV-4", "cctv4": "CCTV-4",
    "cctv-5": "CCTV-5", "cctv5": "CCTV-5", "cctv-6": "CCTV-6", "cctv6": "CCTV-6",
    "cctv-7": "CCTV-7", "cctv7": "CCTV-7", "cctv-8": "CCTV-8", "cctv8": "CCTV-8",
    "cctv-9": "CCTV-9", "cctv9": "CCTV-9", "cctv-10": "CCTV-10", "cctv10": "CCTV-10",
    "cctv-11": "CCTV-11", "cctv11": "CCTV-11", "cctv-12": "CCTV-12", "cctv12": "CCTV-12",
    "cctv-13": "CCTV-13", "cctv13": "CCTV-13", "cctv-14": "CCTV-14", "cctv14": "CCTV-14",
    "cctv-15": "CCTV-15", "cctv15": "CCTV-15", "cctv-16": "CCTV-16", "cctv16": "CCTV-16",
    "cctv-17": "CCTV-17", "cctv17": "CCTV-17",
    "湖南卫视": "Hunan", "浙江卫视": "Zhejiang", "江苏卫视": "Jiangsu",
    "东方卫视": "DragonTV", "北京卫视": "BTV", "广东卫视": "GDTV",
    "深圳卫视": "SZTV", "山东卫视": "SDTV", "安徽卫视": "AHTV",
    "天津卫视": "TJTV", "辽宁卫视": "LNTV", "黑龙江卫视": "HLJTV",
    "东南卫视": "FJTV", "四川卫视": "SCTV", "湖北卫视": "HubeiTV",
    "翡翠台": "TVB", "凤凰卫视中文台": "PHOENIX", "凤凰卫视资讯台": "PHOENIX-I",
    "中天新闻台": "CTI", "东森新闻台": "ETTV", "tvbs": "TVBS",
}

_cache: Dict[str, dict] = {}
_cache_lock = threading.Lock()
CACHE_TTL = 1800
MAX_CACHE = 200


def _normalize(name: str) -> str:
    n = name.strip().lower()
    for s in ("hd", "fhd", "4k", "超清", "高清", "标清", "流畅", "(", "（", " ", "-", "_"):
        n = n.replace(s, "")
    import re
    n = re.sub(r"\d{3,4}p?$", "", n)
    return n.strip()


def _resolve_epg_id(name: str) -> Optional[str]:
    norm = _normalize(name)
    if norm in CHANNEL_EPG_MAP:
        return CHANNEL_EPG_MAP[norm]
    for key, val in CHANNEL_EPG_MAP.items():
        if key in norm or norm in key:
            return val
    return None


def _fetch_xml(epg_id: str) -> Optional[str]:
    url = f"https://epg.pw/api/epg/{epg_id}.xml"
    try:
        resp = requests.get(url, timeout=(3, 5),
                           headers={"User-Agent": "MaoziTV/2.0"})
        if resp.status_code == 200 and resp.text.strip():
            return resp.text
        return None
    except Exception:
        return None


def _parse_xmltv(xml: str) -> List[Dict]:
    import re
    from datetime import datetime, timezone, timedelta
    programs = []
    try:
        root = ElementTree.fromstring(xml)
        now_ts = time.time()
        for prog in root.iter("programme"):
            start_str = prog.get("start", "")
            stop_str = prog.get("stop", "")
            if not start_str:
                continue
            start_ts = _parse_time(start_str)
            if start_ts is None:
                continue
            stop_ts = _parse_time(stop_str) if stop_str else None
            if stop_ts and stop_ts < now_ts - 1800:
                continue
            title_elem = prog.find("title")
            title = title_elem.text.strip() if title_elem is not None and title_elem.text else ""
            desc_elem = prog.find("desc")
            desc = desc_elem.text.strip() if desc_elem is not None and desc_elem.text else ""
            if title:
                programs.append({
                    "start": _fmt_time(start_ts),
                    "end": _fmt_time(stop_ts) if stop_ts else "",
                    "title": title, "desc": desc, "start_ts": int(start_ts),
                })
        programs.sort(key=lambda p: p.get("start_ts", 0))
        return programs[:5]
    except Exception:
        return []


def _parse_time(s: str) -> Optional[float]:
    import re
    from datetime import datetime, timezone, timedelta
    m = re.match(r"(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})", s.strip())
    if not m:
        return None
    y, mo, d, h, mi, sec = (int(m.group(i)) for i in range(1, 7))
    tz_m = re.search(r"([+-])(\d{2})(\d{2})", s)
    if tz_m:
        sign = 1 if tz_m.group(1) == "+" else -1
        tz_off = timezone(timedelta(hours=sign * int(tz_m.group(2)), minutes=sign * int(tz_m.group(3))))
    else:
        tz_off = timezone.utc
    try:
        return datetime(y, mo, d, h, mi, sec, tzinfo=tz_off).timestamp()
    except (ValueError, OverflowError):
        return None


def _fmt_time(ts: float) -> str:
    from datetime import datetime
    try:
        return datetime.fromtimestamp(ts).strftime("%H:%M")
    except (OSError, OverflowError, ValueError):
        return "--:--"


def _evict():
    if len(_cache) >= MAX_CACHE:
        items = sorted(_cache.items(), key=lambda kv: kv[1].get("expire_at", 0))
        for key, _ in items[:int(MAX_CACHE * 0.2)]:
            del _cache[key]


def get_epg(channel_name: str) -> Dict[str, list]:
    """Get EPG programs for a channel.
    Returns: {"programs": [{"start": "08:00", "end": "08:30", "title": "...", "desc": "..."}]}
    """
    normalized = _normalize(channel_name)
    with _cache_lock:
        cached = _cache.get(normalized)
        if cached and time.time() < cached.get("expire_at", 0):
            return {"programs": cached["programs"]}

    epg_id = _resolve_epg_id(channel_name)
    if not epg_id:
        placeholder = [{"start": "--", "title": "暂无节目信息"}]
        with _cache_lock:
            _evict()
            _cache[normalized] = {"programs": placeholder, "expire_at": time.time() + 600}
        return {"programs": placeholder}

    xml = _fetch_xml(epg_id)
    if xml:
        programs = _parse_xmltv(xml)
        if programs:
            with _cache_lock:
                _evict()
                _cache[normalized] = {"programs": programs, "expire_at": time.time() + CACHE_TTL}
            return {"programs": programs}

    return {"programs": [{"start": "--", "title": "暂无节目信息"}]}
