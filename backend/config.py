"""Global configuration for TV live streaming backend.

All settings can be overridden via environment variables (prefix: TV_).
"""

import os
from dataclasses import dataclass, field
from typing import List


@dataclass
class Config:
    # ── Server ────────────────────────────────────────────
    host: str = os.getenv("TV_HOST", "0.0.0.0")
    port: int = int(os.getenv("TV_PORT", "8000"))

    # ── Database ──────────────────────────────────────────
    # Default: project-local (non-Docker); Docker/run.sh override with /data/tv.db
    db_path: str = os.getenv("TV_DB_PATH", "data/tv.db")

    # ── Crawling ──────────────────────────────────────────
    crawl_interval_minutes: int = int(os.getenv("TV_CRAWL_INTERVAL", "60"))
    # GitHub m3u repo URLs to crawl
    # 重心：国内（央视/卫视/地方台）+ 港澳台为主，国外频道有则保留、不强求。
    github_m3u_repos: List[str] = field(default_factory=lambda: [
        # ── 国内综合（央视 + 卫视 + 地方台）──────────────────────
        # bestK/iptv — 540+ 频道, 2026年活跃更新
        "https://raw.githubusercontent.com/bestK/iptv/main/iptv.m3u",
        # sunguanghui/TV — 1757 频道, 900+ 国内(央视/卫视), 测速排序
        "https://raw.githubusercontent.com/sunguanghui/TV/master/output/result.m3u",
        # zilong7728/Collect-IPTV — 667 频道, 已按最佳排序的精选源
        "https://raw.githubusercontent.com/zilong7728/Collect-IPTV/main/best_sorted.m3u",
        # BurningC4 Chinese-IPTV — IPv4 央视列表
        "https://raw.githubusercontent.com/BurningC4/Chinese-IPTV/master/TV-IPV4.m3u",
        # vbskycn/iptv — IPv4 自动扫描源 (CDN加速), 每 6 小时更新
        "https://live.zbds.top/tv/iptv4.m3u",
        # CCSH/IPTV — 每日更新
        "https://raw.githubusercontent.com/CCSH/IPTV/refs/heads/main/live.m3u",
        # yifoo/autoiptv 精简版 — 每频道只保留最佳源, 质量优先
        "https://raw.githubusercontent.com/yifoo/autoiptv/main/merged/%E7%B2%BE%E7%AE%80%E7%89%88.m3u",
        # ── jsdelivr CDN 镜像（国内访问更稳定）────────────────────
        # best-fan/iptv-sources — 每日检测, 425+ 频道(央视/地方分类), 源时效性好
        "https://cdn.jsdelivr.net/gh/best-fan/iptv-sources@main/cn_all.m3u8",
        # cs3306/IPTV-Sources — 40+ 公开源聚合 + ffprobe 检测, 8000+ 频道
        "https://cdn.jsdelivr.net/gh/cs3306/IPTV-Sources@main/data/output/iptv_collection.m3u",
        # imtinge/iptv-api — 每日更新两次 + 测速筛选, ipv4 央视/卫视
        "https://cdn.jsdelivr.net/gh/imtinge/iptv-api@master/output/ipv4/result.m3u",
        # fanmingming/live — IPv6 高清源
        "https://cdn.jsdelivr.net/gh/fanmingming/live@main/tv/m3u/ipv6.m3u",
        # hujingguang/ChinaIPTV — 每 15 分钟自动更新, 稳定性高
        "https://cdn.jsdelivr.net/gh/hujingguang/ChinaIPTV@main/cnTV_AutoUpdate.m3u8",
        # iptv-org 中国频道（jsdelivr 镜像，避免 raw.githubusercontent 被墙）
        "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/streams/cn.m3u",
        # ── 港澳台 ────────────────────────────────────────────
        # epg.pw 台湾 — 138 频道, 港台主力
        "https://epg.pw/test_channels_taiwan.m3u",
        # epg.pw 澳门
        "https://epg.pw/test_channels_macau.m3u",
        # nthack/IPTVM3U 港澳台 — 翡翠台/TVB 等
        "https://raw.githubusercontent.com/nthack/IPTVM3U/master/HKTW.m3u",
        # nthack 广东地方台
        "https://raw.githubusercontent.com/nthack/IPTVM3U/master/GD.m3u",
        # iptv-org 香港 / 台湾（jsdelivr 镜像，作为港澳台补充）
        "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/streams/hk.m3u",
        "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/streams/tw.m3u",
        # iptv-org 韩国 / 日本（jsdelivr 镜像）────────────────────────
        "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/streams/kr.m3u",
        "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/streams/jp.m3u",
        # ── 国内公网源补充（最近活跃更新）─────────────────────────
        # develop202/migu_video 咪咕视频源（央视/卫视，2026-07-30 更新，m3u 格式）
        "https://cdn.jsdelivr.net/gh/develop202/migu_video@main/interface.txt",
        # Supprise0901/TVBox_live（河南地方台+CCTV+卫视，TVBox txt 格式，活跃更新）
        "https://cdn.jsdelivr.net/gh/Supprise0901/TVBox_live@main/live.txt",
        # ── 新增：更多代码平台源 ─────────────────────────────────
        # kilvn/iptv — 直播中国（景区监控）+ CCTV 频道
        "https://raw.githubusercontent.com/kilvn/iptv/master/live-china.m3u",
        # kilvn/iptv+ — CCTV 频道（IPv6 组播源）
        "https://raw.githubusercontent.com/kilvn/iptv/master/iptv+.m3u",
    ])

    # ── Health Check ──────────────────────────────────────
    check_interval_minutes: int = int(os.getenv("TV_CHECK_INTERVAL", "15"))
    check_timeout_seconds: int = int(os.getenv("TV_CHECK_TIMEOUT", "10"))
    # Number of consecutive failures before marking a source as dead
    max_failures: int = int(os.getenv("TV_MAX_FAILURES", "3"))
    # Number of backup sources to keep per channel
    max_sources_per_channel: int = int(os.getenv("TV_MAX_SOURCES_PER_CHANNEL", "5"))
    # Channels with all sources dead for this many checks will be hidden
    hide_after_dead_checks: int = int(os.getenv("TV_HIDE_AFTER", "6"))

    # ── Concurrency (HostAwareExecutor) ───────────────────
    # Global thread pool size for health checks
    check_max_workers: int = int(os.getenv("TV_CHECK_MAX_WORKERS", "20"))
    # Max concurrent requests per host (avoid overwhelming single server)
    check_max_per_host: int = int(os.getenv("TV_CHECK_MAX_PER_HOST", "2"))

    # ── Codec Detection ───────────────────────────────────
    # Enable ffprobe-based codec compatibility check (requires ffmpeg installed)
    # When enabled, sources with incompatible codecs (e.g. MP2 audio) are marked unhealthy
    # Set to False for APK clients (ExoPlayer supports all codecs)
    check_codec_enabled: bool = os.getenv("TV_CHECK_CODEC", "true").lower() in ("1", "true", "yes")

    # Logging — default to project-local; Docker/run.sh override with /data/tv.log
    log_level: str = os.getenv("TV_LOG_LEVEL", "INFO")
    log_file: str = os.getenv("TV_LOG_FILE", "data/tv.log")


config = Config()
