/* TV Live Streaming Player — hls.js + remote-control navigation */

const API_BASE = window.location.origin;

// ── State ───────────────────────────────────────────────

const state = {
    channels: [],
    activeIndex: 0,
    focusIndex: 0,
    isPlaying: false,
    isLoading: false,
    retryCount: 0,
    maxRetries: 3,
    hls: null,
    videoEl: null,
    tabMode: 'all',       // 'all' | 'healthy' | 'fav'
    mode: 'api',          // 'api' = fetch from backend, 'json' = injected by host app
    jsonInitDone: null,   // resolve callback for initFromJson
    // ── 收藏：localStorage 持久化的频道名集合 ──
    favorites: loadFavorites(),
    // ── 音量 OSD ──
    volumeOsdTimer: null,
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

/** Toggle favorite status for the current focused channel. Called by long-press OK. */
function toggleFavorite() {
    const ch = state.channels[state.focusIndex];
    if (!ch) return;
    const idx = state.favorites.indexOf(ch.name);
    let msg;
    if (idx >= 0) {
        state.favorites.splice(idx, 1);
        msg = '已取消收藏: ' + ch.name;
    } else {
        state.favorites.push(ch.name);
        msg = '已收藏: ' + ch.name;
    }
    saveFavorites();
    showOsd(msg);
    renderChannelList();
}
// Expose to Android host (long-press OK → window.toggleFavorite)
window.toggleFavorite = toggleFavorite;

/** Play the currently focused channel. Called by short-press OK (Android onKeyUp). */
function playFocusedChannel() {
    // 报错重试遮罩可见时，触发重试；否则播放焦点频道
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

/** Generic on-screen toast (centered, auto-hide). Used for favorite feedback. */
function showOsd(text) {
    const toast = dom.toast;
    if (!toast) return;
    toast.textContent = text;
    toast.classList.add('show');
    clearTimeout(state._toastTimer);
    state._toastTimer = setTimeout(() => toast.classList.remove('show'), 1800);
}

// ── Update data source (pull fresh channels.json) ───────────

/** Refresh channel config. In JSON mode, asks Android host to re-pull;
 *  in API mode, re-fetches from backend. Exposed to Android UI button. */
window.refreshChannels = function () {
    showOsd('正在更新频道源...');
    if (state.mode === 'json' && window.AndroidBridge && window.AndroidBridge.refreshChannels) {
        // Ask Android host to force re-pull channels.json (ignore cache)
        window.AndroidBridge.refreshChannels();
        return;
    }
    // API mode: just re-fetch
    fetchChannels().then(() => showOsd('频道列表已刷新'));
};

// ── Data injection (called by Android WebView) ────────────

/**
 * Called by the Android host app to inject channel data directly,
 * bypassing the need for a backend API server.
 * @param {Object} data - The parsed channels.json object
 */
window.initFromJson = function (data) {
    // 容错：若传入的是字符串（JSON 文本），先解析。Android 端也可能直接传对象。
    if (typeof data === 'string') {
        try { data = JSON.parse(data); }
        catch (e) { console.error('initFromJson: JSON parse failed', e); return; }
    }
    if (!data || !data.channels || !data.channels.length) {
        console.error('initFromJson: invalid data', data);
        return;
    }
    state.mode = 'json';
    state.channels = data.channels;
    console.log(`initFromJson: loaded ${data.channels.length} channels (v${data.version})`);

    // Reset indices
    if (state.activeIndex >= state.channels.length) state.activeIndex = 0;
    if (state.focusIndex >= state.channels.length) state.focusIndex = 0;

    renderChannelList();

    // If init() is waiting for jsonInitDone, resolve it
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
    nowPlayingName: $('#now-playing-name'),
    nowPlayingSrc: $('#now-playing-source'),
    qualityDot: $('#quality-dot'),
    tabAll: $('#tab-all'),
    tabHealthy: $('#tab-healthy'),
    tabFav: $('#tab-fav'),           // ⭐ 收藏 tab
    volumeOsd: $('#volume-osd'),     // 音量 OSD
    toast: $('#toast'),              // 通用提示
};

// ── Init ────────────────────────────────────────────────

async function init() {
    state.videoEl = dom.video;
    dom.video.volume = 1.0;

    // Setup remote control / keyboard
    setupNavigation();
    setupVideoEvents();
    setupRetry();

    // Fetch channels (API mode) or wait for host app injection (JSON mode)
    await fetchChannels();

    // Start playback if we have channels
    if (state.channels.length) {
        playChannel(state.activeIndex);
    } else {
        showOverlay('empty', '正在加载频道...', '请稍候');
    }

    // Periodic health refresh (only in API mode)
    if (state.mode === 'api') {
        setInterval(refreshHealth, 30_000);
    }

    console.log('TV App initialized (mode: ' + state.mode + ')');
}

// ── API ─────────────────────────────────────────────────

async function fetchChannels() {
    // In JSON mode, wait for host app to inject data (or already done)
    if (state.mode === 'json') {
        if (state.channels.length) return; // already injected by initFromJson
        // Wait up to 15s for host app to call initFromJson
        await new Promise((resolve) => {
            state.jsonInitDone = resolve;
            setTimeout(() => {
                if (state.jsonInitDone) {
                    state.jsonInitDone = null;
                    resolve();
                }
            }, 15000);
        });
        if (!state.channels.length) {
            showOverlay('error', '未收到频道数据', '请检查配置');
        }
        return;
    }

    // API mode: fetch from backend. 'fav' 模式先取全量再本地按收藏过滤。
    const wantFav = state.tabMode === 'fav';
    const url = (state.tabMode === 'healthy')
        ? `${API_BASE}/api/channels?visible_only=true&healthy_only=true`
        : `${API_BASE}/api/channels?visible_only=true`;

    try {
        const resp = await fetch(url);
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        let list = await resp.json();
        if (wantFav) {
            const favSet = new Set(state.favorites);
            list = list.filter(ch => favSet.has(ch.name));
        }
        state.channels = list;

        // Reset indices if out of bounds
        if (state.activeIndex >= state.channels.length) {
            state.activeIndex = 0;
        }
        if (state.focusIndex >= state.channels.length) {
            state.focusIndex = 0;
        }

        renderChannelList();
    } catch (err) {
        console.error('Failed to fetch channels:', err);
        showOverlay('error', '无法连接服务器', '请检查后端服务是否运行');
    }
}

async function refreshHealth() {
    // In JSON mode, no health check needed
    if (state.mode === 'json') return;
    // Silently update health status in background
    try {
        const resp = await fetch(`${API_BASE}/api/summary`);
        if (!resp.ok) return;
        // Re-fetch full channel list to get updated statuses
        await fetchChannels();
    } catch {
        // silent
    }
}

// ── Playback ────────────────────────────────────────────

function playChannel(index) {
    if (!state.channels.length || index < 0 || index >= state.channels.length) return;

    const ch = state.channels[index];
    state.activeIndex = index;
    state.retryCount = 0;

    if (!ch.url) {
        showOverlay('error', '频道无可用源', '请切换到其他频道');
        updateNowPlaying(ch);
        return;
    }

    // Show loading
    showOverlay('loading', '正在加载...', ch.name);
    updateNowPlaying(ch);
    state.isLoading = true;
    renderChannelList();

    // Destroy previous HLS instance
    destroyPlayer();

    // Determine playback method
    const url = ch.url;
    if (url.endsWith('.m3u8') || url.includes('m3u8')) {
        startHls(url, ch);
    } else if (url.endsWith('.flv')) {
        // For FLV, just set the source directly (browser may or may not support)
        dom.video.src = url;
        dom.video.play().catch(handlePlayError);
    } else {
        // Try direct video source (mp4, etc.) or m3u8 as fallback
        dom.video.src = url;
        dom.video.play().catch(() => startHls(url, ch));
    }
}

function startHls(url, ch) {
    // If hls.js failed to load entirely (CDN blocked), fall back to native playback
    if (typeof Hls === 'undefined') {
        console.warn('hls.js not loaded, using native playback');
        dom.video.src = url;
        dom.video.play().catch(handlePlayError);
        return;
    }
    if (!Hls.isSupported()) {
        // Fallback: native HLS (Safari)
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
        manifestLoadingTimeOut: 10000,
        levelLoadingTimeOut: 10000,
        fragLoadingTimeOut: 10000,
    });

    hls.on(Hls.Events.MANIFEST_PARSED, () => {
        // Auto-select highest quality
        if (hls.levels.length > 1) {
            hls.currentLevel = -1; // auto
        }
        dom.video.play().catch(handlePlayError);
    });

    hls.on(Hls.Events.ERROR, (_, data) => {
        if (data.fatal) {
            console.error('HLS fatal error:', data.type, data.details);
            switch (data.type) {
                case Hls.ErrorTypes.NETWORK_ERROR:
                    // Try to recover network error
                    hls.startLoad();
                    break;
                case Hls.ErrorTypes.MEDIA_ERROR:
                    hls.recoverMediaError();
                    break;
                default:
                    // Fatal — try switching source
                    destroyPlayer();
                    trySwitchSource();
                    break;
            }
        }
    });

    hls.loadSource(url);
    hls.attachMedia(dom.video);
    state.hls = hls;
}

function destroyPlayer() {
    if (state.hls) {
        state.hls.destroy();
        state.hls = null;
    }
    dom.video.removeAttribute('src');
    dom.video.load();
}

function trySwitchSource() {
    const ch = state.channels[state.activeIndex];
    if (!ch) return;

    const currentIdx = ch.active_source_index || 0;
    const sources = ch.sources || [];

    if (sources.length <= 1) {
        // No backup — cannot switch
        showOverlay('error', '播放失败', '该频道暂无可用源');
        state.isLoading = false;
        updateStatusDot('dead');
        return;
    }

    // Try next source
    const nextIdx = (currentIdx + 1) % sources.length;
    showOverlay('loading', '正在切换备用源...', ch.name);

    if (state.mode === 'json') {
        // JSON mode: switch source locally, update channel data, retry
        ch.active_source_index = nextIdx;
        ch.url = sources[nextIdx];
        const idx = state.activeIndex;
        state.channels[idx] = ch;
        setTimeout(() => playChannel(idx), 500);
        return;
    }

    // API mode: call backend to switch source
    fetch(`${API_BASE}/api/channels/${ch.id}/switch`, { method: 'POST' })
        .then(r => r.json())
        .then(async data => {
            if (data.status === 'switched') {
                const resp = await fetch(`${API_BASE}/api/channels/${ch.id}`);
                const updated = await resp.json();
                const idx = state.activeIndex;
                state.channels[idx] = updated;
                playChannel(idx);
            } else {
                showOverlay('error', '无更多备用源', '所有源均不可用');
                state.isLoading = false;
                updateStatusDot('dead');
            }
        })
        .catch(() => {
            showOverlay('error', '切换失败', '无法连接服务器');
            state.isLoading = false;
        });
}

function handlePlayError(err) {
    console.error('Playback error:', err);
    state.retryCount++;
    if (state.retryCount <= state.maxRetries) {
        setTimeout(() => trySwitchSource(), 1000);
    } else {
        showOverlay('error', '播放失败', '已达到最大重试次数');
        state.isLoading = false;
        updateStatusDot('dead');
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
            dom.overlayText.textContent = text || '选择频道开始观看';
            dom.overlaySubtext.textContent = subtext || '';
            break;
    }
}

function hideOverlay() {
    dom.overlay.classList.add('hidden');
}

// ── UI: now playing bar ────────────────────────────────

function updateNowPlaying(ch) {
    dom.nowPlayingName.textContent = ch.name || '未选择';
    dom.nowPlayingSrc.textContent = ch.url ? ch.url.slice(0, 60) + '…' : '';
    updateStatusDot(ch.healthy ? 'healthy' : 'dead');
}

function updateStatusDot(status) {
    dom.qualityDot.className = 'quality-dot ' + status;
}

// ── UI: channel list ───────────────────────────────────

function renderChannelList() {
    dom.channelList.innerHTML = '';
    if (!state.channels.length) {
        dom.channelList.innerHTML = '<div class="group-label" style="padding:40px;text-align:center;color:var(--text-secondary);">暂无频道</div>';
        return;
    }

    // 收藏频道置顶：构建 [收藏组] + [其余频道] 的渲染顺序（仅 all 模式）
    let ordered = state.channels.map((ch, i) => ({ ch, i }));
    if (state.tabMode === 'all' && state.favorites.length) {
        const favNames = new Set(state.favorites);
        ordered.sort((a, b) => {
            const af = favNames.has(a.ch.name) ? 0 : 1;
            const bf = favNames.has(b.ch.name) ? 0 : 1;
            return af - bf;
        });
    }

    let favSectionRendered = false;
    ordered.forEach(({ ch, i }) => {
        const isFav = isFavorite(ch.name);

        // 在收藏与非收藏交界处插入分组标题（仅 all 模式且有收藏时）
        if (state.tabMode === 'all' && state.favorites.length) {
            if (isFav && !favSectionRendered) {
                const label = document.createElement('div');
                label.className = 'group-label';
                label.textContent = '⭐ 我的收藏';
                dom.channelList.appendChild(label);
                favSectionRendered = true;
            } else if (!isFav && favSectionRendered) {
                const label = document.createElement('div');
                label.className = 'group-label';
                label.style.marginTop = '8px';
                label.textContent = '全部频道';
                dom.channelList.appendChild(label);
                favSectionRendered = null; // 标记已渲染过第二组标题
            }
        }

        const item = document.createElement('div');
        item.className = 'channel-item';
        if (i === state.activeIndex) item.classList.add('active');
        if (i === state.focusIndex) item.classList.add('focused');  // 修复：重渲染同步焦点类
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
        nameEl.textContent = (isFav ? '⭐ ' : '') + ch.name;

        const statusEl = document.createElement('div');
        statusEl.className = `channel-status ${ch.healthy ? 'healthy' : 'dead'}`;
        statusEl.textContent = ch.healthy ? (ch.last_response_time ? `${(ch.last_response_time * 1000).toFixed(0)}ms` : '正常') : '离线';

        info.appendChild(nameEl);
        info.appendChild(statusEl);
        item.appendChild(logoDiv);
        item.appendChild(info);

        // Click handler
        item.addEventListener('click', () => {
            setActiveFocus(i);
            playChannel(i);
        });

        dom.channelList.appendChild(item);
    });

    // Ensure focus is visible
    scrollToFocus();
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
        switch (e.key) {
            case 'ArrowUp':
                // 上键 = 上一个频道并立即播放（符合传统电视"换台"直觉）
                e.preventDefault();
                changeChannel(-1);
                break;
            case 'ArrowDown':
                // 下键 = 下一个频道并立即播放
                e.preventDefault();
                changeChannel(1);
                break;
            case 'ArrowRight':
                // 右键 = 音量+
                e.preventDefault();
                changeVolume(0.1);
                break;
            case 'ArrowLeft':
                // 左键 = 音量-
                e.preventDefault();
                changeVolume(-0.1);
                break;
            case 'Enter':
            case ' ':
                // OK 键短按 = 播放当前焦点频道（正在播报错时则重试）
                e.preventDefault();
                if (!dom.errorEl.classList.contains('hidden')) {
                    retryPlayback();
                } else if (state.focusIndex !== state.activeIndex) {
                    playChannel(state.focusIndex);
                }
                break;
            case '0':
                // 数字 0 键 = 切换收藏列表
                e.preventDefault();
                switchTab(state.tabMode === 'fav' ? 'all' : 'fav');
                break;
            case 'Escape':
                // 返回全部频道
                if (state.tabMode !== 'all') switchTab('all');
                break;
        }
    });

    // Tab switching
    if (dom.tabAll) dom.tabAll.addEventListener('click', () => switchTab('all'));
    if (dom.tabHealthy) dom.tabHealthy.addEventListener('click', () => switchTab('healthy'));
    if (dom.tabFav) dom.tabFav.addEventListener('click', () => switchTab('fav'));

    // Retry button
    dom.retryBtn.addEventListener('click', retryPlayback);
}

