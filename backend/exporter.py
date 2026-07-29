"""Channel data exporter — exports channels to static JSON for standalone APK use.

The exporter serialises all visible channels into a versioned JSON file.
This file can be:
1. Served via the REST API (`GET /api/export`)
2. Pushed to GitHub Pages / any CDN for the APK to fetch directly
"""

import json
import logging
import os
import time
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from .database import Channel, SessionLocal

logger = logging.getLogger(__name__)

# Current export format version — bump when the schema changes
EXPORT_VERSION = 1


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
        for ch in channels:
            sources = ch.get_sources()
            active_url = ch.get_active_source()
            channel_list.append({
                "id": ch.id,
                "name": ch.name,
                "group": ch.group_name,
                "logo": ch.logo or "",
                "url": active_url or "",
                "sources": sources,
                "healthy": ch.healthy if ch.visible else False,
            })

        payload = {
            "version": EXPORT_VERSION,
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "generated_at_ts": int(time.time()),
            "total": len(channel_list),
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
