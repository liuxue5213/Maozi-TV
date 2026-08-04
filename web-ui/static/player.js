/* TV Live Streaming Player — hls.js + remote-control navigation */
/* Maozi TV 重构版：国内/国外分类、侧边栏隐藏、控制栏、收藏、搜索、频道号 */

const API_BASE = window.location.origin;

// Logo 代理 URL（避免跨域/封锁）
function proxyLogoUrl(url) {
    if (!url) return '';
    // 只代理远程 URL，本地或 base64 不代理
    if (url.startsWith('data:') || url.startsWith('/')) return url;
    return API_BASE + '/api/proxy/logo?url=' + encodeURIComponent(url);
}

// 流代理 URL（避免跨域 / ORB 拦截 m3u8 + ts）
// 把源 URL 包成 /api/proxy/stream?url=<encoded>，由后端转发并重写 m3u8 内部分片 URL
function proxyStreamUrl(url) {
    if (!url) return '';
    // data: URL / 已经走过代理的 URL 不再二次包装
    if (url.startsWith('data:')) return url;
    if (url.startsWith(API_BASE + '/api/proxy/stream?url=')) return url;
    return API_BASE + '/api/proxy/stream?url=' + encodeURIComponent(url);
}

// ── 国内/国外分组判断 ──────────────────────────────────

const DOMESTIC_KEYWORDS = [
    '央视', '卫视', '地方', '港澳台', '体育', '电影', '纪录', '综艺',
    '少儿', '新闻', '音乐', '电视剧', '直播中国', '咪咕', '频道',
    '国内', '央视频', '百视', '数字', '华数', '芒果', '北京', '上海',
    '广东', '浙江', '江苏', '湖南', '深圳', '重庆', '四川', '湖北',
    '安徽', '山东', '河南', '辽宁', '黑龙江', '吉林', '天津', '河北',
    '福建', '广西', '云南', '贵州', '甘肃', '内蒙古', '宁夏', '新疆',
    '西藏', '青海', '陕西', '海南', '江西', '山西',
];

const INTERNATIONAL_GROUPS = new Set([
    'General', 'News', 'Movies', 'Sports', 'Kids', 'Music', 'Documentary',
    'Entertainment', 'Culture', 'Education', 'Religious', 'Lifestyle',
    'Business', 'Comedy', 'Classic', 'Outdoor', 'Travel', 'Animation',
    'Cooking', 'Shop', 'Auto', 'Weather', 'Science', 'MTV', 'Family',
    'Legislative', 'Public', 'Relax', 'NewTV', 'USA', 'UK', 'Canada',
    'Germany', 'France', 'India', 'Australia', 'Latin', 'Europe', 'Asia',
]);

function isChineseGroup(group) {
    if (!group) return false;
    // 含中文关键字
    for (const kw of DOMESTIC_KEYWORDS) {
        if (group.includes(kw)) return true;
    }
    // 含中文字符（Unicode CJK）
    if (/[\u4e00-\u9fff]/.test(group)) return true;
    return false;
}

// 频道名是国内的特征（CCTV/卫视/各省名等），即使 group 是英文也算国内
const DOMESTIC_NAME_KEYWORDS = [
    'CCTV', '央视', '卫视', 'CGTN',
    '北京', '上海', '天津', '重庆', '湖南', '浙江', '江苏', '广东', '山东',
    '河南', '四川', '安徽', '湖北', '深圳', '福建', '东南', '广西', '云南',
    '贵州', '河北', '山西', '陕西', '辽宁', '吉林', '黑龙江', '内蒙', '新疆',
    '西藏', '宁夏', '青海', '甘肃', '海南', '江西', '东方', '南方',
    '翡翠', '明珠', 'TVB', 'ViuTV', 'RTHK', '凤凰', '澳视', '港台',
    '东森', '中天', '三立', '民视', '中视', '台视',
];

function isChineseChannel(name, group) {
    // 1. group 含中文 → 国内
    if (isChineseGroup(group)) return true;
    // 2. 频道名含国内关键字 → 国内（即使 group 是英文如 News/General）
    if (name) {
        for (const kw of DOMESTIC_NAME_KEYWORDS) {
            if (name.includes(kw)) return true;
        }
        // 频道名含中文字符 → 国内
        if (/[\u4e00-\u9fff]/.test(name)) return true;
    }
    return false;
}

function isInternationalGroup(group) {
    if (!group) return false;
    if (INTERNATIONAL_GROUPS.has(group)) return true;
    // 纯英文（不含中文字符）
    if (!/[\u4e00-\u9fff]/.test(group) && /^[A-Za-z]/.test(group)) return true;
    return false;
}

function classifyRegion(group, name) {
    // 优先看频道名+分组综合判断（修复：国内频道 group 是英文时不再误判国外）
    if (isChineseChannel(name || '', group)) return 'domestic';
    if (isInternationalGroup(group)) return 'international';
    return 'unknown';
}

// ── State ───────────────────────────────────────────────

const state = {
    channels: [],           // 当前过滤后的频道列表
    allChannels: [],        // 全量频道（用于搜索/过滤）
    activeIndex: 0,
    focusIndex: 0,
    isPlaying: false,
    isLoading: false,
    retryCount: 0,
    maxRetries: 3,
    sourceTriedCount: 0,    // 本轮已尝试的源数（防止全死时无限循环换源）
    loadTimeout: null,      // 加载超时计时器（超时自动换源，防永久转圈）
    hls: null,
    videoEl: null,
    tabMode: 'all',         // 'all' | 'healthy' | 'fav'
    regionMode: 'domestic', // 'domestic' | 'international'  （默认国内）
    mode: 'api',            // 'api' = fetch from backend, 'json' = injected by host app
    jsonInitDone: null,     // resolve callback for initFromJson
    // 收藏
    favorites: loadFavorites(),
    // 音量 OSD
    volumeOsdTimer: null,
    // 侧边栏
    sidebarOpen: false,
    sidebarTimer: null,
    // 控制栏
    controlsTimer: null,
    controlsVisible: false,
    // 频道号 OSD
    channelNumberTimer: null,
    // 搜索
    searchQuery: '',
    // 频道信息面板
    infoPanelVisible: false,
    // 网速
    lastSpeedKbps: 0,
    speedMeasureInterval: null,
    // 长按收藏
    longPressTimer: null,
    longPressTriggered: false,
    // 分页渲染
    renderedCount: 0,       // 已渲染的频道数量
    renderBatchSize: 100,   // 每次渲染的频道数量（减小以减少初始请求数）
    loadingMore: false,     // 是否正在加载更多
    // Logo 懒加载观察器
    logoObserver: null,
    // 画面比例 (''=默认, '16:9', '4/3', 'stretch', 'zoom')
    aspectRatio: '',
    // 频道排序模式 ('default'|'name'|'group')
    sortMode: 'default',
    // EPG 节目单数据（当前频道）
    currentEpg: null,
    // 自定义收藏夹（多分组，key=分组名，value=频道名数组）
    favoriteGroups: loadFavoriteGroups(),
    currentFavGroup: '默认',
    // 是否在多画面模式
    multiviewMode: false,
    // 播放历史（最近观看频道名列表，最多 30 条，新→旧）
    playHistory: loadPlayHistory(),
    // 频道号快速跳转缓冲（数字键累积）
    channelNumberBuffer: '',
    channelNumberJumpTimer: null,
    // 多源测速缓存（host → 测速结果 ms）
    speedTestCache: {},
    // 音轨 / 字幕（hls.js 轨道）
    audioTracks: [],
    subtitleTracks: [],
    currentAudioTrack: -1,
    currentSubtitleTrack: -1,
};

// ── Favorites (localStorage-persisted channel names) ────────

const FAV_STORAGE_KEY = 'maozi_favorites';

function loadFavorites() {
    try {
        const raw = localStorage.getItem(FAV_STORAGE_KEY);
        return raw ? JSON.parse(raw) : [];
    } catch {
        return [];
    }
}

function saveFavorites() {
    try {
        localStorage.setItem(FAV_STORAGE_KEY, JSON.stringify(state.favorites));
    } catch {
        // localStorage may be unavailable (private mode); ignore
    }
}

function isFavorite(channelName) {
    return state.favorites.includes(channelName);
}

// ── 播放历史 (localStorage) ──────────────────────────────

const HISTORY_STORAGE_KEY = 'maozi_play_history';
const MAX_HISTORY = 30;

function loadPlayHistory() {
    try {
        const raw = localStorage.getItem(HISTORY_STORAGE_KEY);
        return raw ? JSON.parse(raw) : [];
    } catch {
        return [];
    }
}

function savePlayHistory() {
    try {
        localStorage.setItem(HISTORY_STORAGE_KEY, JSON.stringify(state.playHistory));
    } catch { /* ignore */ }
}

/** 记录一次频道观看（名去重 + 移到最前 + 限 30 条） */
function addToHistory(channelName) {
    if (!channelName) return;
    const idx = state.playHistory.indexOf(channelName);
    if (idx >= 0) state.playHistory.splice(idx, 1);
    state.playHistory.unshift(channelName);
    if (state.playHistory.length > MAX_HISTORY) state.playHistory.length = MAX_HISTORY;
    savePlayHistory();
}

/** 通过频道名在 allChannels 里查找频道对象 */
function findChannelByName(name) {
    return state.allChannels.find(ch => ch.name === name) || null;
}

// ── 频道排序 ──────────────────────────────────────────────

function sortChannelList(list) {
    const mode = state.sortMode;
    if (mode === 'name') {
        list.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'));
    } else if (mode === 'group') {
        list.sort((a, b) => (a.group || '').localeCompare(b.group || '', 'zh-CN'));
    }
    // 'default' 保持原始顺序
    return list;
}

function cycleSortMode() {
    const modes = ['default', 'name', 'group'];
    const labels = ['默认排序', '按名称', '按分组'];
    const cur = modes.indexOf(state.sortMode);
    const next = modes[(cur + 1) % modes.length];
    state.sortMode = next;
    showOsd('排序: ' + labels[(cur + 1) % modes.length]);
    applyFiltersAndRender();
}

// ── 自定义收藏夹（多分组）─────────────────────────────────

const FAV_GROUPS_STORAGE_KEY = 'maozi_fav_groups';

