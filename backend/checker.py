"""Source health checker.

Probes live stream URLs (m3u8 / HTTP) to verify availability, measure response time,
and detect codec compatibility (audio must be AAC/MP3/OPUS for browser playback).
Uses HTTP HEAD/GET with range requests to avoid downloading full segments.
"""

import json
import logging
import os
import shutil
import subprocess
import tempfile
import threading
import time
from datetime import datetime, timezone
from typing import Optional, Tuple

import requests

logger = logging.getLogger(__name__)

# 浏览器支持的音频编码（MP2 不支持！）
BROWSER_AUDIO_CODECS = {"aac", "mp3", "opus", "vorbis", "flac", "pcm_s16le", "pcm_s24le"}
# 浏览器支持的视频编码（MPEG-2 不支持！）
BROWSER_VIDEO_CODECS = {"h264", "hevc", "vp8", "vp9", "av1"}

# 缓存服务器编码检测结果（避免重复探测）
# key: host, value: (is_compat, video_codec, audio_codec, expire_at)
_host_codec_cache: dict = {}
_host_codec_lock = threading.Lock()


def _detect_codec_with_ffprobe(ts_bytes: bytes) -> Tuple[bool, str, str]:
    """用 ffprobe 检测 TS 分片的音视频编码。

    返回: (is_compatible, video_codec, audio_codec)
    """
    if not shutil.which("ffprobe"):
        return True, "unknown", "unknown"  # 无 ffprobe 时不检测

    tmp = None
    try:
        tmp = tempfile.NamedTemporaryFile(suffix=".ts", delete=False)
        tmp.write(ts_bytes)
        tmp.close()

        result = subprocess.run(
            [
                "ffprobe", "-v", "quiet",
                "-print_format", "json",
                "-show_streams",
                tmp.name,
            ],
            capture_output=True, text=True, timeout=10,
        )
        if result.returncode != 0:
            return True, "unknown", "unknown"

        data = json.loads(result.stdout)
        video_codec = ""
        audio_codec = ""
        for stream in data.get("streams", []):
            if stream.get("codec_type") == "video" and not video_codec:
                video_codec = stream.get("codec_name", "")
            elif stream.get("codec_type") == "audio" and not audio_codec:
                audio_codec = stream.get("codec_name", "")

        # 音频和视频都需要兼容
        audio_ok = audio_codec in BROWSER_AUDIO_CODECS or audio_codec == ""
        video_ok = video_codec in BROWSER_VIDEO_CODECS or video_codec == ""
        is_compat = audio_ok and video_ok
        return is_compat, video_codec, audio_codec
    except Exception as e:
        logger.debug("ffprobe error: %s", e)
        return True, "unknown", "unknown"
    finally:
        if tmp:
            try:
                os.unlink(tmp.name)
            except OSError:
                pass


def _cleanup_codec_cache():
    """清理过期缓存条目，限制缓存大小（线程安全）"""
    now = time.time()
    with _host_codec_lock:
        expired = [k for k, (_, _, _, exp) in _host_codec_cache.items() if exp < now]
        for k in expired:
            del _host_codec_cache[k]
        # 限制最多缓存 200 个站点
        if len(_host_codec_cache) > 200:
            # 按过期时间排序，删除最老的
            sorted_items = sorted(_host_codec_cache.items(), key=lambda x: x[1][3])
            for k, _ in sorted_items[:len(_host_codec_cache) - 200]:
                del _host_codec_cache[k]


def check_source_codec(
    url: str,
    timeout: int = 15,
) -> Tuple[bool, str, str]:
    """检测流的编码是否浏览器兼容。

    对 m3u8 URL：下载一个 TS 分片，用 ffprobe 检测编码。
    对非 m3u8 URL：返回 True（无法检测时默认兼容）。

    返回: (is_compatible, video_codec, audio_codec)
    """
    from urllib.parse import urlparse

    # 只对 m3u8 流做编码检测
    if not (url.endswith((".m3u8", ".m3u")) or "m3u8" in url):
        return True, "unknown", "unknown"

    host = urlparse(url).hostname or ""

    # 清理过期缓存
    _cleanup_codec_cache()

    # 检查缓存（返回真实编码信息）
    now = time.time()
    with _host_codec_lock:
        if host in _host_codec_cache:
            cached_ok, cached_video, cached_audio, cached_expire = _host_codec_cache[host]
            if now < cached_expire:
                return cached_ok, cached_video, cached_audio

    try:
        # 1. 获取 m3u8 内容
        resp = requests.get(url, timeout=8, headers={
            "User-Agent": "Mozilla/5.0 (TV) AppleWebKit/537.36",
        })
        if resp.status_code != 200:
            return True, "unknown", "unknown"

        text = resp.text
        if not text.startswith("#EXTM3U"):
            return True, "unknown", "unknown"

        # 2. 找到第一个 TS 分片 URL
        base_url = url.rsplit("/", 1)[0] + "/"
        ts_url = None
        for line in text.splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                ts_url = line
                break

        if not ts_url:
            return True, "unknown", "unknown"

        # 构造绝对 URL
        if ts_url.startswith("http"):
            absolute_ts = ts_url
        elif ts_url.startswith("/"):
            from urllib.parse import urlparse as up
            parsed = up(url)
            absolute_ts = f"{parsed.scheme}://{parsed.netloc}{ts_url}"
        else:
            absolute_ts = base_url + ts_url

        # 3. 下载 TS 分片（前 2MB 足够检测编码）
        ts_resp = requests.get(absolute_ts, timeout=timeout, stream=True, headers={
            "User-Agent": "Mozilla/5.0 (TV) AppleWebKit/537.36",
            "Range": "bytes=0-2097151",
        })
        ts_data = ts_resp.raw.read(2 * 1024 * 1024)
        ts_resp.close()

        if len(ts_data) < 1024:
            return True, "unknown", "unknown"

        # 4. ffprobe 检测
        is_compat, v_codec, a_codec = _detect_codec_with_ffprobe(ts_data)

        # 缓存结果（30 分钟）
        with _host_codec_lock:
            _host_codec_cache[host] = (is_compat, v_codec, a_codec, now + 1800)

        logger.info("Codec check %s: video=%s, audio=%s, compatible=%s",
                     host, v_codec, a_codec, is_compat)
        return is_compat, v_codec, a_codec

    except Exception as e:
        logger.debug("Codec check failed for %s: %s", url[:80], e)
        return True, "unknown", "unknown"


