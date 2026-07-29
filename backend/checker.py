"""Source health checker.

Probes live stream URLs (m3u8 / HTTP) to verify availability and measure response time.
Uses HTTP HEAD/GET with range requests to avoid downloading full segments.
"""

import logging
import time
from datetime import datetime, timezone
from typing import Optional, Tuple

import requests

logger = logging.getLogger(__name__)


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