function loadFavoriteGroups() {
    try {
        const raw = localStorage.getItem(FAV_GROUPS_STORAGE_KEY);
        if (raw) return JSON.parse(raw);
    } catch { /* ignore */ }
    // 默认结构：从旧版 favorites 迁移
    const favs = loadFavorites();
    return favs.length > 0 ? { '默认': favs } : {};
}

function saveFavoriteGroups() {
    try {
        localStorage.setItem(FAV_GROUPS_STORAGE_KEY, JSON.stringify(state.favoriteGroups));
    } catch { /* ignore */ }
}

// ── EPG 节目单 ──────────────────────────────────────────────

function fetchEpg(channelName) {
    // EPG 由后端提供（若支持），这里预留接口
    if (state.mode !== 'api') { state.currentEpg = null; return; }
    fetch(`${API_BASE}/api/epg?name=${encodeURIComponent(channelName)}`)
        .then(r => r.ok ? r.json() : null)
        .then(data => { state.currentEpg = data; })
        .catch(() => { state.currentEpg = null; });
}

function toggleFavorite(channelName) {
    const name = channelName || (state.channels[state.activeIndex] && state.channels[state.activeIndex].name);
    if (!name) return;
    const idx = state.favorites.indexOf(name);
    let msg;
    if (idx >= 0) {
        state.favorites.splice(idx, 1);
        msg = '已取消收藏: ' + name;
    } else {
        state.favorites.push(name);
        msg = '已收藏: ' + name;
    }
    saveFavorites();
    showOsd(msg);
    renderChannelList();
}
// Expose to Android host
window.toggleFavorite = function () { toggleFavorite(); };

function playFocusedChannel() {
    if (!dom.errorEl.classList.contains('hidden')) {
        retryPlayback();
    } else if (state.channels.length) {
        playChannel(state.focusIndex);
    }
}
window.playFocusedChannel = playFocusedChannel;

// ── Volume control (ArrowLeft / ArrowRight) ─────────────────

function changeVolume(delta) {
    const v = state.videoEl;
    if (!v) return;
    v.volume = Math.max(0, Math.min(1, +(v.volume + delta).toFixed(2)));
    v.muted = false;
    showVolumeOsd();
    syncVolumeSlider();
}

function toggleMute() {
    const v = state.videoEl;
    if (!v) return;
    v.muted = !v.muted;
    showVolumeOsd();
    syncVolumeSlider();
}

function syncVolumeSlider() {
    const v = state.videoEl;
    const slider = dom.volumeSlider;
    const icon = dom.volIcon;
    if (!v || !slider) return;
    const pct = v.muted ? 0 : Math.round(v.volume * 100);
    slider.value = pct;
    if (icon) {
        icon.textContent = v.muted || v.volume === 0 ? '🔇' : v.volume < 0.5 ? '🔉' : '🔊';
    }
}

// ── 画面比例切换 ──────────────────────────────────────────

const ASPECT_CYCLES = ['', '16:9', '4:3', 'stretch', 'zoom'];
const ASPECT_LABELS = { '': '原始', '16:9': '16:9', '4:3': '4:3', 'stretch': '拉伸', 'zoom' : '缩放' };

function applyAspectRatio(ratio) {
    const v = state.videoEl;
    if (!v) return;
    state.aspectRatio = ratio;
    switch (ratio) {
        case '16:9':  v.style.objectFit = 'cover'; v.style.aspectRatio = '16/9'; break;
        case '4:3':   v.style.objectFit = 'cover'; v.style.aspectRatio = '4/3';  break;
        case 'stretch': v.style.objectFit = 'fill'; v.style.aspectRatio = 'auto'; break;
        case 'zoom':  v.style.objectFit = 'cover'; v.style.aspectRatio = 'auto'; break;
        default:      v.style.objectFit = 'contain'; v.style.aspectRatio = 'auto'; break;
    }
    // 更新按钮文字
    if (dom.btnAspect) dom.btnAspect.textContent = ASPECT_LABELS[ratio] || '比例';
    showOsd('画面比例: ' + (ASPECT_LABELS[ratio] || '原始'));
}

function cycleAspectRatio() {
    const cur = ASPECT_CYCLES.indexOf(state.aspectRatio);
    const next = ASPECT_CYCLES[(cur + 1) % ASPECT_CYCLES.length];
    applyAspectRatio(next);
}

// ── 频道号快速跳转（数字键累积）─────────────────────────

function handleChannelNumberDigit(digit) {
    state.channelNumberBuffer += String(digit);
    // 显示当前输入
    if (dom.channelNumberOsd) {
        dom.channelNumberOsd.textContent = 'CH ' + state.channelNumberBuffer;
        dom.channelNumberOsd.classList.add('show');
    }
    // 重置超时
    if (state.channelNumberJumpTimer) clearTimeout(state.channelNumberJumpTimer);
    state.channelNumberJumpTimer = setTimeout(() => {
        executeChannelNumberJump();
    }, 3000);
}

function executeChannelNumberJump() {
    if (!state.channelNumberBuffer) return;
    const num = parseInt(state.channelNumberBuffer, 10);
    state.channelNumberBuffer = '';
    if (dom.channelNumberOsd) dom.channelNumberOsd.classList.remove('show');
    if (isNaN(num) || num < 1 || num > state.allChannels.length) {
        showOsd('频道号超出范围 (1-' + state.allChannels.length + ')');
        return;
    }
    // 在全频道列表里跳转
    const target = state.allChannels[num - 1];
    if (!target) return;
    // 切到该频道所在分组并播放
    const list = state.channels;
    let idx = list.findIndex(ch => ch.name === target.name);
    if (idx < 0) {
        // 频道不在当前过滤列表，临时切换到全部
        state.searchQuery = '';
        if (dom.searchInput) dom.searchInput.value = '';
        state.tabMode = 'all';
        state.regionMode = 'domestic';
        applyFiltersAndRender();
        idx = state.channels.findIndex(ch => ch.name === target.name);
    }
    if (idx >= 0) playChannel(idx);
}

function showVolumeOsd() {
    const v = state.videoEl;
    const pct = v ? Math.round(v.volume * 100) : 0;
    const osd = dom.volumeOsd;
    if (!osd) return;
    osd.querySelector('.vol-label').textContent = v && v.muted ? '🔇 静音' : '🔊 音量';
    osd.querySelector('.vol-pct').textContent = (v && v.muted ? 0 : pct) + '%';
    osd.querySelector('.vol-fill').style.width = (v && v.muted ? 0 : pct) + '%';
    osd.classList.add('show');
    clearTimeout(state.volumeOsdTimer);
    state.volumeOsdTimer = setTimeout(() => osd.classList.remove('show'), 1800);
}

function showOsd(text) {
    const toast = dom.toast;
    if (!toast) return;
    toast.textContent = text;
    toast.classList.add('show');
    clearTimeout(state._toastTimer);
    state._toastTimer = setTimeout(() => toast.classList.remove('show'), 1800);
}

// ── Update data source ───────────────────────────────────

window.refreshChannels = function () {
    showOsd('正在更新频道源...');
    if (state.mode === 'json' && window.AndroidBridge && window.AndroidBridge.refreshChannels) {
        window.AndroidBridge.refreshChannels();
        return;
    }
    fetchChannels().then(() => showOsd('频道列表已刷新'));
};

// ── Data injection (called by Android WebView) ────────────

window.initFromJson = function (data) {
    if (typeof data === 'string') {
        try { data = JSON.parse(data); }
        catch (e) { console.error('initFromJson: JSON parse failed', e); return; }
    }
    if (!data || !data.channels || !data.channels.length) {
        console.error('initFromJson: invalid data', data);
        return;
    }
    state.mode = 'json';
    state.allChannels = data.channels;
    console.log(`initFromJson: loaded ${data.channels.length} channels (v${data.version})`);

    applyFiltersAndRender();

    if (state.jsonInitDone) {
        state.jsonInitDone();
        state.jsonInitDone = null;
    }
};

// ── DOM refs ────────────────────────────────────────────

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

const dom = {
    channelList: $('#channel-list'),
    video: $('#video-player'),
    overlay: $('#player-overlay'),
    overlayIcon: $('#overlay-icon'),
    overlayText: $('#overlay-text'),
    overlaySubtext: $('#overlay-subtext'),
    spinner: $('#loading-spinner'),
    errorEl: $('#error-state'),
    errorMsg: $('#error-message'),
    retryBtn: $('#retry-btn'),
    tabAll: $('#tab-all'),
    tabHealthy: $('#tab-healthy'),
    tabFav: $('#tab-fav'),
    tabHistory: $('#tab-history'),
    regionDomestic: $('#region-domestic'),
    regionInternational: $('#region-international'),
    volumeOsd: $('#volume-osd'),
    sourceOsd: $('#source-osd'),
    sourceOsdText: $('#source-osd-text'),
    sourceOsdSub: $('#source-osd-sub'),
    signalBadge: $('#signal-badge'),
    signalBars: $('#signal-bars'),
    signalSource: $('#signal-source'),
    signalStatus: $('#signal-status'),
    signalBitrate: $('#signal-bitrate'),
    toast: $('#toast'),
    sidebar: $('#sidebar'),
    videoContainer: $('#video-container'),
    playerArea: $('#player-area'),
    searchInput: $('#search-input'),
    menuToggle: $('#menu-toggle'),   // 常驻菜单按钮（电脑/手机）
    // 控制栏
    playerControls: $('#player-controls'),
    controlsChannelName: $('#controls-channel-name'),
    controlsGroupName: $('#controls-group-name'),
    controlsSpeed: $('#controls-speed'),
    btnPrevCh: $('#btn-prev-ch'),
    btnNextCh: $('#btn-next-ch'),
    btnPlayPause: $('#btn-play-pause'),
    btnSource: $('#btn-source'),
    btnQuality: $('#btn-quality'),
    btnAspect: $('#btn-aspect'),
    btnSnapshot: $('#btn-snapshot'),
    btnFullscreen: $('#btn-fullscreen'),
    volumeSlider: $('#volume-slider'),
    volIcon: $('#vol-icon'),
    sourcePopup: $('#source-popup'),
    qualityPopup: $('#quality-popup'),
    // 频道号 OSD
    channelNumberOsd: $('#channel-number-osd'),
    // 频道信息面板
    infoPanel: $('#channel-info-panel'),
    infoName: $('#info-name'),
    infoGroup: $('#info-group'),
    infoSources: $('#info-sources'),
    infoCurrentSource: $('#info-current-source'),
    infoHealth: $('#info-health'),
};

