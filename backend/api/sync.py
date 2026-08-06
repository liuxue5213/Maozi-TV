"""Cloud sync API: favorites & play history backup/restore.

Simple anonymous sync keyed by client_id (no accounts needed for pilot).
Endpoints:
- POST /api/sync/save   {client_id, favorites: [...], history: [...]}
- GET  /api/sync/load?client_id=xxx
"""

import logging
from datetime import datetime, timezone

from fastapi import APIRouter
from pydantic import BaseModel
from typing import List

from ..database import Base, SessionLocal
from sqlalchemy import Column, Integer, String, Text, DateTime

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api")


# ── Model（延迟声明，保证 database.py 已初始化）──────────
class SyncData(Base):
    __tablename__ = "sync_data"

    id = Column(Integer, primary_key=True, autoincrement=True)
    client_id = Column(String(64), nullable=False, unique=True, index=True)
    favorites = Column(Text, default="[]")   # JSON array of channel ids
    history = Column(Text, default="[]")     # JSON array of channel ids
    updated_at = Column(DateTime, default=lambda: datetime.now(timezone.utc),
                        onupdate=lambda: datetime.now(timezone.utc))


class SyncIn(BaseModel):
    client_id: str
    favorites: List[int] = []
    history: List[int] = []


@router.post("/sync/save")
def sync_save(data: SyncIn):
    """Save favorites & history for a client_id (upsert)."""
    if not data.client_id or len(data.client_id) > 64:
        from fastapi import HTTPException
        raise HTTPException(status_code=400, detail="invalid client_id")

    db = SessionLocal()
    try:
        row = db.query(SyncData).filter(SyncData.client_id == data.client_id).first()
        if row is None:
            row = SyncData(client_id=data.client_id)
            db.add(row)

        import json
        row.favorites = json.dumps(data.favorites[:500])  # 上限保护
        row.history = json.dumps(data.history[:200])
        row.updated_at = datetime.now(timezone.utc)
        db.commit()
        return {"status": "ok"}
    finally:
        db.close()


@router.get("/sync/load")
def sync_load(client_id: str):
    """Restore favorites & history for a client_id."""
    db = SessionLocal()
    try:
        row = db.query(SyncData).filter(SyncData.client_id == client_id).first()
        if row is None:
            return {"favorites": [], "history": [], "updated_at": None}

        import json
        return {
            "favorites": json.loads(row.favorites or "[]"),
            "history": json.loads(row.history or "[]"),
            "updated_at": row.updated_at.isoformat() if row.updated_at else None,
        }
    finally:
        db.close()
