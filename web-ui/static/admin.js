const $ = (id) => document.getElementById(id);
const DEFAULT_API_BASE = 'http://localhost:8000';

function getApiBase() {
    const saved = localStorage.getItem('maozi_admin_api_base');
    if (saved) return saved.replace(/\/+$/, '');
    if (window.location.protocol === 'file:') return DEFAULT_API_BASE;
    return window.location.origin;
}

let apiBase = getApiBase();
let allChannels = [];
let currentChannelFilter = 'visible';
let channelSearchQuery = '';

const CHANNEL_FILTERS = {
    total: { title: '全部频道', predicate: () => true },
    visible: { title: '可见频道', predicate: (ch) => ch.visible !== false },
    healthy: { title: '健康频道', predicate: (ch) => ch.visible !== false && ch.healthy === true },
    dead: { title: '异常频道', predicate: (ch) => ch.visible !== false && ch.healthy !== true },
};

function showNotice(message) {
    const el = $('notice');
    el.textContent = message;
    el.classList.remove('hidden');
}

function hideNotice() {
    $('notice').classList.add('hidden');
}

function toast(message) {
    const el = $('toast');
    el.textContent = message;
    el.classList.add('show');
    setTimeout(() => el.classList.remove('show'), 2200);
}

async function api(path, options) {
    const url = path.startsWith('http') ? path : apiBase + path;
    const res = await fetch(url, options);
    if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
    return res.json();
}

function fmtDate(value) {
    if (!value) return '-';
    return new Date(value).toLocaleString();
}

function scoreClass(score) {
    if (score >= 75) return 'score-good';
    if (score >= 45) return 'score-mid';
    return 'score-low';
}

function bestScore(channel) {
    const list = Array.isArray(channel.source_quality) ? channel.source_quality : [];
    if (!list.length) return null;
    return Math.max(...list.map(item => Number(item.score) || 0));
}

function statusHtml(channel) {
    if (channel.visible === false) return '<span class="status-pill status-hidden">隐藏</span>';
    if (channel.healthy === true) return '<span class="status-pill status-ok">健康</span>';
    return '<span class="status-pill status-bad">异常</span>';
}

function shortUrl(url) {
    if (!url) return '-';
    if (url.length <= 86) return url;
    return `${url.slice(0, 42)}...${url.slice(-34)}`;
}

function renderChannels() {
    const filter = CHANNEL_FILTERS[currentChannelFilter] || CHANNEL_FILTERS.visible;
    const q = channelSearchQuery.trim().toLowerCase();
    let rows = allChannels.filter(filter.predicate);
    if (q) {
        rows = rows.filter(ch => [
            ch.name, ch.group, ch.region, ch.url, ...(ch.sources || []),
        ].some(value => String(value || '').toLowerCase().includes(q)));
    }

    $('channel-title').textContent = filter.title;
    $('channel-count').textContent = `${rows.length} 个`;
    document.querySelectorAll('.metric').forEach(el => {
        el.classList.toggle('active', el.dataset.filter === currentChannelFilter);
    });

    $('channel-table').innerHTML = rows.slice(0, 500).map(ch => {
        const score = bestScore(ch);
        const sourceCount = Array.isArray(ch.sources) ? ch.sources.length : 0;
        return `
            <tr>
                <td class="channel-name">${ch.name || '-'}</td>
                <td>${ch.group || '-'}</td>
                <td>${ch.region || '-'}</td>
                <td>${statusHtml(ch)}</td>
                <td class="source-count">${sourceCount}</td>
                <td class="${score == null ? '' : scoreClass(score)}">${score == null ? '-' : score}</td>
                <td class="url" title="${ch.url || ''}">${shortUrl(ch.url)}</td>
            </tr>
        `;
    }).join('');

    if (!rows.length) {
        $('channel-table').innerHTML = '<tr><td colspan="7">没有匹配的频道</td></tr>';
    } else if (rows.length > 500) {
        $('channel-table').insertAdjacentHTML('beforeend', '<tr><td colspan="7">只显示前 500 条，可用搜索缩小范围。</td></tr>');
    }
}

