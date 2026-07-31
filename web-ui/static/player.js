/* TV Live Streaming Player — hls.js + remote-control navigation */
/* Maozi TV 重构版：国内/国外分类、侧边栏隐藏、控制栏、收藏、搜索、频道号 */

const API_BASE = window.location.origin;

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
    btnFullscreen: $('#btn-fullscreen'),
    volumeSlider: $('#volume-slider'),
    volIcon: $('#vol-icon'),
    sourcePopup: $('#source-popup'),
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
    if (!state.isPlaying) return;
    dom.playerControls.classList.remove('hidden');
    dom.playerControls.classList.add('show');
    state.controlsVisible = true;
    clearTimeout(state.controlsTimer);
    state.controlsTimer = setTimeout(hideControls, 3000);
}

function hideControls() {
    dom.playerControls.classList.remove('show');
    state.controlsVisible = false;
    // 同时关闭源弹窗
    dom.sourcePopup.classList.add('hidden');
    clearTimeout(state.controlsTimer);
}

// ── Channel number OSD ───────────────────────────────

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
    if (state.hls) {
        clearInterval(state.speedMeasureInterval);
        state.speedMeasureInterval = setInterval(() => {
            if (!state.hls) return;
            // hls.js 提供的统计信息
            const stats = state.hls.stats;
            if (stats && stats.fragLoading && stats.fragLoading.lastLoaded) {
                // 简化：使用 bandwidth 估算
            }
            // 使用 video 的 buffered 来估算
            const v = state.videoEl;
            if (!v || !v.buffered || !v.buffered.length) return;
            try {
                const bufEnd = v.buffered.end(v.buffered.length - 1);
                const bufStart = v.buffered.start(v.buffered.length - 1);
                // 简单估算：下载速率（这里用 HLS 的 bandwidth 如果可用）
                if (state.hls.levels && state.hls.levels.length > 0) {
                    const currentLevel = state.hls.levels[state.hls.currentLevel >= 0 ? state.hls.currentLevel : 0];
                    if (currentLevel && currentLevel.bitrate) {
                        state.lastSpeedKbps = Math.round(currentLevel.bitrate / 1000);
                    }
                }
            } catch (e) {
                // ignore
            }
            updateSpeedDisplay();
        }, 2000);
    }
}