/** 切换到相邻频道并播放（上/下键）。delta = -1 或 1。 */
function changeChannel(delta) {
    const len = state.channels.length;
    if (!len) return;
    let idx = state.activeIndex + delta;
    if (idx < 0) idx = len - 1;       // 循环到末尾
    if (idx >= len) idx = 0;          // 循环到开头
    state.focusIndex = idx;
    playChannel(idx);
}

/** 移动焦点但不播放（保留供未来使用，如频道号直接输入）。 */
function moveFocus(delta) {
    const len = state.channels.length;
    if (!len) return;
    state.focusIndex = Math.max(0, Math.min(len - 1, state.focusIndex + delta));
    const items = dom.channelList.querySelectorAll('.channel-item');
    items.forEach((el, i) => el.classList.toggle('focused', i === state.focusIndex));
    scrollToFocus();
}

async function switchTab(mode) {
    state.tabMode = mode;
    if (dom.tabAll) dom.tabAll.classList.toggle('active', mode === 'all');
    if (dom.tabHealthy) dom.tabHealthy.classList.toggle('active', mode === 'healthy');
    if (dom.tabFav) dom.tabFav.classList.toggle('active', mode === 'fav');
    await fetchChannels();
    // Auto-play first channel if healthy/fav mode
    if (state.channels.length && (mode === 'healthy' || mode === 'fav')) {
        playChannel(0);
    }
}

