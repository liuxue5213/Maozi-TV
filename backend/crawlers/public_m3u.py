"""Parser for m3u / m3u8 playlist format.

Supports the extended m3u format with #EXTINF metadata (tvg-name, tvg-logo, group-title).
"""

import logging
import re
from typing import List

from .base import ChannelEntry

logger = logging.getLogger(__name__)

# 非直播频道（点播录像/DJ串烧/春晚回放等）的特征词。这些不是真正的电视直播频道，
# 来自某些源的点播内容混入，会稀释真正的卫视/央视频道，需在解析阶段过滤。
JUNK_NAME_KEYWORDS = (
    "春晚", "DJ", "串烧", "伤感", "情歌", "舞曲", "车载", "精选", "火爆",
    "爆红", "动感", "网络火爆", "伤感情歌", "演唱会", "MV版", "年版",
    "卡啦OK", "卡拉OK", "点播", "回看", "回放", "点播影院",
    "MV欣赏", "Music欣赏", "每日一首", "LATATA", "Uh-oh",
)
# 形如 "1987年春晚" "2018精选" "2026-07-14 09:59:25"（时间戳） 这类录像/自动生成
_YEAR_RECORDING_RE = re.compile(r"^(19|20)\d{2}\s*年")
_TIMESTAMP_RE = re.compile(r"^\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}")


def _is_junk_channel(name: str) -> bool:
    """判断是否为非直播的垃圾频道（点播/DJ/录像/时间戳等）。"""
    if not name:
        return True
    n = name.strip()
    # 年份开头的录像（如 "1987年春晚"）
    if _YEAR_RECORDING_RE.match(n):
        return True
    # 时间戳开头的自动生成内容（如 "2026-07-14 09:59:25"）
    if _TIMESTAMP_RE.match(n):
        return True
    # 含 DJ/串烧/春晚等关键词
    low = n.lower()
    return any(kw in n or kw.lower() in low for kw in JUNK_NAME_KEYWORDS)


# 组播/不可播放 URL 的特征：浏览器无法播放，应在爬取阶段过滤
def _is_unplayable_url(url: str) -> bool:
    """判断 URL 是否在公网浏览器中无法播放（组播/UDP/RTSP 等）。"""
    u = url.lower().strip()
    # rtp:// / udp:// / rtsp:// 协议，浏览器不支持
    if u.startswith(("rtp://", "udp://", "rtsp://", "rtmp://")):
        return True
    # 路径含 /rtp/ （组播转单播代理，公网通常无法访问）
    if "/rtp/" in u:
        return True
    # 主机是组播地址（224.0.0.0 - 239.255.255.255）
    import re as _re
    m = _re.match(r"https?://(\d+)\.(\d+)\.(\d+)\.(\d+)", u)
    if m:
        o1 = int(m.group(1))
        if 224 <= o1 <= 239:
            return True
    return False


# Pattern for EXTINF line: #EXTINF:-1 tvg-name="xxx" tvg-logo="xxx" group-title="xxx",Channel Name
EXTINF_PATTERN = re.compile(
    r'#EXTINF:[-.\d]+\s*'
    r'(?:tvg-name="(?P<tvg_name>[^"]*)")?\s*'
    r'(?:tvg-id="[^"]*")?\s*'
    r'(?:tvg-logo="(?P<tvg_logo>[^"]*)")?\s*'
    r'(?:group-title="(?P<group_title>[^"]*)")?\s*'
    r'(?:channel-id="[^"]*")?\s*'
    r'(?:,)?(?P<channel_name>[^,]*)$'
)