function updateSpeedDisplay() {
    if (dom.controlsSpeed && state.lastSpeedKbps > 0) {
        if (state.lastSpeedKbps > 1000) {
            dom.controlsSpeed.textContent = (state.lastSpeedKbps / 1000).toFixed(1) + ' Mbps';
        } else {
            dom.controlsSpeed.textContent = state.lastSpeedKbps + ' Kbps';
        }
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
    }

    // 3. 搜索过滤
    if (state.searchQuery) {
        const q = state.searchQuery.toLowerCase();
        list = list.filter(ch => ch.name.toLowerCase().includes(q) || (ch.group && ch.group.toLowerCase().includes(q)));
    }

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

    showOverlay('loading', '正在加载...', ch.name);
    updateNowPlaying(ch);
    updateControlsInfo(ch);
    state.isLoading = true;
    renderChannelList();

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

    const url = ch.url;
    if (url.endsWith('.m3u8') || url.includes('m3u8')) {
        startHls(url, ch);
    } else if (url.endsWith('.flv')) {
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
        enableWorker: true,
        lowLatencyMode: true,
        backbufferLength: 30,
        maxBufferLength: 30,
        maxMaxBufferLength: 60,
        // 超时 10 秒：给响应慢的好源足够时间，超时后再切下一个备用源
        manifestLoadingTimeOut: 10000,
        levelLoadingTimeOut: 10000,
        fragLoadingTimeOut: 10000,
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
        dom.video.play().catch(handlePlayError);
    });

    hls.on(Hls.Events.ERROR, (_, data) => {
        if (data.fatal) {
            console.error('HLS fatal error:', data.type, data.details);
            switch (data.type) {
                case Hls.ErrorTypes.NETWORK_ERROR:
                    // 网络错误（源连不上）：有限重试，超过次数就切备用源
                    // （原代码无限 startLoad 同一个死源 → "一直加载"的根因）
                    networkRetryCount++;
                    if (networkRetryCount <= MAX_NETWORK_RETRY) {
                        console.log('Network retry', networkRetryCount);
                        hls.startLoad();
                    } else {
                        // 显示"源 X 不可用，切换中"让用户知道卡在哪
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
    hls.on(Hls.Events.LEVEL_LOADED, (_, data) => {
        if (hls.levels && hls.levels[data.level]) {
            state.lastSpeedKbps = Math.round((hls.levels[data.level].bitrate || 0) / 1000);
            updateSpeedDisplay();
            updateSignalBadge('connected', state.lastSpeedKbps);
        }
    });

    hls.on(Hls.Events.FRAG_LOADED, (_, data) => {
        if (data.frag && data.frag.stats && data.frag.stats.loading) {
            const stats = data.frag.stats;
            const duration = (stats.loading.end - stats.loading.start) / 1000; // seconds
            if (duration > 0 && stats.total) {
                const speedBps = (stats.total * 8) / duration;
                state.lastSpeedKbps = Math.round(speedBps / 1000);
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
    if (state.hls) {
        state.hls.destroy();
        state.hls = null;
    }
    dom.video.removeAttribute('src');
    dom.video.load();
    clearInterval(state.speedMeasureInterval);
}

/** 启动加载超时：12秒内未开始播放就判定源不可用，自动切下一个备用源。 */
function startLoadTimeout() {
    clearLoadTimeout();
    state.loadTimeout = setTimeout(() => {
        if (state.isLoading) {
            console.warn('Load timeout (12s), switching source...');
            destroyPlayer();
            trySwitchSource();
        }
    }, 12000);
}

/** 清除加载超时（源成功开始播放时调用）。 */
function clearLoadTimeout() {
    if (state.loadTimeout) {
        clearTimeout(state.loadTimeout);
        state.loadTimeout = null;
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

    // 码率
    if (dom.signalBitrate) {
        if (bitrateKbps && bitrateKbps > 0) {
            dom.signalBitrate.textContent = bitrateKbps > 1000
                ? (bitrateKbps / 1000).toFixed(1) + ' Mbps'
                : bitrateKbps + ' Kbps';
        } else {
            dom.signalBitrate.textContent = '';
        }
    }

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
    // 不再调用 trySwitchSource（HLS 错误处理已负责换源），
    // 这里只处理 native 播放的瞬时错误，直接重试当前频道而非换源，避免与 HLS 错误叠加死循环。
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

// ── UI: channel list ───────────────────────────────────

function renderChannelList() {
    dom.channelList.innerHTML = '';
    if (!state.channels.length) {
        dom.channelList.innerHTML = '<div class="group-label" style="padding:40px;text-align:center;color:var(--text-secondary);">暂无频道</div>';
        return;
    }

    // 按分组聚集渲染
    const groups = new Map();
    state.channels.forEach((ch, i) => {
        const g = ch.group || '未分组';
        if (!groups.has(g)) groups.set(g, []);
        groups.get(g).push({ ch, i });
    });

    // 收藏优先
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

    // 渲染收藏区
    if (favChannels.length > 0) {
        const label = document.createElement('div');
        label.className = 'group-label';
        label.textContent = '⭐ 我的收藏';
        dom.channelList.appendChild(label);

        for (const { ch, i } of favChannels) {
            renderChannelItem(ch, i, true);
        }
    }

    // 渲染分组频道
    for (const [groupName, items] of nonFavByGroup) {
        const label = document.createElement('div');
        label.className = 'group-label';
        label.textContent = groupName;
        if (favChannels.length > 0) label.style.marginTop = '8px';
        dom.channelList.appendChild(label);

        for (const { ch, i } of items) {
            renderChannelItem(ch, i, false);
        }
    }

    scrollToFocus();
}

function renderChannelItem(ch, i, isFav) {
    const item = document.createElement('div');
    item.className = 'channel-item';
    if (i === state.activeIndex) item.classList.add('active');
    if (i === state.focusIndex) item.classList.add('focused');
    if (isFav) item.classList.add('favorite');
    item.dataset.index = i;

    // Logo
    const logoDiv = document.createElement('div');
    logoDiv.className = 'channel-logo';
    if (ch.logo) {
        logoDiv.innerHTML = `<img src="${ch.logo}" alt="" onerror="this.parentElement.textContent='${ch.name.slice(0, 2)}'">`;
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
    if (items[state.focusIndex]) {
        items[state.focusIndex].scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    }
}

function setActiveFocus(index) {
    state.focusIndex = index;
    state.activeIndex = index;
    renderChannelList();
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
        }
    });

    // Tab switching
    if (dom.tabAll) dom.tabAll.addEventListener('click', () => switchTab('all'));
    if (dom.tabHealthy) dom.tabHealthy.addEventListener('click', () => switchTab('healthy'));
    if (dom.tabFav) dom.tabFav.addEventListener('click', () => switchTab('fav'));

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
    dom.videoContainer.addEventListener('touchstart', () => {
        if (state.isPlaying) showControls();
    }, { passive: true });

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
        clearLoadTimeout();       // 已开始播放，取消超时保护
        hideSourceOsd();          // 播放成功，隐藏源状态 OSD
        // 信号指示器：已连接，持续显示码率
        updateSignalBadge('connected', state.lastSpeedKbps);
        state.isLoading = false;
        state.isPlaying = true;
        updateControlsInfo(state.channels[state.activeIndex] || {});

        const ch = state.channels[state.activeIndex];
        if (ch) {
            ch.healthy = true;
            renderChannelList();
        }

        // 更新播放/暂停按钮
        if (dom.btnPlayPause) dom.btnPlayPause.textContent = '⏸';
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
            console.error('Video error:', err.code, err.message);
            if (state.isPlaying) {
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