// ── Sidebar management ────────────────────────────────

function openSidebar() {
    state.sidebarOpen = true;
    dom.sidebar.classList.add('open');
    resetSidebarTimer();
}

function closeSidebar() {
    state.sidebarOpen = false;
    dom.sidebar.classList.remove('open');
    clearTimeout(state.sidebarTimer);
}

function resetSidebarTimer() {
    clearTimeout(state.sidebarTimer);
    // 电视盒子：自动隐藏（无鼠标，避免遮挡画面）；电脑/手机：保持打开（有鼠标/触摸）
    if (isTvDevice()) {
        state.sidebarTimer = setTimeout(() => {
            if (state.sidebarOpen) closeSidebar();
        }, 4000);
    }
}

/** 粗略判断是否电视盒子：有 dpad 且无触屏/无鼠标 → 电视盒子。 */
function isTvDevice() {
    // 触屏设备（手机/平板）一定不是 TV
    if ('ontouchstart' in window && navigator.maxTouchPoints > 0) return false;
    // 窄屏（手机）不是 TV
    if (window.innerWidth <= 768) return false;
    // 桌面有鼠标精准指针 → 当作桌面，不自动隐藏
    if (window.matchMedia && window.matchMedia('(pointer: fine)').matches) return false;
    // 其余（无触屏 + 无精准指针，如 TV 盒子的遥控器）当作电视盒子
    return true;
}

// ── Controls bar management ──────────────────────────

function showControls() {
    dom.playerControls.classList.remove('hidden');
    dom.playerControls.classList.add('show');
    state.controlsVisible = true;
    clearTimeout(state.controlsTimer);
    state.controlsTimer = setTimeout(hideControls, 3000);
}

function hideControls() {
    dom.playerControls.classList.remove('show');
    state.controlsVisible = false;
    // 同时关闭源弹窗和画质弹窗
    dom.sourcePopup.classList.add('hidden');
    if (dom.qualityPopup) dom.qualityPopup.classList.add('hidden');
    clearTimeout(state.controlsTimer);
}

// ── Channel number OSD ───────────────────────────────

// ── 截图功能 ──────────────────────────────────────────────

function takeSnapshot() {
    const v = state.videoEl;
    if (!v || !v.videoWidth) {
        showOsd('无法截图：视频未就绪');
        return;
    }
    try {
        const canvas = document.createElement('canvas');
        canvas.width = v.videoWidth;
        canvas.height = v.videoHeight;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(v, 0, 0, canvas.width, canvas.height);
        const dataUrl = canvas.toDataURL('image/png');
        // 自动下载
        const a = document.createElement('a');
        a.href = dataUrl;
        a.download = 'MaoziTV_' + (state.channels[state.activeIndex]?.name || 'snapshot') + '_' + Date.now() + '.png';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        showOsd('截图已保存');
    } catch (err) {
        showOsd('截图失败: ' + err.message);
    }
}

// ── 多画面模式（Web 端：同屏显示 4 个频道）─────────────

let multiviewPlayers = [];

function toggleMultiview() {
    state.multiviewMode = !state.multiviewMode;
    const container = dom.videoContainer;
    if (state.multiviewMode) {
        // 创建 2x2 网格
        container.innerHTML = '';
        multiviewPlayers = [];
        const indices = [0, 1, 2, 3].map(i => (state.activeIndex + i) % state.channels.length);
        for (let i = 0; i < 4; i++) {
            const wrapper = document.createElement('div');
            wrapper.className = 'multiview-cell';
            wrapper.style.cssText = 'position:relative;width:50%;height:50%;float:left;';
            const ch = state.channels[indices[i]];
            const label = document.createElement('span');
            label.textContent = ch ? ch.name : '';
            label.style.cssText = 'position:absolute;top:4px;left:8px;color:#fff;font-size:12px;background:rgba(0,0,0,0.6);padding:2px 6px;border-radius:4px;';
            wrapper.appendChild(label);
            container.appendChild(wrapper);
        }
        showOsd('多画面模式 (实验性)');
    } else {
        // 恢复单画面
        container.innerHTML = '';
        container.appendChild(dom.video);
        container.appendChild(dom.channelNumberOsd);
        // 重新添加其他 OSD...
        showOsd('已退出多画面');
    }
}

// ── 频道号显示（覆盖，增加历史记录触发）─────────────────

function showChannelNumber(index) {
    if (!dom.channelNumberOsd) return;
    dom.channelNumberOsd.textContent = 'CH ' + (index + 1);
    dom.channelNumberOsd.classList.add('show');
    clearTimeout(state.channelNumberTimer);
    state.channelNumberTimer = setTimeout(() => {
        dom.channelNumberOsd.classList.remove('show');
    }, 2000);
}

// ── Channel info panel ───────────────────────────────

function toggleInfoPanel() {
    if (state.infoPanelVisible) {
        hideInfoPanel();
    } else {
        showInfoPanel();
    }
}

function showInfoPanel() {
    const ch = state.channels[state.activeIndex];
    if (!ch) return;
    dom.infoName.textContent = ch.name || '—';
    dom.infoGroup.textContent = ch.group || '—';
    const sources = ch.sources || [];
    dom.infoSources.textContent = sources.length || 1;
    const srcIdx = ch.active_source_index || 0;
    dom.infoCurrentSource.textContent = sources.length ? `源 ${srcIdx + 1} / ${sources.length}` : '单源';
    dom.infoHealth.textContent = ch.healthy ? '✅ 正常' : '❌ 离线';
    dom.infoPanel.classList.remove('hidden');
    state.infoPanelVisible = true;
    // 5秒后自动关闭
    clearTimeout(state._infoPanelTimer);
    state._infoPanelTimer = setTimeout(hideInfoPanel, 5000);
}

function hideInfoPanel() {
    dom.infoPanel.classList.add('hidden');
    state.infoPanelVisible = false;
    clearTimeout(state._infoPanelTimer);
}

// ── Speed measurement ────────────────────────────────

function startSpeedMonitor() {
    if (state.speedMeasureInterval) return;
    updateSpeedDisplay();

    // 记录最后一次 FRAG_LOADED 更新的时间，避免定时器用 0 覆盖实时速度
    state._lastFragLoadedTime = 0;
    state.speedMeasureInterval = setInterval(() => {
        // 如果 2 秒内有 FRAG_LOADED 更新过，跳过本次（FRAG_LOADED 更准确）
        if (performance.now() - state._lastFragLoadedTime < 2000) return;

        let speedKbps = 0;

        // 方法1：用 Performance API 直接监控 /api/proxy/stream 请求的下载速度
        // 这是最可靠的方法，不依赖 hls.js 内部 stats API
        try {
            const entries = performance.getEntriesByType('resource')
                .filter(e => e.name.includes('/api/proxy/stream'))
                .filter(e => e.transferSize > 0 || e.decodedBodySize > 0)
                .slice(-3); // 最近3个请求

            if (entries.length > 0) {
                const totalBytes = entries.reduce((sum, e) => sum + (e.transferSize || e.decodedBodySize || 0), 0);
                const totalTime = entries.reduce((sum, e) => sum + e.duration, 0) / 1000;
                if (totalTime > 0 && totalBytes > 0) {
                    speedKbps = Math.round((totalBytes * 8 / totalTime) / 1000);
                }
            }
        } catch (e) {}

        // 方法2：通过 video.buffered 增长估算（如果 Performance API 不可用）
        if (speedKbps <= 0 && dom.video) {
            const v = dom.video;
            if (v.buffered && v.buffered.length > 0) {
                const end = v.buffered.end(v.buffered.length - 1);
                const now = performance.now();
                if (state._lastBufEnd !== undefined && state._lastBufTime) {
                    const bufDelta = end - state._lastBufEnd;
                    const timeDelta = (now - state._lastBufTime) / 1000;
                    if (bufDelta > 0 && timeDelta > 0) {
                        // 用视频码率估算下载速度（缓冲增长秒数 × 码率 / 实际时间）
                        let bitrate = 2000000; // 默认 2 Mbps
                        if (state.hls && state.hls.levels && state.hls.levels.length > 0) {
                            const lvl = state.hls.levels[state.hls.currentLevel >= 0 ? state.hls.currentLevel : 0];
                            if (lvl && lvl.bitrate) bitrate = lvl.bitrate;
                        }
                        speedKbps = Math.round((bufDelta * bitrate / timeDelta) / 1000);
                    }
                }
                state._lastBufEnd = end;
                state._lastBufTime = now;
            }
        }

        // 只在算出有效速度时才更新，避免覆盖 FRAG_LOADED 的实时值
        if (speedKbps > 0) {
            state.lastSpeedKbps = speedKbps;
            updateSpeedDisplay();
        }
    }, 500);
}

function updateSpeedDisplay() {
    let speedText = '';
    if (state.lastSpeedKbps > 0) {
        speedText = state.lastSpeedKbps > 1000
            ? (state.lastSpeedKbps / 1000).toFixed(1) + ' Mbps'
            : state.lastSpeedKbps + ' Kbps';
    } else if (state.isLoading) {
        speedText = '0 Kbps';
    }
    // 控制栏
    if (dom.controlsSpeed) {
        dom.controlsSpeed.textContent = speedText;
    }
    // 信号徽章常驻显示实时下载速度（每秒刷新，加载中也显示）
    if (dom.signalBitrate) {
        dom.signalBitrate.textContent = speedText;
    }
}

// ── Init ────────────────────────────────────────────────

async function init() {
    state.videoEl = dom.video;
    dom.video.volume = 1.0;

    setupNavigation();
    setupVideoEvents();
    setupRetry();
    setupSidebar();
    setupControls();
    setupSearch();

    // Fetch channels
    await fetchChannels();

    // Start: show sidebar for user to pick a channel
    if (state.channels.length) {
        openSidebar();
        showOverlay('empty', '点击选择频道开始观看', '');
    } else {
        showOverlay('empty', '点击屏幕选择频道', '请打开侧边栏选择频道');
    }

    // Periodic health refresh (only in API mode)
    if (state.mode === 'api') {
        setInterval(refreshHealth, 30_000);
    }

    console.log('TV App initialized (mode: ' + state.mode + ', region: ' + state.regionMode + ')');
}

// ── API ─────────────────────────────────────────────────

