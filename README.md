# 🎩 帽子TV — 无广告电视直播

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

# 方式二：直接运行（推荐用 run.sh，会自动建目录、装依赖）
bash run.sh

# 或手动运行
pip install -r requirements.txt
python -m uvicorn backend.main:app --host 0.0.0.0 --port 8000
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
| `TV_HIDE_AFTER` | `6` | 最近 N 次检查全部失败后隐藏 |
| `TV_CHECK_MAX_WORKERS` | `20` | 健康检查全局并发线程数 |
| `TV_CHECK_MAX_PER_HOST` | `2` | 单站点最大并发请求数 |
| `TV_CHECK_CODEC` | `true` | 编码兼容性检测（APK 建议设为 false） |

## � API 接口

后端提供以下 REST API 接口：

### 频道管理

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/channels` | GET | 获取频道列表，支持参数 `visible_only=true&healthy_only=true` |
| `/api/channels/{id}` | GET | 获取单个频道详情 |
| `/api/channels/{id}/switch` | POST | 手动切换到下一个备用源 |
| `/api/channels/{id}/check` | POST | 强制检测单个频道健康状态 |
| `/api/groups` | GET | 按分组获取频道列表 |
| `/api/summary` | GET | 获取系统状态摘要 |

### 手动触发更新

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/crawl` | POST | 立即从 GitHub m3u 源爬取最新频道 |
| `/api/check-all` | POST | 立即对所有频道进行健康检查 |
| `/api/purge` | POST | 标记连续失败的源为"暂停检查"，下次爬取时自动恢复 |
| `/api/export` | GET | 导出所有可见频道的 JSON（供 APK 使用） |

### 示例

```bash
# 手动触发爬取新源
curl -X POST http://localhost:8000/api/crawl

# 手动触发健康检查
curl -X POST http://localhost:8000/api/check-all

# 清理无效源（连续3次以上失败的源将被标记为暂停检查）
curl -X POST http://localhost:8000/api/purge

# 导出频道数据（供 APK 离线使用）
curl http://localhost:8000/api/export > channels.json

# 获取所有在线频道
curl "http://localhost:8000/api/channels?visible_only=true&healthy_only=true"
```

## �📂 项目结构

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

频道重心为**国内（央视/卫视/地方台）+ 港澳台**，国外频道有则保留、不强求。后端会从 **35+ 个公开源**汇总、去重、归一化合并（含繁简体/线路号/画质前缀统一），每频道保留最多 **5 个备用源**并自动健康检测（应对源失效快：CI 每 4 小时更新 + 源挂自动切备用）。

### 源平台分布

| 平台 | 源数量 | 说明 |
|------|--------|------|
| **GitHub** | 20+ | 主力源，通过 raw.githubusercontent + jsdelivr CDN 双通道 |
| **GitHub (jsdelivr 镜像)** | 10 | 国内访问更稳定的 CDN 镜像 |
| **epg.pw** | 2 | 台湾/澳门直播源 |
| **其他** | 3 | 咪咕视频、TVBox 直播源等 |

### 主要源列表

**🇨🇳 国内综合（央视 + 卫视 + 地方台）**
- [bestK/iptv](https://github.com/bestK/iptv) — 540+ 频道，每日更新
- [best-fan/iptv-sources](https://github.com/best-fan/iptv-sources) — **每日检测**，425+ 频道（央视/地方分类），源时效性好
- [cs3306/IPTV-Sources](https://github.com/cs3306/IPTV-Sources) — 40+ 公开源聚合 + ffprobe 检测，8000+ 频道
- [imtinge/iptv-api](https://github.com/imtinge/iptv-api) — 每日更新两次 + 测速筛选，ipv4 央视/卫视
- [sunguanghui/TV](https://github.com/sunguanghui/TV) — 1757 频道，900+ 国内（央视/卫视），测速排序
- [zilong7728/Collect-IPTV](https://github.com/zilong7728/Collect-IPTV) — 667 频道，已按最佳排序的精选源
- [BurningC4/Chinese-IPTV](https://github.com/BurningC4/Chinese-IPTV) — CCTV IPv4 源
- [vbskycn/iptv](https://github.com/vbskycn/iptv) — IPv4 自动扫描源（CDN 加速），每 6 小时更新
- [CCSH/IPTV](https://github.com/CCSH/IPTV) — 每日更新
- [fanmingming/live](https://github.com/fanmingming/live) — IPv6 高清源
- [hujingguang/ChinaIPTV](https://github.com/hujingguang/ChinaIPTV) — **每 15 分钟自动更新**，稳定性高
- [yifoo/autoiptv](https://github.com/yifoo/autoiptv) — 多源同步去重精简版，每频道只保留最佳源
- [iptv-org/iptv](https://github.com/iptv-org/iptv) — 中国频道（cn.m3u，已过滤掉国外为主的全球大表）
- [joevess/IPTV](https://github.com/joevess/IPTV) — 直播源聚合
- [kilvn/iptv](https://github.com/kilvn/iptv) — 直播中国（景区监控）+ CCTV 频道

**🇭🇰🇲🇴🇹🇼 港澳台**
- [epg.pw](https://epg.pw) — 台湾（138 频道）、澳门直播源
- [nthack/IPTVM3U](https://github.com/nthack/IPTVM3U) — 港澳台（翡翠台/TVB 等）、广东地方台
- [iptv-org/iptv](https://github.com/iptv-org/iptv) — 香港（hk.m3u）、台湾（tw.m3u）精简源

**🌏 国外频道**
- [iptv-org/iptv](https://github.com/iptv-org/iptv) — 韩国（kr.m3u）、日本（jp.m3u）

**📦 TVBox 格式源**
- [develop202/migu_video](https://github.com/develop202/migu_video) — 咪咕视频源（央视/卫视）
- [Supprise0901/TVBox_live](https://github.com/Supprise0901/TVBox_live) — 河南地方台+CCTV+卫视

### 健康检测机制

- **站点感知并发**：不同站点并行检测（20 线程），同一站点限速（≤2 并发），避免被封
- **编码兼容性检测**：通过 ffprobe 检测音视频编码，MP2/MPEG-2 等浏览器不兼容的源自动标记为不健康
- **自动换源**：连续 3 次检测失败自动切换到下一个备用源
- **缓存优化**：编码检测结果缓存 30 分钟（按站点），避免重复探测

⚠️ 本程序仅作技术学习用途，所有直播源来自公开网络资源。部分港澳台频道可能需要相应网络环境。

## 📄 许可证

MIT
