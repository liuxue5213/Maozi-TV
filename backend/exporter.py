"""Channel data exporter — exports channels to static JSON for standalone APK use.

The exporter serialises all visible channels into a versioned JSON file.
This file can be:
1. Served via the REST API (`GET /api/export`)
2. Pushed to GitHub Pages / any CDN for the APK to fetch directly
"""

import json
import logging
import os
import re
import time
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from .database import Channel, SessionLocal

logger = logging.getLogger(__name__)

# Current export format version — bump when the schema changes
EXPORT_VERSION = 1

# ── Region detection ──────────────────────────────────────

# Domestic (国内) group keywords
_DOMESTIC_KEYWORDS = [
    "央视", "卫视", "地方", "港澳", "直播中国", "央视频道", "卫视频道",
    "电影频道", "纪录频道", "儿童频道", "综艺频道", "体育频道", "音乐频道", "新闻",
]
# Groups that are ambiguous — fall back to channel name
_AMBIGUOUS_GROUPS = {"未分类", "其他", "Undefined"}
# CJK character regex
_CJK_RE = re.compile(r"[\u4e00-\u9fff]")


def detect_region(group: str, name: str = "") -> str:
    """Return "domestic" or "international" based on group/name heuristics."""
    # 1. Check domestic keywords in group
    for kw in _DOMESTIC_KEYWORDS:
        if kw in group:
            return "domestic"
    # 2. Group contains "频道"
    if "频道" in group:
        return "domestic"
    # 3. Group contains CJK characters (but not ambiguous)
    if group not in _AMBIGUOUS_GROUPS and _CJK_RE.search(group):
        return "domestic"
    # 4. Ambiguous groups: check channel name for CJK
    if group in _AMBIGUOUS_GROUPS and name and _CJK_RE.search(name):
        return "domestic"
    # 5. Default: international
    return "international"


def export_channels(
    output_path: Optional[str] = None,
    include_dead: bool = False,
) -> Dict[str, Any]:
    """Export all (visible) channels to a versioned JSON dict.

    Args:
        output_path: If given, write the JSON to this file path.
        include_dead: Whether to include unhealthy / hidden channels.

    Returns:
        The exported JSON-compatible dict.
    """
    db = SessionLocal()
    try:
        query = db.query(Channel)
        if not include_dead:
            query = query.filter(Channel.visible == True)

        channels = query.order_by(Channel.group_name, Channel.name).all()

        channel_list: List[Dict[str, Any]] = []
        region_counts: Dict[str, int] = {"domestic": 0, "international": 0}
        for ch in channels:
            sources = ch.get_sources()
            active_url = ch.get_active_source()
            region = detect_region(ch.group_name, ch.name)
            region_counts[region] = region_counts.get(region, 0) + 1
            channel_list.append({
                "id": ch.id,
                "name": ch.name,
                "group": ch.group_name,
                "logo": ch.logo or "",
                "url": active_url or "",
                "sources": sources,
                "healthy": ch.healthy if ch.visible else False,
                "region": region,
            })

        payload = {
            "version": EXPORT_VERSION,
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "generated_at_ts": int(time.time()),
            "total": len(channel_list),
            "regions": region_counts,
            "channels": channel_list,
        }

        if output_path:
            os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
            with open(output_path, "w", encoding="utf-8") as f:
                json.dump(payload, f, ensure_ascii=False, indent=2)
            logger.info(
                "Exported %d channels to %s (version %d)",
                len(channel_list), output_path, EXPORT_VERSION,
            )

        return payload

    finally:
        db.close()