async function fetchChannels() {
    if (state.mode === 'json') {
        if (state.allChannels.length) {
            applyFiltersAndRender();
            return;
        }
        await new Promise((resolve) => {
            state.jsonInitDone = resolve;
            setTimeout(() => {
                if (state.jsonInitDone) {
                    state.jsonInitDone = null;
                    resolve();
                }
            }, 15000);
        });
        if (!state.allChannels.length) {
            showOverlay('error', '未收到频道数据', '请检查配置');
        }
        return;
    }

    // API mode
    const url = (state.tabMode === 'healthy')
        ? `${API_BASE}/api/channels?visible_only=true&healthy_only=true`
        : `${API_BASE}/api/channels?visible_only=true`;

    try {
        const resp = await fetch(url);
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        state.allChannels = await resp.json();
        applyFiltersAndRender();
    } catch (err) {
        console.error('Failed to fetch channels:', err);
        showOverlay('error', '无法连接服务器', '请检查后端服务是否运行');
    }
}

async function refreshHealth() {
    if (state.mode === 'json') return;
    try {
        const resp = await fetch(`${API_BASE}/api/summary`);
        if (!resp.ok) return;
        await fetchChannels();
    } catch {
        // silent
    }
}

// ── Filtering: region + tab + search ──────────────────

function applyFiltersAndRender() {
    let list = state.allChannels.slice();

    // 1. 区域过滤（同时看 group 和 name，避免国内频道因 group 英文被误判国外）
    if (state.regionMode === 'domestic') {
        list = list.filter(ch => classifyRegion(ch.group, ch.name) === 'domestic' || classifyRegion(ch.group, ch.name) === 'unknown');
    } else if (state.regionMode === 'international') {
        list = list.filter(ch => classifyRegion(ch.group, ch.name) === 'international');
    }

    // 2. Tab 过滤
    if (state.tabMode === 'healthy') {
        list = list.filter(ch => ch.healthy);
    } else if (state.tabMode === 'fav') {
        const favSet = new Set(state.favorites);
        list = list.filter(ch => favSet.has(ch.name));
    } else if (state.tabMode === 'history') {
        // 按播放历史排序（最新观看在前），只显示有历史的频道
        const histSet = new Set(state.playHistory);
        list = state.playHistory.map(name => findChannelByName(name)).filter(ch => ch && list.includes(ch));
    }

    // 3. 搜索过滤
    if (state.searchQuery) {
        const q = state.searchQuery.toLowerCase();
        list = list.filter(ch => ch.name.toLowerCase().includes(q) || (ch.group && ch.group.toLowerCase().includes(q)));
    }

    // 4. 排序
    list = sortChannelList(list);

    state.channels = list;

    // Reset indices if out of bounds
    if (state.activeIndex >= state.channels.length) state.activeIndex = 0;
    if (state.focusIndex >= state.channels.length) state.focusIndex = 0;

    renderChannelList();
}

// ── Playback ────────────────────────────────────────────

function playChannel(index, isSourceSwitch = false) {
    if (!state.channels.length || index < 0 || index >= state.channels.length) return;

    const ch = state.channels[index];
    state.activeIndex = index;
    state.focusIndex = index;
    state.retryCount = 0;
    // 仅用户主动切台时重置已尝试源数；换源路径(isSourceSwitch)保留计数以正确终止
    if (!isSourceSwitch) {
        state.sourceTriedCount = 0;
    }

    if (!ch.url) {
        showOverlay('error', '频道无可用源', '请切换到其他频道');
        updateNowPlaying(ch);
        return;
    }

    // 记录播放历史
    addToHistory(ch.name);

    showOverlay('loading', '正在加载...', ch.name);
    updateNowPlaying(ch);
    updateControlsInfo(ch);
    state.isLoading = true;
    renderChannelList();

    // 切台后立即显示控制栏（含网速区域），避免长时间看不到控制条
    showControls();
    // 提前启动网速监控（即使还在加载，也能显示估算码率）
    startSpeedMonitor();

    // 显示源状态 OSD（让用户知道正在尝试第几个源）
    const totalSources = (ch.sources || []).length;
    const curIdx = (ch.active_source_index || 0) + 1;
    updateSourceOsd(`📡 源 ${curIdx}/${totalSources}`, ch.name);
    // 信号指示器：连接中
    updateSignalBadge('connecting');

    // 加载超时保护：12秒内未开始播放就切备用源（防卡死源永久转圈）
    startLoadTimeout();

    // 显示频道号
    showChannelNumber(index);

    destroyPlayer();

    // 源 URL 通过后端流代理，避免浏览器跨域 / ORB 拦截 m3u8 + ts
    // （判断 m3u8/flv 仍用原始 URL；实际加载使用代理 URL）
    const rawUrl = ch.url;
    const url = proxyStreamUrl(rawUrl);
    if (rawUrl.endsWith('.m3u8') || rawUrl.includes('m3u8')) {
        startHls(url, ch);
    } else if (rawUrl.endsWith('.flv')) {
        dom.video.src = url;
        dom.video.play().catch(handlePlayError);
    } else {
        dom.video.src = url;
        dom.video.play().catch(() => startHls(url, ch));
    }
}

function startHls(url, ch) {
    if (typeof Hls === 'undefined') {
        console.warn('hls.js not loaded, using native playback');
        dom.video.src = url;
        dom.video.play().catch(handlePlayError);
        return;
    }
    if (!Hls.isSupported()) {
        dom.video.src = url;
        dom.video.play().catch(handlePlayError);
        return;
    }

    const hls = new Hls({
        enableWorker: false,
        lowLatencyMode: false,
        backbufferLength: 30,
        maxBufferLength: 30,
        maxMaxBufferLength: 60,
        // 超时：m3u8 10秒，ts 分片 30秒（某些源分片大 11MB+，下载慢）
        manifestLoadingTimeOut: 10000,
        levelLoadingTimeOut: 10000,
        fragLoadingTimeOut: 30000,
    });

    // 同一源只试 1 次，失败立即切下一个备用源（快速切源优先，不等待）
    let networkRetryCount = 0;
    const MAX_NETWORK_RETRY = 1;

    hls.on(Hls.Events.MANIFEST_PARSED, () => {
        // manifest 解析成功 → 清除加载超时（说明源是活的）
        clearLoadTimeout();
        // 信号指示器：已连接，显示码率
        const bitrate = hls.levels && hls.levels.length > 0
            ? Math.round((hls.levels[hls.currentLevel >= 0 ? hls.currentLevel : 0] || {}).bitrate / 1000 || 0)
            : 0;
        updateSignalBadge('connected', bitrate);
        if (hls.levels.length > 1) {
            hls.currentLevel = -1;
        }
        updateQualityButtonText(); // 更新画质按钮状态
        dom.video.play().catch(handlePlayError);

        // 播放启动超时：如果 5 秒内视频仍未开始播放（可能是编码不兼容如 MP2 音频），
        // 自动尝试下一个备用源，避免永远卡在"正在连接"
        clearTimeout(state._playStartTimer);
        state._playStartTimer = setTimeout(() => {
            if (!state.isPlaying && state.isLoading) {
                console.warn('[HLS] 播放启动超时（可能编码不兼容），切换备用源');
                destroyPlayer();
                trySwitchSource();
            }
        }, 5000);
    });

    hls.on(Hls.Events.ERROR, (_, data) => {
        if (data.fatal) {
            switch (data.type) {
                case Hls.ErrorTypes.NETWORK_ERROR:
                    // 网络错误（源连不上）：有限重试，超过次数就切备用源
                    networkRetryCount++;
                    if (networkRetryCount <= MAX_NETWORK_RETRY) {
                        hls.startLoad();
                    } else {
                        const curSrc = (state.channels[state.activeIndex].active_source_index || 0) + 1;
                        const total = state.channels[state.activeIndex].sources.length;
                        updateSourceOsd(`⚠️ 源 ${curSrc}/${total} 不可用`, '正在切换下一个源...');
                        destroyPlayer();
                        trySwitchSource();
                    }
                    break;
                case Hls.ErrorTypes.MEDIA_ERROR:
                    hls.recoverMediaError();
                    break;
                default:
                    destroyPlayer();
                    trySwitchSource();
                    break;
            }
        }
    });

    // 码率/网速监控 → 同步更新信号指示器
    // LEVEL_LOADED：只在还没有实时速度时设置初始值（m3u8 声明的固定码率），
    // 一旦 FRAG_LOADED 算出实时速度，不再覆盖，避免实时速度被静态值替换。
    hls.on(Hls.Events.LEVEL_LOADED, (_, data) => {
        if (hls.levels && hls.levels[data.level]) {
            const declaredKbps = Math.round((hls.levels[data.level].bitrate || 0) / 1000);
            // 只在首次（无实时数据）时写入，后续由 FRAG_LOADED 更新
            if (state.lastSpeedKbps <= 0) {
                state.lastSpeedKbps = declaredKbps;
                updateSpeedDisplay();
            }
            updateSignalBadge('connected', state.lastSpeedKbps);
        }
    });

    // LEVEL_SWITCHED：画质切换完成 → 更新画质按钮文字
    hls.on(Hls.Events.LEVEL_SWITCHED, () => {
        updateQualityButtonText();
    });

    // FRAG_LOADED：每个 .ts 分片下载完成后计算真实下载速度（实时变化）
    hls.on(Hls.Events.FRAG_LOADED, (_, data) => {
        if (data.frag && data.frag.stats && data.frag.stats.loading) {
            const stats = data.frag.stats;
            const duration = (stats.loading.end - stats.loading.start) / 1000; // seconds
            if (duration > 0 && stats.total) {
                const speedBps = (stats.total * 8) / duration;
                state.lastSpeedKbps = Math.round(speedBps / 1000);
                state._lastFragLoadedTime = performance.now(); // 记录更新时间
                updateSpeedDisplay();
                updateSignalBadge('connected', state.lastSpeedKbps);
            }
        }
    });

    hls.loadSource(url);
    hls.attachMedia(dom.video);
    state.hls = hls;
    startSpeedMonitor();
}

function destroyPlayer() {
    clearLoadTimeout();
    clearTimeout(state._playStartTimer);
    state._playStartTimer = null;
    if (state.hls) {
        state.hls.destroy();
        state.hls = null;
    }
    dom.video.removeAttribute('src');
    dom.video.load();
    clearInterval(state.speedMeasureInterval);
    state.speedMeasureInterval = null;
    state._lastBufEnd = undefined;
}

