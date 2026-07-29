# 🐱 Maozi TV — 无广告电视直播

一个**完全自建、无广告**的电视直播解决方案。包含后端源管理服务 + Android TV 客户端。

## ✨ 特点

- **🚫 无广告** — 完全自建，没有任何广告 SDK
- **🔄 自动换源** — 当前源失效自动切换到备用源
- **📡 源自动更新** — 定时从公开 m3u 源爬取最新频道
- **🏥 健康检测** — 定时并发验证所有源，自动剔除死源
- **📺 电视优化** — 支持遥控器方向键导航
- **🐳 一键部署** — Docker Compose 启动服务

## 🏗 架构

```
┌─────────────────────┐     ┌──────────────────────────┐
│  Android TV APK     │────▶│  后端服务 (FastAPI)      │
│  (WebView + hls.js) │◀────│  爬虫 + 检测 + API      │
└─────────────────────┘     └────────┬─────────────────┘
                                     │
                          ┌──────────▼─────────────────┐
                          │  公开 m3u 源 (GitHub/CDN)  │
                          │  (iptv-org, bestK, 等)    │
                          └────────────────────────────┘
```

## 🚀 快速开始

### 1. 启动后端服务

```bash
# 方式一：Docker（推荐）
docker compose up -d

# 方式二：直接运行
pip install -r requirements.txt
python backend/main.py
```

服务默认运行在 `http://0.0.0.0:8000`

### 2. 在电视盒子上使用

**方式一：浏览器访问**
在电视盒子的浏览器中打开 `http://<后端IP>:8000`

**方式二：安装 APK**
1. 从 [Releases](https://github.com/liuxue5213/Maozi-TV/releases) 下载 APK
2. 安装到电视盒子
3. 打开 App，长按 OK 键设置后端服务器地址

### 3. 配置后端地址

启动后长按遥控器 **OK 键** 或按 **Menu 键**，输入后端服务器地址（如 `192.168.1.100:8000`）

## 📦 从源码构建 APK

### 方式一：GitHub Actions（推荐）

推送到 GitHub 后自动构建：

```bash
git push origin main
```

前往 GitHub 仓库 → Actions → 下载 Artifact

### 方式二：本地构建

```bash
# 安装 Android SDK
cd android
./gradlew assembleRelease
# APK 在 app/build/outputs/apk/release/
```

## ⚙️ 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `TV_CRAWL_INTERVAL` | `60` | 源爬取间隔（分钟） |
| `TV_CHECK_INTERVAL` | `15` | 健康检查间隔（分钟） |
| `TV_CHECK_TIMEOUT` | `10` | 源检测超时（秒） |
| `TV_MAX_FAILURES` | `3` | 连续失败触发热切换 |
| `TV_MAX_SOURCES_PER_CHANNEL` | `5` | 每频道保留最大源数 |
| `TV_HIDE_AFTER` | `6` | 全部源死N次后隐藏 |

## 📂 项目结构

```
├── backend/              # Python 后端
│   ├── main.py           # FastAPI 入口 + 定时任务
│   ├── config.py         # 配置
│   ├── database.py       # SQLite 模型
│   ├── source_manager.py # 爬取 + 检测 + 替换
│   ├── checker.py        # 源可用性检测
│   └── crawlers/         # m3u 爬虫
├── web-ui/               # 电视端 Web 界面
│   └── static/           # HTML + CSS + JS
├── android/              # Android TV WebView 包装
│   └── app/src/          # Kotlin/Java 源码
├── docker-compose.yml    # Docker 部署
└── Dockerfile
```

## 📡 数据来源

频道源来自以下公开项目（感谢各位开源作者）：

- [bestK/iptv](https://github.com/bestK/iptv) — 540+ 频道，每日更新
- [BurningC4/Chinese-IPTV](https://github.com/BurningC4/Chinese-IPTV) — CCTV IPv4 源
- [iptv-org/iptv](https://github.com/iptv-org/iptv) — 全球频道集合
- [vbskycn/iptv](https://github.com/vbskycn/iptv) — 自动扫描源

⚠️ 本程序仅作技术学习用途，所有直播源来自公开网络资源。

## 📄 许可证

MIT
