"""Database models and session management using SQLAlchemy + SQLite."""

import json
import logging
from datetime import datetime, timezone
from typing import Dict, List, Optional

from sqlalchemy import (
    Column, DateTime, Float, Integer, String, Text, create_engine, JSON, Boolean
)
from sqlalchemy.orm import declarative_base, sessionmaker

from .config import config

logger = logging.getLogger(__name__)

engine = create_engine(
    f"sqlite:///{config.db_path}",
    connect_args={"check_same_thread": False},
    echo=False,
)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()


class Channel(Base):
    """A TV channel with multiple backup stream sources."""

    __tablename__ = "channels"

    id = Column(Integer, primary_key=True, autoincrement=True)
    # Channel name, e.g. "CCTV-1", "湖南卫视"
    name = Column(String(128), nullable=False, index=True)
    # Group/category, e.g. "央视", "卫视", "地方台"
    group_name = Column(String(64), default="未分类")
    # Logo URL
    logo = Column(String(512), default="")
    # JSON list of source URLs, ordered by preference
    sources = Column(Text, default="[]")
    # JSON list of dead source URLs (temporarily excluded from health checks)
    dead_sources = Column(Text, default="[]")
    # Index of the currently active source in the sources list
    active_source_index = Column(Integer, default=0)
    # Whether the active source is currently healthy
    healthy = Column(Boolean, default=True)
    # Consecutive failure count for current source
    consecutive_failures = Column(Integer, default=0)
    # Timestamp of last health check
    last_checked = Column(DateTime, nullable=True)
    # Response time of last successful check (seconds)
    last_response_time = Column(Float, nullable=True)
    # Whether this channel is visible (hidden after all sources dead for too long)
    visible = Column(Boolean, default=True)
    # When the channel was first created
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))
    # When the channel was last updated
    updated_at = Column(DateTime, default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc))

    def get_sources(self) -> List[str]:
        return json.loads(self.sources) if self.sources else []

    def set_sources(self, sources: List[str]) -> None:
        self.sources = json.dumps(sources)
        # Reset index if it's out of bounds
        current = self.active_source_index or 0
        if current >= len(sources):
            self.active_source_index = 0

    def get_dead_sources(self) -> List[str]:
        """Get the list of temporarily disabled sources."""
        return json.loads(self.dead_sources) if self.dead_sources else []

    def set_dead_sources(self, sources: List[str]) -> None:
        """Set the list of temporarily disabled sources."""
        self.dead_sources = json.dumps(sources)

    def get_active_source(self) -> Optional[str]:
        sources = self.get_sources()
        if not sources:
            return None
        idx = min(self.active_source_index, len(sources) - 1)
        return sources[idx]

    def switch_to_next_source(self) -> Optional[str]:
        """Switch to the next healthy source. Returns the new source URL or None."""
        sources = self.get_sources()
        if not sources:
            return None
        new_idx = (self.active_source_index + 1) % len(sources)
        self.active_source_index = new_idx
        self.consecutive_failures = 0
        return sources[new_idx]

    def to_dict(self) -> Dict:
        from .exporter import detect_region
        return {
            "id": self.id,
            "name": self.name,
            "group": self.group_name,
            "logo": self.logo,
            "url": self.get_active_source(),
            "sources": self.get_sources(),
            "active_source_index": self.active_source_index,
            "healthy": self.healthy,
            "last_response_time": self.last_response_time,
            "visible": self.visible,
            "region": detect_region(self.group_name, self.name),
        }


class SourceCheckLog(Base):
    """Log of each health check result for debugging."""

    __tablename__ = "source_check_log"

    id = Column(Integer, primary_key=True, autoincrement=True)
    channel_id = Column(Integer, nullable=False, index=True)
    source_index = Column(Integer, nullable=False)
    source_url = Column(String(1024), nullable=False)
    healthy = Column(Boolean, default=False)
    response_time = Column(Float, nullable=True)
    status_code = Column(Integer, nullable=True)
    error_message = Column(String(512), default="")
    checked_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))


def init_db():
    """Create all tables."""
    Base.metadata.create_all(bind=engine)
    logger.info("Database initialized at %s", config.db_path)


def get_db():
    """Context manager for database sessions."""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