/** 启动加载超时：8秒内未开始播放就判定源不可用，自动切下一个备用源。
 * 同时显示倒计时，让用户知道在尝试连接（不是卡死）。*/
function startLoadTimeout() {
    clearLoadTimeout();
    let remaining = 15;
    // 倒计时显示：每秒更新信号徽章，让用户看到"连接中 14s/13s/12s..."
    state.loadCountdown = setInterval(() => {
        remaining--;
        if (remaining >= 0 && state.isLoading) {
            if (dom.signalStatus) {
                dom.signalStatus.textContent = '连接中 ' + remaining + 's';
            }
        }
    }, 1000);
    // 15秒 > manifestLoadingTimeOut(10秒)，让 hls.js 有足够时间加载 m3u8
    state.loadTimeout = setTimeout(() => {
        if (state.isLoading) {
            destroyPlayer();
            trySwitchSource();
        }
    }, 15000);
}

/** 清除加载超时（源成功开始播放时调用）。 */
function clearLoadTimeout() {
    if (state.loadTimeout) {
        clearTimeout(state.loadTimeout);
        state.loadTimeout = null;
    }
    if (state.loadCountdown) {
        clearInterval(state.loadCountdown);
        state.loadCountdown = null;
    }
}

// ── 信号状态指示器（常驻右上角，显示源序号/连接状态/码率/信号格）──

/** 更新信号状态指示器。
 * status: 'connecting' | 'connected' | 'reconnecting' | 'error'
 * bitrateKbps: 码率（可选）
 */
function updateSignalBadge(status, bitrateKbps) {
    if (!dom.signalBadge) return;
    dom.signalBadge.classList.remove('hidden');

    // 源序号
    const ch = state.channels[state.activeIndex];
    if (ch && dom.signalSource) {
        const idx = (ch.active_source_index || 0) + 1;
        const total = (ch.sources || []).length;
        dom.signalSource.textContent = '源 ' + idx + '/' + total;
    }

    // 状态文字 + 颜色
    const statusMap = {
        connecting: { text: '连接中', cls: 'sig-connecting' },
        connected: { text: '已连接', cls: 'sig-connected' },
        reconnecting: { text: '重连中', cls: 'sig-connecting' },
        buffering: { text: '缓冲中', cls: 'sig-connecting' },
        error: { text: '信号差', cls: 'sig-error' },
    };
    const s = statusMap[status] || statusMap.connecting;
    if (dom.signalStatus) {
        dom.signalStatus.textContent = s.text;
        dom.signalStatus.className = 'signal-status ' + s.cls;
    }
    // 实时下载速度由 speedMonitor 每秒更新（updateSpeedDisplay），这里不覆盖

    // 信号格（根据码率/状态决定亮几格）
    updateSignalBars(status, bitrateKbps);
}

/** 根据码率/状态更新信号格数量（1-4格）。 */
function updateSignalBars(status, bitrateKbps) {
    if (!dom.signalBars) return;
    const bars = dom.signalBars.querySelectorAll('.bar');
    let level = 0;
    if (status === 'connected' && bitrateKbps) {
        if (bitrateKbps >= 4000) level = 4;       // 4K/超清
        else if (bitrateKbps >= 2000) level = 3;  // 高清
        else if (bitrateKbps >= 800) level = 2;   // 标清
        else level = 1;                            // 流畅
    } else if (status === 'connecting' || status === 'reconnecting' || status === 'buffering') {
        level = 1;  // 连接中亮1格（闪烁）
    }
    bars.forEach((bar, i) => {
        bar.classList.toggle('active', i < level);
        bar.classList.toggle('pulse', status === 'connecting' || status === 'reconnecting');
    });
}

/** 隐藏信号指示器。 */
function hideSignalBadge() {
    if (dom.signalBadge) dom.signalBadge.classList.add('hidden');
}

/** 更新源状态 OSD（让用户看到"正在尝试源 1/5"、"源1不可用，切换中"等）。 */
function updateSourceOsd(text, sub = '') {
    if (!dom.sourceOsd || !dom.sourceOsdText) return;
    dom.sourceOsdText.textContent = text;
    if (dom.sourceOsdSub) dom.sourceOsdSub.textContent = sub;
    dom.sourceOsd.classList.remove('hidden');
    dom.sourceOsd.classList.add('show');
}

/** 隐藏源状态 OSD（播放成功后调用）。 */
function hideSourceOsd() {
    if (!dom.sourceOsd) return;
    dom.sourceOsd.classList.remove('show');
    dom.sourceOsd.classList.add('hidden');
}

function trySwitchSource() {
    const ch = state.channels[state.activeIndex];
    if (!ch) return;

    const currentIdx = ch.active_source_index || 0;
    const sources = ch.sources || [];

    // 终止条件：已尝试的源数 >= 总源数 → 所有源都不可用，停止换源避免无限循环
    state.sourceTriedCount++;
    if (state.sourceTriedCount >= sources.length) {
        showOverlay('error', '所有源均不可用', '请切换到其他频道');
        hideSourceOsd();
        state.isLoading = false;
        return;
    }

    if (sources.length <= 1) {
        showOverlay('error', '播放失败', '该频道暂无可用源');
        hideSourceOsd();
        state.isLoading = false;
        return;
    }

    const nextIdx = (currentIdx + 1) % sources.length;
    // 显示"切换到源 X/N"让用户知道正在换源
    updateSourceOsd(`🔄 切换到源 ${nextIdx + 1}/${sources.length} (尝试 ${state.sourceTriedCount}/${sources.length})`, ch.name);
    showOverlay('loading', `正在切换源 ${nextIdx + 1}/${sources.length}...`, ch.name);
    // 信号指示器：重连中
    updateSignalBadge('reconnecting');

    if (state.mode === 'json') {
        ch.active_source_index = nextIdx;
        ch.url = sources[nextIdx];
        // 同步更新 allChannels 里对应的频道（ch 是引用，state.channels[idx] 已是 ch，无需重赋值）
        const allIdx = state.allChannels.findIndex(c => c.name === ch.name);
        if (allIdx >= 0) {
            state.allChannels[allIdx].active_source_index = nextIdx;
            state.allChannels[allIdx].url = sources[nextIdx];
        }
        setTimeout(() => playChannel(state.activeIndex, true), 500);
        return;
    }

    // API mode
    fetch(`${API_BASE}/api/channels/${ch.id}/switch`, { method: 'POST' })
        .then(r => r.json())
        .then(async data => {
            if (data.status === 'switched') {
                const resp = await fetch(`${API_BASE}/api/channels/${ch.id}`);
                const updated = await resp.json();
                const idx = state.activeIndex;
                state.channels[idx] = updated;
                playChannel(idx, true);
            } else {
                showOverlay('error', '无更多备用源', '所有源均不可用');
                hideSourceOsd();
                state.isLoading = false;
            }
        })
        .catch(() => {
            showOverlay('error', '切换失败', '无法连接服务器');
            hideSourceOsd();
            state.isLoading = false;
        });
}

// ── 画质切换 ──────────────────────────────────────────

/** 根据 level 的宽高返回易读的画质标签 */
function qualityLabel(level) {
    if (!level) return '未知';
    const h = level.height || 0;
    const w = level.width || 0;
    if (h >= 2160 || w >= 3840) return '4K';
    if (h >= 1440 || w >= 2560) return '2K';
    if (h >= 1080 || w >= 1920) return '1080p';
    if (h >= 720 || w >= 1280) return '720p';
    if (h >= 576) return '576p';
    if (h >= 480) return '480p';
    if (h > 0) return h + 'p';
    // 没有宽高信息时用码率
    const bw = level.bitrate || 0;
    if (bw >= 8000000) return '4K';
    if (bw >= 4000000) return '1080p';
    if (bw >= 2000000) return '720p';
    return '标清';
}

/** 更新画质按钮上的文字（显示当前画质） */
function updateQualityButtonText() {
    if (!dom.btnQuality) return;
    if (!state.hls || !state.hls.levels || state.hls.levels.length <= 1) {
        // 只有一个画质或无 HLS → 隐藏按钮
        dom.btnQuality.style.display = 'none';
        return;
    }
    dom.btnQuality.style.display = '';
    const current = state.hls.currentLevel;
    if (current < 0) {
        // 自动模式：显示当前实际播放画质 + "自动" 标记
        const autoLevel = state.hls.levels[state.hls.currentProgram ? state.hls.currentProgram[0] : -1]
            || state.hls.streamController?.currentLevel;
        const actualIdx = (autoLevel != null && autoLevel >= 0) ? autoLevel : 0;
        const label = state.hls.levels[actualIdx] ? qualityLabel(state.hls.levels[actualIdx]) : '自动';
        dom.btnQuality.textContent = label + ' ⚡';
    } else {
        dom.btnQuality.textContent = qualityLabel(state.hls.levels[current]);
    }
}

/** 显示画质选择弹窗 */
function toggleQualityPopup() {
    if (!dom.qualityPopup || !state.hls) return;
    const levels = state.hls.levels || [];
    if (levels.length <= 1) return; // 只有一个画质不显示

    if (dom.qualityPopup.classList.contains('hidden')) {
        dom.qualityPopup.innerHTML = '';
        const currentIdx = state.hls.currentLevel;

        // 自动选项
        const autoItem = document.createElement('div');
        autoItem.className = 'source-popup-item' + (currentIdx < 0 ? ' active' : '');
        autoItem.innerHTML = '🔄 自动 <span class="quality-badge">推荐</span>';
        autoItem.addEventListener('click', (e) => {
            e.stopPropagation();
            state.hls.currentLevel = -1; // 自动
            updateQualityButtonText();
            dom.qualityPopup.classList.add('hidden');
        });
        dom.qualityPopup.appendChild(autoItem);

        // 各画质选项（按分辨率从高到低排序）
        const sorted = levels.map((lvl, i) => ({ lvl, i }))
            .sort((a, b) => (b.lvl.height || 0) - (a.lvl.height || 0));
        for (const { lvl, i } of sorted) {
            const item = document.createElement('div');
            item.className = 'source-popup-item' + (i === currentIdx ? ' active' : '');
            const label = qualityLabel(lvl);
            const bw = lvl.bitrate ? ` · ${Math.round(lvl.bitrate / 1000)}kbps` : '';
            item.innerHTML = label + bw;
            item.addEventListener('click', (e) => {
                e.stopPropagation();
                state.hls.currentLevel = i; // 手动选择
                updateQualityButtonText();
                dom.qualityPopup.classList.add('hidden');
            });
            dom.qualityPopup.appendChild(item);
        }

        dom.qualityPopup.classList.remove('hidden');
    } else {
        dom.qualityPopup.classList.add('hidden');
    }
}

