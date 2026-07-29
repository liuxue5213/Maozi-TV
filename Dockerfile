# Dockerfile for TV Live Streaming Backend
# Multi-stage: install deps, then run

FROM python:3.11-slim AS builder

WORKDIR /build

COPY requirements.txt .
RUN pip install --no-cache-dir --user -r requirements.txt

# ── Runtime ──────────────────────────────────────────

FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    # needed for requests SSL
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Copy installed packages from builder
COPY --from=builder /root/.local /root/.local
ENV PATH=/root/.local/bin:$PATH

WORKDIR /app

# Copy backend code
COPY backend/ /app/backend/
COPY web-ui/ /app/web-ui/

# Volume for persistent data (SQLite DB, logs)
VOLUME ["/data"]

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')" || exit 1

CMD ["python", "-m", "uvicorn", "backend.main:app", "--host", "0.0.0.0", "--port", "8000"]