def check_source(
    url: str,
    timeout: int = 10,
) -> Tuple[bool, Optional[float], Optional[int], str]:
    """Check if a stream URL is healthy.

    Strategy:
    1. Try HEAD first (fastest).
    2. If HEAD fails, try GET with a small range (Range: bytes=0-1).
    3. For m3u8 URLs, also try fetching the playlist content and parse it.

    Returns:
        (is_healthy, response_time_seconds, status_code_or_None, error_message)
    """
    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/120.0.0.0 Safari/537.36"
        ),
        "Accept": "*/*",
    }

    start = time.monotonic()
    error_msg = ""

    # Strategy 1: HEAD request
    try:
        resp = requests.head(url, headers=headers, timeout=timeout, allow_redirects=True)
        elapsed = time.monotonic() - start
        status = resp.status_code

        if 200 <= status < 400:
            logger.debug("HEAD ok (%.2fs, %d) for %s", elapsed, status, url[:80])
            return True, elapsed, status, ""

        # For 4xx/5xx, still try GET (some servers reject HEAD)
        if status in (400, 405, 501):
            error_msg = f"HEAD returned {status}"
            # Fall through to GET
        else:
            error_msg = f"HEAD returned {status}"
            return False, elapsed, status, error_msg

    except requests.exceptions.Timeout:
        error_msg = "HEAD timeout"
    except requests.exceptions.ConnectionError:
        error_msg = "HEAD connection error"
    except Exception as e:
        error_msg = f"HEAD error: {e}"

    # Strategy 2: GET with Range header (partial download)
    try:
        get_headers = {**headers, "Range": "bytes=0-1023"}
        resp = requests.get(url, headers=get_headers, timeout=timeout, stream=True)
        elapsed = time.monotonic() - start
        status = resp.status_code

        # Accept both 200 (full response) and 206 (partial content)
        if status in (200, 206):
            # Read a small chunk to verify there's actually data
            chunk = resp.raw.read(256)
            resp.close()
            if chunk:
                logger.debug("GET+Range ok (%.2fs, %d, %d bytes) for %s",
                             elapsed, status, len(chunk), url[:80])
                return True, elapsed, status, ""
            else:
                error_msg = "empty response body"
                return False, elapsed, status, error_msg

        error_msg = f"GET returned {status}"

    except requests.exceptions.Timeout:
        error_msg = "GET timeout"
    except requests.exceptions.ConnectionError:
        error_msg = "GET connection error"
    except Exception as e:
        error_msg = f"GET error: {e}"

    # Strategy 3: For m3u8 URLs, try full GET (some servers require this)
    if url.endswith((".m3u8", ".m3u")) or "m3u8" in url:
        try:
            resp = requests.get(url, headers=headers, timeout=timeout)
            elapsed = time.monotonic() - start
            status = resp.status_code

            if status == 200 and len(resp.text) > 20:
                # Verify it actually looks like an m3u8 playlist
                text = resp.text.strip()
                if text.startswith("#EXTM3U") or text.startswith("#EXTINF"):
                    logger.debug("m3u8 GET ok (%.2fs, %d, %d bytes) for %s",
                                 elapsed, status, len(text), url[:80])
                    return True, elapsed, status, ""
                else:
                    # Got a 200 but content doesn't look like a playlist
                    error_msg = f"content doesn't look like m3u8 (starts with: {text[:50]})"
                    return False, elapsed, status, error_msg

            error_msg = f"m3u8 GET: status={status}, len={len(resp.text)}" if status != 200 else "m3u8 GET: empty response"

        except requests.exceptions.Timeout:
            error_msg = "m3u8 GET timeout"
        except requests.exceptions.ConnectionError:
            error_msg = "m3u8 GET connection error"
        except Exception as e:
            error_msg = f"m3u8 GET error: {e}"

    return False, (time.monotonic() - start), None, error_msg
