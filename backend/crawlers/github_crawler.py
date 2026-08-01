"""Crawler that fetches m3u playlists from GitHub raw URLs (iptv-org, fanmingming, etc.).

支持 GitHub raw URL 的国内镜像加速（raw.githubusercontent.com 在国内访问经常超时）：
- raw.gitmirror.com（GitHub raw 镜像，直接替换域名）
- cdn.jsdelivr.net（jsDelivr CDN，路径格式不同）
- 原始 URL（兜底）
"""

import logging
from typing import List, Tuple

import requests

from .base import BaseCrawler, ChannelEntry
from .public_m3u import parse_playlist

logger = logging.getLogger(__name__)


# GitHub raw 镜像列表（按优先级，第一个最优先尝试）
# 每项是 (镜像名, URL 转换函数或 None)，None 表示用原始 URL
def _gitmirror_url(url: str) -> str:
    """raw.githubusercontent.com → raw.gitmirror.com（直接替换域名）。"""
    return url.replace(
        "https://raw.githubusercontent.com/",
        "https://raw.gitmirror.com/",
    )


def _jsdelivr_url(url: str) -> str:
    """raw.githubusercontent.com/{user}/{repo}/{branch}/{path}
    → cdn.jsdelivr.net/gh/{user}/{repo}@{branch}/{path}"""
    if not url.startswith("https://raw.githubusercontent.com/"):
        return url
    rest = url[len("https://raw.githubusercontent.com/"):]
    parts = rest.split("/", 3)
    if len(parts) != 4:
        return url
    user, repo, branch, path = parts
    return f"https://cdn.jsdelivr.net/gh/{user}/{repo}@{branch}/{path}"


# 镜像列表：依次尝试，第一个成功就用第一个
# 顺序：jsdelivr（实测可用，CDN 加速，1-2 秒响应）→ gitmirror（备用）→ 原始 URL（兜底）
_GITHUB_MIRRORS: List[Tuple[str, callable]] = [
    ("jsdelivr", _jsdelivr_url),
    ("gitmirror", _gitmirror_url),
]


class GitHubM3uCrawler(BaseCrawler):
    """Crawl m3u playlist files from GitHub raw URLs."""

    def __init__(self, urls: List[str]):
        super().__init__("GitHubM3u")
        self.urls = urls

    def _candidate_urls(self, url: str) -> List[Tuple[str, str]]:
        """返回 [(镜像名, 实际 URL), ...]，按优先级排序，原始 URL 兜底。

        只对 raw.githubusercontent.com 的 URL 生成镜像，其他域名直接用原始 URL。
        """
        candidates: List[Tuple[str, str]] = []
        if url.startswith("https://raw.githubusercontent.com/"):
            for name, converter in _GITHUB_MIRRORS:
                mirror = converter(url)
                if mirror and mirror != url:
                    candidates.append((name, mirror))
        # 原始 URL 兜底
        candidates.append(("origin", url))
        return candidates

    def _fetch_m3u(self, url: str) -> str:
        """Fetch an m3u playlist from a URL.

        对 GitHub raw URL 依次尝试国内镜像，第一个成功就用第一个。
        每个候选 URL 超时 15 秒（镜像通常更快）。
        """
        headers = {
            "User-Agent": (
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/120.0.0.0 Safari/537.36"
            ),
            "Accept": "*/*",
        }

        candidates = self._candidate_urls(url)
        last_error: Exception = None
        for name, actual_url in candidates:
            try:
                resp = requests.get(actual_url, headers=headers, timeout=15)
                resp.raise_for_status()
                if name != "origin":
                    logger.info("  -> via mirror [%s]: %s", name, actual_url)
                return resp.text
            except requests.exceptions.Timeout:
                logger.warning("  -> Timeout via %s: %s", name, actual_url)
                last_error = requests.exceptions.Timeout(f"timeout via {name}")
            except requests.exceptions.HTTPError as e:
                logger.warning("  -> HTTP %s via %s", e.response.status_code, name)
                last_error = e
            except Exception as e:
                logger.warning("  -> Failed via %s: %s", name, e)
                last_error = e

        # 所有候选都失败，抛出最后一个错误
        raise last_error or requests.exceptions.RequestException(
            f"All mirrors failed for {url}"
        )

    def crawl(self) -> List[ChannelEntry]:
        """Fetch all configured m3u URLs and merge results."""
        all_entries: List[ChannelEntry] = []
        for url in self.urls:
            try:
                logger.info("Fetching m3u: %s", url)
                content = self._fetch_m3u(url)
                entries = parse_playlist(content, source=url)
                for e in entries:
                    e.source = url
                all_entries.extend(entries)
                logger.info("  -> Got %d channels from %s", len(entries), url)
            except requests.exceptions.Timeout:
                logger.warning("  -> Timeout fetching %s (all mirrors)", url)
            except requests.exceptions.HTTPError as e:
                logger.warning("  -> HTTP error %s for %s (all mirrors)", e.response.status_code, url)
            except Exception as e:
                logger.warning("  -> Failed to fetch %s (all mirrors): %s", url, e)
        return all_entries
