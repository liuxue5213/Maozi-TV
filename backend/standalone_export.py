#!/usr/bin/env python3
"""Standalone channel exporter — runs without a database.

Crawls m3u sources directly, (optionally) health-checks every stream, and
outputs a channels.json that the APK fetches. Designed to run on GitHub
Actions (no database, no backend required).

Usage:
    python backend/standalone_export.py [output_path] [--no-check] [--timeout 10] [--workers 40]
    # Default output: ./channels.json
"""

import argparse
import concurrent.futures
import json
import logging
import os
import sys
import time
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple

# Add parent dir so we can import sibling modules
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from backend.crawlers.github_crawler import GitHubM3uCrawler
from backend.crawlers.base import ChannelEntry
from backend.crawlers.normalize import normalize_channel_name, display_name_for
from backend.crawlers.classify import classify_channel, sort_key_domestic_first
from backend.exporter import detect_region

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("standalone_export")

EXPORT_VERSION = 1

# Sources (same as config.py defaults, but hardcoded for standalone use)
# 重心：国内（央视/卫视/地方台）+ 港澳台为主，国外频道有则保留、不强求。
M3U_SOURCES = [
    # ── 国内综合（央视 + 卫视 + 地方台）──────────────────────
    "https://raw.githubusercontent.com/bestK/iptv/main/iptv.m3u",
    # best-fan — 每日检测, 425+ 频道, 源时效性好
    "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_all.m3u8",
    # cs3306 — 40+ 公开源聚合 + ffprobe 检测, 8000+ 频道
    "https://raw.githubusercontent.com/cs3306/IPTV-Sources/main/data/output/iptv_collection.m3u",
    # imtinge — 每日更新两次 + 测速筛选, ipv4 央视/卫视
    "https://raw.githubusercontent.com/imtinge/iptv-api/master/output/ipv4/result.m3u",
    # sunguanghui — 1757 频道, 900+ 国内, 测速排序
    "https://raw.githubusercontent.com/sunguanghui/TV/master/output/result.m3u",
    # Collect-IPTV — 667 频道, 已按最佳排序的精选源
    "https://raw.githubusercontent.com/zilong7728/Collect-IPTV/main/best_sorted.m3u",
    "https://raw.githubusercontent.com/BurningC4/Chinese-IPTV/master/TV-IPV4.m3u",
    "https://live.zbds.top/tv/iptv4.m3u",
    "https://raw.githubusercontent.com/CCSH/IPTV/refs/heads/main/live.m3u",
    "https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/ipv6.m3u",
    "https://raw.githubusercontent.com/hujingguang/ChinaIPTV/main/cnTV_AutoUpdate.m3u8",
    "https://raw.githubusercontent.com/yifoo/autoiptv/main/merged/%E7%B2%BE%E7%AE%80%E7%89%88.m3u",
    "https://iptv-org.github.io/iptv/countries/cn.m3u",
    # ── 港澳台 ────────────────────────────────────────────
    "https://epg.pw/test_channels_taiwan.m3u",
    "https://epg.pw/test_channels_macau.m3u",
    "https://raw.githubusercontent.com/nthack/IPTVM3U/master/HKTW.m3u",
    "https://raw.githubusercontent.com/nthack/IPTVM3U/master/GD.m3u",
    "https://iptv-org.github.io/iptv/countries/hk.m3u",
    "https://iptv-org.github.io/iptv/countries/tw.m3u",
]

MAX_SOURCES_PER_CHANNEL = 8  # 应对源失效快：每频道保留更多备用源，一个挂了还有的用


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
            "healthy": True,  # 将由 health_check_channels() 真实验证
            "region": detect_region(primary.group or "未分类", display),
        })

    # 过滤掉所有国外频道（只保留国内+港澳台），国内优先排序
    # 国外源在国内大多无法播放，且会淹没国内频道
    channels = [ch for ch in channels
                if classify_channel(ch["name"], ch["group"]) != "foreign"]
    channels.sort(key=lambda ch: sort_key_domestic_first(ch["name"], ch["group"]))

    # 重新编号
    for i, ch in enumerate(channels):
        ch["id"] = i + 1

    return channels


def _check_one_source(url: str, timeout: int) -> Tuple[str, bool, Optional[float]]:
    """Probe a single stream URL. Returns (url, is_healthy, response_time).

    Reuses the backend's battle-tested checker (HEAD → GET+Range → m3u8 parse),
    so the standalone build shares the exact same detection logic as the live
    server. run_health_check_safe will swallow any exception here.
    """
    from backend.checker import check_source
    is_healthy, resp_time, _, _ = check_source(url, timeout=timeout)
    return (url, is_healthy, resp_time)