async function loadDashboard() {
    hideNotice();
    const [summary, quality, diff, channels] = await Promise.all([
        api('/api/summary'),
        api('/api/sources/quality?limit=200'),
        api('/api/diff-log?limit=50'),
        api('/api/channels?visible_only=false'),
    ]);
    allChannels = channels;

    $('metrics').innerHTML = [
        ['total', '总频道', summary.channels_total],
        ['visible', '可见频道', summary.channels_visible],
        ['healthy', '健康频道', summary.channels_healthy],
        ['dead', '异常频道', summary.channels_dead],
    ].map(([filter, label, value]) => `
        <button class="metric" data-filter="${filter}">
            <div>${label}</div>
            <div class="value">${value ?? '-'}</div>
        </button>
    `).join('');
    document.querySelectorAll('.metric').forEach(el => {
        el.addEventListener('click', () => {
            currentChannelFilter = el.dataset.filter;
            renderChannels();
        });
    });
    renderChannels();

    $('quality-count').textContent = `${quality.length} 条`;
    $('quality-table').innerHTML = quality.map(row => `
        <tr>
            <td class="${scoreClass(row.score)}">${row.score}</td>
            <td>${row.success_count}/${row.failure_count}</td>
            <td>${row.playback_failure_count}</td>
            <td>${row.avg_response_time == null ? '-' : row.avg_response_time.toFixed(2) + 's'}</td>
            <td class="url" title="${row.url}">${row.url}</td>
        </tr>
    `).join('');

    $('diff-count').textContent = `${diff.length} 条`;
    $('diff-table').innerHTML = diff.map(row => `
        <tr>
            <td>${fmtDate(row.created_at)}</td>
            <td>${row.crawled_entries}</td>
            <td>${row.new_channels}</td>
            <td>${row.updated_channels}</td>
            <td>${row.added_sources}</td>
            <td>${row.recovered_sources}</td>
        </tr>
    `).join('');

    if (!quality.length) {
        $('quality-table').innerHTML = '<tr><td colspan="5">暂无源质量数据。先点“健康检查”，或等 Android 播放失败上报后会逐步生成。</td></tr>';
    }
    if (!diff.length) {
        $('diff-table').innerHTML = '<tr><td colspan="6">暂无更新差异记录。先点“立即爬取”生成第一条记录。</td></tr>';
    }
}

async function runAction(path, label) {
    toast(`${label}已提交...`);
    const job = await api(path, { method: 'POST' });
    if (!job.id) {
        toast(`${label}完成`);
        await loadDashboard();
        return;
    }
    await pollJob(job.id, label);
}

async function pollJob(jobId, label) {
    for (let i = 0; i < 240; i++) {
        const job = await api(`/api/jobs/${jobId}`);
        if (job.status === 'completed') {
            toast(`${label}完成`);
            await loadDashboard();
            return;
        }
        if (job.status === 'failed' || job.status === 'rejected') {
            showNotice(`${label}未完成：${job.error || job.status}`);
            return;
        }
        toast(`${label}${job.status === 'queued' ? '排队中' : '运行中'}...`);
        await new Promise(resolve => setTimeout(resolve, 2000));
    }
    showNotice(`${label}仍在运行，可稍后刷新查看结果。`);
}

$('api-base').value = apiBase;
$('btn-save-api').addEventListener('click', () => {
    apiBase = $('api-base').value.trim().replace(/\/+$/, '') || DEFAULT_API_BASE;
    localStorage.setItem('maozi_admin_api_base', apiBase);
    loadDashboard().then(() => toast('已连接')).catch(err => showNotice(`无法连接后端 ${apiBase}：${err.message}`));
});

$('btn-refresh').addEventListener('click', () => loadDashboard().then(() => toast('已刷新')).catch(err => showNotice(`无法加载数据：${err.message}`)));
$('btn-crawl').addEventListener('click', () => runAction('/api/crawl', '爬取'));
$('btn-check').addEventListener('click', () => runAction('/api/check-all', '健康检查'));
$('channel-search').addEventListener('input', (event) => {
    channelSearchQuery = event.target.value;
    renderChannels();
});

loadDashboard().catch(err => {
    showNotice(`无法连接后端 ${apiBase}。请先启动后端服务，然后访问 ${apiBase}/admin.html；如果用文件方式打开，也可以在上方填写后端地址后连接。错误：${err.message}`);
});
