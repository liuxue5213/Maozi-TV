#!/usr/bin/env python3
"""Standalone channel exporter — runs without a database.

Crawls m3u sources directly and outputs channels.json.
Designed to run on GitHub Actions (no database, no backend required).

Usage:
    python backend/standalone_export.py [output_path]
    # Default output: ./channels.json
"""

import json
import logging
import os
import sys
import time
from datetime import datetime, timezone
from typing import Any, Dict, List

# Add parent dir so we can import sibling modules
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from backend.crawlers.github_crawler import GitHubM3uCrawler
from backend.crawlers.base import ChannelEntry
from backend.crawlers.normalize import normalize_channel_name, display_name_for

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("standalone_export")

EXPORT_VERSION = 1

# Sources (same as config.py defaults, but hardcoded for standalone use)
M3U_SOURCES = [
    "https://raw.githubusercontent.com/bestK/iptv/main/iptv.m3u",
    "https://iptv-org.github.io/iptv/index.m3u",
    "https://iptv-org.github.io/iptv/countries/cn.m3u",
    "https://raw.githubusercontent.com/BurningC4/Chinese-IPTV/master/TV-IPV4.m3u",
    "https://live.zbds.top/tv/iptv4.m3u",
    "https://raw.githubusercontent.com/CCSH/IPTV/refs/heads/main/live.m3u",
    # fanmingming IPv6 — 原域名失效, 改用 GitHub raw
    "https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/ipv6.m3u",
    # hujingguang — 每 15 分钟自动更新, 稳定性高
    "https://raw.githubusercontent.com/hujingguang/ChinaIPTV/main/cnTV_AutoUpdate.m3u8",
    # yifoo 精简版 — 每频道只保留最佳源, 质量优先
    "https://raw.githubusercontent.com/yifoo/autoiptv/main/merged/%E7%B2%BE%E7%AE%80%E7%89%88.m3u",
]

MAX_SOURCES_PER_CHANNEL = 5


def build_channel_list(entries: List[ChannelEntry]) -> List[Dict[str, Any]]:
    """Group entries by NORMALISED name and merge sources.

    Mirrors source_manager._merge_into_db so "CCTV-1" / "CCTV1" / "央视一套"
    collapse into a single channel with merged backup URLs.
    """
    groups: Dict[str, List[ChannelEntry]] = {}
    for entry in entries:
        key = normalize_channel_name(entry.name)
        if not key or not entry.url:
            continue
        if not entry.url.startswith(("http://", "https://")):
            continue
        if key not in groups:
            groups[key] = []
        groups[key].append(entry)

    channels = []
    for key, group_entries in groups.items():
        primary = group_entries[0]
        display = display_name_for(primary.name)
        urls = list(dict.fromkeys(e.url for e in group_entries))
        # Limit sources per channel
        urls = urls[:MAX_SOURCES_PER_CHANNEL]

        channels.append({
            "id": len(channels) + 1,
            "name": display,
            "group": primary.group or "未分类",
            "logo": primary.logo or "",
            "url": urls[0] if urls else "",
            "sources": urls,
            "healthy": True,  # In standalone mode we can't health-check
        })

    return channels


def main():
    output_path = sys.argv[1] if len(sys.argv) > 1 else "channels.json"

    logger.info("Starting standalone export...")

    crawler = GitHubM3uCrawler(M3U_SOURCES)
    entries = crawler.crawl()
    logger.info("Crawled %d entries", len(entries))

    channels = build_channel_list(entries)
    logger.info("Built %d channels", len(channels))

    payload = {
        "version": EXPORT_VERSION,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "generated_at_ts": int(time.time()),
        "total": len(channels),
        "channels": channels,
    }

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    logger.info("Exported %d channels to %s", len(channels), output_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
