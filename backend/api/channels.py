"""API routes for TV live streaming backend."""

import logging
from typing import Dict, List, Optional

from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

from ..database import Channel, SessionLocal, SourceCheckLog
from ..source_manager import SourceManager
from ..exporter import export_channels

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api")
source_manager = SourceManager()


# ── Pydantic Schemas ──────────────────────────────────────────

class ChannelOut(BaseModel):
    id: int  # noqa: VNE003
    name: str
    group: str
    logo: str
    url: Optional[str] = None
    sources: List[str]
    active_source_index: int
    healthy: bool
    last_response_time: Optional[float] = None
    visible: bool

    class Config:
        from_attributes = True


class ChannelGroup(BaseModel):
    group: str
    channels: List[ChannelOut]


class SummaryOut(BaseModel):
    channels_total: int
    channels_visible: int
    channels_healthy: int
    channels_dead: int
    last_cycle: Optional[str] = None


# ── Endpoints ─────────────────────────────────────────────────

@router.get("/channels", response_model=List[ChannelOut])
def list_channels(
    group: Optional[str] = Query(None, description="Filter by group name"),
    visible_only: bool = Query(True, description="Only show visible channels"),
    healthy_only: bool = Query(False, description="Only show healthy channels"),
):
    """List all channels, with optional filters."""
    db = SessionLocal()
    try:
        query = db.query(Channel)
        if visible_only:
            query = query.filter(Channel.visible == True)
        if healthy_only:
            query = query.filter(Channel.healthy == True)
        if group:
            query = query.filter(Channel.group_name == group)

        channels = query.order_by(Channel.group_name, Channel.name).all()
        return [ch.to_dict() for ch in channels]
    finally:
        db.close()


@router.get("/groups", response_model=List[ChannelGroup])
def list_groups(
    visible_only: bool = Query(True, description="Only include visible channels"),
):
    """List all channels grouped by category."""
    db = SessionLocal()
    try:
        query = db.query(Channel)
        if visible_only:
            query = query.filter(Channel.visible == True)

        channels = query.order_by(Channel.group_name, Channel.name).all()

        # Group by group_name
        groups: Dict[str, List[dict]] = {}
        for ch in channels:
            d = ch.to_dict()
            if d["group"] not in groups:
                groups[d["group"]] = []
            groups[d["group"]].append(d)

        result = [
            ChannelGroup(group=name, channels=ch_list)
            for name, ch_list in groups.items()
        ]
        return result
    finally:
        db.close()


@router.get("/channels/{channel_id}", response_model=ChannelOut)
def get_channel(channel_id: int):
    """Get a single channel by ID."""
    db = SessionLocal()
    try:
        ch = db.query(Channel).filter(Channel.id == channel_id).first()
        if not ch:
            raise HTTPException(status_code=404, detail="Channel not found")
        return ch.to_dict()
    finally:
        db.close()


@router.post("/channels/{channel_id}/switch")
def switch_source(channel_id: int):
    """Manually switch to the next backup source for a channel."""
    db = SessionLocal()
    try:
        ch = db.query(Channel).filter(Channel.id == channel_id).first()
        if not ch:
            raise HTTPException(status_code=404, detail="Channel not found")

        new_url = ch.switch_to_next_source()
        db.commit()

        if new_url:
            logger.info("Manual switch for '%s' -> source #%d", ch.name, ch.active_source_index)
            return {"status": "switched", "new_source_index": ch.active_source_index, "url": new_url}
        else:
            return {"status": "error", "message": "No sources available"}
    finally:
        db.close()


@router.post("/channels/{channel_id}/check")
def check_single_channel(channel_id: int):
    """Force a health check on a single channel."""
    ch = source_manager.check_single_channel(channel_id)
    if ch is None:
        raise HTTPException(status_code=404, detail="Channel not found")
    return {"status": "checked", "healthy": ch.healthy, "active_source_index": ch.active_source_index}


@router.post("/crawl")
def trigger_crawl():
    """Manually trigger a full crawl cycle."""
    summary = source_manager.run_full_cycle()
    return {"status": "completed", "summary": summary}


@router.post("/check-all")
def trigger_check_all():
    """Manually trigger health check on all channels."""
    checked, replaced, hidden = source_manager.check_and_replace_all()
    return {
        "status": "completed",
        "checked": checked,
        "replaced": replaced,
        "hidden": hidden,
    }


@router.get("/summary", response_model=SummaryOut)
def get_summary():
    """Get a summary of the current system state."""
    db = SessionLocal()
    try:
        total = db.query(Channel).count()
        visible = db.query(Channel).filter(Channel.visible == True).count()
        healthy = db.query(Channel).filter(Channel.healthy == True, Channel.visible == True).count()
        dead = visible - healthy

        # Get last check log time
        last_log = db.query(SourceCheckLog).order_by(SourceCheckLog.checked_at.desc()).first()

        return SummaryOut(
            channels_total=total,
            channels_visible=visible,
            channels_healthy=healthy,
            channels_dead=dead,
            last_cycle=last_log.checked_at.isoformat() if last_log else None,
        )
    finally:
        db.close()


@router.get("/export")
def export_channel_data(
    pretty: bool = Query(True, description="Pretty-print JSON"),
):
    """Export all visible channels as a static JSON file (for standalone APK use)."""
    data = export_channels()
    return data
