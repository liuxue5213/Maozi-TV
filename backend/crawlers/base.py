"""Base crawler class and channel data model."""

from dataclasses import dataclass, field
from typing import List, Optional


@dataclass
class ChannelEntry:
    """A single channel entry parsed from a playlist."""
    name: str
    url: str
    group: str = "未分类"
    logo: str = ""
    source: str = ""  # Which source/crawler it came from


class BaseCrawler:
    """Base class for all source crawlers."""

    def __init__(self, name: str):
        self.name = name

    def crawl(self) -> List[ChannelEntry]:
        """Fetch and parse channels from the source. Override in subclasses."""
        raise NotImplementedError

    def __repr__(self) -> str:
        return f"<Crawler {self.name}>"
