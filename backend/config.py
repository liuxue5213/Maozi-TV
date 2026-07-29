"""Global configuration for TV live streaming backend."""

import os
from dataclasses import dataclass, field
from typing import List


@dataclass
class Config:
    # Server
    host: str = os.getenv("TV_HOST", "0.0.0.0")
    port: int = int(os.getenv("TV_PORT", "8000"))

    # Database
    db_path: str = os.getenv("TV_DB_PATH", "/data/tv.db")

    # Source crawling
    crawl_interval_minutes: int = int(os.getenv("TV_CRAWL_INTERVAL", "60"))
    # GitHub m3u repo URLs to crawl
    github_m3u_repos: List[str] = field(default_factory=lambda: [
        # bestK/iptv — 540个频道, 2026年7月活跃更新
        "https://raw.githubusercontent.com/bestK/iptv/main/iptv.m3u",
        # iptv-org — 全球频道集合
        "https://iptv-org.github.io/iptv/index.m3u",
        # iptv-org 中国频道
        "https://iptv-org.github.io/iptv/countries/cn.m3u",
        # BurningC4 Chinese-IPTV — IPv4 央视列表
        "https://raw.githubusercontent.com/BurningC4/Chinese-IPTV/master/TV-IPV4.m3u",
        # vbskycn/iptv — IPv4 自动扫描源 (CDN加速)
        "https://live.zbds.top/tv/iptv4.m3u",
        # CCSH/IPTV — 每日更新
        "https://raw.githubusercontent.com/CCSH/IPTV/refs/heads/main/live.m3u",
        # fanmingming/live — IPv6 源 (国内镜像)
        "https://live.fanmingming.cn/tv/m3u/ipv6.m3u",
    ])

    # Health check
    check_interval_minutes: int = int(os.getenv("TV_CHECK_INTERVAL", "15"))
    check_timeout_seconds: int = int(os.getenv("TV_CHECK_TIMEOUT", "10"))
    # Number of consecutive failures before marking a source as dead
    max_failures: int = int(os.getenv("TV_MAX_FAILURES", "3"))
    # Number of backup sources to keep per channel
    max_sources_per_channel: int = int(os.getenv("TV_MAX_SOURCES", "5"))

    # Channel auto-replacement
    # Channels with all sources dead for this many checks will be hidden
    hide_after_dead_checks: int = int(os.getenv("TV_HIDE_AFTER", "6"))

    # Logging
    log_level: str = os.getenv("TV_LOG_LEVEL", "INFO")
    log_file: str = os.getenv("TV_LOG_FILE", "/data/tv.log")


config = Config()
