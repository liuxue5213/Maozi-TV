"""Source manager: orchestrates crawling, health checking, and auto-replacement.

This is the core module that ensures channels always have working sources.
"""

import concurrent.futures
import logging
import time
from datetime import datetime, timedelta, timezone
from typing import Dict, List, Optional, Tuple

import requests
from sqlalchemy import desc, func

from .config import config
from .database import Channel, SessionLocal, SourceCheckLog
from .checker import check_source

from .crawlers.base import ChannelEntry
from .crawlers.github_crawler import GitHubM3uCrawler
from .crawlers.normalize import normalize_channel_name, display_name_for
from .crawlers.classify import classify_channel, sort_key_domestic_first

logger = logging.getLogger(__name__)

# SourceCheckLog rows older than this are purged each check cycle.
LOG_RETENTION_DAYS = 7


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

        Uses a thread pool for concurrent health checks. Each worker runs in
        its own SQLAlchemy session to stay thread-safe (a Session object must
        not be shared across threads).
        Returns (checked_count, replaced_count, hidden_count).
        """
        # Snapshot channel IDs to check in the main thread (shared session).
        with SessionLocal() as db:
            channel_ids = [
                cid for (cid,) in db.query(Channel.id)
                .filter(Channel.visible == True).all()
            ]

        checked = replaced = hidden = 0

        # Use thread pool for parallel checks
        max_workers = min(10, len(channel_ids) or 1)
        with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as pool:
            future_to_id = {
                pool.submit(self._check_and_fix_channel_by_id, cid): cid
                for cid in channel_ids
            }
            for future in concurrent.futures.as_completed(future_to_id):
                checked += 1
                try:
                    action = future.result()
                    if action == "replaced":
                        replaced += 1
                    elif action == "hidden":
                        hidden += 1
                except Exception as e:
                    logger.error("Check error (channel %s): %s",
                                 future_to_id[future], e)

        # Opportunistically purge old check logs to keep the table bounded
        try:
            self._purge_old_logs()
        except Exception as e:
            logger.warning("Log purge failed: %s", e)

        return checked, replaced, hidden

    def check_single_channel(self, channel_id: int) -> Optional[Channel]:
        """Check and fix a single channel. Returns updated channel or None."""
        ch = self._check_and_fix_channel_by_id(channel_id)
        if ch is None:
            return None
        # Re-fetch a detached copy for the API layer
        with SessionLocal() as db:
            return db.query(Channel).filter(Channel.id == channel_id).first()

    # ── Internal ────────────────────────────────────────────────

    def _check_and_fix_channel_by_id(self, channel_id: int) -> Optional[Channel]:
        """Check one channel by ID inside its own short-lived session.

        Each call owns its own session so this is safe to run concurrently
        across worker threads.
        """
        with SessionLocal() as db:
            ch = db.query(Channel).filter(Channel.id == channel_id).first()
            if not ch:
                return None
            self._check_and_fix_channel(db, ch)
            db.commit()
            return ch

    @staticmethod
    def _apply_domestic_first_order(db) -> None:
        """将国内频道排到列表前面（按 id 重排）。

        网页端和 APP 按 id 顺序展示频道列表，国内优先确保用户先看到国内频道。
        排序：国内(cn) → 港澳台(hkmt) → 国外(foreign)。
        """
        from .database import Channel
        channels = db.query(Channel).filter(Channel.visible == True).all()
        channels.sort(key=lambda ch: sort_key_domestic_first(ch.name, ch.group_name))
        for i, ch in enumerate(channels):
            ch.id = i + 1
        db.commit()

    def _merge_into_db(self, entries: List[ChannelEntry]) -> int:
        """Merge crawled entries into the database.

        Strategy:
        - Group entries by a NORMALISED channel-name key (so "CCTV-1",
          "CCTV1", "央视一套" collapse together).
        - For each key, merge all unique URLs into the channel's source list.
        - New channels get created; existing channels get new sources appended.
        """
        db = SessionLocal()
        try:
            # Group entries by normalised name
            groups: Dict[str, List[ChannelEntry]] = {}
            for entry in entries:
                key = normalize_channel_name(entry.name)
                if not key or not entry.url:
                    continue
                # Filter out non-stream extensions
                if not self._looks_like_stream_url(entry.url):
                    continue
                if key not in groups:
                    groups[key] = []
                groups[key].append(entry)

            # Build an in-memory map of existing channels keyed by their
            # normalised name, so we can match across naming variants without
            # adding a DB column.
            existing_channels = db.query(Channel).all()
            existing_by_key: Dict[str, Channel] = {
                normalize_channel_name(ch.name): ch for ch in existing_channels
            }

            new_count = 0
            for key, group_entries in groups.items():
                # Use the first entry's group/logo as the primary, and pick a
                # tidy display name (canonical alias where known).
                primary = group_entries[0]
                display = display_name_for(primary.name)

                # Collect all unique URLs (preserve order)
                urls = list(dict.fromkeys(e.url for e in group_entries))

                # Match against existing channels by normalised key
                existing = existing_by_key.get(key)
                if existing:
                    # Merge new URLs into existing source list
                    current_sources = existing.get_sources()
                    existing_urls = set(current_sources)
                    dead_urls = set(existing.get_dead_sources())

                    # Check if any dead sources should be recovered (found in new crawl)
                    recovered = [u for u in urls if u in dead_urls]
                    if recovered:
                        # Remove from dead_sources and add to active sources
                        new_dead = [u for u in existing.get_dead_sources() if u not in recovered]
                        existing.set_dead_sources(new_dead)
                        logger.info("Recovered %d previously dead sources for '%s'",
                                    len(recovered), existing.name)

                    # Add new sources not already in active or dead list
                    added = [u for u in urls if u not in existing_urls and u not in dead_urls]
                    if added or recovered:
                        # Append new + recovered sources, keep max limit
                        merged = current_sources + added + recovered
                        if len(merged) > config.max_sources_per_channel:
                            merged = merged[:config.max_sources_per_channel]
                        existing.set_sources(merged)
                        existing.updated_at = datetime.now(timezone.utc)
                        # If channel was hidden and we found new sources, unhide it
                        if not existing.visible and (added or recovered):
                            existing.visible = True
                            logger.info("Unhid channel '%s' with new sources", existing.name)
                        if added:
                            logger.info("Merged %d new sources into '%s' (total: %d)",
                                        len(added), existing.name, len(merged))
                else:
                    # Create new channel
                    new_ch = Channel(
                        name=display,
                        group_name=primary.group or "未分类",
                        logo=primary.logo or "",
                    )
                    # Keep at most max_sources_per_channel sources
                    new_ch.set_sources(urls[:config.max_sources_per_channel])
                    db.add(new_ch)
                    # Register in the map so duplicate keys in the same batch
                    # don't create a second row.
                    existing_by_key[key] = new_ch
                    new_count += 1
                    logger.info("Created new channel '%s' with %d sources",
                                 display, len(urls[:config.max_sources_per_channel]))

            db.commit()

            # 国内优先排序：让国内频道排在前面（网页/APP 列表展示用）
            self._apply_domestic_first_order(db)

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

        # Hide the channel only if its MOST RECENT N checks were all failures
        # (previously this counted the lifetime failure total, which grew
        # monotonically and hid channels permanently even after recovery).
        window = config.hide_after_dead_checks
        recent_logs = (
            db.query(SourceCheckLog)
            .filter(SourceCheckLog.channel_id == ch.id)
            .order_by(desc(SourceCheckLog.checked_at))
            .limit(window)
            .all()
        )
        recent_total = len(recent_logs)
        recent_dead = sum(1 for log in recent_logs if not log.healthy)

        # All sources currently failed AND we've accumulated enough dead checks
        if recent_total >= window and recent_dead == recent_total:
            ch.visible = False
            logger.warning("All sources dead for '%s' across last %d checks, hiding",
                           ch.name, recent_dead)
            return "hidden"

        logger.warning("All sources dead for '%s' (dead in last %d/%d checks), keeping for retry",
                       ch.name, recent_dead, max(recent_total, window))
        return "noop"

    def _purge_old_logs(self) -> int:
        """Delete source-check logs older than LOG_RETENTION_DAYS.

        Keeps the SourceCheckLog table bounded so the "recent checks" lookups
        used by the hide logic stay fast. Returns the number of rows deleted.
        """
        cutoff = datetime.now(timezone.utc) - timedelta(days=LOG_RETENTION_DAYS)
        with SessionLocal() as db:
            deleted = db.query(SourceCheckLog).filter(
                SourceCheckLog.checked_at < cutoff
            ).delete(synchronize_session=False)
            db.commit()
        if deleted:
            logger.info("Purged %d check logs older than %d days", deleted, LOG_RETENTION_DAYS)
        return deleted

    def purge_dead_sources(self, min_failures: int = 3) -> Dict[str, int]:
        """Mark consistently dead sources as temporarily disabled (not deleted).

        For each source URL, checks if it has min_failures consecutive failures.
        If so, moves it to the dead_sources list (excluded from health checks).
        This preserves the sources for potential recovery on next crawl.

        Args:
            min_failures: Minimum consecutive failures to consider a source dead.

        Returns:
            Dict with 'sources_marked_dead' and 'channels_hidden' counts.
        """
        sources_marked = 0
        channels_hidden = 0

        with SessionLocal() as db:
            channels = db.query(Channel).all()

            for ch in channels:
                sources = ch.get_sources()
                if not sources:
                    # No sources, hide channel
                    if ch.visible:
                        ch.visible = False
                        channels_hidden += 1
                        logger.info("Hidden channel '%s' with no sources", ch.name)
                    continue

                # Build a map of source_index -> recent check logs
                channel_logs = (
                    db.query(SourceCheckLog)
                    .filter(SourceCheckLog.channel_id == ch.id)
                    .order_by(SourceCheckLog.checked_at.desc())
                    .limit(50)
                    .all()
                )

                # Group logs by source index
                logs_by_index: Dict[int, List[SourceCheckLog]] = {}
                for log in channel_logs:
                    if log.source_index not in logs_by_index:
                        logs_by_index[log.source_index] = []
                    logs_by_index[log.source_index].append(log)

                # Identify dead sources (all recent checks were failures)
                dead_indices = set()
                for idx, logs in logs_by_index.items():
                    if idx >= len(sources):
                        continue
                    recent = logs[:min_failures]
                    if len(recent) >= min_failures and all(not log.healthy for log in recent):
                        dead_indices.add(idx)

                if dead_indices:
                    # Move dead sources to dead_sources list instead of deleting
                    dead_urls = [sources[i] for i in sorted(dead_indices)]
                    remaining_sources = [s for i, s in enumerate(sources) if i not in dead_indices]

                    # Add to existing dead_sources
                    existing_dead = ch.get_dead_sources()
                    all_dead = list(dict.fromkeys(existing_dead + dead_urls))

                    ch.set_sources(remaining_sources)
                    ch.set_dead_sources(all_dead)
                    sources_marked += len(dead_urls)

                    # Reset active source index if needed
                    if remaining_sources and ch.active_source_index >= len(remaining_sources):
                        ch.active_source_index = 0

                    ch.updated_at = datetime.now(timezone.utc)
                    logger.info(
                        "Marked %d sources as dead for '%s' (%d active, %d dead)",
                        len(dead_urls), ch.name, len(remaining_sources), len(all_dead)
                    )

                    # Hide channel if no remaining sources
                    if not remaining_sources:
                        ch.visible = False
                        ch.healthy = False
                        channels_hidden += 1
                        logger.warning("Hidden channel '%s' - all sources marked dead", ch.name)

            db.commit()

        logger.info(
            "Purge complete: marked %d sources as dead, hidden %d channels",
            sources_marked, channels_hidden
        )
        return {"sources_marked_dead": sources_marked, "channels_hidden": channels_hidden}

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
