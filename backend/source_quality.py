"""Source quality scoring and persistence helpers."""

from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import Dict, Iterable, List, Tuple

from sqlalchemy import text
from sqlalchemy.orm import Session

from .database import SourceQualityStats


_IP_URL_RE = re.compile(r"^https?://\d+\.\d+\.\d+\.\d+")
_PORT_RE = re.compile(r":(\d+)")


def heuristic_score(url: str) -> float:
    """Return a static URL-shape score in the 0-100 range."""
    score = 35.0
    lower = (url or "").lower()

    if lower.startswith("https://"):
        score += 10
    elif lower.startswith("http://"):
        score += 5

    if any(host in lower for host in (
        ".alicdn.com", ".aliyun.com", ".tencentcdn.com", ".qcdn.com",
        ".hwcdn.com", ".cdn163.com", "jsdelivr.net", "epg.pw",
    )):
        score += 15

    if ".m3u8" in lower:
        score += 10
    elif ".flv" in lower:
        score += 5

    if not _PORT_RE.search(lower):
        score += 5
    if len(url) < 100:
        score += 5
    if not _IP_URL_RE.search(lower):
        score += 8
    if any(suffix in lower for suffix in (".cn", ".com", ".net")):
        score += 3
    if any(term in lower for term in ("test", "temp", "localhost")):
        score -= 20

    return max(0.0, min(100.0, score))


def compute_score(stat: SourceQualityStats | None, url: str) -> float:
    """Blend static URL heuristics with observed backend/client quality."""
    score = heuristic_score(url)
    if not stat:
        return round(score, 1)

    success_count = stat.success_count or 0
    failure_count = stat.failure_count or 0
    playback_failure_count = stat.playback_failure_count or 0
    total = success_count + failure_count
    if total:
        success_rate = success_count / total
        score = score * 0.45 + success_rate * 100 * 0.55

    if stat.avg_response_time is not None:
        if stat.avg_response_time <= 1:
            score += 12
        elif stat.avg_response_time <= 3:
            score += 6
        elif stat.avg_response_time >= 8:
            score -= 15

    score -= min(playback_failure_count * 6, 30)
    score -= min(failure_count * 1.5, 25)
    return round(max(0.0, min(100.0, score)), 1)


def get_quality_map(db: Session, urls: Iterable[str]) -> Dict[str, SourceQualityStats]:
    unique_urls = list(dict.fromkeys(u for u in urls if u))
    if not unique_urls:
        return {}
    rows = db.query(SourceQualityStats).filter(SourceQualityStats.source_url.in_(unique_urls)).all()
    return {row.source_url: row for row in rows}


def _ensure_stat(db: Session, url: str) -> SourceQualityStats:
    db.execute(
        text(
            "INSERT OR IGNORE INTO source_quality_stats "
            "(source_url, score, success_count, failure_count, playback_failure_count, last_error, updated_at) "
            "VALUES (:url, :score, 0, 0, 0, '', :updated_at)"
        ),
        {
            "url": url,
            "score": heuristic_score(url),
            "updated_at": datetime.now(timezone.utc),
        },
    )
    return db.query(SourceQualityStats).filter(SourceQualityStats.source_url == url).one()


def rank_sources(db: Session, urls: List[str]) -> Tuple[List[str], List[dict]]:
    """Return URLs sorted by quality plus serialisable score details."""
    quality_map = get_quality_map(db, urls)
    details = []
    for idx, url in enumerate(urls):
        stat = quality_map.get(url)
        score = compute_score(stat, url)
        details.append({
            "url": url,
            "score": score,
            "rank": idx + 1,
            "success_count": stat.success_count if stat else 0,
            "failure_count": stat.failure_count if stat else 0,
            "playback_failure_count": stat.playback_failure_count if stat else 0,
            "avg_response_time": stat.avg_response_time if stat else None,
            "last_error": stat.last_error if stat else "",
        })

    details.sort(key=lambda item: (-item["score"], item["rank"]))
    ranked = [item["url"] for item in details]
    for idx, item in enumerate(details, start=1):
        item["rank"] = idx
    return ranked, details


def record_check_result(
    db: Session,
    url: str,
    healthy: bool,
    response_time: float | None = None,
    status_code: int | None = None,
    error: str = "",
) -> SourceQualityStats:
    _ensure_stat(db, url)
    now = datetime.now(timezone.utc)
    safe_error = (error or "")[:512]
    response_expr = (
        "CASE WHEN avg_response_time IS NULL THEN :response_time "
        "ELSE avg_response_time * 0.75 + :response_time * 0.25 END"
        if response_time is not None else "avg_response_time"
    )
    count_column = "success_count" if healthy else "failure_count"
    db.execute(
        text(
            f"UPDATE source_quality_stats SET "
            f"{count_column} = COALESCE({count_column}, 0) + 1, "
            f"avg_response_time = {response_expr}, "
            "last_status_code = :status_code, "
            "last_error = :last_error, "
            "last_checked_at = :now, "
            "updated_at = :now "
            "WHERE source_url = :url"
        ),
        {
            "url": url,
            "response_time": response_time,
            "status_code": status_code,
            "last_error": safe_error,
            "now": now,
        },
    )
    stat = db.query(SourceQualityStats).filter(SourceQualityStats.source_url == url).one()
    stat.score = compute_score(stat, url)
    return stat



def record_playback_failure(db: Session, url: str, error: str = "") -> SourceQualityStats:
    _ensure_stat(db, url)
    db.execute(
        text(
            "UPDATE source_quality_stats SET "
            "playback_failure_count = COALESCE(playback_failure_count, 0) + 1, "
            "last_error = :last_error, "
            "updated_at = :now "
            "WHERE source_url = :url"
        ),
        {
            "url": url,
            "last_error": (error or "client playback failure")[:512],
            "now": datetime.now(timezone.utc),
        },
    )
    stat = db.query(SourceQualityStats).filter(SourceQualityStats.source_url == url).one()
    stat.score = compute_score(stat, url)
    return stat
