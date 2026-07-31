"""Global configuration for TV live streaming backend."""

import os
from dataclasses import dataclass, field
from typing import List


@dataclass
class Config:
    # Server
    host: str = os.getenv("TV_HOST", "0.0.0.0")
    port: int = int(os.getenv("TV_PORT", "8000"))

    # Database — default to a project-local path so `python backend/main.py`
    # works without Docker; Docker/run.sh override this with /data/tv.db
    db_path: str = os.getenv("TV_DB_PATH", "data/tv.db")

    # Source crawling
    crawl_interval_minutes: int = int(os.getenv("TV_CRAWL_INTERVAL", "60"))
    # GitHub m3u repo URLs to crawl
    # 重心：国内（央视/卫视/地方台）+ 港澳台为主，国外频道有则保留、不强求。
    github_m3u_repos: List[str] = field(default_factory=lambda: [
        # ── 国内综合（央视 + 卫视 + 地方台）──────────────────────
        # bestK/iptv — 540+ 频道, 2026年活跃更新
        "https://raw.githubusercontent.com/bestK/iptv/main/iptv.m3u",
        # BurningC4 Chinese-IPTV — IPv4 央视列表
        "https://raw.githubusercontent.com/BurningC4/Chinese-IPTV/master/TV-IPV4.m3u",
        # vbskycn/iptv — IPv4 自动扫描源 (CDN加速), 每 6 小时更新
        "https://live.zbds.top/tv/iptv4.m3u",
        # CCSH/IPTV — 每日更新
        "https://raw.githubusercontent.com/CCSH/IPTV/refs/heads/main/live.m3u",
        # fanmingming/live — IPv6 高清源
        "https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/ipv6.m3u",
        # hujingguang/ChinaIPTV — 每 15 分钟自动更新, 稳定性高
        "https://raw.githubusercontent.com/hujingguang/ChinaIPTV/main/cnTV_AutoUpdate.m3u8",
        # yifoo/autoiptv 精简版 — 每频道只保留最佳源, 质量优先
        "https://raw.githubusercontent.com/yifoo/autoiptv/main/merged/%E7%B2%BE%E7%AE%80%E7%89%88.m3u",
        # iptv-org 中国频道（仅国内，过滤掉国外为主的全球大表）
        "https://iptv-org.github.io/iptv/countries/cn.m3u",
        # ── 港澳台 ────────────────────────────────────────────
        # epg.pw 台湾 — 138 频道, 港台主力
        "https://epg.pw/test_channels_taiwan.m3u",
        # epg.pw 澳门
        "https://epg.pw/test_channels_macau.m3u",
        # nthack/IPTVM3U 港澳台 — 翡翠台/TVB 等
        "https://raw.githubusercontent.com/nthack/IPTVM3U/master/HKTW.m3u",
        # nthack 广东地方台
        "https://raw.githubusercontent.com/nthack/IPTVM3U/master/GD.m3u",
        # iptv-org 香港 / 台湾（精简, 作为港澳台补充）
        "https://iptv-org.github.io/iptv/countries/hk.m3u",
        "https://iptv-org.github.io/iptv/countries/tw.m3u",
    ])

    # Health check
    check_interval_minutes: int = int(os.getenv("TV_CHECK_INTERVAL", "15"))
    check_timeout_seconds: int = int(os.getenv("TV_CHECK_TIMEOUT", "10"))
    # Number of consecutive failures before marking a source as dead
    max_failures: int = int(os.getenv("TV_MAX_FAILURES", "3"))
    # Number of backup sources to keep per channel
    # NOTE: env var name is TV_MAX_SOURCES_PER_CHANNEL (matches docker-compose & README)
    max_sources_per_channel: int = int(os.getenv("TV_MAX_SOURCES_PER_CHANNEL", "5"))

    # Channel auto-replacement
    # Channels with all sources dead for this many checks will be hidden
    hide_after_dead_checks: int = int(os.getenv("TV_HIDE_AFTER", "6"))

    # Logging — default to project-local; Docker/run.sh override with /data/tv.log
    log_level: str = os.getenv("TV_LOG_LEVEL", "INFO")
    log_file: str = os.getenv("TV_LOG_FILE", "data/tv.log")


config = Config()
