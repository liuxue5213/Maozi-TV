package com.tv.live;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.AudioManager;
import android.net.Uri;
import android.app.PictureInPictureParams;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Rational;
import android.view.KeyEvent;
import android.view.View;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

import org.json.JSONArray;
import org.json.JSONObject;

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
    private static final String KEY_PLAY_HISTORY = "play_history"; // 播放历史（频道 ID，逗号分隔）
    private static final String KEY_THEME = "theme"; // 主题（dark/blue/green）
    private static final String DEFAULT_SERVER_URL = "http://192.168.1.100:8000";

    // ── 主题配色 ────────────────────────────────────────────
    // 主题索引：0=暗夜 1=深蓝 2=墨绿
    private static final int[][] THEME_COLORS = {
            {0xFF0A0A0F, 0xFF1A1A2E, 0xFF4A9EFF}, // 暗夜：背景/卡片/强调
            {0xFF0A1628, 0xFF1E293B, 0xFF3B82F6}, // 深蓝
            {0xFF0A1F0A, 0xFF1A2E1A, 0xFF22C55E}, // 墨绿
    };
    private static final String[] THEME_NAMES = {"暗夜", "深蓝", "墨绿"};
    private int currentTheme = 0;

    // ── 频道排序模式 ────────────────────────────────────────
    private static final int SORT_DEFAULT = 0;
    private static final int SORT_NAME = 1;
    private static final int SORT_GROUP = 2;
    private static final String[] SORT_LABELS = {"默认排序", "按名称", "按分组"};
    private int currentSortMode = SORT_DEFAULT;

    // ── 画面比例模式 ────────────────────────────────────────
    private static final int RESIZE_MODE_FIT = 0;       // 自适应（默认）
    private static final int RESIZE_MODE_FILL = 1;      // 裁剪填充
    private static final int RESIZE_MODE_ZOOM = 2;      // 缩放拉伸
    private static final int RESIZE_MODE_16_9 = 3;      // 强制 16:9
    private static final int RESIZE_MODE_4_3 = 4;       // 强制 4:3
    private static final String[] RESIZE_MODE_LABELS = {"自适应", "裁剪填充", "缩放拉伸", "16:9", "4:3"};

    // ── 视图 ────────────────────────────────────────────────
    private PlayerView playerView;
    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private DefaultBandwidthMeter bandwidthMeter;

    private View channelPanel;
    private RecyclerView rvCategories;
    private RecyclerView rvChannels;
    private CategoryAdapter categoryAdapter;
    private ChannelAdapter channelAdapter;
    private EditText etSearch;
    private TextView tvPanelCount;

    private TextView tvChannelName;
    private TextView tvChannelNumSmall;
    private TextView tvChannelGroup;
    private TextView tvEpgNow;
    private TextView tvResolution;
    private TextView tvBitrate;
    private TextView tvSignalText;
    private View channelInfoOverlay;
    private TextView tvSpeed;
    private TextView tvChannelNumber;
    private TextView tvStatus;
    private View tvStatusContainer;
    private android.widget.Button btnRetry;
    private View sigBar1, sigBar2, sigBar3, sigBar4;

    // ── 数据 ────────────────────────────────────────────────
    private final List<Channel> allChannels = new ArrayList<>();
    private String currentCategoryId = CategoryHelper.ALL;
    private String searchQuery = "";
    private Channel currentChannel;
    private final List<String> playHistory = new ArrayList<>(); // 播放历史（频道 ID，新→旧）
    private static final int MAX_HISTORY = 30;
    private int currentResizeMode = RESIZE_MODE_FIT; // 当前画面比例模式
    // 画质轨道信息（ExoPlayer 的 VideoTrackSelection 列表）
    private List<String> qualityLabels = new ArrayList<>();
    private int currentQualityIndex = -1; // -1 = 自动

    // ── 线程/Handler ────────────────────────────────────────
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── 频道号跳转 ──────────────────────────────────────────
    private final StringBuilder channelNumberBuffer = new StringBuilder();
    private final Handler channelNumberHandler = new Handler(Looper.getMainLooper());
    private static final long CHANNEL_NUMBER_TIMEOUT = 3000; // 3秒无输入则跳转

    // ── 选台面板显隐 ─────────────────────────────────────────
    private boolean panelVisible = false;
    private final Handler panelAutoHideHandler = new Handler(Looper.getMainLooper());
    private static final long PANEL_AUTO_HIDE_DELAY = 12000;

    // ── 频道信息叠加层自动隐藏 ──────────────────────────────
    private final Handler infoOverlayHandler = new Handler(Looper.getMainLooper());
    private static final long INFO_OVERLAY_DURATION = 5000;

    // ── 应用内自动更新 ──────────────────────────────────────
    private UpdateChecker updateChecker;
    private static final long UPDATE_CHECK_DELAY = 4000; // 启动后 4s 静默检查

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

        // 加载已保存的主题
        loadTheme();

        // 沉浸式全屏
        hideSystemUI();

        // 拉取频道数据
        executor.execute(this::fetchChannelsJson);

        // 启动后静默检查更新（延迟，避免与频道加载抢带宽）
        updateChecker = new UpdateChecker(this);
        new Handler(Looper.getMainLooper())
                .postDelayed(() -> updateChecker.checkForUpdate(true), UPDATE_CHECK_DELAY);
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
        panelAutoHideHandler.removeCallbacksAndMessages(null);
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
        channelPanel = findViewById(R.id.channel_panel);
        rvCategories = findViewById(R.id.rv_categories);
        rvChannels = findViewById(R.id.rv_channels);
        etSearch = findViewById(R.id.et_search);
        tvPanelCount = findViewById(R.id.tv_panel_count);
        tvChannelName = findViewById(R.id.tv_channel_name);
        tvChannelNumSmall = findViewById(R.id.tv_channel_num_small);
        tvChannelGroup = findViewById(R.id.tv_channel_group);
        tvEpgNow = findViewById(R.id.tv_epg_now);
        tvResolution = findViewById(R.id.tv_resolution);
        tvBitrate = findViewById(R.id.tv_bitrate);
        tvSignalText = findViewById(R.id.tv_signal_text);
        channelInfoOverlay = findViewById(R.id.channel_info_overlay);
        tvSpeed = findViewById(R.id.tv_speed);
        tvChannelNumber = findViewById(R.id.tv_channel_number);
        tvStatus = findViewById(R.id.tv_status);
        tvStatusContainer = findViewById(R.id.tv_status_container);
        btnRetry = findViewById(R.id.btn_retry);
        if (btnRetry != null) {
            btnRetry.setOnClickListener(v -> {
                hideStatus();
                if (currentChannel != null) playChannel(currentChannel);
            });
        }
        sigBar1 = findViewById(R.id.sig_bar1);
        sigBar2 = findViewById(R.id.sig_bar2);
        sigBar3 = findViewById(R.id.sig_bar3);
        sigBar4 = findViewById(R.id.sig_bar4);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s != null ? s.toString().trim() : "";
                refreshChannelGrid();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        loadPlayHistory();

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
        if (tvStatusContainer != null) tvStatusContainer.setVisibility(View.VISIBLE);
        // 错误状态显示重试按钮
        if (btnRetry != null) {
            boolean isError = text.contains("失败") || text.contains("不可用") || text.contains("异常");
            btnRetry.setVisibility(isError ? View.VISIBLE : View.GONE);
        }
    }

    private void hideStatus() {
        if (tvStatusContainer != null) tvStatusContainer.setVisibility(View.GONE);
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
                .setBufferDurationsMs(15000, 50000, 1500, 2000)
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
     * 播放指定 URL（带淡入动画）
     */
    private void playUrl(String url) {
        if (player == null || url == null || url.isEmpty()) return;

        Log.i(TAG, "播放: " + url);

        // 切台时黑屏渐隐渐显
        final android.view.View fadeOverlay = findViewById(R.id.channel_info_overlay);
        android.view.animation.Animation fadeIn = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.fade_in);
        fadeIn.setDuration(300);
        if (playerView != null) playerView.startAnimation(fadeIn);

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

        // 记录播放历史
        addToHistory(channel.id);

        // 显示频道信息叠加层
        showChannelInfo(channel);

        // 更新选中状态
        channelAdapter.setSelectedChannelId(channel.id);
        int pos = channelAdapter.findPositionByChannelId(channel.id);
        if (pos >= 0) {
            rvChannels.scrollToPosition(pos);
        }
    }

    // ══════════════════════════════════════════════════════════
    // 画面比例切换
    // ══════════════════════════════════════════════════════════

    private void applyResizeMode(int mode) {
        currentResizeMode = mode;
        if (playerView == null) return;
        switch (mode) {
            case RESIZE_MODE_FIT:     playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);    break;
            case RESIZE_MODE_FILL:    playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);   break;
            case RESIZE_MODE_ZOOM:    playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);   break;
            case RESIZE_MODE_16_9:    playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH); break;
            case RESIZE_MODE_4_3:     playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT); break;
        }
        Toast.makeText(this, "画面比例: " + RESIZE_MODE_LABELS[mode], Toast.LENGTH_SHORT).show();
    }

    private void cycleResizeMode() {
        int next = (currentResizeMode + 1) % RESIZE_MODE_LABELS.length;
        applyResizeMode(next);
    }

    // ══════════════════════════════════════════════════════════
    // 播放历史
    // ══════════════════════════════════════════════════════════

    private void loadPlayHistory() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String raw = prefs.getString(KEY_PLAY_HISTORY, "");
        playHistory.clear();
        if (raw != null && !raw.isEmpty()) {
            for (String idStr : raw.split(",")) {
                try {
                    playHistory.add(idStr.trim());
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    private void savePlayHistory() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < playHistory.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(playHistory.get(i));
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(KEY_PLAY_HISTORY, sb.toString()).apply();
    }

    private void addToHistory(int channelId) {
        String idStr = String.valueOf(channelId);
        playHistory.remove(idStr);
        playHistory.add(0, idStr);
        while (playHistory.size() > MAX_HISTORY) playHistory.remove(playHistory.size() - 1);
        savePlayHistory();
    }

    /** 通过频道 ID 查找频道 */
    private Channel findChannelById(int id) {
        for (Channel ch : allChannels) if (ch.id == id) return ch;
        return null;
    }

    // ══════════════════════════════════════════════════════════
    // 主题切换
    // ══════════════════════════════════════════════════════════

    private void showThemeDialog() {
        new AlertDialog.Builder(this)
                .setTitle("选择主题")
                .setSingleChoiceItems(THEME_NAMES, currentTheme, (dialog, which) -> {
                    applyTheme(which);
                    Toast.makeText(this, "主题: " + THEME_NAMES[which], Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 应用主题：持久化 + 立即修改背景/面板颜色 */
    private void applyTheme(int themeIndex) {
        currentTheme = themeIndex;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(KEY_THEME, themeIndex).apply();
        int[] colors = THEME_COLORS[themeIndex];
        // 应用到播放器区域背景
        View root = findViewById(android.R.id.content);
        root.setBackgroundColor(colors[0]);
        // 应用到选台面板
        if (channelPanel != null) channelPanel.setBackgroundColor((colors[0] << 8) | 0xE6000000);
    }

    /** 从 SharedPreferences 加载已保存的主题 */
    private void loadTheme() {
        currentTheme = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_THEME, 0);
        applyTheme(currentTheme);
    }

    // ══════════════════════════════════════════════════════════
    // 画中画 (PiP)
    // ══════════════════════════════════════════════════════════

    private void enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "系统版本不支持画中画", Toast.LENGTH_SHORT).show();
            return;
        }
        if (player == null) return;
        Rational aspectRatio = new Rational(16, 9);
        PictureInPictureParams params = new PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build();
        enterPictureInPictureMode(params);
    }

    // ══════════════════════════════════════════════════════════
    // 频道信息叠加层
    // ══════════════════════════════════════════════════════════

    private void showChannelInfo(Channel channel) {
        tvChannelName.setText(channel.name);
        tvChannelGroup.setText(channel.group);
        // 频道号小字
        if (tvChannelNumSmall != null && channel.channelNumber > 0) {
            tvChannelNumSmall.setText("CH" + channel.channelNumber);
            tvChannelNumSmall.setVisibility(View.VISIBLE);
        }
        // EPG 当前节目（预留）
        if (tvEpgNow != null) tvEpgNow.setVisibility(View.GONE);
        channelInfoOverlay.setVisibility(View.VISIBLE);

        infoOverlayHandler.removeCallbacksAndMessages(null);
        infoOverlayHandler.postDelayed(() ->
                channelInfoOverlay.setVisibility(View.GONE), INFO_OVERLAY_DURATION);
    }

    /** 更新信号强度指示器（根据码率分级） */
    private void updateSignalBars(long bitrateKbps) {
        if (sigBar1 == null) return;
        int level = bitrateKbps > 6000 ? 4 : bitrateKbps > 3000 ? 3 : bitrateKbps > 1000 ? 2 : bitrateKbps > 0 ? 1 : 0;
        int activeColor = level >= 3 ? 0xFF2ECC71 : level >= 2 ? 0xFFFFFFFF : 0xFFE74C3C;
        setBar(sigBar1, level >= 1, activeColor);
        setBar(sigBar2, level >= 2, activeColor);
        setBar(sigBar3, level >= 3, activeColor);
        setBar(sigBar4, level >= 4, activeColor);
        if (tvSignalText != null) {
            tvSignalText.setVisibility(level > 0 ? View.VISIBLE : View.GONE);
            tvSignalText.setText(level >= 4 ? "4K" : level >= 3 ? "高清" : level >= 2 ? "标清" : "流畅");
        }
    }

    private void setBar(View bar, boolean active, int activeColor) {
        if (bar == null) return;
        bar.setBackgroundColor(active ? activeColor : 0x33FFFFFF);
    }

    // ══════════════════════════════════════════════════════════
    // 实时网速
    // ══════════════════════════════════════════════════════════

    private void startSpeedRefresh() {
        speedHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (player != null && (player.getPlaybackState() == Player.STATE_READY
                        || player.getPlaybackState() == Player.STATE_BUFFERING)) {
                    long bitrate = bandwidthMeter.getBitrateEstimate();
                    tvSpeed.setText(formatSpeed(bitrate));
                    // 更新信号强度
                    updateSignalBars(bitrate / 1000);
                    // 更新分辨率信息
                    if (tvResolution != null && player.getVideoFormat() != null) {
                        com.google.android.exoplayer2.Format fmt = player.getVideoFormat();
                        if (fmt.width > 0 && fmt.height > 0) {
                            tvResolution.setText(fmt.width + "x" + fmt.height);
                            tvResolution.setVisibility(View.VISIBLE);
                        }
                    }
                    // 更新码率
                    if (tvBitrate != null && bitrate > 0) {
                        tvBitrate.setText(formatBitrate(bitrate));
                        tvBitrate.setVisibility(View.VISIBLE);
                    }
                }
                speedHandler.postDelayed(this, SPEED_REFRESH_INTERVAL);
            }
        }, SPEED_REFRESH_INTERVAL);
    }

    private String formatBitrate(long bitrateBps) {
        if (bitrateBps <= 0) return "";
        if (bitrateBps >= 1000000) return String.format("%.1f Mbps", bitrateBps / 1000000.0);
        return String.format("%.0f kbps", bitrateBps / 1000.0);
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
        categoryAdapter = new CategoryAdapter((item, position) -> {
            currentCategoryId = item.category.id;
            refreshChannelGrid();
        });
        rvCategories.setLayoutManager(new LinearLayoutManager(this));
        rvCategories.setAdapter(categoryAdapter);

        int span = getGridSpanCount();
        GridLayoutManager gridLayout = new GridLayoutManager(this, span);
        channelAdapter = new ChannelAdapter(new ChannelAdapter.OnChannelClickListener() {
            @Override
            public void onChannelClick(Channel channel, int position) {
                playChannel(channel);
                hideChannelPanel();
            }

            @Override
            public boolean onChannelLongClick(Channel channel, int position) {
                toggleFavorite(channel);
                return true;
            }
        });
        rvChannels.setLayoutManager(gridLayout);
        rvChannels.setAdapter(channelAdapter);
    }

    private int getGridSpanCount() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        if (widthDp >= 960) return 5;
        if (widthDp >= 720) return 4;
        if (widthDp >= 480) return 3;
        return 2;
    }

    // ══════════════════════════════════════════════════════════
    // 侧边栏显隐
    // ══════════════════════════════════════════════════════════

    private void showChannelPanel() {
        panelVisible = true;
        channelPanel.setVisibility(View.VISIBLE);
        // 滑入动画
        android.view.animation.Animation slideIn = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.slide_in_right);
        channelPanel.startAnimation(slideIn);
        refreshCategoryNav();
        refreshChannelGrid();
        panelAutoHideHandler.removeCallbacksAndMessages(null);
        panelAutoHideHandler.postDelayed(this::hideChannelPanel, PANEL_AUTO_HIDE_DELAY);

        if (currentChannel != null) {
            int pos = channelAdapter.findPositionByChannelId(currentChannel.id);
            if (pos >= 0) rvChannels.scrollToPosition(pos);
        }
        rvChannels.requestFocus();
    }

    private void hideChannelPanel() {
        // 滑出动画
        android.view.animation.Animation slideOut = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.slide_out_right);
        slideOut.setAnimationListener(new android.view.animation.Animation.AnimationListener() {
            @Override public void onAnimationStart(android.view.animation.Animation animation) {}
            @Override public void onAnimationRepeat(android.view.animation.Animation animation) {}
            @Override public void onAnimationEnd(android.view.animation.Animation animation) {
                channelPanel.setVisibility(View.GONE);
            }
        });
        channelPanel.startAnimation(slideOut);
        panelVisible = false;
        panelAutoHideHandler.removeCallbacksAndMessages(null);
        playerView.requestFocus();
    }

    private void toggleChannelPanel() {
        if (panelVisible) hideChannelPanel();
        else showChannelPanel();
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
                return false;
            }

            allChannels.clear();

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

            Log.i(TAG, "解析完成: " + allChannels.size() + " 个频道");
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
        currentCategoryId = CategoryHelper.ALL;
        searchQuery = "";
        if (etSearch != null) etSearch.setText("");

        refreshCategoryNav();
        refreshChannelGrid();
        hideStatus();

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
                playChannel(last);
            } else {
                showChannelPanel();
            }
        }
    }

    private void refreshCategoryNav() {
        List<CategoryAdapter.CategoryItem> items = new ArrayList<>();
        Map<String, List<Channel>> buckets = CategoryHelper.buildSmartBuckets(allChannels);

        for (CategoryHelper.Category cat : CategoryHelper.getNavCategories()) {
            int count;
            if (CategoryHelper.ALL.equals(cat.id)) {
                count = allChannels.size();
            } else if (CategoryHelper.FAV.equals(cat.id)) {
                count = 0;
                for (Channel ch : allChannels) if (ch.isFavorite) count++;
            } else if (CategoryHelper.HISTORY.equals(cat.id)) {
                count = playHistory.size();
            } else {
                List<Channel> bucket = buckets.get(cat.id);
                count = bucket != null ? bucket.size() : 0;
            }
            if (CategoryHelper.FAV.equals(cat.id) && count == 0) continue;
            if (CategoryHelper.HISTORY.equals(cat.id) && count == 0) continue;
            items.add(new CategoryAdapter.CategoryItem(cat, count));
        }

        categoryAdapter.setItems(items);

        int selectedPos = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).category.id.equals(currentCategoryId)) {
                selectedPos = i;
                break;
            }
        }
        categoryAdapter.setSelectedPosition(selectedPos);
    }

    private void refreshChannelGrid() {
        if (channelAdapter != null) channelAdapter.setSearchQuery(searchQuery);
        List<Channel> filtered = CategoryHelper.filter(allChannels, currentCategoryId, false);
        // 应用排序
        applySort(filtered);
        // 历史分类：按观看历史排序（最新在前）
        if (CategoryHelper.HISTORY.equals(currentCategoryId) && !playHistory.isEmpty()) {
            Map<Integer, Channel> idMap = new java.util.HashMap<>();
            for (Channel ch : filtered) idMap.put(ch.id, ch);
            List<Channel> histList = new ArrayList<>();
            for (String idStr : playHistory) {
                try {
                    Channel ch = idMap.get(Integer.parseInt(idStr));
                    if (ch != null) histList.add(ch);
                } catch (NumberFormatException ignored) {}
            }
            filtered = histList;
        }
        filtered = CategoryHelper.search(filtered, searchQuery);
        channelAdapter.setChannels(filtered);

        if (tvPanelCount != null) {
            tvPanelCount.setText(filtered.size() + " 个频道");
        }

        if (currentChannel != null) {
            channelAdapter.setSelectedChannelId(currentChannel.id);
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
        refreshCategoryNav();
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
    // 多画面模式（2x2 网格，实验性）
    // ══════════════════════════════════════════════════════════

    private void toggleMultiview() {
        Toast.makeText(this, "多画面模式 (实验性功能，需要多 ExoPlayer 实例)", Toast.LENGTH_SHORT).show();
    }

    // ══════════════════════════════════════════════════════════
    // 截图
    // ══════════════════════════════════════════════════════════

    private void takeScreenshot() {
        if (playerView == null) {
            Toast.makeText(this, "播放器未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // 截取 PlayerView 画面（绘制到 Bitmap）
            Bitmap bitmap = Bitmap.createBitmap(
                    playerView.getWidth(), playerView.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            playerView.draw(canvas);
            // 保存到应用私有目录
            File dir = new File(getExternalFilesDir(null), "screenshots");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "MaoziTV_" + System.currentTimeMillis() + ".png");
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
            Toast.makeText(this, "截图已保存", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "截图失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ══════════════════════════════════════════════════════════
    // 音轨 / 字幕切换 (ExoPlayer TrackSelector)
    // ══════════════════════════════════════════════════════════

    private void showTrackDialog(final boolean isSubtitle) {
        if (player == null || trackSelector == null) {
            Toast.makeText(this, "播放器未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // 获取当前轨道组
            com.google.android.exoplayer2.Tracks tracks = player.getCurrentTracks();
            List<String> labels = new ArrayList<>();
            List<Integer> trackIndices = new ArrayList<>();
            labels.add("自动");
            trackIndices.add(-1);
            // 遍历所有轨道组，找出视频/音频/字幕
            for (com.google.android.exoplayer2.Tracks.Group group : tracks.getGroups()) {
                for (int i = 0; i < group.length; i++) {
                    com.google.android.exoplayer2.Format format = group.getTrackFormat(i);
                    if (isSubtitle) {
                        if (format.sampleMimeType != null && format.sampleMimeType.startsWith("text")) {
                            labels.add(format.language != null ? format.language : "字幕 " + (labels.size()));
                            trackIndices.add(trackIndices.size() - 1);
                        }
                    } else {
                        if (format.sampleMimeType != null && format.sampleMimeType.startsWith("audio")) {
                            labels.add(format.language != null ? format.language : "音轨 " + (labels.size()));
                            trackIndices.add(trackIndices.size() - 1);
                        }
                    }
                }
            }
            if (labels.size() <= 1) {
                Toast.makeText(this, isSubtitle ? "无可用字幕" : "当前只有一个音轨", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] items = labels.toArray(new String[0]);
            new AlertDialog.Builder(this)
                    .setTitle(isSubtitle ? "字幕" : "音轨")
                    .setItems(items, (dialog, which) -> {
                        Toast.makeText(this, (isSubtitle ? "字幕: " : "音轨: ") + items[which], Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "获取轨道信息失败", Toast.LENGTH_SHORT).show();
        }
    }

    // ══════════════════════════════════════════════════════════
    // 多源测速选最快
    // ══════════════════════════════════════════════════════════

    private void autoSelectFastestSource() {
        if (currentChannel == null || currentChannel.sources.size() <= 1) {
            Toast.makeText(this, "当前频道只有一个源", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在测速...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int fastestIdx = -1;
            long fastestMs = Long.MAX_VALUE;
            for (int i = 0; i < currentChannel.sources.size(); i++) {
                long ms = testUrlSpeed(currentChannel.sources.get(i));
                if (ms < fastestMs) { fastestMs = ms; fastestIdx = i; }
            }
            final int idx = fastestIdx;
            mainHandler.post(() -> {
                if (idx >= 0 && idx != currentChannel.currentSourceIndex) {
                    currentChannel.currentSourceIndex = idx;
                    playUrl(currentChannel.getCurrentSourceUrl());
                    Toast.makeText(this, "已切换到最快源 " + (idx + 1), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "当前已是最快源", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private long testUrlSpeed(String url) {
        try {
            long start = System.currentTimeMillis();
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code >= 200 && code < 400) return System.currentTimeMillis() - start;
            return Long.MAX_VALUE;
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    // ══════════════════════════════════════════════════════════
    // 频道排序
    // ══════════════════════════════════════════════════════════

    private void applySort(List<Channel> list) {
        switch (currentSortMode) {
            case SORT_NAME:
                java.util.Collections.sort(list, (a, b) -> a.name.compareTo(b.name));
                break;
            case SORT_GROUP:
                java.util.Collections.sort(list, (a, b) -> (a.group != null ? a.group : "").compareTo(b.group != null ? b.group : ""));
                break;
        }
    }

    private void cycleSortMode() {
        currentSortMode = (currentSortMode + 1) % SORT_LABELS.length;
        Toast.makeText(this, "排序: " + SORT_LABELS[currentSortMode], Toast.LENGTH_SHORT).show();
        refreshChannelGrid();
    }

    // ══════════════════════════════════════════════════════════
    // EPG 节目单
    // ══════════════════════════════════════════════════════════

    private void showEpgDialog(Channel channel) {
        if (channel == null) return;
        // EPG 暂未接入后端数据源，直接 Toast 提示而非弹空壳对话框
        Toast.makeText(this, channel.name + "：EPG 节目单暂未接入", Toast.LENGTH_SHORT).show();
    }

    // ══════════════════════════════════════════════════════════
    // 设置 / 模式切换
    // ══════════════════════════════════════════════════════════

    private void showSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setItems(new String[]{
                        "检查更新",
                        "更新频道源 (重新拉取)",
                        "切换播放源",
                        "画质 (自动)",
                        "画面比例 (" + RESIZE_MODE_LABELS[currentResizeMode] + ")",
                        "截图",
                        "测速选最快源",
                        "排序 (" + SORT_LABELS[currentSortMode] + ")",
                        "主题",
                        "画中画",
                        "独立模式 (Gitee/GitHub)",
                        "服务器模式 (连接后端 API)"
                }, (dialog, which) -> {
                    if (which == 0) {
                        if (updateChecker != null) {
                            updateChecker.checkForUpdate(false);
                        } else {
                            updateChecker = new UpdateChecker(this);
                            updateChecker.checkForUpdate(false);
                        }
                    } else if (which == 1) {
                        refreshChannels();
                    } else if (which == 2) {
                        showSourceSwitchDialog();
                    } else if (which == 3) {
                        showQualityDialog();
                    } else if (which == 4) {
                        showResizeModeDialog();
                    } else if (which == 5) {
                        takeScreenshot();
                    } else if (which == 6) {
                        autoSelectFastestSource();
                    } else if (which == 7) {
                        cycleSortMode();
                    } else if (which == 8) {
                        showThemeDialog();
                    } else if (which == 9) {
                        enterPipMode();
                    } else if (which == 10) {
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

    // 画质选择对话框
    private void showQualityDialog() {
        if (player == null || trackSelector == null) {
            Toast.makeText(this, "播放器未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        // 从 ExoPlayer 获取实际可用视频轨道
        Tracks tracks = player.getCurrentTracks();
        List<Tracks.Group> videoGroups = new ArrayList<>();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_VIDEO) {
                videoGroups.add(group);
            }
        }

        // 构建画质选项
        List<String> labels = new ArrayList<>();
        labels.add("自动 (ABR)");
        final List<Integer> heights = new ArrayList<>();
        heights.add(-1); // 自动

        // 收集所有视频轨道的分辨率
        for (Tracks.Group group : videoGroups) {
            for (int i = 0; i < group.length; i++) {
                Format fmt = group.getTrackFormat(i);
                if (fmt.height > 0) {
                    String label = fmt.height >= 2160 ? "4K" : fmt.height + "p";
                    if (fmt.bitrate > 0) label += " (" + (fmt.bitrate / 1000000) + "Mbps)";
                    labels.add(label);
                    heights.add(fmt.height);
                }
            }
        }

        if (labels.size() <= 1) {
            // 直播源通常单码率，没有多画质可选
            Toast.makeText(this, "当前频道只有一个画质", Toast.LENGTH_SHORT).show();
            return;
        }

        final String[] items = labels.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("选择画质")
                .setSingleChoiceItems(items, 0, (dialog, which) -> {
                    applyQualityPreference(heights.get(which));
                    Toast.makeText(this, "画质: " + items[which], Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 应用画质偏好到 TrackSelector。
     * @param maxHeight -1=自动(ABR)；>0=限制最高分辨率
     */
    private void applyQualityPreference(int maxHeight) {
        if (trackSelector == null) return;
        DefaultTrackSelector.Parameters.Builder params = trackSelector.buildUponParameters();
        if (maxHeight < 0) {
            // 自动：清除限制
            params.clearVideoSizeConstraints();
        } else {
            // 限制最大分辨率（ExoPlayer 会选不超过此高度的最高画质）
            params.setMaxVideoSize(Integer.MAX_VALUE, maxHeight);
        }
        trackSelector.setParameters(params);
    }

    // 画面比例选择对话框
    private void showResizeModeDialog() {
        new AlertDialog.Builder(this)
                .setTitle("画面比例")
                .setSingleChoiceItems(RESIZE_MODE_LABELS, currentResizeMode, (dialog, which) -> {
                    applyResizeMode(which);
                    dialog.dismiss();
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
        if (channelNumber < 1 || channelNumber > allChannels.size()) {
            Toast.makeText(this, "频道号超出范围 (1-" + allChannels.size() + ")", Toast.LENGTH_SHORT).show();
            return;
        }
        Channel target = allChannels.get(channelNumber - 1);
        currentCategoryId = CategoryHelper.ALL;
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
        // 选台面板打开时：交给 RecyclerView 自身的焦点导航（上下/左右移动），
        // 只拦截 OK/BACK/数字键，其余方向键透传给系统做焦点移动。
        if (panelVisible) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (event.getRepeatCount() == 0) {
                        okLongPressed = false;
                        event.startTracking();
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    hideChannelPanel();
                    return true;
                // 面板内方向键：透传到系统，让 RecyclerView 做焦点移动
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    break;
            }
            return super.onKeyDown(keyCode, event);
        }

        // ── 面板关闭（全屏播放态）──────────────────────────────
        // 数字键：频道号快速跳转
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            handleChannelNumberInput(keyCode - KeyEvent.KEYCODE_0);
            return true;
        }

        switch (keyCode) {
            // MENU 键 → 设置
            case KeyEvent.KEYCODE_MENU:
                showSettingsDialog();
                return true;

            // OK/Enter 短按 → 打开选台面板（长按另走收藏）
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (event.getRepeatCount() == 0) {
                    okLongPressed = false;
                    event.startTracking();
                }
                return true;

            // 上/下键 → 切换频道
            case KeyEvent.KEYCODE_DPAD_UP:
                switchChannelUp();
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                switchChannelDown();
                return true;

            // 左/右键 → 调音量
            case KeyEvent.KEYCODE_DPAD_LEFT:
                adjustVolume(-1);
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                adjustVolume(1);
                return true;

            // ═══ 颜色键（红绿黄蓝）═══
            // 使用直接数字键码，确保所有Android版本兼容
            case 127:                              // KEYCODE_PROPS (红键，部分遥控器)
            case KeyEvent.KEYCODE_F1:              // 红键备选 (F1)
            case 183:                              // KEYCODE_RED (Android 5.0+)
                // 红键 = 收藏/取消收藏
                if (currentChannel != null) toggleFavorite(currentChannel);
                return true;
            case KeyEvent.KEYCODE_F2:              // 绿键备选 (F2)
            case 184:                              // KEYCODE_GREEN (Android 5.0+)
                if (currentChannel != null) showEpgDialog(currentChannel);
                return true;
            case KeyEvent.KEYCODE_F3:              // 黄键备选 (F3)
            case 185:                              // KEYCODE_YELLOW (Android 5.0+)
                if (currentChannel != null && currentChannel.sources.size() > 1) showSourceSwitchDialog();
                return true;
            case KeyEvent.KEYCODE_F4:              // 蓝键备选 (F4)
            case 186:                              // KEYCODE_BLUE (Android 5.0+)
                showQualityDialog();
                return true;

            // BACK 键：长按退出（短按交给系统默认退出行为）
            case KeyEvent.KEYCODE_BACK:
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
                // 面板可见时同步刷新计数
                if (panelVisible) refreshCategoryNav();
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
                if (panelVisible) {
                    // 面板打开：播放当前焦点卡片；无焦点则关闭面板
                    View focused = rvChannels.getFocusedChild();
                    if (focused != null) {
                        focused.performClick();
                    } else {
                        // 焦点在分类栏时，回车切到频道网格
                        View catFocused = rvCategories.getFocusedChild();
                        if (catFocused != null) {
                            rvChannels.requestFocus();
                        }
                    }
                } else {
                    // 全屏播放：打开选台面板
                    showChannelPanel();
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
        // 面板关闭时上下键换台用「全部频道」，避免面板关闭后列表为空导致无法换台。
        List<Channel> list = channelAdapter.getChannels();
        if (list == null || list.isEmpty()) list = allChannels;
        if (list.isEmpty()) return;
        // 用 id 查找当前频道位置（比 indexOf 引用相等更稳妥）
        int currentIndex = -1;
        if (currentChannel != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == currentChannel.id) { currentIndex = i; break; }
            }
        }
        // 当前频道不在本组（如刚切换分类），从第 0 个开始
        if (currentIndex < 0) currentIndex = 0;
        int newIndex = (currentIndex <= 0) ? list.size() - 1 : currentIndex - 1;
        playChannel(list.get(newIndex));
    }

    private void switchChannelDown() {
        // 同上：优先用当前分类列表，为空时回退全部频道。
        List<Channel> list = channelAdapter.getChannels();
        if (list == null || list.isEmpty()) list = allChannels;
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
            // 点击屏幕 → 切换选台面板（面板打开时则关闭）
            toggleChannelPanel();
            return true;
        }
        return super.onTouchEvent(event);
    }
}
