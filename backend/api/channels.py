"""API routes for TV live streaming backend."""

import logging
import re
import time
from typing import Dict, List, Optional, Tuple
from urllib.parse import quote, urljoin

import requests
from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import Response, StreamingResponse
from pydantic import BaseModel

from ..database import Channel, SessionLocal, SourceCheckLog
from ..source_manager import SourceManager
from ..exporter import export_channels, detect_region

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api")
source_manager = SourceManager()

# Logo 内存缓存（减少重复请求，TTL=1小时）
_logo_cache: Dict[str, Tuple[bytes, str, float]] = {}


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
    region: str = "international"

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
    region: Optional[str] = Query(None, description="Filter by region: domestic or international"),
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
        result = [ch.to_dict() for ch in channels]
        if region:
            result = [ch for ch in result if ch.get("region") == region]
        # 国内优先排序：domestic(0) < international(1)，同类按 group + name
        # （以前靠重排主键 id 实现国内优先，但那会触发 UNIQUE 冲突并破坏
        #  SourceCheckLog.channel_id 引用，现已改为查询时排序）
        region_order = {"domestic": 0, "international": 1}
        result.sort(key=lambda ch: (
            region_order.get(ch.get("region"), 2),
            ch.get("group", ""),
            ch.get("name", ""),
        ))
        return result
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

        # 国内优先：按 group 中是否含国内特征排序
        region_order = {"domestic": 0, "international": 1}

        def _group_region(name: str) -> int:
            for ch in groups[name]:
                return region_order.get(ch.get("region"), 2)
            return 2

        sorted_groups = sorted(groups.items(), key=lambda kv: (
            _group_region(kv[0]),
            kv[0],
        ))
        result = [
            ChannelGroup(group=name, channels=ch_list)
            for name, ch_list in sorted_groups
        ]
        return result
    finally:
        db.close()


@router.get("/channels/regions")
def get_regions(
    visible_only: bool = Query(True, description="Only count visible channels"),
):
    """Get channel counts grouped by region (domestic / international)."""
    db = SessionLocal()
    try:
        query = db.query(Channel)
        if visible_only:
            query = query.filter(Channel.visible == True)

        channels = query.all()
        counts = {"domestic": 0, "international": 0}
        for ch in channels:
            r = detect_region(ch.group_name, ch.name)
            counts[r] = counts.get(r, 0) + 1
        return counts
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
def export_channel_data():
    """Export all visible channels as a static JSON file (for standalone APK use)."""
    data = export_channels()
    return data


@router.post("/purge")
def purge_dead_sources(min_failures: int = Query(3, description="Min consecutive failures to mark source as dead")):
    """Mark consistently dead sources as temporarily disabled.

    This helps reduce wasted health checks on broken sources.
    Sources with min_failures consecutive failures will be moved to dead_sources list.
    They will be excluded from health checks until recovered by next crawl.
    """
    result = source_manager.purge_dead_sources(min_failures=min_failures)
    return {
        "status": "completed",
        "sources_marked_dead": result["sources_marked_dead"],
        "channels_hidden": result["channels_hidden"],
    }


# ── Logo 代理 ──────────────────────────────────────────────

@router.get("/proxy/logo")
def proxy_logo(url: str = Query(..., description="Logo URL to proxy")):
    """Proxy a logo image to avoid CORS/blocking issues.

    Some logo hosts (e.g. live.fanmingming.cn) block direct browser requests.
    This endpoint fetches the logo server-side and returns it with proper CORS headers.
    With in-memory cache to reduce repeated requests.
    """
    # 检查缓存（TTL = 3600秒 = 1小时）
    now = time.time()
    if url in _logo_cache:
        cached_content, cached_type, expire_at = _logo_cache[url]
        if now < expire_at:
            return Response(
                content=cached_content,
                media_type=cached_type,
                headers={
                    "Cache-Control": "public, max-age=3600",
                    "Access-Control-Allow-Origin": "*",
                },
            )
        # 缓存过期，删除
        del _logo_cache[url]

    # 清理过期缓存（简单维护）
    if len(_logo_cache) > 500:
        expired_keys = [k for k, (_, _, exp) in _logo_cache.items() if exp < now]
        for k in expired_keys:
            del _logo_cache[k]

    try:
        resp = requests.get(
            url,
            timeout=8,
            headers={"User-Agent": "Mozilla/5.0 (TV) AppleWebKit/537.36"},
            allow_redirects=True,
        )
        if resp.status_code == 200 and resp.content:
            content_type = resp.headers.get("Content-Type", "image/png")
            # 写入缓存
            _logo_cache[url] = (resp.content, content_type, now + 3600)
            return Response(
                content=resp.content,
                media_type=content_type,
                headers={
                    "Cache-Control": "public, max-age=3600",
                    "Access-Control-Allow-Origin": "*",
                },
            )
        else:
            raise HTTPException(status_code=404, detail=f"Logo fetch failed: HTTP {resp.status_code}")
    except requests.Timeout:
        raise HTTPException(status_code=504, detail="Logo fetch timeout")
    except requests.RequestException as e:
        raise HTTPException(status_code=502, detail=f"Logo proxy error: {str(e)[:100]}")


