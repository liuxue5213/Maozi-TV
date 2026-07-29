"""Source manager: orchestrates crawling, health checking, and auto-replacement.

This is the core module that ensures channels always have working sources.
"""

import concurrent.futures
import logging
import time
from datetime import datetime, timezone
from typing import Dict, List, Optional, Tuple

import requests

from .config import config
from .database import Channel, SessionLocal, SourceCheckLog
from .checker import check_source

from .crawlers.base import ChannelEntry
from .crawlers.github_crawler import GitHubM3uCrawler

logger = logging.getLogger(__name__)


class SourceManager:
    """Manages channel sources: crawl, validate, auto-replace."""

    def __init__(self):
        self.crawler = GitHubM3uCrawler(config.github_m3u_repos)

    # ── Public API ──────────────────────────────────────────────

    def run_full_cycle(self) -> Dict[str, int]:
        """Run one full maintenance cycle: crawl → merge → check → replace.

        Returns a summary dict of what happened.
        """
        summary = {"crawled": 0, "new_channels": 0, "checked": 0, "replaced": 0, "hidden": 0}

        # Step 1: Crawl for new sources
        logger.info("=== Starting crawl cycle ===")
        entries = self.crawler.crawl()
        new_count = 0
        summary["crawled"] = len(entries)
        if entries:
            new_count = self._merge_into_db(entries)
            summary["new_channels"] = new_count
        logger.info("Crawl complete: %d entries, %d new channels", len(entries), new_count)

        # Step 2: Check existing channels
        logger.info("=== Starting health check cycle ===")
        checked, replaced, hidden = self.check_and_replace_all()
        summary["checked"] = checked
        summary["replaced"] = replaced
        summary["hidden"] = hidden
        logger.info("Health check complete: checked=%d, replaced=%d, hidden=%d",
                     checked, replaced, hidden)

        return summary

    def check_and_replace_all(self) -> Tuple[int, int, int]:
        """Check all visible channels and auto-replace dead sources.

        Uses a thread pool for concurrent health checks.
        Returns (checked_count, replaced_count, hidden_count).
        """
        db = SessionLocal()
        try:
            channels = db.query(Channel).filter(Channel.visible == True).all()
            checked = 0
            replaced = 0
            hidden = 0

            # Use thread pool for parallel checks
            max_workers = min(10, len(channels) or 1)
            with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as pool:
                # Submit all checks
                future_to_ch = {
                    pool.submit(self._check_and_fix_channel, db, ch): ch
                    for ch in channels
                }
                # Collect results
                for future in concurrent.futures.as_completed(future_to_ch):
                    checked += 1
                    try:
                        action = future.result()
                        if action == "replaced":
                            replaced += 1
                        elif action == "hidden":
                            hidden += 1
                    except Exception as e:
                        logger.error("Check error: %s", e)

            db.commit()
            return checked, replaced, hidden
        finally:
            db.close()

    def check_single_channel(self, channel_id: int) -> Optional[Channel]:
        """Check and fix a single channel. Returns updated channel or None."""
        db = SessionLocal()
        try:
            ch = db.query(Channel).filter(Channel.id == channel_id).first()
            if ch:
                self._check_and_fix_channel(db, ch)
                db.commit()
                return ch
            return None
        finally:
            db.close()

    # ── Internal ────────────────────────────────────────────────

    def _merge_into_db(self, entries: List[ChannelEntry]) -> int:
        """Merge crawled entries into the database.

        Strategy:
        - Group entries by channel name.
        - For each name, merge all unique URLs into the channel's source list.
        - New channels get created; existing channels get new sources appended.
        """
        db = SessionLocal()
        try:
            # Group entries by name (case-insensitive)
            groups: Dict[str, List[ChannelEntry]] = {}
            for entry in entries:
                key = entry.name.strip()
                if not key or not entry.url:
                    continue
                # Filter out non-stream extensions
                if not self._looks_like_stream_url(entry.url):
                    continue
                if key not in groups:
                    groups[key] = []
                groups[key].append(entry)

            new_count = 0
            for name, group_entries in groups.items():
                # Use the first entry's group/logo as the primary
                primary = group_entries[0]

                # Collect all unique URLs
                urls = list(dict.fromkeys(e.url for e in group_entries))

                # Check if channel already exists
                existing = db.query(Channel).filter(Channel.name == name).first()
                if existing:
                    # Merge new URLs into existing source list
                    current_sources = existing.get_sources()
                    existing_urls = set(current_sources)
                    added = [u for u in urls if u not in existing_urls]
                    if added:
                        # Append new sources, keep max limit
                        merged = current_sources + added
                        if len(merged) > config.max_sources_per_channel:
                            merged = merged[:config.max_sources_per_channel]
                        existing.set_sources(merged)
                        existing.updated_at = datetime.now(timezone.utc)
                        # If channel was hidden and we found new sources, unhide it
                        if not existing.visible:
                            existing.visible = True
                            logger.info("Unhid channel '%s' with new sources", name)
                        logger.info("Merged %d new sources into '%s' (total: %d)",
                                     len(added), name, len(merged))
                else:
                    # Create new channel
                    new_ch = Channel(
                        name=name,
                        group_name=primary.group or "未分类",
                        logo=primary.logo or "",
                    )
                    # Keep at most max_sources_per_channel sources
                    new_ch.set_sources(urls[:config.max_sources_per_channel])
                    db.add(new_ch)
                    new_count += 1
                    logger.info("Created new channel '%s' with %d sources",
                                 name, len(urls[:config.max_sources_per_channel]))

            db.commit()
            return new_count
        finally:
            db.close()

    def _check_and_fix_channel(self, db, ch: Channel) -> str:
        """Check a channel's active source and fix if dead. Returns action taken."""
        sources = ch.get_sources()
        if not sources:
            ch.healthy = False
            ch.visible = False
            logger.warning("Channel '%s' has no sources, hiding", ch.name)
            return "hidden"

        # Check the currently active source
        active_url = ch.get_active_source()
        if not active_url:
            return "noop"

        is_healthy, resp_time, status_code, error = check_source(
            active_url, timeout=config.check_timeout_seconds
        )

        # Log the check result
        check_log = SourceCheckLog(
            channel_id=ch.id,
            source_index=ch.active_source_index,
            source_url=active_url,
            healthy=is_healthy,
            response_time=resp_time,
            status_code=status_code,
            error_message=error[:200] if error else "",
        )
        db.add(check_log)

        ch.last_checked = datetime.now(timezone.utc)

        if is_healthy:
            ch.healthy = True
            ch.consecutive_failures = 0
            ch.last_response_time = resp_time
            return "noop"
        else:
            ch.consecutive_failures += 1
            logger.warning("Source dead for '%s': attempt %d/%d — %s",
                           ch.name, ch.consecutive_failures, config.max_failures,
                           error[:80])

            if ch.consecutive_failures >= config.max_failures:
                # This source is dead. Try switching to next source.
                return self._try_switch_source(db, ch)
            return "noop"

    def _try_switch_source(self, db, ch: Channel) -> str:
        """Try to switch to the next backup source. Returns 'replaced', 'hidden', or 'noop'."""
        sources = ch.get_sources()
        current_idx = ch.active_source_index

        # Test all other sources in order, find the first working one
        for offset in range(1, len(sources)):
            test_idx = (current_idx + offset) % len(sources)
            test_url = sources[test_idx]

            is_healthy, resp_time, _, _ = check_source(
                test_url, timeout=config.check_timeout_seconds
            )

            if is_healthy:
                ch.active_source_index = test_idx
                ch.consecutive_failures = 0
                ch.healthy = True
                ch.last_response_time = resp_time
                logger.info("Switched '%s' to backup source #%d: %s",
                            ch.name, test_idx, test_url[:80])
                return "replaced"

        # All sources are dead
        ch.healthy = False
        ch.consecutive_failures = 0  # Reset counter to avoid log spam

        # Check if we've been dead for too long
        hidden_dead_count = db.query(SourceCheckLog).filter(
            SourceCheckLog.channel_id == ch.id,
            SourceCheckLog.healthy == False,
        ).count()

        if hidden_dead_count >= config.hide_after_dead_checks:
            ch.visible = False
            logger.warning("All sources dead for '%s' too long (%d checks), hiding",
                           ch.name, hidden_dead_count)
            return "hidden"

        logger.warning("All sources dead for '%s', keeping for retry", ch.name)
        return "noop"

    @staticmethod
    def _looks_like_stream_url(url: str) -> bool:
        """Filter out URLs that are unlikely to be streamable video."""
        # Must be http/https
        if not url.startswith(("http://", "https://")):
            return False

        # Exclude common non-stream patterns
        exclude_patterns = [
            ".jpg", ".jpeg", ".png", ".gif", ".svg", ".webp",
            ".css", ".js", ".json", ".xml", ".html", ".htm",
            ".torrent", ".exe", ".zip", ".rar", ".7z",
            ".mp3", ".aac", ".flac", ".wav",  # audio-only
        ]
        lower = url.lower()
        for pat in exclude_patterns:
            if pat in lower:
                return False

        return True