// ── 音轨切换 (hls.js) ──────────────────────────────────────

function toggleAudioTrackPopup() {
    if (!dom.sourcePopup || !state.hls) return;
    const tracks = state.hls.audioTracks || [];
    if (tracks.length <= 1) {
        showOsd('当前只有一个音轨');
        return;
    }
    if (dom.sourcePopup.classList.contains('hidden')) {
        dom.sourcePopup.innerHTML = '';
        for (let i = 0; i < tracks.length; i++) {
            const t = tracks[i];
            const item = document.createElement('div');
            item.className = 'source-popup-item' + (i === state.hls.audioTrack ? ' active' : '');
            item.innerHTML = (t.name || t.lang || '音轨 ' + (i + 1)) + (i === state.hls.audioTrack ? ' ✓' : '');
            item.addEventListener('click', (e) => {
                e.stopPropagation();
                state.hls.audioTrack = i;
                showOsd('音轨: ' + (t.name || t.lang || '#' + (i + 1)));
                dom.sourcePopup.classList.add('hidden');
            });
            dom.sourcePopup.appendChild(item);
        }
        dom.sourcePopup.classList.remove('hidden');
    } else {
        dom.sourcePopup.classList.add('hidden');
    }
}

// ── 字幕切换 (hls.js) ──────────────────────────────────────

function toggleSubtitlePopup() {
    if (!dom.qualityPopup || !state.hls) return;
    const tracks = state.hls.subtitleTracks || [];
    if (dom.qualityPopup.classList.contains('hidden')) {
        dom.qualityPopup.innerHTML = '';
        // 关闭字幕选项
        const offItem = document.createElement('div');
        offItem.className = 'source-popup-item' + (state.hls.subtitleDisplay === false ? ' active' : '');
        offItem.innerHTML = '🚫 关闭字幕';
        offItem.addEventListener('click', (e) => {
            e.stopPropagation();
            state.hls.subtitleTrack = -1;
            showOsd('字幕已关闭');
            dom.qualityPopup.classList.add('hidden');
        });
        dom.qualityPopup.appendChild(offItem);
        for (let i = 0; i < tracks.length; i++) {
            const t = tracks[i];
            const item = document.createElement('div');
            item.className = 'source-popup-item' + (i === state.hls.subtitleTrack ? ' active' : '');
            item.innerHTML = (t.name || t.lang || '字幕 ' + (i + 1)) + (i === state.hls.subtitleTrack ? ' ✓' : '');
            item.addEventListener('click', (e) => {
                e.stopPropagation();
                state.hls.subtitleTrack = i;
                showOsd('字幕: ' + (t.name || t.lang || '#' + (i + 1)));
                dom.qualityPopup.classList.add('hidden');
            });
            dom.qualityPopup.appendChild(item);
        }
        if (tracks.length === 0) {
            dom.qualityPopup.innerHTML = '<div class="source-popup-item">无可用字幕</div>';
        }
        dom.qualityPopup.classList.remove('hidden');
    } else {
        dom.qualityPopup.classList.add('hidden');
    }
}

// ── 多源测速（测试所有源的速度，返回最快源的索引）─────────

function testSourceSpeed(url) {
    return new Promise((resolve) => {
        const start = performance.now();
        const controller = (typeof AbortController !== 'undefined') ? new AbortController() : null;
        const timeout = setTimeout(() => {
            if (controller) controller.abort();
            resolve(Infinity);
        }, 5000);
        fetch(url, { method: 'HEAD', mode: 'no-cors', signal: controller ? controller.signal : undefined })
            .then(() => {
                clearTimeout(timeout);
                resolve(performance.now() - start);
            })
            .catch(() => {
                clearTimeout(timeout);
                resolve(Infinity);
            });
    });
}

async function autoSelectFastestSource() {
    const ch = state.channels[state.activeIndex];
    if (!ch || !ch.sources || ch.sources.length <= 1) return;
    showOsd('正在测速选择最快源...');
    const speeds = [];
    for (const src of ch.sources) {
        const ms = await testSourceSpeed(proxyStreamUrl(src));
        speeds.push(ms);
    }
    let fastestIdx = 0;
    let fastestMs = speeds[0];
    for (let i = 1; i < speeds.length; i++) {
        if (speeds[i] < fastestMs) { fastestMs = speeds[i]; fastestIdx = i; }
    }
    if (fastestIdx !== (ch.active_source_index || 0)) {
        switchToSource(fastestIdx);
    } else {
        showOsd('当前已是最快源');
    }
}

/** 手动选择源（由控制栏源按钮触发） */
function switchToSource(sourceIndex) {
    const ch = state.channels[state.activeIndex];
    if (!ch) return;
    const sources = ch.sources || [];
    if (sourceIndex < 0 || sourceIndex >= sources.length) return;

    ch.active_source_index = sourceIndex;
    ch.url = sources[sourceIndex];
    const idx = state.activeIndex;
    state.channels[idx] = ch;

    // 更新 allChannels 中的对应频道
    const allIdx = state.allChannels.findIndex(c => c.name === ch.name);
    if (allIdx >= 0) {
        state.allChannels[allIdx] = { ...state.allChannels[allIdx], active_source_index: sourceIndex, url: sources[sourceIndex] };
    }

    dom.sourcePopup.classList.add('hidden');
    playChannel(idx);
}

function handlePlayError(err) {
    console.error('Playback error:', err);
    // HLS 模式下 play() 失败通常是编码不兼容（如 MP2 音频），直接换源
    if (state.hls) {
        console.warn('[HLS] play() 失败，编码可能不兼容，切换备用源');
        destroyPlayer();
        trySwitchSource();
        return;
    }
    // native 播放失败：重试当前频道
    state.retryCount++;
    if (state.retryCount <= state.maxRetries) {
        setTimeout(() => playChannel(state.activeIndex), 1000);
    } else {
        showOverlay('error', '播放失败', '已达到最大重试次数');
        hideSourceOsd();
        updateSignalBadge('error');
        state.isLoading = false;
    }
}

// ── UI: overlay ─────────────────────────────────────────

function showOverlay(type, text, subtext = '') {
    dom.overlay.classList.remove('hidden');
    dom.spinner.classList.add('hidden');
    dom.errorEl.classList.add('hidden');

    switch (type) {
        case 'loading':
            dom.overlayIcon.textContent = '📡';
            dom.spinner.classList.remove('hidden');
            dom.overlayText.textContent = text;
            dom.overlaySubtext.textContent = subtext;
            break;
        case 'error':
            dom.overlayIcon.textContent = '⚠️';
            dom.errorEl.classList.remove('hidden');
            dom.errorMsg.textContent = text;
            dom.overlaySubtext.textContent = subtext;
            break;
        case 'empty':
            dom.overlayIcon.textContent = '📺';
            dom.overlayText.textContent = text || '点击屏幕选择频道';
            dom.overlaySubtext.textContent = subtext || '';
            break;
    }
}

function hideOverlay() {
    dom.overlay.classList.add('hidden');
}

// ── UI: now playing + controls info ─────────────────

function updateNowPlaying(ch) {
    updateControlsInfo(ch);
}

function updateControlsInfo(ch) {
    if (dom.controlsChannelName) dom.controlsChannelName.textContent = ch.name || '未选择';
    if (dom.controlsGroupName) dom.controlsGroupName.textContent = ch.group || '';
    // 更新播放/暂停按钮
    if (dom.btnPlayPause) {
        dom.btnPlayPause.textContent = (state.isPlaying && !state.videoEl.paused) ? '⏸' : '▶';
    }
}

// ── UI: channel list (分页渲染 + Logo 按需加载) ────────────

// 初始化 IntersectionObserver 用于 Logo 按需加载
function initLogoObserver() {
    if (state.logoObserver) return;
    
    state.logoObserver = new IntersectionObserver((entries) => {
        for (const entry of entries) {
            if (entry.isIntersecting) {
                const img = entry.target;
                const src = img.dataset.src;
                if (src) {
                    img.src = src;
                    img.removeAttribute('data-src');
                }
                state.logoObserver.unobserve(img);
            }
        }
    }, {
        rootMargin: '200px',  // 提前 200px 开始加载，确保流畅
        threshold: 0
    });
}

// 构建分组结构（仅数据，不渲染 DOM）
function buildChannelGroups() {
    const groups = new Map();
    state.channels.forEach((ch, i) => {
        const g = ch.group || '未分组';
        if (!groups.has(g)) groups.set(g, []);
        groups.get(g).push({ ch, i });
    });

    const favNames = new Set(state.favorites);
    const favChannels = [];
    const nonFavByGroup = new Map();

    for (const [groupName, items] of groups) {
        for (const { ch, i } of items) {
            if (favNames.has(ch.name)) {
                favChannels.push({ ch, i });
            } else {
                if (!nonFavByGroup.has(groupName)) nonFavByGroup.set(groupName, []);
                nonFavByGroup.get(groupName).push({ ch, i });
            }
        }
    }

    return { favChannels, nonFavByGroup };
}

// 将扁平化的频道列表转换为带分组标签的有序列表（仅返回指定范围）
function buildRenderQueue(startIdx, endIdx) {
    const { favChannels, nonFavByGroup } = buildChannelGroups();
    const queue = [];
    let globalIdx = 0;
    
    // 收藏优先
    if (favChannels.length > 0) {
        if (globalIdx >= startIdx && globalIdx < endIdx) {
            queue.push({ type: 'label', text: '⭐ 我的收藏' });
        }
        globalIdx++;
        
        for (const item of favChannels) {
            if (globalIdx >= startIdx && globalIdx < endIdx) {
                queue.push({ type: 'channel', ...item, isFav: true });
            }
            globalIdx++;
        }
    }
    
    // 分组频道
    for (const [groupName, items] of nonFavByGroup) {
        if (globalIdx >= startIdx && globalIdx < endIdx) {
            queue.push({ type: 'label', text: groupName });
        }
        globalIdx++;
        
        for (const item of items) {
            if (globalIdx >= startIdx && globalIdx < endIdx) {
                queue.push({ type: 'channel', ...item, isFav: false });
            }
            globalIdx++;
        }
    }
    
    return { items: queue, totalCount: globalIdx };
}