# ── Stream proxy (m3u8 + ts) ─────────────────────────────

# 用于识别 m3u8 内容的 Content-Type 关键字
_M3U8_CONTENT_TYPE_KEYWORDS = ("mpegurl", "m3u8")


def _rewrite_uri_in_tag(tag_line: str, base_url: str) -> str:
    """Rewrite URI="..." attribute inside #EXT-X-KEY / #EXT-X-MAP tags.

    These tags carry the key/init-segment URL as URI="..." attribute.
    We need to route those through the proxy too, otherwise the browser
    will try to fetch the key/init segment directly from the upstream host
    and hit CORS again.
    """
    def _replace(match: "re.Match[str]") -> str:
        original = match.group(1)
        absolute = urljoin(base_url, original)
        proxied = f"/api/proxy/stream?url={quote(absolute, safe='')}"
        return f'URI="{proxied}"'

    return re.sub(r'URI="([^"]+)"', _replace, tag_line)


def _rewrite_m3u8(content: str, base_url: str) -> str:
    """Rewrite all URLs in an m3u8 playlist to go through /api/proxy/stream.

    - Lines that don't start with '#' are segment / sub-playlist URLs:
        * relative path (./1000.ts, 1000.ts)  → resolve against base_url
        * absolute path (/hls/61/1000.ts)     → resolve against base_url host
        * full URL (http://other/x.ts)        → use as-is
      All → /api/proxy/stream?url=<encoded absolute URL>
    - #EXT-X-KEY and #EXT-X-MAP tags: rewrite their URI="..." attribute
    - Other comment lines (#EXT-X-VERSION, #EXTINF, etc.): unchanged
    """
    lines = content.splitlines()
    rewritten: List[str] = []
    for line in lines:
        stripped = line.strip()
        if not stripped:
            rewritten.append(line)
            continue
        if stripped.startswith("#"):
            if stripped.startswith("#EXT-X-KEY") or stripped.startswith("#EXT-X-MAP"):
                rewritten.append(_rewrite_uri_in_tag(stripped, base_url))
            else:
                rewritten.append(line)
            continue
        # 段 / 子 playlist URL 行 → 改为代理 URL
        absolute_url = urljoin(base_url, stripped)
        proxied = f"/api/proxy/stream?url={quote(absolute_url, safe='')}"
        rewritten.append(proxied)

    out = "\n".join(rewritten)
    # 保留末尾换行（某些播放器需要）
    if content.endswith("\n"):
        out += "\n"
    return out


@router.get("/proxy/stream")
async def proxy_stream(url: str = Query(..., description="Stream URL (m3u8 or ts) to proxy")):
    """Proxy an HLS stream to bypass browser CORS / ORB restrictions.

    Async implementation using httpx to avoid blocking the event loop
    (synchronous requests.get would exhaust the thread pool when multiple
    ts segments are being proxied concurrently).

    Behavior:
    - m3u8 manifest: fetch fully, rewrite all internal URLs to also go
      through /api/proxy/stream, return rewritten text.
    - ts / binary segment: stream through chunk-by-chunk.
    - Returns 502 Bad Gateway on upstream HTTP errors.
    - Returns 504 Gateway Timeout on connection timeout.
    """
    import httpx

    cors_headers = {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "GET, OPTIONS",
        "Access-Control-Allow-Headers": "*",
        "Cache-Control": "no-cache, no-store",
    }

    try:
        async with httpx.AsyncClient(timeout=10, follow_redirects=True) as client:
            resp = await client.get(
                url,
                headers={"User-Agent": "Mozilla/5.0 (TV) AppleWebKit/537.36"},
            )
    except httpx.TimeoutException:
        raise HTTPException(status_code=504, detail="Stream fetch timeout")
    except httpx.HTTPError as e:
        raise HTTPException(status_code=502, detail=f"Stream proxy error: {str(e)[:100]}")

    if resp.status_code != 200:
        raise HTTPException(status_code=502, detail=f"Upstream returned HTTP {resp.status_code}")

    upstream_ct = resp.headers.get("Content-Type", "").lower()
    url_path = url.split("?", 1)[0].lower()
    content = resp.content

    is_m3u8 = (
        b"#EXTM3U" in content[:64]
        or any(kw in upstream_ct for kw in _M3U8_CONTENT_TYPE_KEYWORDS)
        or url_path.endswith(".m3u8")
        or url_path.endswith(".m3u")
    )

    if is_m3u8:
        try:
            text = content.decode("utf-8")
        except UnicodeDecodeError:
            text = content.decode("latin-1", errors="replace")
        rewritten = _rewrite_m3u8(text, url)
        return Response(
            content=rewritten.encode("utf-8"),
            media_type="application/vnd.apple.mpegurl",
            headers=cors_headers,
        )

    # ts 分片：强制 video/mp2t（上游可能返回 application/octet-stream 等，
    # 带 charset=utf-8 会导致 hls.js 误判为文本）
    seg_ct = "video/mp2t"
    return Response(content=content, media_type=seg_ct, headers=cors_headers)
