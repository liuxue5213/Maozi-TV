"""TV Live Streaming Backend — FastAPI Application.

Provides:
- REST API for channel listing, health status, playback
- Periodic source crawling and health checking
- Auto-replacement of dead sources
"""

import logging
import os
import signal
import sys
import threading
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from .config import config
from .database import init_db
from .source_manager import SourceManager

# ── Logging setup ────────────────────────────────────────────
def _build_log_handlers():
    handlers = [logging.StreamHandler(sys.stdout)]
    if config.log_file:
        try:
            log_dir = os.path.dirname(config.log_file)
            if log_dir:
                os.makedirs(log_dir, exist_ok=True)
            handlers.append(logging.FileHandler(config.log_file))
        except OSError:
            # Fall back to stdout-only if the log file path isn't writable
            pass
    return handlers


logging.basicConfig(
    level=getattr(logging, config.log_level.upper(), logging.INFO),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
    handlers=_build_log_handlers(),
)
logger = logging.getLogger(__name__)


# ── Scheduled background tasks ──────────────────────────────

class BackgroundScheduler:
    """Runs periodic crawl and health-check cycles in a background thread."""

    def __init__(self):
        self._thread: threading.Thread | None = None
        self._stop_event = threading.Event()

    def start(self):
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run_loop, daemon=True)
        self._thread.start()
        logger.info("Background scheduler started")

    def stop(self):
        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=5)
        logger.info("Background scheduler stopped")

    def _run_loop(self):
        manager = SourceManager()
        crawl_interval = config.crawl_interval_minutes * 60
        check_interval = config.check_interval_minutes * 60
        purge_interval = crawl_interval * 2  # Purge dead sources every 2 crawl cycles

        # Wait a bit before first run to let the server start
        if self._stop_event.wait(timeout=15):
            return

        logger.info("Starting initial crawl cycle...")
        try:
            manager.run_full_cycle()
            # Purge dead sources after initial crawl
            manager.purge_dead_sources()
        except Exception as e:
            logger.error("Initial crawl failed: %s", e, exc_info=True)

        last_crawl = time.monotonic()
        last_check = time.monotonic()
        last_purge = time.monotonic()

        while not self._stop_event.is_set():
            now = time.monotonic()

            try:
                if now - last_crawl >= crawl_interval:
                    logger.info("=== Periodic crawl cycle ===")
                    manager.run_full_cycle()
                    last_crawl = now

                if now - last_check >= check_interval:
                    logger.info("=== Periodic health check ===")
                    manager.check_and_replace_all()
                    last_check = now

                if now - last_purge >= purge_interval:
                    logger.info("=== Periodic purge of dead sources ===")
                    result = manager.purge_dead_sources()
                    if result["sources_marked_dead"] or result["channels_hidden"]:
                        logger.info("Marked %d sources as dead, hidden %d channels",
                                    result["sources_marked_dead"], result["channels_hidden"])
                    last_purge = now

                # Sleep for 30 seconds between loops, checking stop event
                self._stop_event.wait(timeout=30)

            except Exception as e:
                logger.error("Background cycle error: %s", e, exc_info=True)
                self._stop_event.wait(timeout=60)  # Wait a bit before retry


scheduler = BackgroundScheduler()


# ── Application lifespan ────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup and shutdown handler."""
    logger.info("Starting TV Live Streaming Backend...")
    init_db()
    scheduler.start()
    yield
    scheduler.stop()
    logger.info("Shutdown complete.")


# ── FastAPI App ──────────────────────────────────────────────

app = FastAPI(
    title="TV Live Streaming Backend",
    version="1.0.0",
    lifespan=lifespan,
)

# CORS — allow TV app from any origin.
# allow_credentials MUST be False when allow_origins=["*"] (per the CORS spec),
# otherwise browsers reject credentialed responses. This service uses no cookies.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── Health check (MUST be before static mount) ────────────────

@app.get("/health")
def health():
    return {"status": "ok", "version": "1.0.0"}


# Mount API routes
from .api.channels import router as channels_router  # noqa: E402
app.include_router(channels_router)

from .api.analytics import router as analytics_router  # noqa: E402
app.include_router(analytics_router)

from .api.sync import router as sync_router  # noqa: E402
app.include_router(sync_router)

# ── 静态文件 no-cache 中间件（避免浏览器缓存旧版 player.js）─────
from starlette.middleware.base import BaseHTTPMiddleware  # noqa: E402

class NoCacheStaticMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request, call_next):
        resp = await call_next(request)
        path = request.url.path
        if path.endswith(('.js', '.css', '.html')):
            resp.headers['Cache-Control'] = 'no-cache, no-store, must-revalidate'
            resp.headers['Pragma'] = 'no-cache'
            resp.headers['Expires'] = '0'
        return resp

app.add_middleware(NoCacheStaticMiddleware)

# Mount static web UI — mounted last so API routes take precedence
static_dir = os.path.join(os.path.dirname(__file__), "..", "web-ui", "static")
static_dir = os.path.normpath(static_dir)
if os.path.isdir(static_dir):
    app.mount("/", StaticFiles(directory=static_dir, html=True), name="webui")
    logger.info("Web UI mounted from %s", static_dir)
else:
    logger.warning("Web UI static directory not found at %s", static_dir)


# ── Entry point ──────────────────────────────────────────────

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host=config.host,
        port=config.port,
        log_level=config.log_level.lower(),
        reload=False,
    )