def health_check_channels(
    channels: List[Dict[str, Any]],
    timeout: int = 10,
    max_workers: int = 40,
) -> List[Dict[str, Any]]:
    """Health-check every source of every channel concurrently.

    - Drops dead sources, keeps only working ones (ordered by response time).
    - Channels whose every source is dead are removed entirely.
    Returns the filtered + re-sorted channel list.
    """
    # Flatten all (channel_id, url) pairs for a single concurrent sweep.
    tasks: List[Tuple[int, str]] = []
    for ch in channels:
        for url in ch.get("sources", []):
            tasks.append((ch["id"], url))
    logger.info("Health-checking %d sources across %d channels (timeout=%ds, workers=%d)...",
                len(tasks), len(channels), timeout, max_workers)

    # ch_id -> {url: (healthy, resp_time)}
    results: Dict[int, Dict[str, Tuple[bool, Optional[float]]]] = {}
    checked = 0
    dead = 0
    t0 = time.monotonic()

    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as pool:
        future_to_task = {
            pool.submit(_check_one_source, url, timeout): (ch_id, url)
            for (ch_id, url) in tasks
        }
        for future in concurrent.futures.as_completed(future_to_task):
            ch_id, url = future_to_task[future]
            try:
                _, is_healthy, resp_time = future.result()
            except Exception as e:
                logger.debug("check error for %s: %s", url[:60], e)
                is_healthy, resp_time = False, None
            results.setdefault(ch_id, {})[url] = (is_healthy, resp_time)
            checked += 1
            if not is_healthy:
                dead += 1
            if checked % 50 == 0:
                logger.info("  checked %d/%d (dead so far: %d)...", checked, len(tasks), dead)

    elapsed = time.monotonic() - t0
    logger.info("Health check done in %.1fs: %d ok / %d dead", elapsed, checked - dead, dead)

    # Rebuild channels keeping only healthy sources, sorted fastest-first.
    cleaned: List[Dict[str, Any]] = []
    new_id = 0
    for ch in channels:
        ch_res = results.get(ch["id"], {})
        alive = [
            (url, info[1])
            for url, info in ch_res.items()
            if info[0]
        ]
        # Sort alive sources by response time (fastest first); None last.
        alive.sort(key=lambda x: (x[1] is None, x[1] or 999))
        if not alive:
            continue  # all sources dead → drop channel
        new_id += 1
        alive_urls = [u for (u, _) in alive]
        cleaned.append({
            "id": new_id,
            "name": ch["name"],
            "group": ch["group"],
            "logo": ch["logo"],
            "url": alive_urls[0],
            "sources": alive_urls,
            "healthy": True,
            "region": ch.get("region", detect_region(ch["group"], ch["name"])),
        })

    dropped = len(channels) - len(cleaned)
    logger.info("Kept %d channels with healthy sources, dropped %d fully-dead channels",
                len(cleaned), dropped)
    return cleaned


def main():
    parser = argparse.ArgumentParser(description="Standalone channel exporter")
    parser.add_argument("output_path", nargs="?", default="channels.json",
                        help="Output JSON path (default: channels.json)")
    parser.add_argument("--no-check", action="store_true",
                        help="Skip health check (export all crawled sources, unfiltered)")
    parser.add_argument("--timeout", type=int, default=10,
                        help="Per-source health-check timeout in seconds (default: 10)")
    parser.add_argument("--workers", type=int, default=40,
                        help="Concurrent health-check workers (default: 40)")
    args = parser.parse_args()

    output_path = args.output_path

    logger.info("Starting standalone export (health check: %s)...",
                "OFF" if args.no_check else "ON")

    crawler = GitHubM3uCrawler(M3U_SOURCES)
    entries = crawler.crawl()
    logger.info("Crawled %d entries", len(entries))

    channels = build_channel_list(entries)
    logger.info("Built %d channels", len(channels))

    # 健康检测：并发验证所有源，剔除死源，按响应时间排序（除非 --no-check）
    if not args.no_check:
        channels = health_check_channels(
            channels, timeout=args.timeout, max_workers=args.workers)

    # Build region summary
    region_counts = {"domestic": 0, "international": 0}
    for ch in channels:
        r = ch.get("region", "international")
        region_counts[r] = region_counts.get(r, 0) + 1

    payload = {
        "version": EXPORT_VERSION,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "generated_at_ts": int(time.time()),
        "total": len(channels),
        "regions": region_counts,
        "channels": channels,
    }

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    logger.info("Exported %d channels to %s", len(channels), output_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
