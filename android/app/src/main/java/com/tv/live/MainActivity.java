package com.tv.live;

import android.content.Intent;
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
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
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
import androidx.recyclerview.widget.DiffUtil;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
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

    // 请求码：SettingsActivity 返回
    private static final int REQ_SETTINGS = 1001;

    // ── Standalone 模式：JSON 源地址（回退用，优先使用 source-list.json）──
    private static final String[] JSON_URLS = {
            "https://gitee.com/liuxue5213/maozi-tv/raw/main/channels.json",
            "https://raw.githubusercontent.com/liuxue5213/Maozi-TV/main/channels.json",
    };

    // ── 软解：只使用软件解码器（OMX.google.*），兼容硬解黑屏的盒子 ──
    private static final MediaCodecSelector SOFTWARE_ONLY_SELECTOR = new MediaCodecSelector() {
        @Override
        public java.util.List<MediaCodecInfo> getDecoderInfos(String mimeType,
                                                              boolean requiresSecureDecoder,
                                                              boolean requiresTunnelingDecoder)
                throws MediaCodecUtil.DecoderQueryException {
            java.util.List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT
                    .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
            java.util.List<MediaCodecInfo> software = new ArrayList<>();
            for (MediaCodecInfo info : all) {
                if (info.name != null && info.name.startsWith("OMX.google.")) {
                    software.add(info);
                }
            }
            return software;
        }
    };

    // ── SharedPreferences 缓存 ───────────────────────────────
    private static final String PREFS_NAME = "maozi_tv_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_CACHED_JSON = "cached_channels_json";
    private static final String KEY_CACHED_VERSION = "cached_version";
    private static final String KEY_SOURCE_INFO = "source_info_json"; // 信号源信息（版本/时间/频道数）
    private static final String KEY_CACHED_TS = "cached_generated_ts";
    private static final String KEY_MODE = "mode";
    private static final String KEY_FAVORITES = "favorites"; // 收藏频道 ID 集合
    private static final String KEY_LAST_CHANNEL = "last_channel_id"; // 上次播放的频道 ID
    private static final String KEY_PLAY_HISTORY = "play_history"; // 播放历史（频道 ID，逗号分隔）
    private static final String KEY_HIDDEN_GROUPS = "hidden_groups"; // 用户隐藏的分组（逗号分隔）
    private static final String KEY_THEME = "theme"; // 主题（dark/blue/green）
    private static final String KEY_SOFT_DECODER = "soft_decoder"; // 软解开关
    private static final String KEY_BOOT_LAUNCH = "boot_launch_enabled"; // 开机自启
    private static final String KEY_SLEEP_TIMER = "sleep_timer_minutes"; // 定时关机(分钟)
    private static final String KEY_PARENTAL_LOCK = "parental_lock"; // 家长模式密码
    private static final String KEY_PARENTAL_ENABLED = "parental_enabled"; // 家长模式开关
    private static final String KEY_CUSTOM_SOURCES = "custom_sources"; // 自定义源 URL 列表
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
    private static final String PREF_CHANNEL_RESIZE_MODES = "channel_resize_modes";

    // ── 手势控制 ───────────────────────────────────────────
    private GestureDetector gestureDetector;
    private AudioManager audioManager;
    private int maxVolume;
    private float brightness = -1f; // -1 表示使用系统亮度

    // ── 视图 ────────────────────────────────────────────────
    private PlayerView playerView;
    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private DefaultBandwidthMeter bandwidthMeter;
    private SpeedMeter speedMeter = new SpeedMeter(); // 实时网速统计

    private View channelPanel;
    private RecyclerView rvCategories;
    private RecyclerView rvChannels;
    private CategoryAdapter categoryAdapter;
    private ChannelAdapter channelAdapter;
    private EditText etSearch;
    private TextView tvPanelCount;
    private TextView tvSourceInfo; // 信号源版本信息显示
    private TextView tvEmptyState; // 搜索无结果空状态
    private android.widget.ProgressBar progressChannels; // 频道加载进度条

    private TextView tvChannelName;
    private TextView tvChannelNumSmall;
    private TextView tvChannelGroup;
    private TextView tvEpgNow;
    private TextView tvResolution;
    private TextView tvBitrate;
    private TextView tvSignalText;
    private TextView tvPlayPause;
    private View channelInfoOverlay;
    private TextView tvSpeed;
    private TextView tvChannelNumber;
    private TextView tvStatus;
    private View tvStatusContainer;
    private android.widget.Button btnRetry;
    private View sigBar1, sigBar2, sigBar3, sigBar4;

    // ── 数据 ────────────────────────────────────────────────
    private final List<ChannelOptimized> allChannels = new ArrayList<>();
    private String currentCategoryId = CategoryHelper.ALL;
    private String currentSubGroup = null; // 二级分组（省份），null=显示全部
    private String searchQuery = "";
    private ChannelOptimized currentChannel;
    private final List<String> playHistory = new ArrayList<>(); // 播放历史（频道 ID，新→旧）
    private static final int MAX_HISTORY = 30;
    private int currentResizeMode = RESIZE_MODE_FIT; // 当前画面比例模式
    // 画质轨道信息（ExoPlayer 的 VideoTrackSelection 列表）
    private List<String> qualityLabels = new ArrayList<>();
    private int currentQualityIndex = -1; // -1 = 自动

    // ── 分类缓存（避免重复计算 buildSmartBuckets）────────────
    private Map<String, List<ChannelOptimized>> cachedBuckets = null;

    // ── 搜索防抖 ───────────────────────────────────────────
    private Handler searchHandler = null;

    // ── 用户偏好：隐藏的分组（Set 便于 O(1) 判断）──────────
    private final java.util.Set<String> hiddenGroups = new java.util.HashSet<>();

    // ── 播放器扩展状态 ──────────────────────────────────────
    private boolean useSoftwareDecoder = false; // true=软解
    private float currentPlaybackSpeed = 1.0f;  // 当前播放速度

    // ── 定时关机 ────────────────────────────────────────────
    private final Handler sleepTimerHandler = new Handler(Looper.getMainLooper());
    private long sleepTimerEndTime = 0; // 定时关机结束时间(ms)，0=未设置

    // ── 多画面模式 ──────────────────────────────────────────
    private boolean multiviewActive = false; // 多画面是否激活
    private android.widget.GridLayout multiviewGrid;
    private final List<com.google.android.exoplayer2.ui.PlayerView> mvPlayerViews = new ArrayList<>();
    private final List<ExoPlayer> mvPlayers = new ArrayList<>();
    private static final int MV_COUNT = 4; // 2x2

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
    private static final long PANEL_AUTO_HIDE_DELAY = 30000; // 延长到30秒
    private long lastUserInteractionTime = 0; // 最后一次用户操作时间

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

    // ══════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 🔥 全局异常捕获 — 防止未捕获异常直接闪退，记录日志 + 优雅恢复
        setupGlobalExceptionHandler();

        // 返回键：targetSdk 34 必须用 OnBackPressedDispatcher（onKeyDown(BACK) 已失效）
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });

        setContentView(R.layout.activity_main);

        // 保持屏幕常亮：防止手机按系统"屏幕超时"自动锁屏（TV 盒子本就不会锁屏，手机端才需要）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        // 检查上次闪退的崩溃日志，若有则弹窗展示（便于排查，无 adb 环境可用）
        checkCrashLog();

        initViews();
        initPlayer();
        initGestureControl();
        initRecyclerViews();
        setupKeyListener();

        // 加载已保存的主题
        loadTheme();

        // 恢复画质限制设置（-1=自动）
        applyQualityPreference(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt("quality_max_height", -1));

        // 沉浸式全屏
        hideSystemUI();

        // 拉取频道数据（多源聚合）
        executor.execute(this::fetchChannelsMultiSource);

        // 启动后静默检查 APK 更新（延迟，避免与频道加载抢带宽）
        updateChecker = new UpdateChecker(this);
        new Handler(Looper.getMainLooper())
                .postDelayed(() -> updateChecker.checkForUpdate(true), UPDATE_CHECK_DELAY);

        // 注册 WorkManager 后台频道源周期检查（每 6 小时）
        ChannelUpdateWorker.schedule(this);

        // 恢复未到期的定时关机
        restoreSleepTimer();

        // 恢复上次播放速度
        currentPlaybackSpeed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getFloat("playback_speed", 1.0f);
        if (player != null) {
            player.setPlaybackSpeed(currentPlaybackSpeed);
        }

        // 家长模式：开启时启动要求输入密码
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_PARENTAL_ENABLED, false)) {
            String savedPin = prefs.getString(KEY_PARENTAL_LOCK, "");
            if (!savedPin.isEmpty()) {
                promptPin("家长模式已开启，请输入密码", pin -> {
                    if (!pin.equals(savedPin)) {
                        Toast.makeText(this, "密码错误，即将退出", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                });
            }
        }

        // 首次启动引导：检测是否从未配置过（默认 server_url + 无缓存数据）
        checkFirstLaunchGuide();

        // 埋点：启动事件
        CloudSync.track(this, "app_start", null, "", "");

        // 云同步：加载云端收藏/历史（延迟，避免与频道加载抢带宽）
        new Handler(Looper.getMainLooper()).postDelayed(this::loadCloudData, 6000);
    }

    /**
     * 从云端加载收藏和历史，合并到本地。
     * 仅在本地无数据时导入云端数据，避免覆盖用户当前状态。
     */
    private void loadCloudData() {
        CloudSync.load(this, (favorites, history) -> {
            if (favorites == null || favorites.isEmpty()) return;
            if (!allChannels.isEmpty()) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String localFav = prefs.getString(KEY_FAVORITES, "");
                if (localFav == null || localFav.isEmpty()) {
                    // 本地无收藏 → 导入云端收藏
                    StringBuilder sb = new StringBuilder();
                    for (Integer id : favorites) {
                        if (sb.length() > 0) sb.append(",");
                        sb.append(id);
                    }
                    prefs.edit().putString(KEY_FAVORITES, sb.toString()).apply();
                    for (ChannelOptimized ch : allChannels) {
                        if (favorites.contains(ch.id)) ch.isFavorite = true;
                    }
                    channelAdapter.notifyDataSetChanged();
                    refreshCategoryNav();
                    Toast.makeText(this, "已从云端恢复收藏 (" + favorites.size() + ")",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SETTINGS) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            // 从设置页返回：刷新设置状态（主题/解码方式/比例/画质/倍速等）
            loadTheme(); // 应用主题（可能已修改）
            if (player != null) {
                // 应用持久化的倍速
                player.setPlaybackSpeed(prefs.getFloat("playback_speed", 1.0f));
                // 应用画面比例
                applyResizeMode(prefs.getInt("resize_mode", RESIZE_MODE_FIT));
            }
            // 应用画质限制（可能已修改）
            applyQualityPreference(prefs.getInt("quality_max_height", -1));
            // 刷新信号源信息显示（遵守 show_source_info 设置）
            updateSourceInfoDisplay();
            // 刷新频道列表（遵守 healthy_only 等设置）
            refreshChannelGrid();
            // 设置页触发了「更新频道源」
            if (resultCode == 1001) {
                refreshChannels();
            }
            // 设置页触发了「分组显示管理」变更
            if (resultCode == 1002) {
                loadHiddenGroups();
                // 重新计算分类桶 + 刷新分类栏和频道列表
                cachedBuckets = CategoryHelper.buildSmartBuckets(filterHiddenGroups(allChannels));
                refreshCategoryNav();
                refreshChannelGrid();
            }
            // 设置页触发了「收藏分组管理」
            if (resultCode == 1003) {
                showGroupManagementDialog();
            }
            // 刷新收藏状态（可能从云端/设置页变化）
            if (channelAdapter != null) {
                channelAdapter.notifyDataSetChanged();
            }
        }
    }

    @Override
    protected void onDestroy() {
        // 恢复默认异常处理器
        if (originalExceptionHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler(originalExceptionHandler);
        }

        // 清理 Handler（含定时关机每秒任务和搜索防抖）
        mainHandler.removeCallbacksAndMessages(null);
        speedHandler.removeCallbacksAndMessages(null);
        channelNumberHandler.removeCallbacksAndMessages(null);
        panelAutoHideHandler.removeCallbacksAndMessages(null);
        infoOverlayHandler.removeCallbacksAndMessages(null);
        sleepTimerHandler.removeCallbacksAndMessages(null);
        if (searchHandler != null) {
            searchHandler.removeCallbacksAndMessages(null);
        }

        // 释放主播放器
        if (player != null) {
            player.release();
            player = null;
        }
        // 释放多画面播放器
        releaseMultiviewPlayers();
        executor.shutdown();
        super.onDestroy();
    }

    // ── 全局异常捕获（闪退兜底）────────────────────────────
    private Thread.UncaughtExceptionHandler originalExceptionHandler;

    /**
     * 设置全局未捕获异常处理器。
     * 目的：
     * 1. 记录崩溃日志（Log.e），方便排查
     * 2. 主线程异常尽量恢复，不直接崩溃
     * 3. 非主线程异常交给系统默认处理（避免死循环）
     */
    private void setupGlobalExceptionHandler() {
        originalExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "未捕获异常 [" + thread.getName() + "]: " + throwable.getMessage(), throwable);
            // 写入崩溃日志文件（便于无 adb 环境排查）
            try {
                CrashLogHandler.writeCrashLog(thread, throwable);
            } catch (Exception ignored) {}

            if (thread.getName().equals("main")) {
                // 主线程异常：尝试恢复，避免直接闪退
                try {
                    if (tvStatusContainer != null && tvStatus != null) {
                        tvStatus.setText("发生错误，正在恢复...");
                        tvStatusContainer.setVisibility(View.VISIBLE);
                    }
                } catch (Exception ignored) {}

                // 记录后交给系统处理（Activity 可能已处于不可用状态）
                if (originalExceptionHandler != null) {
                    originalExceptionHandler.uncaughtException(thread, throwable);
                }
            } else {
                // 子线程异常：交给系统默认处理
                if (originalExceptionHandler != null) {
                    originalExceptionHandler.uncaughtException(thread, throwable);
                } else {
                    System.exit(1);
                }
            }
        });
    }

    /**
     * 检查崩溃日志文件。若上次运行有闪退，弹窗展示堆栈（仅显示最近一次异常的开头部分）。
     */
    private void checkCrashLog() {
        try {
            String log = CrashLogHandler.readCrashLog();
            if (log == null || log.trim().isEmpty()) return;

            // 截取最近一次崩溃记录（最后一条 ==== 段）
            int lastIdx = log.lastIndexOf("========================================");
            String recent = lastIdx >= 0 ? log.substring(lastIdx) : log;
            // 限制展示长度
            if (recent.length() > 800) {
                recent = recent.substring(0, 800) + "\n...(已截断)";
            }

            final String crashInfo = recent;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isFinishing()) return;
                new AlertDialog.Builder(this)
                        .setTitle("⚠️ 上次异常退出")
                        .setMessage("App 上次异常退出了，以下是崩溃信息：\n\n" + crashInfo
                                + "\n\n可反馈给开发者排查。")
                        .setPositiveButton("知道了", (d, w) -> {
                            CrashLogHandler.clearCrashLog();
                            d.dismiss();
                        })
                        .setNegativeButton("保留日志", null)
                        .show();
            }, 1500); // 延迟到界面稳定后弹出
        } catch (Exception e) {
            Log.d(TAG, "检查崩溃日志失败: " + e.getMessage());
        }
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
        tvSourceInfo = findViewById(R.id.tv_source_info);
        tvEmptyState = findViewById(R.id.tv_empty_state);
        progressChannels = findViewById(R.id.progress_channels);

        // 设置入口按钮（触摸设备/无 MENU 键时点击进入设置中心）
        android.widget.Button btnSettings = findViewById(R.id.btn_settings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v ->
                    startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS));
        }
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
        tvPlayPause = findViewById(R.id.tv_play_pause);
        if (tvPlayPause != null) {
            tvPlayPause.setOnClickListener(v -> togglePlayPause());
        }

        // 频道信息叠加层长按 → 音轨选择
        GestureDetector overlayGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                if (player != null && trackSelector != null) {
                    showTrackDialog(false); // false = 音轨
                }
            }
        });
        if (channelInfoOverlay != null) {
            channelInfoOverlay.setOnTouchListener((v, event) -> {
                overlayGestureDetector.onTouchEvent(event);
                return true;
            });
        }

        // 多画面网格
        multiviewGrid = findViewById(R.id.multiview_grid);
        mvPlayerViews.clear();
        mvPlayerViews.add(findViewById(R.id.mv_player1));
        mvPlayerViews.add(findViewById(R.id.mv_player2));
        mvPlayerViews.add(findViewById(R.id.mv_player3));
        mvPlayerViews.add(findViewById(R.id.mv_player4));

        // 搜索防抖：300ms 延迟，避免每次按键都刷新列表导致卡顿
        searchHandler = new Handler(Looper.getMainLooper());
        final Runnable searchRunnable = () -> {
            refreshChannelGrid();
        };
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s != null ? s.toString().trim() : "";
                searchHandler.removeCallbacks(searchRunnable);
                searchHandler.postDelayed(searchRunnable, 300);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        loadPlayHistory();

        showStatus("加载中…");
    }

    private void hideSystemUI() {
        // 不再使用沉浸式全屏（IMMERSIVE_STICKY + LAYOUT_HIDE_NAVIGATION），
        // 那会让内容延伸到虚拟导航栏下方被遮挡，且碰边缘会弹出按钮覆盖内容。
        // 改为：保留系统状态栏/导航栏，App 内容在其安全区内显示，
        // 三个虚拟按钮始终可见可用。
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_VISIBLE
        );
    }

    private void showStatus(String text) {
        tvStatus.setText(text);
        if (tvStatusContainer != null) tvStatusContainer.setVisibility(View.VISIBLE);
        // 加载中状态显示进度条
        boolean isLoading = text.contains("加载") || text.contains("缓冲") || text.contains("更新");
        if (progressChannels != null) {
            progressChannels.setVisibility(isLoading && !text.contains("失败") ? View.VISIBLE : View.GONE);
        }
        // 错误状态显示重试按钮
        if (btnRetry != null) {
            boolean isError = text.contains("失败") || text.contains("不可用") || text.contains("异常");
            btnRetry.setVisibility(isError ? View.VISIBLE : View.GONE);
        }
    }

    private void hideStatus() {
        if (tvStatusContainer != null) tvStatusContainer.setVisibility(View.GONE);
        if (progressChannels != null) progressChannels.setVisibility(View.GONE);
    }

    // ══════════════════════════════════════════════════════════
    // 手势控制初始化
    // ══════════════════════════════════════════════════════════

    private void initGestureControl() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        maxVolume = audioManager != null ? audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) : 100;

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(android.view.MotionEvent e) {
                // 双击 → 暂停/播放
                togglePlayPause();
                return true;
            }

            @Override
            public boolean onScroll(android.view.MotionEvent e1, android.view.MotionEvent e2, float distanceX, float distanceY) {
                if (player == null || !player.getPlayWhenReady()) return false;

                float screenWidth = getResources().getDisplayMetrics().widthPixels;
                float x = e1.getX();
                float y = e1.getY();

                if (x < screenWidth / 3) {
                    // 左侧滑动 → 音量调节
                    adjustVolume(distanceY);
                } else if (x > screenWidth * 2 / 3) {
                    // 右侧滑动 → 亮度调节
                    adjustBrightness(distanceY);
                } else {
                    // 中间左右滑动 → 快进/快退
                    seekVideo(distanceX);
                }
                return true;
            }
        });
    }

    private void adjustVolume(float distanceY) {
        if (audioManager == null) return;

        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int delta = (int) (distanceY / 10); // 调整灵敏度

        int newVolume = currentVolume + delta;
        if (newVolume < 0) newVolume = 0;
        if (newVolume > maxVolume) newVolume = maxVolume;

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0);

        // 显示音量提示
        int volumePercent = (int) ((newVolume * 100f) / maxVolume);
        Toast.makeText(this, "音量: " + volumePercent + "%", Toast.LENGTH_SHORT).show();
    }

    private void adjustBrightness(float distanceY) {
        // 获取当前窗口亮度
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (brightness < 0) {
            brightness = lp.screenBrightness == -1 ? 0.5f : lp.screenBrightness;
        }

        float delta = distanceY / 1000f; // 调整灵敏度
        brightness += delta;
        if (brightness < 0.1f) brightness = 0.1f;
        if (brightness > 1f) brightness = 1f;

        lp.screenBrightness = brightness;
        getWindow().setAttributes(lp);

        // 显示亮度提示
        int brightnessPercent = (int) (brightness * 100);
        Toast.makeText(this, "亮度: " + brightnessPercent + "%", Toast.LENGTH_SHORT).show();
    }

    private void seekVideo(float distanceX) {
        if (player == null) return;

        // ExoPlayer 直播流不支持 seek，只在缓冲窗口内有效
        // 这里仅作为占位，实际直播场景快进意义不大
        // 可用于暂停后的时移场景
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 仅在播放器可见且面板关闭时处理手势
        if (channelPanel != null && channelPanel.getVisibility() == View.VISIBLE) {
            // 面板打开时，点击屏幕关闭面板
            if (event.getAction() == MotionEvent.ACTION_UP) {
                toggleChannelPanel();
                return true;
            }
            return super.onTouchEvent(event);
        }

        // 面板关闭时，交给手势检测器
        return gestureDetector != null && gestureDetector.onTouchEvent(event);
    }

    // ══════════════════════════════════════════════════════════
    // ExoPlayer 初始化
    // ══════════════════════════════════════════════════════════

    private void initPlayer() {
        try {
            bandwidthMeter = new DefaultBandwidthMeter.Builder(this).build();

            trackSelector = new DefaultTrackSelector(this);

            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent("MaoziTV/2.0")
                    .setConnectTimeoutMs(8000)
                    .setReadTimeoutMs(8000)
                    .setAllowCrossProtocolRedirects(true)
                    .setTransferListener(speedMeter); // 挂载实时网速统计

            DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(httpFactory);

            // 缓冲策略：根据设置中的「缓冲模式」动态调整
            // 低延迟(5s/15s) / 标准(10s/30s) / 高稳定(20s/60s)
            SharedPreferences bufPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String bufferMode = bufPrefs.getString("buffer_mode", "standard");
            int minBuff, maxBuff;
            switch (bufferMode) {
                case "low_latency": minBuff = 5000; maxBuff = 15000; break;
                case "high_stability": minBuff = 20000; maxBuff = 60000; break;
                default: minBuff = 10000; maxBuff = 30000; break;
            }
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(minBuff, maxBuff, 1500, 2000)
                    .build();

            ExoPlayer.Builder playerBuilder = new ExoPlayer.Builder(this)
                    .setTrackSelector(trackSelector)
                    .setLoadControl(loadControl)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .setBandwidthMeter(bandwidthMeter);

            // 软解：通过 MediaCodecSelector 只保留软件解码器（OMX.google.*）
            // 硬解黑屏/花屏的盒子可切软解兜底
            useSoftwareDecoder = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(KEY_SOFT_DECODER, false);
            if (useSoftwareDecoder) {
                DefaultRenderersFactory softFactory = new DefaultRenderersFactory(this)
                        .setMediaCodecSelector(SOFTWARE_ONLY_SELECTOR)
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
                playerBuilder.setRenderersFactory(softFactory);
                Log.i(TAG, "已启用软解模式（仅软件解码器）");
            }

            player = playerBuilder.build();

            playerView.setPlayer(player);

            // 播放事件监听
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        hideStatus();
                        retryCount = 0;
                        if (currentChannel != null) {
                            hideChannelInfoOverlay();
                        }
                    } else if (state == Player.STATE_BUFFERING) {
                        showStatus("缓冲中…");
                        if (currentChannel != null) {
                            showChannelInfo(currentChannel, false);
                        }
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

        } catch (Exception e) {
            // ExoPlayer 在某些低端 TV 盒子上可能初始化失败（缺少 native codec）
            Log.e(TAG, "播放器初始化失败: " + e.getMessage(), e);
            player = null;
            Toast.makeText(this, "播放器初始化失败，请检查设备解码器", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 播放失败处理：自动切换下一个源
     */
    private void handlePlaybackError() {
        if (currentChannel == null) {
            showStatus("播放失败");
            return;
        }

        // 检查是否启用自动切源
        boolean autoSwitch = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean("auto_switch_source", true);
        if (!autoSwitch) {
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

        try {
            // 切台时淡入动画（缓存 view 引用，避免重复 findViewById）
            if (channelInfoOverlay != null) {
                android.view.animation.Animation fadeIn = android.view.animation.AnimationUtils
                        .loadAnimation(this, R.anim.fade_in);
                fadeIn.setDuration(300);
                if (playerView != null) playerView.startAnimation(fadeIn);
            }

            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(Uri.parse(url))
                    .build();

            player.setMediaItem(mediaItem);
            player.prepare();
            player.setPlayWhenReady(true);
        } catch (Exception e) {
            Log.e(TAG, "播放 URL 失败: " + url + " — " + e.getMessage());
            showStatus("播放出错");
        }
    }

    /**
     * 播放指定频道
     */
    private void playChannel(ChannelOptimized channel) {
        currentChannel = channel;
        retryCount = 0;
        channel.currentSourceIndex = 0;
        // 切台时重置测速器，避免旧频道数据污染新频道显示
        if (speedMeter != null) speedMeter.reset();
        playUrl(channel.getCurrentSourceUrl());

        // 应用该频道的画幅偏好（如存在）
        Integer channelResizeMode = getChannelResizeMode(channel.id);
        if (channelResizeMode != null) {
            applyResizeMode(channelResizeMode);
        }

        // 记住上次播放的频道，下次启动自动恢复
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putInt(KEY_LAST_CHANNEL, channel.id).apply();

        // 记录播放历史
        addToHistory(channel.id);

        // 埋点：换台事件
        CloudSync.track(this, "play_channel", channel.id, channel.name, "");

        // 立即显示频道信息叠加层（不自动隐藏，由播放状态控制）
        showChannelInfo(channel, false);

        // 异步拉取 EPG 节目单并显示在叠加层
        fetchAndShowEpgOverlay(channel);

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
        // 持久化全局设置，供设置中心读取
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putInt("resize_mode", mode).apply();
        // 同时保存到当前频道的偏好
        if (currentChannel != null) {
            saveChannelResizeMode(currentChannel.id, mode);
        }
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

    private void saveChannelResizeMode(int channelId, int mode) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String existingJson = prefs.getString(PREF_CHANNEL_RESIZE_MODES, "{}");
            org.json.JSONObject obj = new org.json.JSONObject(existingJson);
            obj.put(String.valueOf(channelId), mode);
            prefs.edit().putString(PREF_CHANNEL_RESIZE_MODES, obj.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Integer getChannelResizeMode(int channelId) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String existingJson = prefs.getString(PREF_CHANNEL_RESIZE_MODES, "{}");
            org.json.JSONObject obj = new org.json.JSONObject(existingJson);
            if (obj.has(String.valueOf(channelId))) {
                return obj.getInt(String.valueOf(channelId));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
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

    // ══════════════════════════════════════════════════════════
    // 播放/暂停控制（直播暂停/时移）
    // ══════════════════════════════════════════════════════════

    private void togglePlayPause() {
        if (player == null) return;
        boolean isPlaying = player.getPlayWhenReady();
        player.setPlayWhenReady(!isPlaying);
        updatePlayPauseButton();
        Toast.makeText(this, !isPlaying ? "已暂停" : "继续播放", Toast.LENGTH_SHORT).show();
    }

    private void updatePlayPauseButton() {
        if (tvPlayPause == null || player == null) return;
        boolean isPlaying = player.getPlayWhenReady();
        tvPlayPause.setText(isPlaying ? "⏸ 暂停" : "▶️ 继续");
    }

    private void addToHistory(int channelId) {
        String idStr = String.valueOf(channelId);
        playHistory.remove(idStr);
        playHistory.add(0, idStr);
        while (playHistory.size() > MAX_HISTORY) playHistory.remove(playHistory.size() - 1);
        savePlayHistory();
    }

    /** 通过频道 ID 查找频道 */
    private ChannelOptimized findChannelById(int id) {
        for (ChannelOptimized ch : allChannels) if (ch.id == id) return ch;
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

    private void showChannelInfo(ChannelOptimized channel) {
        showChannelInfo(channel, true);
    }

    private void showChannelInfo(ChannelOptimized channel, boolean autoHide) {
        tvChannelName.setText(channel.name);
        tvChannelGroup.setText(channel.group);
        // 频道号小字
        if (tvChannelNumSmall != null && channel.channelNumber > 0) {
            tvChannelNumSmall.setText("CH" + channel.channelNumber);
            tvChannelNumSmall.setVisibility(View.VISIBLE);
        }
        // EPG 当前节目：预留位置，由 fetchAndShowEpgOverlay 异步填充
        if (tvEpgNow != null) tvEpgNow.setVisibility(View.GONE);

        // 显示播放/暂停按钮
        if (tvPlayPause != null) {
            tvPlayPause.setVisibility(View.VISIBLE);
            updatePlayPauseButton();
        }

        channelInfoOverlay.setVisibility(View.VISIBLE);

        infoOverlayHandler.removeCallbacksAndMessages(null);
        if (autoHide) {
            infoOverlayHandler.postDelayed(() ->
                    channelInfoOverlay.setVisibility(View.GONE), INFO_OVERLAY_DURATION);
        }
    }

    private void hideChannelInfoOverlay() {
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
                    // 读取显示偏好设置
                    SharedPreferences dispPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    boolean showBitrate = dispPrefs.getBoolean("show_bitrate", true);
                    boolean showSignal = dispPrefs.getBoolean("show_signal", true);

                    // 实时网速：用 SpeedMeter 统计的实际下载字节（每秒采样+3秒平滑）
                    long realtimeBps = speedMeter.tick();
                    tvSpeed.setText(formatSpeed(realtimeBps));
                    // 更新信号强度：用带宽估算（供信号分级参考）
                    long bitrate = bandwidthMeter.getBitrateEstimate();
                    if (showSignal) {
                        updateSignalBars(bitrate / 1000);
                    } else if (tvSignalText != null) {
                        tvSignalText.setVisibility(View.GONE);
                    }
                    // 更新分辨率/码率信息（遵守设置开关）
                    if (showBitrate) {
                        if (tvResolution != null && player.getVideoFormat() != null) {
                            com.google.android.exoplayer2.Format fmt = player.getVideoFormat();
                            if (fmt.width > 0 && fmt.height > 0) {
                                tvResolution.setText(fmt.width + "x" + fmt.height);
                                tvResolution.setVisibility(View.VISIBLE);
                            }
                        }
                        if (tvBitrate != null && bitrate > 0) {
                            tvBitrate.setText(formatBitrate(bitrate));
                            tvBitrate.setVisibility(View.VISIBLE);
                        }
                    } else {
                        if (tvResolution != null) tvResolution.setVisibility(View.GONE);
                        if (tvBitrate != null) tvBitrate.setVisibility(View.GONE);
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

    /**
     * 格式化实时网速（字节/秒）。
     * 单位自动适配，并保留足够精度让变化可见。
     */
    private String formatSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) return "0 B/s";
        if (bytesPerSec < 1024) {
            // <1KB 显示字节级
            return bytesPerSec + " B/s";
        } else if (bytesPerSec < 1024 * 1024) {
            // KB 级保留1位小数，变化可见
            return String.format("%.1f KB/s", bytesPerSec / 1024.0);
        } else {
            return String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024));
        }
    }

    // ══════════════════════════════════════════════════════════
    // RecyclerView 初始化
    // ══════════════════════════════════════════════════════════

    private void initRecyclerViews() {
        categoryAdapter = new CategoryAdapter((item, position) -> {
            Log.d(TAG, "Category clicked: " + item.category.id + " (" + item.category.name + ")");
            currentCategoryId = item.category.id;
            // 二级分组：地方/港澳台分类弹出省份选择
            if (CategoryHelper.LOCAL.equals(item.category.id)
                    || CategoryHelper.HKTW.equals(item.category.id)) {
                showSubGroupDialog(item.category.id);
            } else {
                currentSubGroup = null;
                refreshChannelGrid();
            }
            recordUserInteraction();
        });
        rvCategories.setLayoutManager(new LinearLayoutManager(this));
        rvCategories.setAdapter(categoryAdapter);

        int span = getGridSpanCount();
        GridLayoutManager gridLayout = new GridLayoutManager(this, span);
        // 性能优化：Detach 时回收子 View 缓存，加快滚动；RecyclerView 自动预取可见项
        gridLayout.setRecycleChildrenOnDetach(true);
        rvChannels.setItemViewCacheSize(12); // 加大离屏缓存，减少重建
        rvChannels.setHasFixedSize(true);    // 固定大小跳过 requestLayout 重算
        channelAdapter = new ChannelAdapter(new ChannelAdapter.OnChannelClickListener() {
            @Override
            public void onChannelClick(ChannelOptimized channel, int position) {
                // 点击立即视觉反馈：卡片变"播放中"，再延迟关面板
                channelAdapter.setSelectedChannelId(channel.id);
                playChannel(channel);
                // 延迟 250ms 关面板，让用户看到选中反馈
                new Handler(Looper.getMainLooper()).postDelayed(
                        MainActivity.this::hideChannelPanel, 250);
            }

            @Override
            public boolean onChannelLongClick(ChannelOptimized channel, int position) {
                // 长按频道 → 弽出菜单（收藏/取消收藏 + 移动到分组 + 调整频道号）
                List<String> menuItems = new ArrayList<>();
                menuItems.add(channel.isFavorite ? "取消收藏" : "收藏");
                menuItems.add("移动到分组");
                menuItems.add("调整频道号");

                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle(channel.name)
                        .setItems(menuItems.toArray(new String[0]), (dialog, which) -> {
                            if (which == 0) {
                                // 第一项：收藏/取消收藏
                                toggleFavorite(channel);
                            } else if (which == 1) {
                                // 第二项：移动到分组
                                showMoveToGroupDialog(channel);
                            } else if (which == 2) {
                                // 第三项：调整频道号
                                showChannelNumberEditDialog(channel);
                            }
                        })
                        .show();
                recordUserInteraction();
                return true;
            }
        });
        rvChannels.setLayoutManager(gridLayout);
        rvChannels.setAdapter(channelAdapter);

        // 滚动时暂停 Glide 加载 logo，减少内存和 CPU 占用
        rvChannels.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                super.onScrollStateChanged(rv, newState);
                if (channelAdapter != null) {
                    channelAdapter.setScrolling(newState != RecyclerView.SCROLL_STATE_IDLE);
                }
            }
        });
    }

    private int getGridSpanCount() {
        // 优先读取用户设置的网格列数（-1 = 自动）
        int customCols = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt("grid_columns", -1);
        if (customCols >= 2 && customCols <= 5) return customCols;
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
        lastUserInteractionTime = System.currentTimeMillis();
        channelPanel.setVisibility(View.VISIBLE);
        // 滑入动画
        android.view.animation.Animation slideIn = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.slide_in_right);
        channelPanel.startAnimation(slideIn);
        refreshCategoryNav();
        refreshChannelGrid();
        resetPanelAutoHide();

        if (currentChannel != null) {
            int pos = channelAdapter.findPositionByChannelId(currentChannel.id);
            if (pos >= 0) rvChannels.scrollToPosition(pos);
        }
        rvChannels.requestFocus();
    }

    private void resetPanelAutoHide() {
        panelAutoHideHandler.removeCallbacksAndMessages(null);
        panelAutoHideHandler.postDelayed(this::checkAndHidePanel, 5000); // 每5秒检查一次
    }

    private void checkAndHidePanel() {
        if (!panelVisible) return;

        long inactiveTime = System.currentTimeMillis() - lastUserInteractionTime;
        if (inactiveTime >= PANEL_AUTO_HIDE_DELAY) {
            hideChannelPanel();
        } else {
            // 继续等待
            long remainingTime = PANEL_AUTO_HIDE_DELAY - inactiveTime;
            panelAutoHideHandler.postDelayed(this::checkAndHidePanel, Math.min(5000, remainingTime));
        }
    }

    private void recordUserInteraction() {
        lastUserInteractionTime = System.currentTimeMillis();
        if (panelVisible) {
            resetPanelAutoHide();
        }
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

    /**
     * 二级分组选择对话框（地方→省份，港澳台→地区）。
     * 选定后过滤到该二级分组的频道。
     */
    private void showSubGroupDialog(String categoryId) {
        // 收集该分类下所有频道的二级分组
        List<ChannelOptimized> filtered = CategoryHelper.filter(
                filterHiddenGroups(allChannels), categoryId, false, cachedBuckets);
        java.util.LinkedHashMap<String, String> subGroups = new java.util.LinkedHashMap<>();
        String allLabel = "全部" + (CategoryHelper.LOCAL.equals(categoryId) ? "地方" : "港澳台");
        subGroups.put("__all__", allLabel);
        for (ChannelOptimized ch : filtered) {
            String subId = CategoryHelper.subGroupId(ch);
            String subName;
            if (subId == null) {
                subId = "__other__";
                subName = "其他";
            } else if (subId.startsWith("province_")) {
                subName = subId.substring("province_".length());
            } else if (subId.startsWith("region_hk")) {
                subName = "香港";
            } else if (subId.startsWith("region_mo")) {
                subName = "澳门";
            } else if (subId.startsWith("region_tw")) {
                subName = "台湾";
            } else {
                subName = "其他";
            }
            if (!subGroups.containsKey(subId)) {
                subGroups.put(subId, subName);
            }
        }

        String[] labels = subGroups.values().toArray(new String[0]);
        String[] ids = subGroups.keySet().toArray(new String[0]);

        new AlertDialog.Builder(this)
                .setTitle("选择" + (CategoryHelper.LOCAL.equals(categoryId) ? "省份" : "地区"))
                .setItems(labels, (dialog, which) -> {
                    String subId = ids[which];
                    currentSubGroup = "__all__".equals(subId) ? null : subId;
                    refreshChannelGrid();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ══════════════════════════════════════════════════════════
    // 频道数据解析
    // ══════════════════════════════════════════════════════════

    /**
     * 在后台线程解析 JSON，返回临时频道列表（不触碰 allChannels，线程安全）。
     * 解析失败返回 null 并已在主线程提示错误。
     */
    private List<ChannelOptimized> parseChannelsData(String jsonData) {
        try {
            JSONObject root = new JSONObject(jsonData);
            JSONArray chArr = root.optJSONArray("channels");
            if (chArr == null) {
                Log.e(TAG, "JSON 无 channels 数组");
                return null;
            }

            // 加载收藏集
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String favStr = prefs.getString(KEY_FAVORITES, "");
            java.util.Set<String> favSet = new java.util.HashSet<>();
            if (!favStr.isEmpty()) {
                Collections.addAll(favSet, favStr.split(","));
            }

            // 一次性迁移：将旧收藏数据迁移到新的分组结构
            if (FavoriteGroupManager.getGroups(this).size() == 1 &&
                FavoriteGroupManager.getChannelsInGroup(this, "默认").isEmpty() &&
                !favStr.isEmpty()) {
                FavoriteGroupManager.migrateFromOldFavorites(this, favStr);
            }

            // 解析频道（使用优化模型）
            List<ChannelOptimized> parsed = new ArrayList<>();
            int channelIndex = 0;
            for (int i = 0; i < chArr.length(); i++) {
                ChannelOptimized ch = ChannelOptimized.fromJson(chArr.getJSONObject(i));

                // 过滤无信号源频道（sources 为空则跳过）
                if (ch.sources == null || ch.sources.isEmpty()) {
                    continue;
                }

                ch.channelNumber = ++channelIndex; // 频道号从1开始，跳过无源频道后连续编号

                // 恢复收藏状态（从新的分组管理器读取，兼容旧格式）
                ch.isFavorite = FavoriteGroupManager.isChannelInAnyGroup(this, ch.id) ||
                               favSet.contains(String.valueOf(ch.id));

                parsed.add(ch);
            }

            Log.i(TAG, "解析完成: " + parsed.size() + " 个频道");
            return parsed;

        } catch (Exception e) {
            Log.e(TAG, "JSON 解析失败: " + e.getMessage(), e);
            String msg = "频道数据解析失败: " + e.getMessage();
            mainHandler.post(() -> {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                showStatus("频道数据异常，请更新频道源");
            });
            return null;
        }
    }

    /** 必须在主线程调用：将解析结果安全换入 allChannels */
    private void replaceAllChannels(List<ChannelOptimized> parsed) {
        allChannels.clear();
        allChannels.addAll(parsed);
    }

    // ── 用户偏好：隐藏分组 ─────────────────────────────────

    /** 从 SharedPreferences 加载隐藏分组集合 */
    private void loadHiddenGroups() {
        hiddenGroups.clear();
        String saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_HIDDEN_GROUPS, "");
        if (saved != null && !saved.isEmpty()) {
            Collections.addAll(hiddenGroups, saved.split(","));
        }
    }

    /** 保存隐藏分组集合到 SharedPreferences */
    private void saveHiddenGroups() {
        StringBuilder sb = new StringBuilder();
        for (String g : hiddenGroups) {
            if (sb.length() > 0) sb.append(",");
            sb.append(g);
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(KEY_HIDDEN_GROUPS, sb.toString()).apply();
    }

    /** 判断某个分组是否被用户隐藏 */
    private boolean isGroupHidden(String group) {
        return group != null && hiddenGroups.contains(group);
    }

    /**
     * 过滤掉用户隐藏的分组，返回可见频道列表。
     * 收藏/历史分类也应用该过滤（隐藏分组内的频道不再出现）。
     */
    private List<ChannelOptimized> filterHiddenGroups(List<ChannelOptimized> input) {
        if (hiddenGroups.isEmpty()) return input;
        List<ChannelOptimized> result = new ArrayList<>(input.size());
        for (ChannelOptimized ch : input) {
            if (!isGroupHidden(ch.group)) {
                result.add(ch);
            }
        }
        return result;
    }

    /** 切换某个分组的隐藏状态，返回新状态（true=已隐藏） */
    private boolean toggleGroupHidden(String group) {
        if (group == null || group.isEmpty()) return false;
        boolean nowHidden;
        if (hiddenGroups.contains(group)) {
            hiddenGroups.remove(group);
            nowHidden = false;
        } else {
            hiddenGroups.add(group);
            nowHidden = true;
        }
        saveHiddenGroups();
        return nowHidden;
    }

    /**
     * 更新分组标签和频道列表到 UI
     */
    private void updateUI() {
        // 保持当前选中的分类，不要重置为 ALL
        // currentCategoryId = CategoryHelper.ALL;
        searchQuery = "";
        if (etSearch != null) etSearch.setText("");

        // 加载用户偏好（隐藏分组）
        loadHiddenGroups();

        // 预计算分类桶（基于过滤隐藏分组后的可见频道）
        cachedBuckets = CategoryHelper.buildSmartBuckets(filterHiddenGroups(allChannels));

        refreshCategoryNav();
        refreshChannelGrid();
        updateSourceInfoDisplay();
        hideStatus();

        if (!allChannels.isEmpty()) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            ChannelOptimized startChannel = null;

            // 根据「启动页面」设置决定进入哪个页面
            String startupPage = prefs.getString("startup_page", "all");
            switch (startupPage) {
                case "favorites":
                    // 进入收藏分类
                    currentCategoryId = CategoryHelper.FAV;
                    refreshChannelGrid();
                    showChannelPanel();
                    break;
                case "hot":
                    // 热门频道：按播放历史排序显示全部
                    currentCategoryId = CategoryHelper.ALL;
                    refreshChannelGrid();
                    showChannelPanel();
                    break;
                case "last":
                default:
                    // 播放上次频道（兼容旧设置 start_last_channel）
                    boolean startLast = prefs.getBoolean("start_last_channel", true);
                    if (startLast) {
                        int lastId = prefs.getInt(KEY_LAST_CHANNEL, -1);
                        if (lastId >= 0) {
                            for (ChannelOptimized ch : allChannels) {
                                if (ch.id == lastId && !isGroupHidden(ch.group)) {
                                    startChannel = ch;
                                    break;
                                }
                            }
                        }
                    }
                    if (startChannel != null) {
                        playChannel(startChannel);
                    } else {
                        showChannelPanel();
                    }
                    break;
            }
        }
    }

    private void refreshCategoryNav() {
        List<CategoryAdapter.CategoryItem> items = new ArrayList<>();
        // 使用缓存的 buckets（updateUI 时已预计算），避免重复遍历
        Map<String, List<ChannelOptimized>> buckets = cachedBuckets != null
                ? cachedBuckets : CategoryHelper.buildSmartBuckets(allChannels);

        for (CategoryHelper.Category cat : CategoryHelper.getNavCategories()) {
            int count;
            if (CategoryHelper.ALL.equals(cat.id)) {
                count = filterHiddenGroups(allChannels).size();
            } else if (CategoryHelper.FAV.equals(cat.id)) {
                count = 0;
                for (ChannelOptimized ch : allChannels) {
                    if (ch.isFavorite && !isGroupHidden(ch.group)) count++;
                }
            } else if (CategoryHelper.HISTORY.equals(cat.id)) {
                count = 0;
                for (String idStr : playHistory) {
                    ChannelOptimized ch = findChannelById(parseIntSafe(idStr));
                    if (ch != null && !isGroupHidden(ch.group)) count++;
                }
            } else {
                List<ChannelOptimized> bucket = buckets.get(cat.id);
                count = bucket != null ? bucket.size() : 0;
            }
            if (CategoryHelper.FAV.equals(cat.id) && count == 0) continue;
            if (CategoryHelper.HISTORY.equals(cat.id) && count == 0) continue;
            items.add(new CategoryAdapter.CategoryItem(cat, count));
        }

        categoryAdapter.setItems(items);

        // 确保 currentCategoryId 在可用分类中，如果不在则重置为 ALL
        boolean categoryIdFound = false;
        for (CategoryAdapter.CategoryItem item : items) {
            if (item.category.id.equals(currentCategoryId)) {
                categoryIdFound = true;
                break;
            }
        }
        if (!categoryIdFound && !items.isEmpty()) {
            Log.d(TAG, "currentCategoryId '" + currentCategoryId + "' not found in available categories, resetting to ALL");
            currentCategoryId = CategoryHelper.ALL;
        }

        // 使用更新后的 currentCategoryId 查找选中位置
        int selectedPos = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).category.id.equals(currentCategoryId)) {
                selectedPos = i;
                break;
            }
        }
        Log.d(TAG, "refreshCategoryNav: selectedPos=" + selectedPos + ", currentCategoryId=" + currentCategoryId);
        categoryAdapter.setSelectedPosition(selectedPos);
    }

    private void refreshChannelGrid() {
        if (channelAdapter != null) channelAdapter.setSearchQuery(searchQuery);
        Log.d(TAG, "refreshChannelGrid: currentCategoryId=" + currentCategoryId + ", allChannels.size()=" + allChannels.size());
        // 传入缓存的 buckets，避免 filter 内部重复调用 buildSmartBuckets
        List<ChannelOptimized> filtered = CategoryHelper.filter(allChannels, currentCategoryId, false, cachedBuckets);
        Log.d(TAG, "refreshChannelGrid: filtered.size()=" + filtered.size());

        // 应用排序
        applySort(filtered);
        // 历史分类：按观看历史排序（最新在前）
        if (CategoryHelper.HISTORY.equals(currentCategoryId) && !playHistory.isEmpty()) {
            Map<Integer, ChannelOptimized> idMap = new java.util.HashMap<>();
            for (ChannelOptimized ch : filtered) idMap.put(ch.id, ch);
            List<ChannelOptimized> histList = new ArrayList<>();
            for (String idStr : playHistory) {
                try {
                    ChannelOptimized ch = idMap.get(Integer.parseInt(idStr));
                    if (ch != null) histList.add(ch);
                } catch (NumberFormatException ignored) {}
            }
            filtered = histList;
        }
        // 用户偏好：过滤掉隐藏分组中的频道
        filtered = filterHiddenGroups(filtered);

        // 「仅显示健康频道」设置过滤
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("healthy_only", false)) {
            List<ChannelOptimized> healthyList = new ArrayList<>();
            for (ChannelOptimized ch : filtered) {
                if (ch.healthy) healthyList.add(ch);
            }
            filtered = healthyList;
        }

        // 二级分组过滤（省份/地区）
        if (currentSubGroup != null) {
            List<ChannelOptimized> subFiltered = new ArrayList<>();
            for (ChannelOptimized ch : filtered) {
                String subId = CategoryHelper.subGroupId(ch);
                if (subId == null) subId = "__other__";
                if (currentSubGroup.equals(subId)) subFiltered.add(ch);
            }
            filtered = subFiltered;
        }

        filtered = CategoryHelper.search(filtered, searchQuery);
        channelAdapter.setChannels(filtered);

        if (tvPanelCount != null) {
            tvPanelCount.setText(filtered.size() + " 个频道");
        }

        // 空状态：搜索无结果时提示
        if (tvEmptyState != null) {
            boolean showEmpty = filtered.isEmpty() && searchQuery != null && !searchQuery.isEmpty();
            tvEmptyState.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        }

        if (currentChannel != null) {
            channelAdapter.setSelectedChannelId(currentChannel.id);
        }
    }

    // ══════════════════════════════════════════════════════════
    // 收藏功能
    // ══════════════════════════════════════════════════════════

    private void toggleFavorite(ChannelOptimized channel) {
        boolean wasFavorite = channel.isFavorite;
        channel.isFavorite = !channel.isFavorite;

        if (channel.isFavorite) {
            // 收藏：添加到默认分组
            FavoriteGroupManager.addChannelToGroup(this, channel.id, "默认");
        } else {
            // 取消收藏：从所有分组中移除
            FavoriteGroupManager.removeChannelFromAllGroups(this, channel.id);
        }

        saveFavorites();

        String msg = channel.isFavorite ? "已收藏: " + channel.name : "取消收藏: " + channel.name;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();

        // 埋点：收藏/取消收藏事件
        CloudSync.track(this, channel.isFavorite ? "fav" : "unfav",
                channel.id, channel.name, "");

        // 性能优化：局部刷新该频道卡片，避免全量 notifyDataSetChanged
        if (CategoryHelper.FAV.equals(currentCategoryId)) {
            // 收藏分类下取消收藏 → 该卡片应从列表移除，全量刷新（此分类通常较小）
            refreshChannelGrid();
        } else {
            // 其他分类：只更新该卡片
            channelAdapter.notifyChannelChanged(channel.id);
        }
        // 轻量刷新收藏分类计数（仅更新收藏 Tab 的数字）
        refreshFavoriteCount();
    }

    // ══════════════════════════════════════════════════════════
    // 频道号自定义编辑
    // ══════════════════════════════════════════════════════════

    private static final String PREF_CUSTOM_CHANNEL_NUMBERS = "custom_channel_numbers";

    private void saveCustomChannelNumber(int channelId, int customNumber) {
        try {
            SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
            String existingJson = prefs.getString(PREF_CUSTOM_CHANNEL_NUMBERS, "{}");
            org.json.JSONObject obj = new org.json.JSONObject(existingJson);
            obj.put(String.valueOf(channelId), customNumber);
            prefs.edit().putString(PREF_CUSTOM_CHANNEL_NUMBERS, obj.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Integer getCustomChannelNumber(int channelId) {
        try {
            SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
            String existingJson = prefs.getString(PREF_CUSTOM_CHANNEL_NUMBERS, "{}");
            org.json.JSONObject obj = new org.json.JSONObject(existingJson);
            if (obj.has(String.valueOf(channelId))) {
                return obj.getInt(String.valueOf(channelId));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void showChannelNumberEditDialog(ChannelOptimized channel) {
        if (channel == null) return;

        Integer currentCustom = getCustomChannelNumber(channel.id);
        int currentNum = currentCustom != null ? currentCustom : channel.channelNumber;

        android.widget.LinearLayout dialogLayout = new android.widget.LinearLayout(this);
        dialogLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        dialogLayout.setPadding(48, 32, 48, 16);

        android.widget.TextView titleView = new android.widget.TextView(this);
        titleView.setText("调整频道号");
        titleView.setTextSize(18);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setPadding(0, 0, 0, 24);
        dialogLayout.addView(titleView);

        android.widget.TextView hintView = new android.widget.TextView(this);
        hintView.setText("当前频道: " + channel.name);
        hintView.setTextSize(14);
        hintView.setTextColor(0xCCFFFFFF);
        hintView.setPadding(0, 0, 0, 16);
        dialogLayout.addView(hintView);

        android.widget.LinearLayout numberInputLayout = new android.widget.LinearLayout(this);
        numberInputLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        numberInputLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.widget.Button btnDown = new android.widget.Button(this);
        btnDown.setText("−");
        btnDown.setTextSize(24);
        android.widget.LinearLayout.LayoutParams downParams = new android.widget.LinearLayout.LayoutParams(80, 80);
        downParams.setMargins(0, 0, 16, 0);
        btnDown.setLayoutParams(downParams);
        numberInputLayout.addView(btnDown);

        android.widget.EditText numberInput = new android.widget.EditText(this);
        numberInput.setText(String.valueOf(currentNum));
        numberInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        numberInput.setTextSize(20);
        numberInput.setTextColor(0xFFFFFFFF);
        numberInput.setGravity(android.view.Gravity.CENTER);
        numberInput.setPadding(24, 16, 24, 16);
        numberInput.setBackgroundColor(0x33FFFFFF);
        android.widget.LinearLayout.LayoutParams inputParams = new android.widget.LinearLayout.LayoutParams(160, 80);
        inputParams.setMargins(0, 0, 16, 0);
        numberInput.setLayoutParams(inputParams);
        numberInputLayout.addView(numberInput);

        android.widget.Button btnUp = new android.widget.Button(this);
        btnUp.setText("+");
        btnUp.setTextSize(24);
        android.widget.LinearLayout.LayoutParams upParams = new android.widget.LinearLayout.LayoutParams(80, 80);
        btnUp.setLayoutParams(upParams);
        numberInputLayout.addView(btnUp);

        dialogLayout.addView(numberInputLayout);

        btnUp.setOnClickListener(v -> {
            try {
                int val = Integer.parseInt(numberInput.getText().toString());
                numberInput.setText(String.valueOf(val + 1));
            } catch (NumberFormatException e) {
                numberInput.setText("1");
            }
        });

        btnDown.setOnClickListener(v -> {
            try {
                int val = Integer.parseInt(numberInput.getText().toString());
                if (val > 1) {
                    numberInput.setText(String.valueOf(val - 1));
                }
            } catch (NumberFormatException e) {
                numberInput.setText("1");
            }
        });

        new android.app.AlertDialog.Builder(this)
                .setTitle("调整频道号")
                .setView(dialogLayout)
                .setPositiveButton("确定", (dialog, which) -> {
                    try {
                        int newNumber = Integer.parseInt(numberInput.getText().toString());
                        if (newNumber > 0) {
                            saveCustomChannelNumber(channel.id, newNumber);
                            channel.channelNumber = newNumber;
                            channelAdapter.notifyChannelChanged(channel.id);
                            Toast.makeText(this, "频道号已更新: " + channel.name + " → " + newNumber, Toast.LENGTH_SHORT).show();
                        }
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "请输入有效的频道号", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showMoveToGroupDialog(ChannelOptimized channel) {
        Map<String, List<Integer>> groups = FavoriteGroupManager.getGroups(this);
        String[] groupNames = groups.keySet().toArray(new String[0]);

        new android.app.AlertDialog.Builder(this)
                .setTitle("移动到分组")
                .setItems(groupNames, (dialog, which) -> {
                    String groupName = groupNames[which];
                    FavoriteGroupManager.addChannelToGroup(this, channel.id, groupName);
                    channel.isFavorite = true;
                    channelAdapter.notifyChannelChanged(channel.id);
                    Toast.makeText(this, "已移动到: " + groupName, Toast.LENGTH_SHORT).show();
                    refreshFavoriteCount();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showGroupManagementDialog() {
        Map<String, List<Integer>> groups = FavoriteGroupManager.getGroups(this);
        String[] groupNames = groups.keySet().toArray(new String[0]);

        new android.app.AlertDialog.Builder(this)
                .setTitle("分组管理")
                .setItems(groupNames, (dialog, which) -> {
                    String groupName = groupNames[which];
                    showGroupEditDialog(groupName);
                })
                .setPositiveButton("新建分组", (dialog, which) -> {
                    showCreateGroupDialog();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showGroupEditDialog(String groupName) {
        String[] options = {"重命名", "删除分组"};
        new android.app.AlertDialog.Builder(this)
                .setTitle(groupName)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        // 重命名
                        showRenameGroupDialog(groupName);
                    } else if (which == 1) {
                        // 删除分组
                        if (!groupName.equals("默认")) {
                            FavoriteGroupManager.deleteGroup(this, groupName);
                            refreshFavoriteCount();
                            Toast.makeText(this, "已删除分组: " + groupName, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "默认分组不能删除", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void showRenameGroupDialog(String oldName) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(oldName);
        input.setSelectAllOnFocus(true);

        new android.app.AlertDialog.Builder(this)
                .setTitle("重命名分组")
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (!newName.isEmpty() && !newName.equals(oldName)) {
                        FavoriteGroupManager.renameGroup(this, oldName, newName);
                        refreshFavoriteCount();
                        Toast.makeText(this, "分组已重命名", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCreateGroupDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("分组名称");

        new android.app.AlertDialog.Builder(this)
                .setTitle("新建分组")
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> {
                    String groupName = input.getText().toString().trim();
                    if (!groupName.isEmpty()) {
                        FavoriteGroupManager.createGroup(this, groupName);
                        refreshFavoriteCount();
                        Toast.makeText(this, "已创建分组: " + groupName, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 仅更新左侧「收藏」分类的计数，避免每次收藏都重建整个分类栏。
     */
    private void refreshFavoriteCount() {
        if (categoryAdapter == null || cachedBuckets == null) return;
        int favCount = 0;
        for (ChannelOptimized ch : allChannels) {
            if (ch.isFavorite && !isGroupHidden(ch.group)) favCount++;
        }
        categoryAdapter.updateCount(CategoryHelper.FAV, favCount);
    }

    private void saveFavorites() {
        // 性能优化：单次遍历 allChannels，同时构建本地存储 + 云同步列表
        StringBuilder sb = new StringBuilder();
        List<Integer> favIds = new ArrayList<>();
        for (ChannelOptimized ch : allChannels) {
            if (ch.isFavorite) {
                if (sb.length() > 0) sb.append(",");
                sb.append(ch.id);
                favIds.add(ch.id);
            }
        }

        // 保持向后兼容：旧格式仍然保存（供其他组件可能读取）
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(KEY_FAVORITES, sb.toString()).apply();

        // 新格式：分组数据由 FavoriteGroupManager 管理
        // 注意：实际的分组数据在 toggleFavorite() 中已经更新，这里只负责云同步

        // 云同步：收藏 + 历史 推送到后端
        List<Integer> histIds = new ArrayList<>();
        for (String idStr : playHistory) {
            try {
                histIds.add(Integer.parseInt(idStr));
            } catch (NumberFormatException ignored) {}
        }
        CloudSync.save(this, favIds, histIds);
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
    // 多源频道聚合拉取（source-list.json → 并行拉取 → 合并去重）
    // ══════════════════════════════════════════════════════════

    private void fetchChannelsMultiSource() {
        Log.i(TAG, "开始多源频道聚合...");
        // 本方法运行在后台 executor 线程，UI 更新必须 post 到主线程
        mainHandler.post(() -> showStatus("加载频道源..."));

        MultiSourceFetcher fetcher = new MultiSourceFetcher();

        // 加载用户自定义源（后台线程读 SharedPreferences 是线程安全的）
        String customSources = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_CUSTOM_SOURCES, "");
        if (customSources != null && !customSources.isEmpty()) {
            for (String url : customSources.split("\n")) {
                fetcher.addCustomSource(url);
            }
        }

        // fetchAll 回调运行在后台线程，全程不得直接触碰 UI
        fetcher.fetchAll(result -> {
            if (result == null || result.channels == null || result.channels.isEmpty()) {
                Log.w(TAG, "多源聚合失败，回退到单源拉取");
                // 多源失败 → 回退到旧的单源逻辑
                fetchChannelsJson();
                return;
            }

            // 保存信号源信息到 SharedPreferences（线程安全）
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            JSONObject info = new JSONObject();
            try {
                info.put("version", result.version);
                info.put("generated_at", result.generatedAt);
                info.put("generated_at_ts", result.generatedAtTs);
                info.put("total_count", result.totalCount);
                info.put("merged_count", result.mergedCount);
                info.put("source_count", result.sourceCount);
                info.put("fetch_time", System.currentTimeMillis());
                if (!result.errors.isEmpty()) {
                    info.put("errors", new JSONArray(result.errors));
                }
            } catch (Exception ignored) {}
            prefs.edit().putString(KEY_SOURCE_INFO, info.toString()).apply();

            // 构建合并后的 JSON 用于缓存
            JSONObject mergedJson = buildMergedJson(result);
            String jsonData = mergedJson.toString();

            // 缓存（线程安全）
            prefs.edit()
                    .putString(KEY_CACHED_JSON, jsonData)
                    .putInt(KEY_CACHED_VERSION, parseIntSafe(result.version))
                    .putLong(KEY_CACHED_TS, result.generatedAtTs)
                    .apply();

            Log.i(TAG, "多源聚合成功: " + result.mergedCount + " 频道, " + result.sourceCount + " 源");

            // 后台线程解析 JSON（不触碰 allChannels，返回临时列表）
            ExecutorService parseExecutor = Executors.newSingleThreadExecutor();
            parseExecutor.execute(() -> {
                List<ChannelOptimized> parsed = parseChannelsData(jsonData);
                mainHandler.post(() -> {
                    if (parsed != null && !parsed.isEmpty()) {
                        replaceAllChannels(parsed);
                        updateUI();
                        String msg = "频道已加载: " + result.mergedCount + " 个频道 (" + result.sourceCount + " 个源)";
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                    }
                });
            });
            parseExecutor.shutdown();
        });
    }

    /** 将聚合结果构建为与原始 channels.json 兼容的 JSON */
    private JSONObject buildMergedJson(MultiSourceFetcher.FetchResult result) {
        JSONObject root = new JSONObject();
        try {
            root.put("version", parseIntSafe(result.version));
            root.put("generated_at", result.generatedAt != null ? result.generatedAt : "");
            root.put("generated_at_ts", result.generatedAtTs);
            root.put("total", result.mergedCount);
            root.put("source_count", result.sourceCount);

            JSONArray chArr = new JSONArray();
            for (ChannelOptimized ch : result.channels) {
                JSONObject chObj = new JSONObject();
                chObj.put("id", ch.id);
                chObj.put("name", ch.name);
                chObj.put("group", ch.group != null ? ch.group : "其他");
                chObj.put("logo", ch.logo != null ? ch.logo : "");
                chObj.put("url", ch.url != null ? ch.url : "");
                chObj.put("healthy", ch.healthy);
                chObj.put("region", ch.region != null ? ch.region : "domestic");

                JSONArray srcArr = new JSONArray();
                for (String src : ch.sources) {
                    srcArr.put(src);
                }
                chObj.put("sources", srcArr);
                chArr.put(chObj);
            }
            root.put("channels", chArr);
        } catch (Exception e) {
            Log.e(TAG, "构建合并 JSON 失败", e);
        }
        return root;
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    // ══════════════════════════════════════════════════════════
    // JSON 拉取 (Gitee 优先 → GitHub 备用) — 回退方案
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

                    // 后台线程解析，避免主线程阻塞
                    final String data = jsonData;
                    ExecutorService parseExecutor = Executors.newSingleThreadExecutor();
                    parseExecutor.execute(() -> {
                        List<ChannelOptimized> parsed = parseChannelsData(data);
                        mainHandler.post(() -> {
                            if (parsed != null && !parsed.isEmpty()) {
                                replaceAllChannels(parsed);
                                updateUI();
                                Toast.makeText(MainActivity.this, "频道已更新", Toast.LENGTH_SHORT).show();
                            }
                        });
                    });
                    parseExecutor.shutdown();
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
        // 后台线程解析缓存 JSON
        ExecutorService parseExecutor = Executors.newSingleThreadExecutor();
        parseExecutor.execute(() -> {
            List<ChannelOptimized> parsed = parseChannelsData(cachedJson);
            mainHandler.post(() -> {
                if (parsed != null && !parsed.isEmpty()) {
                    replaceAllChannels(parsed);
                    updateUI();
                    Toast.makeText(MainActivity.this, "使用缓存的频道列表", Toast.LENGTH_SHORT).show();
                }
            });
        });
        parseExecutor.shutdown();
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
    // 多画面模式（2x2 网格：当前频道 + 相邻 3 个频道）
    // ══════════════════════════════════════════════════════════

    private void toggleMultiview() {
        if (multiviewActive) {
            exitMultiview();
        } else {
            enterMultiview();
        }
    }

    private void enterMultiview() {
        if (allChannels.isEmpty()) {
            Toast.makeText(this, "暂无频道", Toast.LENGTH_SHORT).show();
            return;
        }

        // 暂停主播放器
        if (player != null) {
            player.setPlayWhenReady(false);
        }
        // 隐藏主画面和信息叠加层
        playerView.setVisibility(View.INVISIBLE);
        channelInfoOverlay.setVisibility(View.GONE);
        tvSpeed.setVisibility(View.GONE);
        multiviewGrid.setVisibility(View.VISIBLE);

        // 选 4 个频道：当前频道 + 后续 3 个（全部频道列表）
        List<ChannelOptimized> list = channelAdapter.getChannels();
        if (list == null || list.isEmpty()) list = allChannels;

        List<ChannelOptimized> mvChannels = new ArrayList<>();
        if (currentChannel != null) {
            int idx = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == currentChannel.id) { idx = i; break; }
            }
            if (idx >= 0) {
                for (int k = 0; k < MV_COUNT; k++) {
                    mvChannels.add(list.get((idx + k) % list.size()));
                }
            }
        }
        while (mvChannels.size() < MV_COUNT && list.size() > mvChannels.size()) {
            mvChannels.add(list.get(mvChannels.size() % list.size()));
        }
        if (mvChannels.isEmpty()) return;

        // 为每个格子创建独立 ExoPlayer 并播放
        releaseMultiviewPlayers();
        mvPlayers.clear();

        // 多画面：缩小缓冲避免 4 路流同时大缓冲撑爆内存（低端盒子 OOM 防护）
        com.google.android.exoplayer2.DefaultLoadControl mvLoadControl =
                new com.google.android.exoplayer2.DefaultLoadControl.Builder()
                        .setBufferDurationsMs(3000, 8000, 1000, 1500) // 最小缓冲(3s/8s)
                        .build();

        for (int i = 0; i < mvChannels.size(); i++) {
            ChannelOptimized ch = mvChannels.get(i);
            if (ch.sources == null || ch.sources.isEmpty()) continue;

            ExoPlayer mvPlayer = new ExoPlayer.Builder(this)
                    .setTrackSelector(new DefaultTrackSelector(this))
                    .setLoadControl(mvLoadControl)
                    .build();
            com.google.android.exoplayer2.ui.PlayerView pv = mvPlayerViews.get(i);
            pv.setPlayer(mvPlayer);
            pv.setVisibility(View.VISIBLE);

            String url = ch.getCurrentSourceUrl();
            if (url != null && !url.isEmpty()) {
                MediaItem item = new MediaItem.Builder().setUri(Uri.parse(url)).build();
                mvPlayer.setMediaItem(item);
                mvPlayer.prepare();
                mvPlayer.setPlayWhenReady(true);
            }
            mvPlayers.add(mvPlayer);
        }

        multiviewActive = true;
        Toast.makeText(this, "多画面模式 (当前频道 + 后3个频道)", Toast.LENGTH_SHORT).show();
    }

    private void exitMultiview() {
        multiviewActive = false;
        multiviewGrid.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        channelInfoOverlay.setVisibility(View.VISIBLE);
        tvSpeed.setVisibility(View.VISIBLE);

        releaseMultiviewPlayers();

        // 恢复主播放器
        if (player != null) {
            player.setPlayWhenReady(true);
        }
        // 重播当前频道
        if (currentChannel != null) {
            playChannel(currentChannel);
        }
    }

    private void releaseMultiviewPlayers() {
        for (int i = 0; i < mvPlayers.size(); i++) {
            try {
                ExoPlayer p = mvPlayers.get(i);
                if (p != null) p.release();
                mvPlayerViews.get(i).setPlayer(null);
            } catch (Exception ignored) {}
        }
        mvPlayers.clear();
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

    private void applySort(List<ChannelOptimized> list) {
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
    // 首次启动引导（零配置体验）
    // ══════════════════════════════════════════════════════════

    /**
     * 检测首次启动：如果 server_url 是默认值且无缓存频道数据，
     * 弹出引导对话框，让用户选择「快速开始」或「配置服务器」。
     */
    private void checkFirstLaunchGuide() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String serverUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
        String cachedJson = prefs.getString(KEY_CACHED_JSON, "");
        boolean hasShownGuide = prefs.getBoolean("first_launch_guide_shown", false);

        // 已显示过引导 或 已配置了非默认服务器 或 已有缓存数据 → 跳过
        if (hasShownGuide) return;
        if (!serverUrl.equals(DEFAULT_SERVER_URL)) return;
        if (cachedJson != null && !cachedJson.isEmpty()) return;

        // 标记已显示（避免重复弹出）
        prefs.edit().putBoolean("first_launch_guide_shown", true).apply();

        // 延迟弹出，等界面稳定
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            new AlertDialog.Builder(this)
                    .setTitle("👋 欢迎使用帽子TV")
                    .setMessage("您希望如何开始使用？\n\n"
                            + "• 快速开始：直接使用托管频道源，无需自建服务器\n"
                            + "• 配置服务器：输入自建后端地址（高级用户）")
                    .setPositiveButton("快速开始", (d, w) -> {
                        // 使用默认托管源，无需额外配置
                        Toast.makeText(this, "已启用托管频道源", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("配置服务器", (d, w) -> {
                        showFirstLaunchServerInput();
                    })
                    .setCancelable(true)
                    .show();
        }, 2000);
    }

    /** 首次启动时的服务器地址输入框 */
    private void showFirstLaunchServerInput() {
        EditText input = new EditText(this);
        input.setHint("192.168.1.100:8000");
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0x88FFFFFF);
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
                .setTitle("配置服务器地址")
                .setMessage("输入后端服务器的 IP:端口\n（如 192.168.1.100:8000）")
                .setView(input)
                .setPositiveButton("确定", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) {
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            url = "http://" + url;
                        }
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                .edit().putString(KEY_SERVER_URL, url).apply();
                        Toast.makeText(this, "服务器: " + url, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ══════════════════════════════════════════════════════════
    // EPG 节目单
    // ══════════════════════════════════════════════════════════

    private void showEpgDialog(ChannelOptimized channel) {
        if (channel == null) return;

        // 立即弹出一个"加载中"的节目单框架
        AlertDialog loading = new AlertDialog.Builder(this)
                .setTitle(channel.name + " · 节目单")
                .setMessage("正在加载 EPG 数据...")
                .setNegativeButton("关闭", null)
                .show();

        // 后台拉取 EPG（优先后端 API，失败则回退本地 XMLTV 缓存）
        executor.execute(() -> {
            List<String[]> programList = fetchEpgPrograms(channel);
            // 获取当前时间用于判断"正在播放"
            long currentMillis = System.currentTimeMillis();
            String currentTimeStr = new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(currentMillis));

            mainHandler.post(() -> {
                try {
                    loading.dismiss();
                } catch (Exception ignored) {}

                if (programList.isEmpty()) {
                    Toast.makeText(this, "暂无该频道 EPG 数据", Toast.LENGTH_SHORT).show();
                    return;
                }

                // 构建节目单时间线显示（带当前节目高亮）
                StringBuilder sb = new StringBuilder();
                sb.append("━━━━━━━━━━━━━━━━━━━━━━━━\n");
                for (int i = 0; i < programList.size(); i++) {
                    String[] p = programList.get(i);
                    String time = p[0];
                    String title = p[1];

                    // 判断是否为当前节目（简单判断：时间在前后 30 分钟内）
                    boolean isCurrent = Math.abs(time.compareTo(currentTimeStr)) <= 30 ||
                            (i > 0 && programList.get(i-1)[0].compareTo(currentTimeStr) <= 0 && currentTimeStr.compareTo(time) <= 0);

                    if (isCurrent) {
                        sb.append("🔵 ").append(time).append("  ").append(title).append("  [正在播放]\n");
                    } else {
                        sb.append("    ").append(time).append("  ").append(title).append("\n");
                    }

                    // 节目间分隔线
                    if (i < programList.size() - 1) {
                        sb.append("    ├─ ").append(programList.get(i+1)[0]).append("\n");
                    }
                }
                sb.append("━━━━━━━━━━━━━━━━━━━━━━━━");

                new AlertDialog.Builder(this)
                        .setTitle(channel.name + " · 节目单（今天）")
                        .setMessage(sb.toString())
                        .setNegativeButton("关闭", null)
                        .setPositiveButton("切换频道", (d, w) -> {
                            // 可扩展：点击节目名直接跳转（暂不支持）
                            Toast.makeText(this, "节目预告功能开发中", Toast.LENGTH_SHORT).show();
                        })
                        .show();
            });
        });
    }

    /**
     * 拉取频道 EPG 节目单。
     * 优先请求后端服务器 /api/epg?channel=xxx；
     * 后端不可用时回退返回最近 24h 占位数据（标注"后端未启用"）。
     */
    private List<String[]> fetchEpgPrograms(ChannelOptimized channel) {
        List<String[]> result = new ArrayList<>();

        // 尝试后端 EPG API（参数名 name，与后端 /api/epg?name=xxx 一致）
        try {
            String server = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
            String api = server + "/api/epg?name="
                    + java.net.URLEncoder.encode(channel.name, "UTF-8");

            HttpURLConnection conn = (HttpURLConnection) new URL(api).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "MaoziTV-EPG/2.0");
            if (conn.getResponseCode() == 200) {
                StringBuilder sb = new StringBuilder();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                JSONObject obj = new JSONObject(sb.toString());
                JSONArray arr = obj.optJSONArray("programs");
                if (arr != null && arr.length() > 0) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject p = arr.getJSONObject(i);
                        // 后端返回格式: {"start": "08:00", "end": "08:30", "title": "..."}
                        String start = p.optString("start", "--");
                        String title = p.optString("title", "未知节目");
                        result.add(new String[]{start, title});
                    }
                    return result;
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.d(TAG, "EPG 后端不可用: " + e.getMessage());
        }

        // 后端不可用 → 返回占位提示
        result.add(new String[]{"--", "暂无节目信息"});
        return result;
    }

    /**
     * 自动拉取 EPG 并显示在频道信息叠加层（tvEpgNow）。
     * 仅在设置中开启 EPG 显示时调用。
     */
    private void fetchAndShowEpgOverlay(ChannelOptimized channel) {
        if (tvEpgNow == null) return;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean("show_epg", true)) {
            tvEpgNow.setVisibility(View.GONE);
            return;
        }
        tvEpgNow.setVisibility(View.GONE); // 先隐藏，拉到数据再显示
        executor.execute(() -> {
            List<String[]> programs = fetchEpgPrograms(channel);
            mainHandler.post(() -> {
                if (programs.isEmpty() || currentChannel != channel) return;
                String title = programs.get(0)[1];
                if (!title.isEmpty() && !title.equals("暂无节目信息")) {
                    tvEpgNow.setText("📋 " + title);
                    tvEpgNow.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    // ══════════════════════════════════════════════════════════
    // 设置：MENU 键已改为打开 SettingsActivity（全屏设置中心）
    // 下列子方法保留为兼容入口，供 SettingsActivity 逻辑复用
    // ══════════════════════════════════════════════════════════

    /** 当前解码方式标签 */
    private String currentDecoderLabel() {
        return useSoftwareDecoder ? "软解" : "硬解";
    }

    /** 切换解码方式（硬解 → 软解） */
    private void toggleDecoder() {
        useSoftwareDecoder = !useSoftwareDecoder;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_SOFT_DECODER, useSoftwareDecoder).apply();
        Toast.makeText(this, "已切换为" + currentDecoderLabel() + "，重新播放生效",
                Toast.LENGTH_SHORT).show();
        // 重新加载当前频道生效
        if (currentChannel != null) {
            playChannel(currentChannel);
        }
    }

    private void showDecoderDialog() {
        new AlertDialog.Builder(this)
                .setTitle("解码方式")
                .setSingleChoiceItems(new String[]{"硬解（默认，性能好）", "软解（兼容性好）"},
                        useSoftwareDecoder ? 1 : 0, (dialog, which) -> {
                            boolean wantSoft = which == 1;
                            if (wantSoft != useSoftwareDecoder) {
                                toggleDecoder();
                            }
                            dialog.dismiss();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    // ══════════════════════════════════════════════════════════
    // 倍速播放
    // ══════════════════════════════════════════════════════════
    private void showSpeedDialog() {
        if (player == null) {
            Toast.makeText(this, "播放器未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] speeds = {"0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x"};
        int cur = 2; // 默认 1.0x 索引2
        float current = currentPlaybackSpeed;
        for (int i = 0; i < speeds.length; i++) {
            try {
                if (Float.parseFloat(speeds[i].replace("x", "")) == current) {
                    cur = i;
                    break;
                }
            } catch (Exception ignored) {}
        }
        new AlertDialog.Builder(this)
                .setTitle("播放速度")
                .setSingleChoiceItems(speeds, cur, (dialog, which) -> {
                    float speed = Float.parseFloat(speeds[which].replace("x", ""));
                    setPlaybackSpeed(speed);
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void setPlaybackSpeed(float speed) {
        currentPlaybackSpeed = speed;
        if (player != null) {
            player.setPlaybackSpeed(speed);
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putFloat("playback_speed", speed).apply();
        Toast.makeText(this, "播放速度: " + speed + "x", Toast.LENGTH_SHORT).show();
    }

    // ══════════════════════════════════════════════════════════
    // 定时关机 / 睡眠
    // ══════════════════════════════════════════════════════════
    private void showSleepTimerDialog() {
        new AlertDialog.Builder(this)
                .setTitle("定时关机")
                .setItems(new String[]{"30 分钟", "1 小时", "2 小时", "4 小时", "关闭定时"},
                        (dialog, which) -> {
                            switch (which) {
                                case 0: scheduleSleepTimer(30); break;
                                case 1: scheduleSleepTimer(60); break;
                                case 2: scheduleSleepTimer(120); break;
                                case 3: scheduleSleepTimer(240); break;
                                default: cancelSleepTimer(); break;
                            }
                            dialog.dismiss();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void scheduleSleepTimer(int minutes) {
        sleepTimerEndTime = System.currentTimeMillis() + minutes * 60_000L;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putLong(KEY_SLEEP_TIMER, sleepTimerEndTime).apply();
        Toast.makeText(this, "将在 " + minutes + " 分钟后关机", Toast.LENGTH_SHORT).show();
        checkSleepTimer();
    }

    private void cancelSleepTimer() {
        sleepTimerEndTime = 0;
        sleepTimerHandler.removeCallbacksAndMessages(null);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().remove(KEY_SLEEP_TIMER).apply();
        Toast.makeText(this, "定时关机已取消", Toast.LENGTH_SHORT).show();
    }

    /** 每秒检查一次定时关机时间是否到达 */
    private void checkSleepTimer() {
        sleepTimerHandler.removeCallbacksAndMessages(null);
        if (sleepTimerEndTime <= 0) return;
        long remaining = sleepTimerEndTime - System.currentTimeMillis();
        if (remaining <= 0) {
            // 时间到 → 关闭 App（回到桌面）
            sleepTimerEndTime = 0;
            Toast.makeText(this, "定时关机时间到", Toast.LENGTH_SHORT).show();
            finishAndRemoveTask();
        } else {
            sleepTimerHandler.postDelayed(this::checkSleepTimer, 1000);
        }
    }

    /** 开机时恢复未到期的定时关机 */
    private void restoreSleepTimer() {
        sleepTimerEndTime = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getLong(KEY_SLEEP_TIMER, 0);
        if (sleepTimerEndTime > System.currentTimeMillis()) {
            checkSleepTimer();
        }
    }

    // ══════════════════════════════════════════════════════════
    // 开机自启开关
    // ══════════════════════════════════════════════════════════
    private void toggleBootLaunch() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_BOOT_LAUNCH, false);
        enabled = !enabled;
        prefs.edit().putBoolean(KEY_BOOT_LAUNCH, enabled).apply();
        Toast.makeText(this, "开机自启: " + (enabled ? "已开启" : "已关闭"),
                Toast.LENGTH_SHORT).show();
    }

    // ══════════════════════════════════════════════════════════
    // 自定义源导入（m3u / JSON / 接口 URL）
    // ══════════════════════════════════════════════════════════
    private void showCustomSourceDialog() {
        // 显示已添加的自定义源列表 + 添加按钮
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String saved = prefs.getString(KEY_CUSTOM_SOURCES, "");
        List<String> customUrls = new ArrayList<>();
        if (!saved.isEmpty()) {
            Collections.addAll(customUrls, saved.split("\n"));
        }

        new AlertDialog.Builder(this)
                .setTitle("自定义源 (" + customUrls.size() + ")")
                .setItems(customUrls.toArray(new String[0]), (dialog, which) -> {
                    // 长按删除
                })
                .setPositiveButton("添加源", (dialog, which) -> showAddCustomSourceInput())
                .setNegativeButton("清除全部", (dialog, which) -> {
                    prefs.edit().remove(KEY_CUSTOM_SOURCES).apply();
                    Toast.makeText(this, "已清除自定义源", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("关闭", null)
                .show();
    }

    private void showAddCustomSourceInput() {
        EditText input = new EditText(this);
        input.setHint("https://.../channels.json 或 m3u 地址");
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0x88FFFFFF);
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
                .setTitle("添加自定义源")
                .setMessage("支持 channels.json / m3u / txt (TVBox接口) 地址")
                .setView(input)
                .setPositiveButton("添加", (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) return;
                    saveCustomSource(url);
                    refreshChannels();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveCustomSource(String url) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String saved = prefs.getString(KEY_CUSTOM_SOURCES, "");
        if (!saved.contains(url)) {
            String newVal = saved.isEmpty() ? url : saved + "\n" + url;
            prefs.edit().putString(KEY_CUSTOM_SOURCES, newVal).apply();
            Toast.makeText(this, "自定义源已添加", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "该源已存在", Toast.LENGTH_SHORT).show();
        }
    }

    // ══════════════════════════════════════════════════════════
    // 家长模式 / 儿童锁
    // ══════════════════════════════════════════════════════════
    private void showParentalLockDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_PARENTAL_ENABLED, false);
        String savedPin = prefs.getString(KEY_PARENTAL_LOCK, "");

        if (enabled) {
            // 已开启 → 需输入密码解锁关闭
            promptPin("输入密码关闭家长模式", pin -> {
                if (pin.equals(savedPin)) {
                    prefs.edit().putBoolean(KEY_PARENTAL_ENABLED, false).apply();
                    Toast.makeText(this, "家长模式已关闭", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "密码错误", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            // 未开启 → 设置密码
            promptPin("设置家长模式密码 (4位数字)", pin -> {
                if (pin.length() < 4) {
                    Toast.makeText(this, "密码至少 4 位", Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.edit()
                        .putString(KEY_PARENTAL_LOCK, pin)
                        .putBoolean(KEY_PARENTAL_ENABLED, true)
                        .apply();
                Toast.makeText(this, "家长模式已开启", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void promptPin(String title, java.util.function.Consumer<String> onPin) {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setTextColor(0xFFFFFFFF);
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> onPin.accept(input.getText().toString().trim()))
                .setNegativeButton("取消", null)
                .show();
    }

    // ══════════════════════════════════════════════════════════
    // 关于 / 版权声明
    // ══════════════════════════════════════════════════════════
    private void showAboutDialog() {
        String versionName = "";
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}

        String msg = "帽子TV v" + versionName + "\n\n"
                + "本应用仅提供播放器功能，不包含任何内容。\n"
                + "直播源来自公开的开源仓库（GitHub/Gitee），\n"
                + "版权归原权利人所有。\n\n"
                + "📡 " + allChannels.size() + " 个频道";

        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setMessage(msg)
                .setPositiveButton("确定", null)
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
        // 持久化，供设置中心读取
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putInt("quality_max_height", maxHeight).apply();
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
        // 使用过滤隐藏分组后的可见频道列表，避免频道号落在隐藏频道上
        List<ChannelOptimized> visible = filterHiddenGroups(allChannels);
        if (channelNumber < 1 || channelNumber > visible.size()) {
            Toast.makeText(this, "频道号超出范围 (1-" + visible.size() + ")", Toast.LENGTH_SHORT).show();
            return;
        }
        ChannelOptimized target = visible.get(channelNumber - 1);
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

    /**
     * 统一的返回处理（OnBackPressedDispatcher 与 onKeyDown(BACK) 共用）。
     * 优先级：多画面 → 选台面板 → 频道号输入 → 退出确认。
     */
    private void handleBack() {
        // 1. 多画面模式：先退出多画面
        if (multiviewActive) {
            exitMultiview();
            return;
        }
        // 2. 选台面板打开：关闭面板
        if (panelVisible) {
            hideChannelPanel();
            return;
        }
        // 3. 频道号输入中：取消
        if (channelNumberBuffer.length() > 0) {
            channelNumberBuffer.setLength(0);
            tvChannelNumber.setVisibility(View.GONE);
            channelNumberHandler.removeCallbacksAndMessages(null);
            return;
        }
        // 4. 默认：退出 App
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 选台面板打开时：交给 RecyclerView 自身的焦点导航（上下/左右移动），
        // 只拦截 OK/BACK/数字键，其余方向键透传给系统做焦点移动。
        if (panelVisible) {
            // 记录用户操作，防止面板自动隐藏
            recordUserInteraction();
            
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (event.getRepeatCount() == 0) {
                        okLongPressed = false;
                        event.startTracking();
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    handleBack();
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
            // MENU 键 → 打开设置中心（全屏设置页）
            case KeyEvent.KEYCODE_MENU:
                startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS);
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

            // BACK 键：交给 OnBackPressedDispatcher 统一处理
            case KeyEvent.KEYCODE_BACK:
                handleBack();
                return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        // 长按 OK/Enter → 字幕选择
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER) {
            okLongPressed = true;
            if (player != null && trackSelector != null) {
                showTrackDialog(true); // true = 字幕
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
        List<ChannelOptimized> list = channelAdapter.getChannels();
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
        List<ChannelOptimized> list = channelAdapter.getChannels();
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

    /**
     * 频道健康检查功能
     */
    private void runHealthCheck() {
        if (currentChannel == null || currentChannel.sources.isEmpty()) {
            Toast.makeText(this, "当前频道无源信息", Toast.LENGTH_SHORT).show();
            return;
        }

        showStatus("正在进行健康检查...");
        
        // 在后台执行健康检查
        executor.execute(() -> {
            int healthyCount = 0;
            int totalCount = currentChannel.sources.size();
            List<Integer> healthyIndices = new ArrayList<>();

            for (int i = 0; i < totalCount; i++) {
                String url = currentChannel.sources.get(i);
                boolean isHealthy = ChannelHealthChecker.checkUrlHealth(url);
                if (isHealthy) {
                    healthyCount++;
                    healthyIndices.add(i);
                }
            }

            int finalHealthyCount = healthyCount;
            int finalTotalCount = totalCount;
            List<Integer> finalHealthyIndices = healthyIndices;

            mainHandler.post(() -> {
                hideStatus();
                String message = String.format("健康检查完成：可用源 %d/%d", finalHealthyCount, finalTotalCount);
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                
                // 如果当前源不健康，切换到第一个健康源
                if (!finalHealthyIndices.isEmpty() && !finalHealthyIndices.contains(currentChannel.currentSourceIndex)) {
                    int bestIndex = finalHealthyIndices.get(0);
                    if (bestIndex != currentChannel.currentSourceIndex) {
                        currentChannel.currentSourceIndex = bestIndex;
                        playUrl(currentChannel.getCurrentSourceUrl());
                        Toast.makeText(this, "自动切换到健康源", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        });
    }

    // ══════════════════════════════════════════════════════════
    // 触摸操作
    // ══════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════
    // 信号源信息显示
    // ══════════════════════════════════════════════════════════

    /** 更新面板头部的信号源版本/时间/频道数显示 */
    private void updateSourceInfoDisplay() {
        if (tvSourceInfo == null) return;

        // 遵守「频道源信息」设置开关
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean("show_source_info", true)) {
            tvSourceInfo.setVisibility(View.GONE);
            return;
        }
        tvSourceInfo.setVisibility(View.VISIBLE);
        String infoStr = prefs.getString(KEY_SOURCE_INFO, null);

        String display;
        if (infoStr != null) {
            try {
                JSONObject info = new JSONObject(infoStr);
                String version = info.optString("version", "?");
                int mergedCount = info.optInt("merged_count", allChannels.size());
                int sourceCount = info.optInt("source_count", 1);
                String generatedAt = info.optString("generated_at", "");

                // 提取日期部分 (YYYY-MM-DD)
                String date = "";
                if (generatedAt.length() >= 10) {
                    date = generatedAt.substring(0, 10);
                }

                display = String.format("📡 v%s · %d频道 · %d源 · %s",
                        version, mergedCount, sourceCount, date);
            } catch (Exception e) {
                display = "📡 " + allChannels.size() + " 频道";
            }
        } else {
            display = "📡 " + allChannels.size() + " 频道";
        }

        tvSourceInfo.setText(display);
    }
}
