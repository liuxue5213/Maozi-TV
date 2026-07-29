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
    tabMode: 'all',       // 'all' | 'healthy'
    groupExpanded: {},     // track which groups are expanded (all expanded by default)
    mode: 'api',          // 'api' = fetch from backend, 'json' = injected by host app
    jsonInitDone: null,   // resolve callback for initFromJson
};

// ── Data injection (called by Android WebView) ────────────

/**
 * Called by the Android host app to inject channel data directly,
 * bypassing the need for a backend API server.
 * @param {Object} data - The parsed channels.json object
 */
window.initFromJson = function (data) {
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

    // API mode: fetch from backend
    const url = state.tabMode === 'healthy'
        ? `${API_BASE}/api/channels?visible_only=true&healthy_only=true`
        : `${API_BASE}/api/channels?visible_only=true`;

    try {
        const resp = await fetch(url);
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        state.channels = await resp.json();

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

    let focusIdx = 0;
    const chs = state.channels;

    chs.forEach((ch, i) => {
        const item = document.createElement('div');
        item.className = 'channel-item';
        if (i === state.activeIndex) item.classList.add('active');
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

        // Click handler
        item.addEventListener('click', () => {
            setActiveFocus(i);
            playChannel(i);
        });

        dom.channelList.appendChild(item);

        // Track focus index
        if (i === state.focusIndex) {
            focusIdx = i;
        }
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
                e.preventDefault();
                moveFocus(-1);
                break;
            case 'ArrowDown':
                e.preventDefault();
                moveFocus(1);
                break;
            case 'ArrowRight':
                e.preventDefault();
                // Could be used for next channel in group, but for now: volume up
                break;
            case 'ArrowLeft':
                e.preventDefault();
                break;
            case 'Enter':
            case ' ':
                e.preventDefault();
                if (dom.errorEl.classList.contains('hidden') === false) {
                    // Retry is showing
                    retryPlayback();
                } else {
                    playChannel(state.focusIndex);
                }
                break;
            case 'Escape':
                // Could show/hide sidebar on mobile
                break;
        }
    });

    // Tab switching
    dom.tabAll.addEventListener('click', () => switchTab('all'));
    dom.tabHealthy.addEventListener('click', () => switchTab('healthy'));

    // Retry button
    dom.retryBtn.addEventListener('click', retryPlayback);
}

function moveFocus(delta) {
    const len = state.channels.length;
    if (!len) return;

    state.focusIndex = Math.max(0, Math.min(len - 1, state.focusIndex + delta));

    // Update visual focus
    const items = dom.channelList.querySelectorAll('.channel-item');
    items.forEach((el, i) => el.classList.toggle('focused', i === state.focusIndex));

    scrollToFocus();
}

async function switchTab(mode) {
    state.tabMode = mode;
    dom.tabAll.classList.toggle('active', mode === 'all');
    dom.tabHealthy.classList.toggle('active', mode === 'healthy');
    await fetchChannels();
    // Auto-play first channel if healthy mode
    if (state.channels.length && mode === 'healthy') {
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
