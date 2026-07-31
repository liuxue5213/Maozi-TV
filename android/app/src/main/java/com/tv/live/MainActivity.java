package com.tv.live;

import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Maozi TV — 双模式 Android TV 播放器（原生 ExoPlayer 版）
 *
 * 核心特性：
 * - ExoPlayer 原生播放（HLS/FLV/MP4/DASH）
 * - 频道分组分类（侧边栏分组标签）
 * - 多源切换（播放失败自动切换 + 手动选源）
 * - 实时网速显示
 * - 频道收藏
 * - 遥控器/触摸操作
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MaoziTV";

    // ── Standalone 模式：JSON 源地址 ──────────────────────────
    private static final String[] JSON_URLS = {
            "https://gitee.com/liuxue5213/maozi-tv/raw/main/channels.json",
            "https://raw.githubusercontent.com/liuxue5213/Maozi-TV/main/channels.json",
    };

    // ── SharedPreferences 缓存 ───────────────────────────────
    private static final String PREFS_NAME = "maozi_tv_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_CACHED_JSON = "cached_channels_json";
    private static final String KEY_CACHED_VERSION = "cached_version";
    private static final String KEY_CACHED_TS = "cached_generated_ts";
    private static final String KEY_MODE = "mode";
    private static final String KEY_FAVORITES = "favorites"; // 收藏频道 ID 集合
    private static final String KEY_LAST_CHANNEL = "last_channel_id"; // 上次播放的频道 ID
    private static final String DEFAULT_SERVER_URL = "http://192.168.1.100:8000";

    // ── 视图 ────────────────────────────────────────────────
    private PlayerView playerView;
    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private DefaultBandwidthMeter bandwidthMeter;

    private LinearLayout sidebar;
    private RecyclerView rvGroups;
    private RecyclerView rvChannels;
    private GroupAdapter groupAdapter;
    private ChannelAdapter channelAdapter;

    private TextView tvChannelName;
    private TextView tvChannelGroup;
    private LinearLayout channelInfoOverlay;
    private TextView tvSpeed;
    private TextView tvChannelNumber;
    private TextView tvStatus;

    // ── 数据 ────────────────────────────────────────────────
    private final List<Channel> allChannels = new ArrayList<>();
    private final Map<String, List<Channel>> groupMap = new LinkedHashMap<>();
    private final List<String> groupNames = new ArrayList<>();
    private String currentGroup = "全部";
    private Channel currentChannel;
    private int currentChannelPosition = -1;

    // ── 线程/Handler ────────────────────────────────────────
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── 频道号跳转 ──────────────────────────────────────────
    private final StringBuilder channelNumberBuffer = new StringBuilder();
    private final Handler channelNumberHandler = new Handler(Looper.getMainLooper());
    private static final long CHANNEL_NUMBER_TIMEOUT = 3000; // 3秒无输入则跳转

    // ── 侧边栏显隐 ─────────────────────────────────────────
    private boolean sidebarVisible = false;
    private final Handler sidebarAutoHideHandler = new Handler(Looper.getMainLooper());
    private static final long SIDEBAR_AUTO_HIDE_DELAY = 8000;

    // ── 频道信息叠加层自动隐藏 ──────────────────────────────
    private final Handler infoOverlayHandler = new Handler(Looper.getMainLooper());
    private static final long INFO_OVERLAY_DURATION = 5000;

    // ── 网速刷新 ────────────────────────────────────────────
    private final Handler speedHandler = new Handler(Looper.getMainLooper());
    private static final long SPEED_REFRESH_INTERVAL = 1000;

    // ── 源切换重试 ──────────────────────────────────────────
    private static final int MAX_RETRY_COUNT = 3;
    private int retryCount = 0;
    // OK 键长按判定标志（isLongPress() 在 KEY_UP 上不可靠，用手动标志）
    private boolean okLongPressed = false;

    // ── 音量 ────────────────────────────────────────────────
    private AudioManager audioManager;

    // ══════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        initViews();
        initPlayer();
        initRecyclerViews();
        setupKeyListener();

        // 沉浸式全屏
        hideSystemUI();

        // 拉取频道数据
        executor.execute(this::fetchChannelsJson);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null) {
            player.setPlayWhenReady(true);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.setPlayWhenReady(false);
        }
    }

    @Override
    protected void onDestroy() {
        // 清理 Handler
        mainHandler.removeCallbacksAndMessages(null);
        speedHandler.removeCallbacksAndMessages(null);
        channelNumberHandler.removeCallbacksAndMessages(null);
        sidebarAutoHideHandler.removeCallbacksAndMessages(null);
        infoOverlayHandler.removeCallbacksAndMessages(null);

        // 释放播放器
        if (player != null) {
            player.release();
            player = null;
        }
        executor.shutdown();
        super.onDestroy();
    }

    // ══════════════════════════════════════════════════════════
    // 视图初始化
    // ══════════════════════════════════════════════════════════

    private void initViews() {
        playerView = findViewById(R.id.player_view);
        sidebar = findViewById(R.id.sidebar);
        rvGroups = findViewById(R.id.rv_groups);
        rvChannels = findViewById(R.id.rv_channels);
        tvChannelName = findViewById(R.id.tv_channel_name);
        tvChannelGroup = findViewById(R.id.tv_channel_group);
        channelInfoOverlay = findViewById(R.id.channel_info_overlay);
        tvSpeed = findViewById(R.id.tv_speed);
        tvChannelNumber = findViewById(R.id.tv_channel_number);
        tvStatus = findViewById(R.id.tv_status);

        // 显示加载状态
        showStatus("加载中…");
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    private void showStatus(String text) {
        tvStatus.setText(text);
        tvStatus.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        tvStatus.setVisibility(View.GONE);
    }

    // ══════════════════════════════════════════════════════════
    // ExoPlayer 初始化
    // ══════════════════════════════════════════════════════════

    private void initPlayer() {
        bandwidthMeter = new DefaultBandwidthMeter.Builder(this).build();

        trackSelector = new DefaultTrackSelector(this);

        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("MaoziTV/2.0")
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(8000)
                .setAllowCrossProtocolRedirects(true);

        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(this)
                .setDataSourceFactory(httpFactory);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        DefaultLoadControl.MIN_BUFFER_MS,
                        DefaultLoadControl.MAX_BUFFER_MS,
                        DefaultLoadControl.BUFFER_FOR_PLAYBACK_MS,
                        DefaultLoadControl.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .build();

        player = new ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(mediaSourceFactory)
                .setBandwidthMeter(bandwidthMeter)
                .build();

        playerView.setPlayer(player);

        // 播放事件监听
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    hideStatus();
                    retryCount = 0; // 播放成功，重置重试
                } else if (state == Player.STATE_BUFFERING) {
                    showStatus("缓冲中…");
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "播放错误: " + error.getMessage());
                handlePlaybackError();
            }
        });

        // 启动网速刷新
        startSpeedRefresh();
    }

    /**
     * 播放失败处理：自动切换下一个源
     */
    private void handlePlaybackError() {
        if (currentChannel == null) {
            showStatus("播放失败");
            return;
        }

        retryCount++;
        if (retryCount <= MAX_RETRY_COUNT && currentChannel.sources.size() > 1) {
            // 尝试切换下一个源
            currentChannel.switchToNextSource();
            String newUrl = currentChannel.getCurrentSourceUrl();
            Log.i(TAG, "自动切换源 [" + currentChannel.currentSourceIndex + "]: " + newUrl);
            Toast.makeText(this, "播放失败，切换源 " + (currentChannel.currentSourceIndex + 1)
                    + "/" + currentChannel.sources.size(), Toast.LENGTH_SHORT).show();
            playUrl(newUrl);
        } else {
            showStatus("播放失败，所有源均不可用");
            retryCount = 0;
        }
    }

    /**
     * 播放指定 URL
     */
    private void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) return;

        Log.i(TAG, "播放: " + url);

        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(Uri.parse(url))
                .build();

        player.setMediaItem(mediaItem);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    /**
     * 播放指定频道
     */
    private void playChannel(Channel channel) {
        currentChannel = channel;
        retryCount = 0;
        channel.currentSourceIndex = 0;
        playUrl(channel.getCurrentSourceUrl());

        // 记住上次播放的频道，下次启动自动恢复
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putInt(KEY_LAST_CHANNEL, channel.id).apply();

        // 显示频道信息叠加层
        showChannelInfo(channel);

        // 更新选中状态
        int pos = channelAdapter.findPositionByChannelId(channel.id);
        if (pos >= 0) {
            currentChannelPosition = pos;
            channelAdapter.setSelectedPosition(pos);
            rvChannels.scrollToPosition(pos);
        }
    }

    // ══════════════════════════════════════════════════════════
    // 频道信息叠加层
    // ══════════════════════════════════════════════════════════

    private void showChannelInfo(Channel channel) {
        tvChannelName.setText(channel.name);
        tvChannelGroup.setText(channel.group);
        channelInfoOverlay.setVisibility(View.VISIBLE);

        infoOverlayHandler.removeCallbacksAndMessages(null);
        infoOverlayHandler.postDelayed(() ->
                channelInfoOverlay.setVisibility(View.GONE), INFO_OVERLAY_DURATION);
    }

    // ══════════════════════════════════════════════════════════
    // 实时网速
    // ══════════════════════════════════════════════════════════

    private void startSpeedRefresh() {
        speedHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (player != null && player.getPlaybackState() == Player.STATE_READY
                        || player != null && player.getPlaybackState() == Player.STATE_BUFFERING) {
                    long bitrate = bandwidthMeter.getBitrateEstimate();
                    tvSpeed.setText(formatSpeed(bitrate));
                }
                speedHandler.postDelayed(this, SPEED_REFRESH_INTERVAL);
            }
        }, SPEED_REFRESH_INTERVAL);
    }

    private String formatSpeed(long bitrateBps) {
        if (bitrateBps <= 0) return "0 KB/s";
        double bytesPerSec = bitrateBps / 8.0;
        if (bytesPerSec < 1024) {
            return String.format("%.0f B/s", bytesPerSec);
        } else if (bytesPerSec < 1024 * 1024) {
            return String.format("%.1f KB/s", bytesPerSec / 1024);
        } else {
            return String.format("%.1f MB/s", bytesPerSec / (1024 * 1024));
        }
    }

    // ══════════════════════════════════════════════════════════
    // RecyclerView 初始化
    // ══════════════════════════════════════════════════════════

    private void initRecyclerViews() {
        // 分组列表（水平）
        groupAdapter = new GroupAdapter((group, position) -> {
            currentGroup = group.name;
            updateChannelList();
        });
        rvGroups.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvGroups.setAdapter(groupAdapter);

        // 频道列表（垂直）
        channelAdapter = new ChannelAdapter(new ChannelAdapter.OnChannelClickListener() {
            @Override
            public void onChannelClick(Channel channel, int position) {
                playChannel(channel);
                hideSidebar();
            }

            @Override
            public boolean onChannelLongClick(Channel channel, int position) {
                toggleFavorite(channel);
                return true;
            }
        });
        rvChannels.setLayoutManager(new LinearLayoutManager(this));
        rvChannels.setAdapter(channelAdapter);
    }

    // ══════════════════════════════════════════════════════════
    // 侧边栏显隐
    // ══════════════════════════════════════════════════════════

    private void showSidebar() {
        sidebarVisible = true;
        sidebar.setVisibility(View.VISIBLE);
        sidebarAutoHideHandler.removeCallbacksAndMessages(null);
        sidebarAutoHideHandler.postDelayed(this::hideSidebar, SIDEBAR_AUTO_HIDE_DELAY);

        // 聚焦到频道列表
        if (rvChannels.getAdapter() != null && channelAdapter.getItemCount() > 0) {
            rvChannels.requestFocus();
            if (currentChannelPosition >= 0) {
                rvChannels.scrollToPosition(currentChannelPosition);
            }
        }
    }

    private void hideSidebar() {
        sidebarVisible = false;
        sidebar.setVisibility(View.GONE);
        sidebarAutoHideHandler.removeCallbacksAndMessages(null);
    }

    private void toggleSidebar() {
        if (sidebarVisible) {
            hideSidebar();
        } else {
            showSidebar();
        }
    }

    // ══════════════════════════════════════════════════════════
    // 频道数据解析
    // ══════════════════════════════════════════════════════════

    private boolean parseChannelsJson(String jsonData) {
        try {
            JSONObject root = new JSONObject(jsonData);
            JSONArray chArr = root.optJSONArray("channels");
            if (chArr == null) {
                Log.e(TAG, "JSON 无 channels 数组");
                return;
            }

            allChannels.clear();
            groupMap.clear();
            groupNames.clear();

            // 加载收藏集
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String favStr = prefs.getString(KEY_FAVORITES, "");
            java.util.Set<String> favSet = new java.util.HashSet<>();
            if (!favStr.isEmpty()) {
                Collections.addAll(favSet, favStr.split(","));
            }

            // 解析频道
            for (int i = 0; i < chArr.length(); i++) {
                Channel ch = Channel.fromJson(chArr.getJSONObject(i));
                ch.channelNumber = i + 1; // 频道号从1开始

                // 恢复收藏状态
                ch.isFavorite = favSet.contains(String.valueOf(ch.id));

                allChannels.add(ch);
            }

            // 按分组归类
            for (Channel ch : allChannels) {
                String grp = ch.group;
                if (!groupMap.containsKey(grp)) {
                    groupMap.put(grp, new ArrayList<>());
                }
                groupMap.get(grp).add(ch);
            }

            groupNames.addAll(groupMap.keySet());

            Log.i(TAG, "解析完成: " + allChannels.size() + " 个频道, "
                    + groupNames.size() + " 个分组");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "JSON 解析失败: " + e.getMessage(), e);
            String msg = "频道数据解析失败: " + e.getMessage();
            mainHandler.post(() -> {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                showStatus("频道数据异常，请更新频道源");
            });
            return false;
        }
    }

    /**
     * 更新分组标签和频道列表到 UI
     */
    private void updateUI() {
        // 构建分组列表（全部 + 收藏 + 各分组）
        List<GroupAdapter.GroupItem> groupItems = new ArrayList<>();
        groupItems.add(new GroupAdapter.GroupItem("全部", allChannels.size()));

        // 收藏数量
        int favCount = 0;
        for (Channel ch : allChannels) {
            if (ch.isFavorite) favCount++;
        }
        if (favCount > 0) {
            groupItems.add(new GroupAdapter.GroupItem("收藏", favCount));
        }

        for (String grp : groupNames) {
            List<Channel> chs = groupMap.get(grp);
            if (chs != null) {
                groupItems.add(new GroupAdapter.GroupItem(grp, chs.size()));
            }
        }

        groupAdapter.setGroups(groupItems);
        groupAdapter.setSelectedPosition(0);
        currentGroup = "全部";

        updateChannelList();

        // 隐藏加载状态
        hideStatus();

        // 启动行为：优先恢复上次播放的频道；无记录则展示侧边栏让用户选台。
        if (!allChannels.isEmpty()) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            int lastId = prefs.getInt(KEY_LAST_CHANNEL, -1);
            Channel last = null;
            if (lastId >= 0) {
                for (Channel ch : allChannels) {
                    if (ch.id == lastId) { last = ch; break; }
                }
            }
            if (last != null) {
                // 切到上次频道所在分组并播放
                currentGroup = "全部";
                updateChannelList();
                groupAdapter.setSelectedPosition(0);
                playChannel(last);
            } else {
                // 首次使用：展示侧边栏，不自动播放
                showSidebar();
            }
        }
    }

    /**
     * 根据当前选中分组更新频道列表
     */
    private void updateChannelList() {
        List<Channel> filtered;
        if ("全部".equals(currentGroup)) {
            filtered = allChannels;
        } else if ("收藏".equals(currentGroup)) {
            filtered = new ArrayList<>();
            for (Channel ch : allChannels) {
                if (ch.isFavorite) filtered.add(ch);
            }
        } else {
            filtered = groupMap.getOrDefault(currentGroup, Collections.emptyList());
        }

        channelAdapter.setChannels(filtered);

        // 更新选中位置
        if (currentChannel != null) {
            int pos = channelAdapter.findPositionByChannelId(currentChannel.id);
            if (pos >= 0) {
                currentChannelPosition = pos;
                channelAdapter.setSelectedPosition(pos);
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // 收藏功能
    // ══════════════════════════════════════════════════════════

    private void toggleFavorite(Channel channel) {
        channel.isFavorite = !channel.isFavorite;
        saveFavorites();

        String msg = channel.isFavorite ? "已收藏: " + channel.name : "取消收藏: " + channel.name;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();

        // 刷新列表
        channelAdapter.notifyDataSetChanged();
        updateGroupTabs();
    }

    private void saveFavorites() {
        StringBuilder sb = new StringBuilder();
        for (Channel ch : allChannels) {
            if (ch.isFavorite) {
                if (sb.length() > 0) sb.append(",");
                sb.append(ch.id);
            }
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(KEY_FAVORITES, sb.toString()).apply();
    }

    private void updateGroupTabs() {
        // 刷新分组标签（收藏数可能变了）
        List<GroupAdapter.GroupItem> groupItems = new ArrayList<>();
        groupItems.add(new GroupAdapter.GroupItem("全部", allChannels.size()));

        int favCount = 0;
        for (Channel ch : allChannels) {
            if (ch.isFavorite) favCount++;
        }
        if (favCount > 0) {
            groupItems.add(new GroupAdapter.GroupItem("收藏", favCount));
        }

        for (String grp : groupNames) {
            List<Channel> chs = groupMap.get(grp);
            if (chs != null) {
                groupItems.add(new GroupAdapter.GroupItem(grp, chs.size()));
            }
        }

        groupAdapter.setGroups(groupItems);
    }

    // ══════════════════════════════════════════════════════════
    // 多源切换对话框
    // ══════════════════════════════════════════════════════════

    private void showSourceSwitchDialog() {
        if (currentChannel == null || currentChannel.sources.size() <= 1) {
            Toast.makeText(this, "当前频道只有一个源", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] items = new String[currentChannel.sources.size()];
        for (int i = 0; i < currentChannel.sources.size(); i++) {
            String url = currentChannel.sources.get(i);
            // 截断显示
            if (url.length() > 60) {
                url = url.substring(0, 30) + "…" + url.substring(url.length() - 25);
            }
            String marker = (i == currentChannel.currentSourceIndex) ? " ▶ " : "   ";
            items[i] = marker + "源" + (i + 1) + ": " + url;
        }

        new AlertDialog.Builder(this)
                .setTitle("切换播放源 — " + currentChannel.name)
                .setItems(items, (dialog, which) -> {
                    currentChannel.currentSourceIndex = which;
                    retryCount = 0;
                    playUrl(currentChannel.getCurrentSourceUrl());
                    Toast.makeText(this, "已切换到源 " + (which + 1), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ══════════════════════════════════════════════════════════
    // JSON 拉取 (Gitee 优先 → GitHub 备用)
    // ══════════════════════════════════════════════════════════

    private void fetchChannelsJson() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        long cachedTs = prefs.getLong(KEY_CACHED_TS, 0);
        int cachedVersion = prefs.getInt(KEY_CACHED_VERSION, 0);
        String cachedJson = prefs.getString(KEY_CACHED_JSON, null);

        for (String urlStr : JSON_URLS) {
            try {
                Log.i(TAG, "拉取频道: " + urlStr);

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36");

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    Log.w(TAG, urlStr + " 返回 " + responseCode + ", 尝试下一个...");
                    conn.disconnect();
                    continue;
                }

                StringBuilder sb = new StringBuilder();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                reader.close();
                conn.disconnect();

                String jsonData = sb.toString();

                // 解析时间戳判断是否需要更新
                JSONObject obj = new JSONObject(jsonData);
                long remoteTs = obj.optLong("generated_at_ts", 0);
                int remoteVersion = obj.optInt("version", 0);
                Log.i(TAG, "远程 ts=" + remoteTs + " v" + remoteVersion
                        + ", 缓存 ts=" + cachedTs + " v" + cachedVersion);

                boolean noCache = (cachedJson == null);
                boolean hasNewerData = (remoteTs > cachedTs)
                        || (remoteTs == 0 && remoteVersion > cachedVersion);

                if (hasNewerData || noCache) {
                    prefs.edit()
                            .putString(KEY_CACHED_JSON, jsonData)
                            .putInt(KEY_CACHED_VERSION, remoteVersion)
                            .putLong(KEY_CACHED_TS, remoteTs)
                            .apply();
                    Log.i(TAG, "新数据已缓存: v" + remoteVersion + " ts=" + remoteTs);

                    String data = jsonData;
                    mainHandler.post(() -> {
                        if (parseChannelsJson(data)) {
                            updateUI();
                            Toast.makeText(MainActivity.this, "频道已更新", Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    Log.i(TAG, "数据未变化，使用缓存");
                    useCachedJson(cachedJson);
                }

                return; // 拉取成功

            } catch (Exception e) {
                Log.w(TAG, "拉取失败 " + urlStr + ": " + e.getMessage());
            }
        }

        // 所有源都失败 → 用缓存
        Log.w(TAG, "所有源均失败，回退到缓存");
        useCachedJson(cachedJson);
    }

    private void useCachedJson(String cachedJson) {
        if (cachedJson == null) {
            mainHandler.post(() -> {
                showStatus("无法获取频道列表");
                Toast.makeText(this, "无法获取频道列表", Toast.LENGTH_LONG).show();
            });
            return;
        }
        mainHandler.post(() -> {
            if (parseChannelsJson(cachedJson)) {
                updateUI();
                Toast.makeText(MainActivity.this, "使用缓存的频道列表", Toast.LENGTH_SHORT).show();
            }
        });
        });
    }

    /**
     * 强制重新拉取频道
     */
    private void refreshChannels() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putLong(KEY_CACHED_TS, 0).apply();
        mainHandler.post(() ->
                Toast.makeText(this, "正在更新频道源…", Toast.LENGTH_SHORT).show());
        executor.execute(this::fetchChannelsJson);
    }

    // ══════════════════════════════════════════════════════════
    // 设置 / 模式切换
    // ══════════════════════════════════════════════════════════

    private void showSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setItems(new String[]{
                        "更新频道源 (重新拉取)",
                        "切换播放源",
                        "独立模式 (Gitee/GitHub)",
                        "服务器模式 (连接后端 API)"
                }, (dialog, which) -> {
                    if (which == 0) {
                        refreshChannels();
                    } else if (which == 1) {
                        showSourceSwitchDialog();
                    } else if (which == 2) {
                        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                        prefs.edit().putString(KEY_MODE, "standalone").apply();
                        Toast.makeText(this, "当前为独立模式", Toast.LENGTH_SHORT).show();
                    } else {
                        showUrlDialog();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showUrlDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String currentUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);

        EditText input = new EditText(this);
        input.setText(currentUrl);
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle("设置服务器地址")
                .setMessage("输入后端服务器的 IP:端口")
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> {
                    String newUrl = input.getText().toString().trim();
                    if (!newUrl.isEmpty()) {
                        if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                            newUrl = "http://" + newUrl;
                        }
                        prefs.edit()
                                .putString(KEY_MODE, "server")
                                .putString(KEY_SERVER_URL, newUrl)
                                .apply();
                        Toast.makeText(this, "服务器: " + newUrl, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ══════════════════════════════════════════════════════════
    // 频道号快速跳转
    // ══════════════════════════════════════════════════════════

    private void handleChannelNumberInput(int digit) {
        channelNumberBuffer.append(digit);
        tvChannelNumber.setText(channelNumberBuffer.toString());
        tvChannelNumber.setVisibility(View.VISIBLE);

        // 重置超时计时
        channelNumberHandler.removeCallbacksAndMessages(null);
        channelNumberHandler.postDelayed(() -> {
            try {
                int chNum = Integer.parseInt(channelNumberBuffer.toString());
                channelNumberBuffer.setLength(0);
                tvChannelNumber.setVisibility(View.GONE);
                jumpToChannel(chNum);
            } catch (NumberFormatException e) {
                channelNumberBuffer.setLength(0);
                tvChannelNumber.setVisibility(View.GONE);
            }
        }, CHANNEL_NUMBER_TIMEOUT);
    }

    private void jumpToChannel(int channelNumber) {
        // 在所有频道中查找频道号
        if (channelNumber < 1 || channelNumber > allChannels.size()) {
            Toast.makeText(this, "频道号超出范围 (1-" + allChannels.size() + ")", Toast.LENGTH_SHORT).show();
            return;
        }
        Channel target = allChannels.get(channelNumber - 1);

        // 切换到对应的分组
        if (!"全部".equals(currentGroup) && !"收藏".equals(currentGroup)
                && !currentGroup.equals(target.group)) {
            currentGroup = "全部";
            updateChannelList();
            // 选中 "全部" 分组
            groupAdapter.setSelectedPosition(0);
        }

        playChannel(target);
        Log.i(TAG, "跳转到频道 " + channelNumber + ": " + target.name);
    }

    // ══════════════════════════════════════════════════════════
    // 遥控器按键
    // ══════════════════════════════════════════════════════════

    private void setupKeyListener() {
        // 在 playerView 上监听按键
        playerView.setFocusable(true);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 数字键：频道号快速跳转
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            if (!sidebarVisible) {
                handleChannelNumberInput(keyCode - KeyEvent.KEYCODE_0);
                return true;
            }
        }

        switch (keyCode) {
            // MENU 键 → 设置
            case KeyEvent.KEYCODE_MENU:
                showSettingsDialog();
                return true;

            // OK/Enter 短按 → 播放/切换侧边栏
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (event.getRepeatCount() == 0) {
                    okLongPressed = false;   // 重置长按标志
                    event.startTracking();
                }
                return true;

            // 上/下键 → 切换频道（侧边栏关闭时）或移动焦点
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!sidebarVisible) {
                    switchChannelUp();
                    return true;
                }
                break;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!sidebarVisible) {
                    switchChannelDown();
                    return true;
                }
                break;

            // 左/右键 → 调音量
            case KeyEvent.KEYCODE_DPAD_LEFT:
                adjustVolume(-1);
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                adjustVolume(1);
                return true;

            // BACK 键
            case KeyEvent.KEYCODE_BACK:
                if (sidebarVisible) {
                    hideSidebar();
                    return true;
                }
                // 长按 BACK 退出
                break;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        // 长按 OK/Enter → 收藏/取消收藏
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER) {
            okLongPressed = true;   // 标记已长按，防止 onKeyUp 误触发短按
            if (currentChannel != null) {
                toggleFavorite(currentChannel);
            }
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER) {
            // 用手动标志判断长按（isLongPress() 在 KEY_UP 上恒为 false）
            if (!okLongPressed) {
                // 短按 OK：如果侧边栏可见则播放选中频道，否则切换侧边栏
                if (sidebarVisible) {
                    // 让 RecyclerView 自己处理焦点点击
                    View focused = rvChannels.getFocusedChild();
                    if (focused != null) {
                        focused.performClick();
                    }
                } else {
                    toggleSidebar();
                }
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ══════════════════════════════════════════════════════════
    // 频道切换
    // ══════════════════════════════════════════════════════════

    private void switchChannelUp() {
        // 用当前分组（侧边栏列表）的频道，而非全部频道，避免跨分组跳台。
        List<Channel> list = channelAdapter.getChannels();
        if (list.isEmpty()) return;
        // 用 id 查找当前频道位置（比 indexOf 引用相等更稳妥）
        int currentIndex = -1;
        if (currentChannel != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == currentChannel.id) { currentIndex = i; break; }
            }
        }
        // 当前频道不在本组（如刚切换分组），从第 0 个开始
        if (currentIndex < 0) currentIndex = 0;
        int newIndex = (currentIndex <= 0) ? list.size() - 1 : currentIndex - 1;
        playChannel(list.get(newIndex));
    }

    private void switchChannelDown() {
        // 同上：用当前分组列表，循环切换。
        List<Channel> list = channelAdapter.getChannels();
        if (list.isEmpty()) return;
        int currentIndex = -1;
        if (currentChannel != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == currentChannel.id) { currentIndex = i; break; }
            }
        }
        // 当前频道不在本组，从第 0 个开始（按下→开头）
        int newIndex = (currentIndex < 0 || currentIndex >= list.size() - 1)
                ? 0 : currentIndex + 1;
        playChannel(list.get(newIndex));
    }

    // ══════════════════════════════════════════════════════════
    // 音量调节
    // ══════════════════════════════════════════════════════════

    private void adjustVolume(int direction) {
        if (audioManager == null) return;
        // 电视盒子的媒体音量通常是 STREAM_MUSIC，但部分盒子走 STREAM_SYSTEM。
        // 优先调 STREAM_MUSIC（ExoPlayer 输出在此流），用 FLAG_SHOW_UI 让系统
        // 弹出原生音量条，比自定义 Toast 更直观、也更广泛兼容。
        int stream = AudioManager.STREAM_MUSIC;
        audioManager.adjustStreamVolume(stream,
                direction > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_PLAY_SOUND);
    }

    // ══════════════════════════════════════════════════════════
    // 触摸操作
    // ══════════════════════════════════════════════════════════

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
            // 点击屏幕 → 切换侧边栏
            toggleSidebar();
            return true;
        }
        return super.onTouchEvent(event);
    }
}
