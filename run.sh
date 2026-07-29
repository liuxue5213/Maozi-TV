#!/usr/bin/env bash
# Maozi TV — 本地运行后端服务 (非 Docker)
# Usage: bash run.sh

set -e

cd "$(dirname "$0")"

# 创建数据目录
mkdir -p data

# 检查 Python
if ! command -v python3 &>/dev/null; then
    echo "❌ 请先安装 Python 3.10+"
    exit 1
fi

# 安装依赖
echo "📦 安装依赖..."
python3 -m pip install -r requirements.txt -q

# 启动服务
echo "🚀 启动后端服务..."
echo "   地址: http://0.0.0.0:8000"
echo "   Web UI: http://localhost:8000"
echo "   API 导出: http://localhost:8000/api/export"
echo ""

export TV_DB_PATH="$(pwd)/data/tv.db"
export TV_LOG_FILE="$(pwd)/data/tv.log"
export TV_LOG_LEVEL=INFO

exec python3 -m uvicorn backend.main:app --host 0.0.0.0 --port 8000
