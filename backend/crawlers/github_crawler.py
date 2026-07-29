"""Crawler that fetches m3u playlists from GitHub raw URLs (iptv-org, fanmingming, etc.)."""

import logging
from typing import List

import requests

from .base import BaseCrawler, ChannelEntry
from .public_m3u import parse_m3u

logger = logging.getLogger(__name__)


class GitHubM3uCrawler(BaseCrawler):
    """Crawl m3u playlist files from GitHub raw URLs."""

    def __init__(self, urls: List[str]):
        super().__init__("GitHubM3u")
        self.urls = urls

    def _fetch_m3u(self, url: str) -> str:
        """Fetch an m3u playlist from a URL with a timeout."""
        headers = {
            "User-Agent": (
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/120.0.0.0 Safari/537.36"
            ),
            "Accept": "*/*",
        }
        resp = requests.get(url, headers=headers, timeout=30)
        resp.raise_for_status()
        return resp.text

    def crawl(self) -> List[ChannelEntry]:
        """Fetch all configured m3u URLs and merge results."""
        all_entries: List[ChannelEntry] = []
        for url in self.urls:
            try:
                logger.info("Fetching m3u: %s", url)
                content = self._fetch_m3u(url)
                entries = parse_m3u(content, source=url)
                for e in entries:
                    e.source = url
                all_entries.extend(entries)
                logger.info("  -> Got %d channels from %s", len(entries), url)
            except requests.exceptions.Timeout:
                logger.warning("  -> Timeout fetching %s", url)
            except requests.exceptions.HTTPError as e:
                logger.warning("  -> HTTP error %s for %s", e.response.status_code, url)
            except Exception as e:
                logger.warning("  -> Failed to fetch %s: %s", url, e)
        return all_entries