def parse_m3u(content: str, source: str = "") -> List[ChannelEntry]:
    """Parse m3u/m3u8 content into a list of ChannelEntry.

    Handles both extended (#EXTINF) and simple (one URL per line) m3u.
    """
    entries: List[ChannelEntry] = []
    lines = content.splitlines()

    i = 0
    while i < len(lines):
        line = lines[i].strip()

        # Skip empty lines and headers
        if not line or line.startswith("#EXTM3U"):
            i += 1
            continue

        # Check if this is an EXTINF line
        if line.startswith("#EXTINF"):
            channel_name = ""
            tvg_logo = ""
            group_title = "未分类"

            m = EXTINF_PATTERN.match(line)
            if m:
                channel_name = (m.group("tvg_name") or m.group("channel_name") or "").strip()
                tvg_logo = (m.group("tvg_logo") or "").strip()
                group_title = (m.group("group_title") or "未分类").strip()
            else:
                # Fallback: take everything after the last comma
                if "," in line:
                    channel_name = line.rsplit(",", 1)[-1].strip()

            if not channel_name:
                channel_name = f"未知频道_{len(entries) + 1}"

            # Next non-empty line should be the URL
            i += 1
            while i < len(lines) and not lines[i].strip():
                i += 1
            if i < len(lines):
                url = lines[i].strip()
                if url and not url.startswith("#"):
                    # 过滤非直播频道（DJ/春晚录像等点播内容）
                    if _is_junk_channel(channel_name):
                        logger.debug("Dropped junk channel: %s", channel_name)
                    elif _is_unplayable_url(url):
                        logger.debug("Dropped unplayable URL: %s", url)
                    else:
                        entries.append(ChannelEntry(
                            name=channel_name,
                            url=url,
                            group=group_title,
                            logo=tvg_logo,
                            source=source,
                        ))
            i += 1

        elif line.startswith("#"):
            # Other comment/header lines - skip
            i += 1

        else:
            # Bare URL line without EXTINF (simple m3u)
            url = line
            # Filter out URLs that are clearly not streamable
            if url and url.startswith("http"):
                # Try to infer a name from the URL
                name = _infer_name_from_url(url)
                entries.append(ChannelEntry(
                    name=name,
                    url=url,
                    group="未分类",
                    source=source,
                ))
            i += 1

    # Deduplicate by URL (keep first occurrence)
    seen_urls = set()
    deduped = []
    for entry in entries:
        if entry.url not in seen_urls:
            seen_urls.add(entry.url)
            deduped.append(entry)
        else:
            logger.debug("Dropped duplicate URL: %s", entry.url)

    logger.info("Parsed %d channels from m3u (after dedup: %d)", len(entries), len(deduped))
    return deduped


def _infer_name_from_url(url: str) -> str:
    """Try to extract a human-readable channel name from a URL."""
    # Try common patterns
    patterns = [
        r'/([^/]+)\.m3u8?$',
        r'/([^/]+)/(?:index|playlist|stream|live)\.m3u8?',
        r'/([^/]+)/(?:index|playlist|stream|live)',
        r'/(?:live|stream|play)/([^/?#]+)',
        r'channel[=/]([^/?#&]+)',
    ]
    for pat in patterns:
        m = re.search(pat, url)
        if m:
            name = m.group(1)
            # Clean up URL-encoded chars
            from urllib.parse import unquote
            name = unquote(name)
            # Remove file extensions
            name = re.sub(r'\.(m3u8?|ts|flv|mp4)$', '', name)
            # Replace common separators
            name = name.replace('_', ' ').replace('-', ' ')
            name = name.strip()
            if name and len(name) < 60:
                return name

    # Last resort: use domain as identifier
    m = re.search(r'://([^/]+)', url)
    if m:
        return m.group(1)

    return f"频道_{hash(url) % 10000}"


def parse_tvbox_txt(content: str, source: str = "") -> List[ChannelEntry]:
    """Parse TVBox txt format: "分类名,#genre#" followed by "频道名,URL" lines.

    Example:
        🇨🇳央视,#genre#
        CCTV-1,http://...
        CCTV-2,http://...
        卫视,#genre#
        湖南卫视,http://...
    """
    entries: List[ChannelEntry] = []
    lines = content.splitlines()
    current_group = "未分类"

    for line in lines:
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        # 分组标记: "分类名,#genre#"
        if line.endswith("#genre#"):
            group_name = line.replace("#genre#", "").rstrip(",").strip()
            if group_name:
                current_group = group_name
            continue
        # 频道行: "频道名,URL"
        if "," in line:
            # 分割第一个逗号（频道名可能含逗号的情况极少，URL 不含逗号）
            parts = line.split(",", 1)
            if len(parts) == 2:
                name = parts[0].strip()
                url = parts[1].strip()
                if url.startswith("http") and name:
                    if _is_junk_channel(name):
                        logger.debug("Dropped junk channel: %s", name)
                    elif _is_unplayable_url(url):
                        logger.debug("Dropped unplayable URL: %s", url)
                    else:
                        entries.append(ChannelEntry(
                            name=name,
                            url=url,
                            group=current_group,
                            source=source,
                        ))

    # Deduplicate by URL
    seen_urls = set()
    deduped = []
    for entry in entries:
        if entry.url not in seen_urls:
            seen_urls.add(entry.url)
            deduped.append(entry)

    logger.info("Parsed %d channels from TVBox txt (after dedup: %d)", len(entries), len(deduped))
    return deduped


def parse_playlist(content: str, source: str = "") -> List[ChannelEntry]:
    """Auto-detect format and parse: m3u (#EXTM3U) or TVBox txt (#genre#)."""
    if content.startswith("#EXTM3U") or "#EXTINF" in content[:500]:
        return parse_m3u(content, source=source)
    if "#genre#" in content[:2000]:
        return parse_tvbox_txt(content, source=source)
    # 默认用 m3u 解析（简单 URL 列表）
    return parse_m3u(content, source=source)