function renderChannelList() {
    // 停止之前的 Logo Observer
    if (state.logoObserver) {
        state.logoObserver.disconnect();
        state.logoObserver = null;
    }
    initLogoObserver();
    
    dom.channelList.innerHTML = '';
    state.renderedCount = 0;
    
    if (!state.channels.length) {
        dom.channelList.innerHTML = '<div class="group-label" style="padding:40px;text-align:center;color:var(--text-secondary);">暂无频道</div>';
        return;
    }

    // 初始渲染前一批
    loadMoreChannels(true);
    
    // 设置滚动加载更多
    setupScrollLoadMore();
    
    scrollToFocus();
}

function loadMoreChannels(isInitial = false) {
    if (state.loadingMore) return;
    state.loadingMore = true;
    
    const startIdx = state.renderedCount;
    const { items, totalCount } = buildRenderQueue(startIdx, startIdx + state.renderBatchSize);
    state._totalChannelCount = totalCount; // 保存总数用于判断是否还有更多
    
    for (const item of items) {
        if (item.type === 'label') {
            const label = document.createElement('div');
            label.className = 'group-label';
            label.textContent = item.text;
            if (!isInitial && dom.channelList.children.length > 0) {
                label.style.marginTop = '8px';
            }
            dom.channelList.appendChild(label);
        } else {
            renderChannelItem(item.ch, item.i, item.isFav);
        }
    }
    
    state.renderedCount = startIdx + items.length;
    state.loadingMore = false;
    
    // 更新加载更多指示器
    updateLoadMoreIndicator();
}

function setupScrollLoadMore() {
    const container = dom.channelList;
    if (!container) return;
    
    // 使用 scroll 事件检测滚动到底部
    const onScroll = () => {
        const scrollBottom = container.scrollTop + container.clientHeight;
        const threshold = container.scrollHeight - 200; // 距底部 200px 时加载
        const totalCount = state._totalChannelCount || (state.channels.length + 100);
        
        if (scrollBottom >= threshold && state.renderedCount < totalCount) {
            loadMoreChannels();
        }
    };
    
    // 移除旧的监听器并添加新的
    container.removeEventListener('scroll', onScroll);
    container.addEventListener('scroll', onScroll, { passive: true });
}

function updateLoadMoreIndicator() {
    // 移除旧的指示器
    const oldIndicator = dom.channelList.querySelector('.load-more-indicator');
    if (oldIndicator) oldIndicator.remove();
    
    // 检查是否还有更多内容
    const totalCount = state._totalChannelCount || (state.channels.length + 100);
    if (state.renderedCount < totalCount) {
        const indicator = document.createElement('div');
        indicator.className = 'load-more-indicator';
        indicator.textContent = '向下滚动加载更多...';
        indicator.style.cssText = 'padding:16px;text-align:center;color:var(--text-secondary);font-size:13px;';
        dom.channelList.appendChild(indicator);
    }
}

function renderChannelItem(ch, i, isFav) {
    const item = document.createElement('div');
    item.className = 'channel-item';
    if (i === state.activeIndex) item.classList.add('active');
    if (i === state.focusIndex) item.classList.add('focused');
    if (isFav) item.classList.add('favorite');
    item.dataset.index = i;

    // Logo - 使用 IntersectionObserver 实现真正的按需加载
    const logoDiv = document.createElement('div');
    logoDiv.className = 'channel-logo';
    if (ch.logo) {
        const img = document.createElement('img');
        img.alt = '';
        img.loading = 'lazy';
        img.decoding = 'async';
        img.dataset.src = proxyLogoUrl(ch.logo);
        img.onerror = function() {
            this.onerror = null;
            this.style.display = 'none';
            const parent = this.parentElement;
            if (parent) parent.textContent = ch.name.slice(0, 2);
        };
        logoDiv.appendChild(img);
        // 注册到 Observer，只有进入视口才真正加载
        if (state.logoObserver) {
            state.logoObserver.observe(img);
        } else {
            // Fallback: 直接加载
            img.src = img.dataset.src;
        }
    } else {
        logoDiv.textContent = ch.name.slice(0, 2);
    }

    // Info
    const info = document.createElement('div');
    info.className = 'channel-info';

    const nameEl = document.createElement('div');
    nameEl.className = 'channel-name';
    nameEl.textContent = ch.name;

    const statusEl = document.createElement('div');
    statusEl.className = `channel-status ${ch.healthy ? 'healthy' : 'dead'}`;
    statusEl.textContent = ch.healthy ? (ch.last_response_time ? `${(ch.last_response_time * 1000).toFixed(0)}ms` : '正常') : '离线';

    info.appendChild(nameEl);
    info.appendChild(statusEl);

    item.appendChild(logoDiv);
    item.appendChild(info);

    // 收藏星标
    if (isFav) {
        const star = document.createElement('span');
        star.className = 'channel-fav-star';
        star.textContent = '⭐';
        item.appendChild(star);
    }

    // 点击播放
    item.addEventListener('click', () => {
        setActiveFocus(i);
        playChannel(i);
        closeSidebar();
    });

    // 长按收藏/取消收藏
    item.addEventListener('mousedown', (e) => {
        state.longPressTriggered = false;
        state.longPressTimer = setTimeout(() => {
            state.longPressTriggered = true;
            toggleFavorite(ch.name);
        }, 600);
    });
    item.addEventListener('mouseup', () => clearTimeout(state.longPressTimer));
    item.addEventListener('mouseleave', () => clearTimeout(state.longPressTimer));

    // 触摸长按
    item.addEventListener('touchstart', (e) => {
        state.longPressTriggered = false;
        state.longPressTimer = setTimeout(() => {
            state.longPressTriggered = true;
            toggleFavorite(ch.name);
        }, 600);
    }, { passive: true });
    item.addEventListener('touchend', () => clearTimeout(state.longPressTimer));
    item.addEventListener('touchmove', () => clearTimeout(state.longPressTimer));

    dom.channelList.appendChild(item);
}

function scrollToFocus() {
    const items = dom.channelList.querySelectorAll('.channel-item');
    // 只在已渲染的项中查找
    for (let idx = 0; idx < items.length; idx++) {
        if (parseInt(items[idx].dataset.index) === state.focusIndex) {
            items[idx].scrollIntoView({ block: 'nearest', behavior: 'smooth' });
            return;
        }
    }
}

function setActiveFocus(index) {
    state.focusIndex = index;
    state.activeIndex = index;
    
    // 如果目标频道未渲染，加载更多直到包含该频道
    if (index >= state.renderedCount) {
        let maxIterations = 20; // 防止无限循环
        while (index >= state.renderedCount && maxIterations-- > 0) {
            loadMoreChannels();
        }
    } else {
        // 直接重新渲染以更新焦点状态
        renderChannelList();
    }
}

// ── Navigation (remote control / keyboard) ─────────────

function setupNavigation() {
    document.addEventListener('keydown', (e) => {
        // 搜索框聚焦时不拦截方向键
        if (document.activeElement === dom.searchInput) {
            if (e.key === 'Escape') {
                dom.searchInput.blur();
                e.preventDefault();
            }
            return;
        }

        switch (e.key) {
            case 'ArrowUp':
                e.preventDefault();
                changeChannel(-1);
                break;
            case 'ArrowDown':
                e.preventDefault();
                changeChannel(1);
                break;
            case 'ArrowRight':
                e.preventDefault();
                changeVolume(0.1);
                break;
            case 'ArrowLeft':
                e.preventDefault();
                changeVolume(-0.1);
                break;
            case 'Enter':
            case ' ':
                e.preventDefault();
                if (!dom.errorEl.classList.contains('hidden')) {
                    retryPlayback();
                } else if (state.focusIndex !== state.activeIndex) {
                    playChannel(state.focusIndex);
                }
                break;
            case '0':
                e.preventDefault();
                switchTab(state.tabMode === 'fav' ? 'all' : 'fav');
                break;
            // 数字键 1-9：频道号快速跳转
            case '1': case '2': case '3': case '4': case '5':
            case '6': case '7': case '8': case '9':
                e.preventDefault();
                handleChannelNumberDigit(parseInt(e.key, 10));
                break;
            case 'Escape':
                if (state.infoPanelVisible) {
                    hideInfoPanel();
                } else if (state.sidebarOpen) {
                    closeSidebar();
                } else if (state.tabMode !== 'all') {
                    switchTab('all');
                }
                break;
            case 'i':
            case 'I':
                toggleInfoPanel();
                break;
            // 电脑快捷键：M 打开/关闭频道菜单（电脑用户的主要入口）
            case 'm':
            case 'M':
                e.preventDefault();
                if (state.sidebarOpen) closeSidebar();
                else openSidebar();
                break;
            // A 切换音轨 / S 切换字幕 / T 测速选最快源
            case 'a':
            case 'A':
                e.preventDefault();
                toggleAudioTrackPopup();
                break;
            case 's':
            case 'S':
                e.preventDefault();
                toggleSubtitlePopup();
                break;
            case 't':
            case 'T':
                e.preventDefault();
                autoSelectFastestSource();
                break;
            // 切换画面比例: R
            case 'r':
            case 'R':
                e.preventDefault();
                cycleAspectRatio();
                break;
            // 频道排序: G
            case 'g':
            case 'G':
                e.preventDefault();
                cycleSortMode();
                break;
            // 多画面模式: V
            case 'v':
            case 'V':
                e.preventDefault();
                toggleMultiview();
                break;
        }
    });

    // Tab switching
    if (dom.tabAll) dom.tabAll.addEventListener('click', () => switchTab('all'));
    if (dom.tabHealthy) dom.tabHealthy.addEventListener('click', () => switchTab('healthy'));
    if (dom.tabFav) dom.tabFav.addEventListener('click', () => switchTab('fav'));
    if (dom.tabHistory) dom.tabHistory.addEventListener('click', () => switchTab('history'));

    // Region switching
    if (dom.regionDomestic) dom.regionDomestic.addEventListener('click', () => switchRegion('domestic'));
    if (dom.regionInternational) dom.regionInternational.addEventListener('click', () => switchRegion('international'));

    dom.retryBtn.addEventListener('click', retryPlayback);
}