// ── Video events ────────────────────────────────────────

function setupVideoEvents() {
    dom.video.addEventListener('playing', () => {
        hideOverlay();
        state.isLoading = false;
        state.isPlaying = true;
        updateStatusDot('healthy');

        // Update the channel's status in the list
        const ch = state.channels[state.activeIndex];
        if (ch) {
            ch.healthy = true;
            renderChannelList();
        }
    });

    dom.video.addEventListener('waiting', () => {
        state.isLoading = true;
        updateStatusDot('loading');
    });

    dom.video.addEventListener('stalled', () => {
        state.isLoading = true;
    });

    dom.video.addEventListener('error', () => {
        // MediaError — try source switch
        const err = dom.video.error;
        if (err) {
            console.error('Video error:', err.code, err.message);
            if (state.isPlaying) {
                trySwitchSource();
            }
        }
    });

    dom.video.addEventListener('ended', () => {
        // Some live streams might end — try reconnecting
        console.log('Video ended, reconnecting...');
        playChannel(state.activeIndex);
    });

    dom.video.addEventListener('canplay', () => {
        // Ready to play
    });
}

function setupRetry() {
    dom.retryBtn.addEventListener('click', retryPlayback);
}

function retryPlayback() {
    playChannel(state.activeIndex);
}

// ── Start ───────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', init);
