"""Telemetry (event tracking) and channel hot-ranking API.

Endpoints:
- POST /api/events         客户端上报轻量埋点事件
- GET  /api/analytics/hot  频道热力榜（按播放次数排序）
- GET  /api/analytics/summary  运营数据概览（DAU、事件量）
"""

import logging
from datetime import datetime, timedelta, timezone
from typing import Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from ..database import AppEvent, ChannelPlayStats, SessionLocal

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api")


# ── Schemas ─────────────────────────────────────────────────

class EventIn(BaseModel):
    event_type: str          # app_start / play_channel / fav / unfav / crash / settings
    channel_id: Optional[int] = None
    channel_name: str = ""
    client_id: str = ""      # 匿名设备标识
    extra: str = ""          # 可选 JSON 字符串


# ── Endpoints ───────────────────────────────────────────────

@router.post("/events")
def post_event(event: EventIn):
    """Record a client telemetry event and update play stats."""
    # 校验事件类型，防止垃圾数据刷库
    allowed = {"app_start", "play_channel", "fav", "unfav", "crash", "settings", "search"}
    if event.event_type not in allowed:
        raise HTTPException(status_code=400, detail=f"unknown event_type: {event.event_type}")

    db = SessionLocal()
    try:
        ev = AppEvent(
            event_type=event.event_type,
            channel_id=event.channel_id,
            channel_name=event.channel_name[:128],
            client_id=event.client_id[:64],
            extra=event.extra[:512],
        )
        db.add(ev)

        # play_channel → 更新频道热力榜
        if event.event_type == "play_channel" and event.channel_id is not None:
            stats = db.query(ChannelPlayStats).filter(
                ChannelPlayStats.channel_id == event.channel_id
            ).first()
            if stats is None:
                stats = ChannelPlayStats(
                    channel_id=event.channel_id,
                    channel_name=event.channel_name[:128],
                    play_count=0,
                )
                db.add(stats)
            stats.play_count += 1
            stats.last_played_at = datetime.now(timezone.utc)

        db.commit()
        return {"status": "ok", "id": ev.id}
    finally:
        db.close()


@router.get("/analytics/hot")
def hot_channels(limit: int = 20):
    """Top played channels by play count."""
    db = SessionLocal()
    try:
        rows = db.query(ChannelPlayStats) \
            .order_by(ChannelPlayStats.play_count.desc()) \
            .limit(max(1, min(limit, 100))) \
            .all()
        return [
            {
                "channel_id": r.channel_id,
                "name": r.channel_name,
                "play_count": r.play_count,
                "last_played_at": r.last_played_at.isoformat() if r.last_played_at else None,
            }
            for r in rows
        ]
    finally:
        db.close()


@router.get("/analytics/summary")
def analytics_summary(days: int = 7):
    """Operational overview: active clients, event volume, top channel."""
    db = SessionLocal()
    try:
        since = datetime.now(timezone.utc) - timedelta(days=max(1, min(days, 30)))

        total_events = db.query(AppEvent).filter(AppEvent.created_at >= since).count()
        active_clients = db.query(AppEvent.client_id) \
            .filter(AppEvent.created_at >= since, AppEvent.client_id != "") \
            .distinct().count()

        top = db.query(ChannelPlayStats) \
            .order_by(ChannelPlayStats.play_count.desc()).first()

        return {
            "period_days": days,
            "total_events": total_events,
            "active_clients": active_clients,
            "top_channel": {
                "name": top.channel_name,
                "play_count": top.play_count,
            } if top else None,
        }
    finally:
        db.close()