function changeChannel(delta) {
    const len = state.channels.length;
    if (!len) return;
    // 侧边栏打开时：上下键只移动焦点预览（不换台，避免每按一下就 HLS 重建）
    if (state.sidebarOpen) {
        moveFocus(delta);
        return;
    }
    // 侧边栏关闭时：上下键直接切换频道并播放
    let idx = state.activeIndex + delta;
    if (idx < 0) idx = len - 1;
    if (idx >= len) idx = 0;
    state.focusIndex = idx;
    playChannel(idx);
}

async function switchTab(mode) {
    state.tabMode = mode;
    if (dom.tabAll) dom.tabAll.classList.toggle('active', mode === 'all');
    if (dom.tabHealthy) dom.tabHealthy.classList.toggle('active', mode === 'healthy');
    if (dom.tabFav) dom.tabFav.classList.toggle('active', mode === 'fav');
    if (dom.tabHistory) dom.tabHistory.classList.toggle('active', mode === 'history');

    if (state.mode === 'api') {
        await fetchChannels();
    } else {
        applyFiltersAndRender();
    }

    if (state.channels.length && (mode === 'healthy' || mode === 'fav')) {
        playChannel(0);
    }

    resetSidebarTimer();
}

function switchRegion(mode) {
    state.regionMode = mode;
    if (dom.regionDomestic) dom.regionDomestic.classList.toggle('active', mode === 'domestic');
    if (dom.regionInternational) dom.regionInternational.classList.toggle('active', mode === 'international');

    if (state.mode === 'api') {
        fetchChannels();
    } else {
        applyFiltersAndRender();
    }

    if (state.channels.length) {
        playChannel(0);
    }

    resetSidebarTimer();
}

// ── Sidebar interaction ──────────────────────────────

function setupSidebar() {
    // 点击视频区域 → 打开侧边栏
    dom.videoContainer.addEventListener('click', (e) => {
        // 避免点击控制栏内的按钮时触发
        if (e.target.closest('.player-controls') || e.target.closest('.overlay') || e.target.closest('.channel-info-panel')) return;

        if (state.sidebarOpen) {
            closeSidebar();
        } else {
            openSidebar();
        }
    });

    // 触摸视频区域 → 打开侧边栏
    let touchStartY = 0;
    dom.videoContainer.addEventListener('touchstart', (e) => {
        if (e.target.closest('.player-controls') || e.target.closest('.overlay') || e.target.closest('.channel-info-panel')) return;
        touchStartY = e.touches[0].clientY;
    }, { passive: true });

    dom.videoContainer.addEventListener('touchend', (e) => {
        if (e.target.closest('.player-controls') || e.target.closest('.overlay') || e.target.closest('.channel-info-panel')) return;
        const touchEndY = e.changedTouches[0].clientY;
        // 只有短距离触摸才算点击（避免滚动等）
        if (Math.abs(touchEndY - touchStartY) < 20) {
            if (state.sidebarOpen) {
                closeSidebar();
            } else {
                openSidebar();
            }
        }
    });

    // 侧边栏鼠标活动重置自动隐藏计时器
    dom.sidebar.addEventListener('mousemove', () => {
        if (state.sidebarOpen) resetSidebarTimer();
    });
    dom.sidebar.addEventListener('touchstart', () => {
        if (state.sidebarOpen) resetSidebarTimer();
    }, { passive: true });

    // 点击侧边栏外部关闭（点击主区域，但排除菜单按钮本身）
    dom.playerArea.addEventListener('click', (e) => {
        if (state.sidebarOpen
            && !dom.sidebar.contains(e.target)
            && !dom.menuToggle.contains(e.target)) {
            closeSidebar();
        }
    });

    // 常驻菜单按钮：电脑/手机点击打开/关闭侧边栏
    if (dom.menuToggle) {
        dom.menuToggle.addEventListener('click', (e) => {
            e.stopPropagation();   // 阻止冒泡到 playerArea 的"外部点击关闭"
            if (state.sidebarOpen) closeSidebar();
            else openSidebar();
        });
    }
}

// ── Controls bar interaction ─────────────────────────

function setupControls() {
    // 鼠标移入视频区域 → 显示控制栏
    dom.videoContainer.addEventListener('mousemove', showControls);
    // 触屏：无论是否正在播放，点击/触摸都显示控制栏（含网速）
    dom.videoContainer.addEventListener('touchstart', () => {
        showControls();
    }, { passive: true });
    dom.videoContainer.addEventListener('click', showControls);

    // 控制栏按钮
    if (dom.btnPrevCh) dom.btnPrevCh.addEventListener('click', (e) => {
        e.stopPropagation();
        changeChannel(-1);
    });
    if (dom.btnNextCh) dom.btnNextCh.addEventListener('click', (e) => {
        e.stopPropagation();
        changeChannel(1);
    });
    if (dom.btnPlayPause) dom.btnPlayPause.addEventListener('click', (e) => {
        e.stopPropagation();
        togglePlayPause();
    });
    if (dom.btnFullscreen) dom.btnFullscreen.addEventListener('click', (e) => {
        e.stopPropagation();
        toggleFullscreen();
    });
    if (dom.btnSource) dom.btnSource.addEventListener('click', (e) => {
        e.stopPropagation();
        toggleSourcePopup();
    });
    if (dom.btnQuality) dom.btnQuality.addEventListener('click', (e) => {
        e.stopPropagation();
        toggleQualityPopup();
    });
    if (dom.btnAspect) dom.btnAspect.addEventListener('click', (e) => {
        e.stopPropagation();
        cycleAspectRatio();
    });
    if (dom.btnSnapshot) dom.btnSnapshot.addEventListener('click', (e) => {
        e.stopPropagation();
        takeSnapshot();
    });

    // 音量滑块
    if (dom.volumeSlider) {
        dom.volumeSlider.addEventListener('input', () => {
            const v = state.videoEl;
            if (!v) return;
            v.volume = dom.volumeSlider.value / 100;
            v.muted = false;
            syncVolumeSlider();
        });
    }

    // 静音切换
    if (dom.volIcon) {
        dom.volIcon.addEventListener('click', (e) => {
            e.stopPropagation();
            toggleMute();
        });
    }

    // 控制栏鼠标活动重置计时器
    if (dom.playerControls) {
        dom.playerControls.addEventListener('mousemove', () => {
            if (state.controlsVisible) {
                clearTimeout(state.controlsTimer);
                state.controlsTimer = setTimeout(hideControls, 3000);
            }
        });
    }
}

function togglePlayPause() {
    const v = state.videoEl;
    if (!v) return;
    if (v.paused) {
        v.play().catch(() => {});
    } else {
        v.pause();
    }
    // 更新按钮
    if (dom.btnPlayPause) {
        dom.btnPlayPause.textContent = v.paused ? '▶' : '⏸';
    }
}

function toggleFullscreen() {
    if (document.fullscreenElement) {
        document.exitFullscreen();
    } else {
        dom.playerArea.requestFullscreen().catch(() => {});
    }
}

function toggleSourcePopup() {
    const ch = state.channels[state.activeIndex];
    if (!ch) return;
    const sources = ch.sources || [];

    if (dom.sourcePopup.classList.contains('hidden')) {
        // 构建源列表
        dom.sourcePopup.innerHTML = '';
        const currentIdx = ch.active_source_index || 0;

        if (sources.length === 0) {
            const item = document.createElement('div');
            item.className = 'source-popup-item active';
            item.textContent = '单源模式';
            dom.sourcePopup.appendChild(item);
        } else {
            sources.forEach((src, i) => {
                const item = document.createElement('div');
                item.className = 'source-popup-item' + (i === currentIdx ? ' active' : '');
                item.textContent = `源 ${i + 1}: ${src.slice(0, 50)}${src.length > 50 ? '…' : ''}`;
                item.addEventListener('click', (e) => {
                    e.stopPropagation();
                    switchToSource(i);
                });
                dom.sourcePopup.appendChild(item);
            });
        }

        dom.sourcePopup.classList.remove('hidden');
    } else {
        dom.sourcePopup.classList.add('hidden');
    }
}

// ── Search ───────────────────────────────────────────

function setupSearch() {
    if (!dom.searchInput) return;

    dom.searchInput.addEventListener('input', () => {
        state.searchQuery = dom.searchInput.value.trim();
        applyFiltersAndRender();
    });

    dom.searchInput.addEventListener('focus', () => {
        openSidebar();
    });
}

// ── Video events ────────────────────────────────────────

function setupVideoEvents() {
    dom.video.addEventListener('playing', () => {
        hideOverlay();
        clearLoadTimeout();
        hideSourceOsd();
        state.isLoading = false;
        state.isPlaying = true;
        updateControlsInfo(state.channels[state.activeIndex] || {});

        const ch = state.channels[state.activeIndex];
        if (ch) {
            ch.healthy = true;
            renderChannelList();
        }

        if (dom.btnPlayPause) dom.btnPlayPause.textContent = '⏸';

        // 播放开始时立即显示控制栏 + 启动网速监控
        showControls();
        startSpeedMonitor();
    });

    dom.video.addEventListener('pause', () => {
        if (dom.btnPlayPause) dom.btnPlayPause.textContent = '▶';
    });

    dom.video.addEventListener('waiting', () => {
        state.isLoading = true;
        updateSignalBadge('buffering', state.lastSpeedKbps);
    });

    dom.video.addEventListener('stalled', () => {
        state.isLoading = true;
        updateSignalBadge('buffering', state.lastSpeedKbps);
    });

    dom.video.addEventListener('error', () => {
        const err = dom.video.error;
        if (err) {
            const errNames = {1: 'ABORTED', 2: 'NETWORK', 3: 'DECODE', 4: 'SRC_NOT_SUPPORTED'};
            console.error('[Video] error:', errNames[err.code] || err.code, err.message);
            // MEDIA_ERR_DECODE (3) = 编码不兼容（如 MP2 音频），尝试换源
            // 不仅播放中出错要换源，从未播放成功也要换源（编码不兼容时 isPlaying=false）
            if (state.isPlaying || state.isLoading) {
                clearTimeout(state._playStartTimer);
                trySwitchSource();
            }
        }
    });

    dom.video.addEventListener('ended', () => {
        console.log('Video ended, reconnecting...');
        playChannel(state.activeIndex);
    });
}

function setupRetry() {
    // retryBtn 已在 setupNavigation 中绑定，这里不再重复绑定（避免点击触发两次）
}

function retryPlayback() {
    playChannel(state.activeIndex);
}

// ── Start ───────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', init);
