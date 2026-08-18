package com.tv.live;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 电视端设置中心（全屏）。
 *
 * 布局：左侧分类导航 + 右侧设置项列表。
 * 所有设置持久化到 SharedPreferences，返回主界面后自动生效。
 *
 * 设置分类：
 * - 播放：解码方式 / 画面比例 / 倍速 / 画质 / 多画面
 * - 启动：开机自启 / 家长模式 / 定时关机
 * - 内容：频道源更新 / 自定义源 / 服务器地址
 * - 关于：关于与版权声明
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "maozi_tv_prefs";

    // 分类
    private static final String CAT_PLAY = "play";
    private static final String CAT_BOOT = "boot";
    private static final String CAT_CONTENT = "content";
    private static final String CAT_UI = "ui";
    private static final String CAT_ABOUT = "about";

    // 播放类设置项 key
    private static final String KEY_DECODER = "key_decoder";
    private static final String KEY_RESIZE = "key_resize";
    private static final String KEY_SPEED = "key_speed";
    private static final String KEY_QUALITY = "key_quality";
    private static final String KEY_MULTIVIEW = "key_multiview";
    private static final String KEY_THEME = "key_theme";
    private static final String KEY_SHOW_EPG = "key_show_epg";
    private static final String KEY_SOURCE_STRATEGY = "key_source_strategy";
    private static final String KEY_BUFFER_MODE = "key_buffer_mode";
    private static final String KEY_AUTO_SWITCH = "key_auto_switch";

    // 启动类设置项 key
    private static final String KEY_BOOT_LAUNCH = "key_boot_launch";
    private static final String KEY_START_CHANNEL = "key_start_channel";
    private static final String KEY_STARTUP_PAGE = "key_startup_page";
    private static final String KEY_PARENTAL = "key_parental";
    private static final String KEY_SLEEP_TIMER = "key_sleep_timer";

    // 内容类设置项 key
    private static final String KEY_CHECK_UPDATE = "key_check_update";
    private static final String KEY_REFRESH_SOURCE = "key_refresh_source";
    private static final String KEY_GROUP_DISPLAY = "key_group_display";
    private static final String KEY_CUSTOM_SOURCE = "key_custom_source";
    private static final String KEY_SERVER_URL = "key_server_url";
    private static final String KEY_HEALTHY_ONLY = "key_healthy_only";
    private static final String KEY_AUTO_UPDATE = "key_auto_update";

    // 界面类设置项 key
    private static final String KEY_GRID_COLUMNS = "key_grid_columns";
    private static final String KEY_SHOW_SIGNAL = "key_show_signal";
    private static final String KEY_SHOW_BITRATE = "key_show_bitrate";
    private static final String KEY_SHOW_SOURCE_INFO = "key_show_source_info";

    // 关于
    private static final String KEY_ABOUT = "key_about";

    private RecyclerView rvCategories;
    private RecyclerView rvItems;
    private SettingCategoryAdapter categoryAdapter;
    private SettingAdapter itemAdapter;

    private String currentCategory = CAT_PLAY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        // 不使用沉浸式全屏：保留系统导航栏（三个虚拟按钮）可见可用，
        // App 内容在其安全区内显示
        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | android.view.View.SYSTEM_UI_FLAG_VISIBLE);


        rvCategories = findViewById(R.id.rv_settings_categories);
        rvItems = findViewById(R.id.rv_settings_items);

        // 分类
        List<SettingCategoryAdapter.Category> cats = new ArrayList<>();
        cats.add(new SettingCategoryAdapter.Category(CAT_PLAY, "播放", "▶️"));
        cats.add(new SettingCategoryAdapter.Category(CAT_BOOT, "启动", "🚀"));
        cats.add(new SettingCategoryAdapter.Category(CAT_CONTENT, "内容", "📺"));
        cats.add(new SettingCategoryAdapter.Category(CAT_UI, "界面", "🎨"));
        cats.add(new SettingCategoryAdapter.Category(CAT_ABOUT, "关于", "ℹ️"));

        categoryAdapter = new SettingCategoryAdapter((cat, pos) -> {
            currentCategory = cat.id;
            refreshItems();
        });
        rvCategories.setLayoutManager(new LinearLayoutManager(this));
        rvCategories.setHasFixedSize(true); // 分类数量固定，跳过布局重算
        rvCategories.setAdapter(categoryAdapter);
        categoryAdapter.setCategories(cats);

        // 设置项
        itemAdapter = new SettingAdapter(this::handleSettingClick);
        rvItems.setLayoutManager(new LinearLayoutManager(this));
        rvItems.setHasFixedSize(true);      // 设置项高度固定，跳过布局重算
        rvItems.setItemViewCacheSize(8);    // 加大离屏缓存，切换分类更流畅
        rvItems.setAdapter(itemAdapter);

        refreshItems();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 每次回到设置页都刷新（值可能已在主界面改变）
        if (hasFocus) refreshItems();
    }

    // ── 构建当前分类的设置项列表 ────────────────────────────
    private void refreshItems() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        List<SettingItem> items = new ArrayList<>();

        switch (currentCategory) {
            case CAT_PLAY:
                items.add(new SettingItem(KEY_DECODER, "解码方式", SettingItem.TYPE_ACTION)
                        .withSummary(prefs.getBoolean("soft_decoder", false) ? "软解" : "硬解"));
                items.add(new SettingItem(KEY_RESIZE, "画面比例", SettingItem.TYPE_ACTION)
                        .withSummary(resizeModeLabel(prefs.getInt("resize_mode", 0))));
                items.add(new SettingItem(KEY_QUALITY, "画质限制", SettingItem.TYPE_ACTION)
                        .withSummary(qualityLabel(prefs.getInt("quality_max_height", -1))));
                items.add(new SettingItem(KEY_SPEED, "播放速度", SettingItem.TYPE_ACTION)
                        .withSummary(prefs.getFloat("playback_speed", 1.0f) + "x"));
                items.add(new SettingItem(KEY_THEME, "主题", SettingItem.TYPE_ACTION)
                        .withSummary(themeLabel(prefs.getInt("theme", 0))));
                items.add(new SettingItem(KEY_SHOW_EPG, "EPG 节目单", SettingItem.TYPE_TOGGLE)
                        .withToggle("show_epg", true)
                        .withSummary(prefs.getBoolean("show_epg", true) ? "已开启" : "已关闭"));
                items.add(new SettingItem(KEY_SOURCE_STRATEGY, "源选择策略", SettingItem.TYPE_ACTION)
                        .withSummary(sourceStrategyLabel(prefs.getString("source_strategy", "smart"))));
                items.add(new SettingItem(KEY_BUFFER_MODE, "缓冲模式", SettingItem.TYPE_ACTION)
                        .withSummary(bufferModeLabel(prefs.getString("buffer_mode", "standard"))));
                items.add(new SettingItem(KEY_AUTO_SWITCH, "自动切源", SettingItem.TYPE_TOGGLE)
                        .withToggle("auto_switch_source", true)
                        .withSummary(prefs.getBoolean("auto_switch_source", true) ? "已开启" : "已关闭"));
                items.add(new SettingItem(KEY_MULTIVIEW, "多画面模式", SettingItem.TYPE_ACTION)
                        .withSummary("2x2"));
                break;

            case CAT_BOOT:
                items.add(new SettingItem(KEY_BOOT_LAUNCH, "开机自启", SettingItem.TYPE_TOGGLE)
                        .withToggle("boot_launch_enabled", false)
                        .withSummary(prefs.getBoolean("boot_launch_enabled", false) ? "已开启" : "已关闭"));
                items.add(new SettingItem(KEY_START_CHANNEL, "启动进入频道", SettingItem.TYPE_ACTION)
                        .withSummary(startChannelLabel(prefs.getBoolean("start_last_channel", true))));
                items.add(new SettingItem(KEY_STARTUP_PAGE, "启动页面", SettingItem.TYPE_ACTION)
                        .withSummary(startupPageLabel(prefs.getString("startup_page", "all"))));
                items.add(new SettingItem(KEY_PARENTAL, "家长模式", SettingItem.TYPE_ACTION)
                        .withSummary(prefs.getBoolean("parental_enabled", false) ? "已开启" : "已关闭"));
                items.add(new SettingItem(KEY_SLEEP_TIMER, "定时关机", SettingItem.TYPE_ACTION)
                        .withSummary(sleepTimerSummary(prefs.getLong("sleep_timer_minutes", 0))));
                break;

            case CAT_CONTENT:
                items.add(new SettingItem(KEY_CHECK_UPDATE, "检查更新", SettingItem.TYPE_ACTION)
                        .withSummary("APK 版本"));
                items.add(new SettingItem(KEY_REFRESH_SOURCE, "更新频道源", SettingItem.TYPE_ACTION)
                        .withSummary("重新拉取"));
                items.add(new SettingItem(KEY_HEALTHY_ONLY, "仅显示健康频道", SettingItem.TYPE_TOGGLE)
                        .withToggle("healthy_only", false)
                        .withSummary(prefs.getBoolean("healthy_only", false) ? "已开启" : "已关闭"));
                items.add(new SettingItem(KEY_AUTO_UPDATE, "自动更新间隔", SettingItem.TYPE_ACTION)
                        .withSummary(autoUpdateLabel(prefs.getString("auto_update_interval", "24"))));
                items.add(new SettingItem(KEY_GROUP_DISPLAY, "分组显示管理", SettingItem.TYPE_ACTION)
                        .withSummary(hiddenGroupsSummary(prefs.getString("hidden_groups", ""))));
                items.add(new SettingItem(KEY_CUSTOM_SOURCE, "自定义源", SettingItem.TYPE_ACTION)
                        .withSummary(customSourceSummary(prefs.getString("custom_sources", ""))));
                items.add(new SettingItem(KEY_SERVER_URL, "服务器地址", SettingItem.TYPE_ACTION)
                        .withSummary(prefs.getString("server_url", "http://192.168.1.100:8000")));
                break;

            case CAT_UI:
                items.add(new SettingItem(KEY_GRID_COLUMNS, "网格列数", SettingItem.TYPE_ACTION)
                        .withSummary(gridColumnsLabel(prefs.getInt("grid_columns", -1))));
                items.add(new SettingItem(KEY_SHOW_SIGNAL, "信号指示器", SettingItem.TYPE_TOGGLE)
                        .withToggle("show_signal", true)
                        .withSummary(prefs.getBoolean("show_signal", true) ? "已开启" : "已关闭"));
                items.add(new SettingItem(KEY_SHOW_BITRATE, "码率/分辨率显示", SettingItem.TYPE_TOGGLE)
                        .withToggle("show_bitrate", true)
                        .withSummary(prefs.getBoolean("show_bitrate", true) ? "已开启" : "已关闭"));
                items.add(new SettingItem(KEY_SHOW_SOURCE_INFO, "频道源信息", SettingItem.TYPE_TOGGLE)
                        .withToggle("show_source_info", true)
                        .withSummary(prefs.getBoolean("show_source_info", true) ? "已开启" : "已关闭"));
                break;

            case CAT_ABOUT:
                items.add(new SettingItem(KEY_ABOUT, "关于与版权声明", SettingItem.TYPE_ACTION)
                        .withSummary("v2.2.0"));
                break;
        }

        itemAdapter.setItems(items);
    }

    private String sourceStrategyLabel(String strategy) {
        switch (strategy) {
            case "fastest": return "最快响应";
            case "first": return "首个可用";
            default: return "智能排序";
        }
    }

    private String bufferModeLabel(String mode) {
        switch (mode) {
            case "low_latency": return "低延迟";
            case "high_stability": return "高稳定";
            default: return "标准";
        }
    }

    private String startupPageLabel(String page) {
        switch (page) {
            case "favorites": return "收藏";
            case "last": return "上次频道";
            case "hot": return "热门频道";
            default: return "全部频道";
        }
    }

    private String autoUpdateLabel(String hours) {
        switch (hours) {
            case "1": return "每 1 小时";
            case "6": return "每 6 小时";
            case "never": return "关闭";
            default: return "每天";
        }
    }

    private String gridColumnsLabel(int cols) {
        if (cols >= 2 && cols <= 5) return cols + " 列";
        return "自动";
    }

    private String qualityLabel(int maxHeight) {
        switch (maxHeight) {
            case 2160: return "4K";
            case 1080: return "1080p";
            case 720: return "720p";
            case 480: return "480p";
            default: return "自动";
        }
    }

    private String themeLabel(int index) {
        String[] names = {"暗夜", "深蓝", "墨绿"};
        if (index < 0 || index >= names.length) return names[0];
        return names[index];
    }

    private String startChannelLabel(boolean lastChannel) {
        return lastChannel ? "上次频道" : "默认频道";
    }

    private String resizeModeLabel(int mode) {
        String[] labels = {"自适应", "裁剪填充", "缩放拉伸", "16:9", "4:3"};
        if (mode < 0 || mode >= labels.length) return labels[0];
        return labels[mode];
    }

    private String sleepTimerSummary(long endTime) {
        if (endTime <= 0) return "关闭";
        long remaining = endTime - System.currentTimeMillis();
        if (remaining <= 0) return "已到期";
        long minutes = remaining / 60000;
        if (minutes < 60) return minutes + " 分钟";
        return String.format("%.1f 小时", minutes / 60.0);
    }

    private String customSourceSummary(String saved) {
        if (saved == null || saved.isEmpty()) return "未添加";
        int count = saved.split("\n").length;
        return count + " 个源";
    }

    /** 隐藏分组摘要：显示已隐藏数量 */
    private String hiddenGroupsSummary(String saved) {
        if (saved == null || saved.trim().isEmpty()) return "全部显示";
        int count = saved.split(",").length;
        return count + " 个分组已隐藏";
    }

    /**
     * 分组显示管理对话框：
     * 列出所有分组，勾选 = 隐藏该分组（取消勾选 = 恢复显示）。
     * 分组数据从缓存 channels JSON 提取，避免与主界面数据耦合。
     */
    private void showGroupDisplayDialog() {
        final SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        final String cachedJson = prefs.getString("cached_channels_json", "");
        final String hiddenSaved = prefs.getString("hidden_groups", "");

        // 性能优化：后台线程解析缓存 JSON（1MB+），避免主线程卡顿
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            // 已隐藏分组集合
            final java.util.Set<String> hiddenSet = new java.util.HashSet<>();
            if (hiddenSaved != null && !hiddenSaved.isEmpty()) {
                java.util.Collections.addAll(hiddenSet, hiddenSaved.split(","));
            }

            // 从缓存 JSON 提取所有分组（去重，保持出现顺序）
            final List<String> allGroups = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            try {
                org.json.JSONObject root = new org.json.JSONObject(cachedJson);
                org.json.JSONArray arr = root.optJSONArray("channels");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        String group = arr.getJSONObject(i).optString("group", "");
                        if (!group.isEmpty() && !seen.contains(group)) {
                            seen.add(group);
                            allGroups.add(group);
                        }
                    }
                }
            } catch (Exception ignored) {}

            // 抛回主线程弹窗（Activity 已销毁则跳过，避免崩溃）
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (allGroups.isEmpty()) {
                    Toast.makeText(this, "无可用分组", Toast.LENGTH_SHORT).show();
                    return;
                }

                // 构建选项数组（已隐藏的分组标记为已勾选）
                String[] labels = new String[allGroups.size()];
                boolean[] checked = new boolean[allGroups.size()];
                for (int i = 0; i < allGroups.size(); i++) {
                    String g = allGroups.get(i);
                    labels[i] = hiddenSet.contains(g) ? g + "  [已隐藏]" : g;
                    checked[i] = hiddenSet.contains(g);
                }

                new AlertDialog.Builder(this)
                        .setTitle("分组显示管理")
                        .setMessage("勾选 = 隐藏该分组，取消勾选 = 恢复显示")
                        .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                            String group = allGroups.get(which);
                            if (isChecked) {
                                hiddenSet.add(group);
                            } else {
                                hiddenSet.remove(group);
                            }
                        })
                        .setPositiveButton("确定", (dialog, which) -> {
                            // 保存隐藏分组
                            StringBuilder sb = new StringBuilder();
                            for (String g : hiddenSet) {
                                if (sb.length() > 0) sb.append(",");
                                sb.append(g);
                            }
                            prefs.edit().putString("hidden_groups", sb.toString()).apply();
                            // 通知主界面刷新频道列表
                            setResult(1002);
                            Toast.makeText(this, "已保存，返回主界面生效", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }).start();
    }

    // ── 设置项点击处理 ──────────────────────────────────────
    private void handleSettingClick(SettingItem item) {
        switch (item.key) {
            // 播放
            case KEY_DECODER: showDecoderDialog(); break;
            case KEY_RESIZE: showResizeDialog(); break;
            case KEY_SPEED: showSpeedDialog(); break;
            case KEY_QUALITY: showQualityDialog(); break;
            case KEY_MULTIVIEW: Toast.makeText(this, "多画面需在主界面开启", Toast.LENGTH_SHORT).show(); break;
            case KEY_THEME: showThemeDialog(); break;
            case KEY_SHOW_EPG: toggleGeneric(item, "show_epg"); break;
            case KEY_SOURCE_STRATEGY: showSourceStrategyDialog(); break;
            case KEY_BUFFER_MODE: showBufferModeDialog(); break;
            case KEY_AUTO_SWITCH: toggleGeneric(item, "auto_switch_source"); break;
            // 启动
            case KEY_BOOT_LAUNCH: toggleBootLaunch(item); break;
            case KEY_START_CHANNEL: showStartChannelDialog(); break;
            case KEY_STARTUP_PAGE: showStartupPageDialog(); break;
            case KEY_PARENTAL: showParentalDialog(); break;
            case KEY_SLEEP_TIMER: showSleepTimerDialog(); break;
            // 内容
            case KEY_CHECK_UPDATE: checkUpdate(); break;
            case KEY_REFRESH_SOURCE: refreshSource(); break;
            case KEY_HEALTHY_ONLY: toggleGeneric(item, "healthy_only"); break;
            case KEY_AUTO_UPDATE: showAutoUpdateDialog(); break;
            case KEY_GROUP_DISPLAY: showGroupDisplayDialog(); break;
            case KEY_CUSTOM_SOURCE: showCustomSourceDialog(); break;
            case KEY_SERVER_URL: showServerUrlDialog(); break;
            // 界面
            case KEY_GRID_COLUMNS: showGridColumnsDialog(); break;
            case KEY_SHOW_SIGNAL: toggleGeneric(item, "show_signal"); break;
            case KEY_SHOW_BITRATE: toggleGeneric(item, "show_bitrate"); break;
            case KEY_SHOW_SOURCE_INFO: toggleGeneric(item, "show_source_info"); break;
            // 关于
            case KEY_ABOUT: showAboutDialog(); break;
        }
        refreshItems();
    }

    /** 通用 TOGGLE 切换（适用于所有简单 boolean 设置） */
    private void toggleGeneric(SettingItem item, String prefKey) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(prefKey, item.prefDefault);
        enabled = !enabled;
        prefs.edit().putBoolean(prefKey, enabled).apply();
        Toast.makeText(this, item.title + ": " + (enabled ? "已开启" : "已关闭"),
                Toast.LENGTH_SHORT).show();
    }

    // ── 画质限制 ──────────────────────────────────────────
    private void showQualityDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String[] labels = {"自动 (ABR)", "4K", "1080p", "720p", "480p"};
        final int[] heights = {-1, 2160, 1080, 720, 480};
        int cur = 0;
        int saved = prefs.getInt("quality_max_height", -1);
        for (int i = 0; i < heights.length; i++) {
            if (heights[i] == saved) { cur = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("画质限制")
                .setSingleChoiceItems(labels, cur, (dialog, which) -> {
                    prefs.edit().putInt("quality_max_height", heights[which]).apply();
                    dialog.dismiss();
                    Toast.makeText(this, "画质: " + labels[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ── 主题选择 ──────────────────────────────────────────
    private void showThemeDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String[] names = {"暗夜", "深蓝", "墨绿"};
        int cur = prefs.getInt("theme", 0);
        new AlertDialog.Builder(this)
                .setTitle("主题")
                .setSingleChoiceItems(names, cur, (dialog, which) -> {
                    prefs.edit().putInt("theme", which).apply();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ── 启动进入频道 ──────────────────────────────────────
    private void showStartChannelDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean last = prefs.getBoolean("start_last_channel", true);
        new AlertDialog.Builder(this)
                .setTitle("启动进入频道")
                .setSingleChoiceItems(new String[]{"上次频道", "默认频道 (全部列表)"},
                        last ? 0 : 1, (dialog, which) -> {
                            prefs.edit().putBoolean("start_last_channel", which == 0).apply();
                            dialog.dismiss();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    // ── 源选择策略 ──────────────────────────────────────────
    private void showSourceStrategyDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String current = prefs.getString("source_strategy", "smart");
        String[] labels = {"智能排序 (推荐)", "最快响应", "首个可用"};
        String[] values = {"smart", "fastest", "first"};
        int cur = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) { cur = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("源选择策略")
                .setMessage("智能排序：综合评分排序\n最快响应：测速后选最快\n首个可用：按原始顺序")
                .setSingleChoiceItems(labels, cur, (dialog, which) -> {
                    prefs.edit().putString("source_strategy", values[which]).apply();
                    dialog.dismiss();
                    Toast.makeText(this, "源策略: " + labels[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ── 缓冲模式 ──────────────────────────────────────────
    private void showBufferModeDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String current = prefs.getString("buffer_mode", "standard");
        String[] labels = {"低延迟 (5s/15s)", "标准 (10s/30s)", "高稳定 (20s/60s)"};
        String[] values = {"low_latency", "standard", "high_stability"};
        int cur = 1;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) { cur = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("缓冲模式")
                .setMessage("低延迟：切台快但易卡顿\n标准：平衡\n高稳定：缓冲大但更流畅")
                .setSingleChoiceItems(labels, cur, (dialog, which) -> {
                    prefs.edit().putString("buffer_mode", values[which]).apply();
                    dialog.dismiss();
                    Toast.makeText(this, "缓冲: " + labels[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ── 启动页面 ──────────────────────────────────────────
    private void showStartupPageDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String current = prefs.getString("startup_page", "all");
        String[] labels = {"全部频道", "收藏", "上次频道", "热门频道"};
        String[] values = {"all", "favorites", "last", "hot"};
        int cur = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) { cur = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("启动页面")
                .setSingleChoiceItems(labels, cur, (dialog, which) -> {
                    prefs.edit().putString("startup_page", values[which]).apply();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ── 自动更新间隔 ──────────────────────────────────────────
    private void showAutoUpdateDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String current = prefs.getString("auto_update_interval", "24");
        String[] labels = {"每 1 小时", "每 6 小时", "每天", "关闭"};
        String[] values = {"1", "6", "24", "never"};
        int cur = 2;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) { cur = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("自动更新间隔")
                .setMessage("频道源自动更新频率（仅自建后端生效）")
                .setSingleChoiceItems(labels, cur, (dialog, which) -> {
                    prefs.edit().putString("auto_update_interval", values[which]).apply();
                    dialog.dismiss();
                    Toast.makeText(this, labels[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ── 网格列数 ──────────────────────────────────────────
    private void showGridColumnsDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int current = prefs.getInt("grid_columns", -1);
        String[] labels = {"自动 (推荐)", "2 列", "3 列", "4 列", "5 列"};
        int[] values = {-1, 2, 3, 4, 5};
        int cur = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) { cur = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("网格列数")
                .setSingleChoiceItems(labels, cur, (dialog, which) -> {
                    prefs.edit().putInt("grid_columns", values[which]).apply();
                    dialog.dismiss();
                    Toast.makeText(this, labels[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ── 各设置实现 ─────────────────────────────────────────

    private void showDecoderDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean soft = prefs.getBoolean("soft_decoder", false);
        new AlertDialog.Builder(this)
                .setTitle("解码方式")
                .setSingleChoiceItems(new String[]{"硬解（默认，性能好）", "软解（兼容性好）"},
                        soft ? 1 : 0, (dialog, which) -> {
                            prefs.edit().putBoolean("soft_decoder", which == 1).apply();
                            dialog.dismiss();
                            Toast.makeText(this, "已切换，重启后生效", Toast.LENGTH_SHORT).show();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showResizeDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String[] labels = {"自适应", "裁剪填充", "缩放拉伸", "16:9", "4:3"};
        int cur = prefs.getInt("resize_mode", 0);
        new AlertDialog.Builder(this)
                .setTitle("画面比例")
                .setSingleChoiceItems(labels, cur, (dialog, which) -> {
                    prefs.edit().putInt("resize_mode", which).apply();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSpeedDialog() {
        final String[] speeds = {"0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x"};
        int cur = 2;
        new AlertDialog.Builder(this)
                .setTitle("播放速度")
                .setSingleChoiceItems(speeds, cur, (dialog, which) -> {
                    float speed = Float.parseFloat(speeds[which].replace("x", ""));
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit().putFloat("playback_speed", speed).apply();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toggleBootLaunch(SettingItem item) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(item.prefKey, item.prefDefault);
        enabled = !enabled;
        prefs.edit().putBoolean(item.prefKey, enabled).apply();
        Toast.makeText(this, "开机自启: " + (enabled ? "已开启" : "已关闭"),
                Toast.LENGTH_SHORT).show();
    }

    private void showParentalDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("parental_enabled", false);
        String savedPin = prefs.getString("parental_lock", "");

        if (enabled) {
            promptPin("输入密码关闭家长模式", pin -> {
                if (pin.equals(savedPin)) {
                    prefs.edit().putBoolean("parental_enabled", false).apply();
                    Toast.makeText(this, "家长模式已关闭", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "密码错误", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            promptPin("设置家长模式密码 (4位数字)", pin -> {
                if (pin.length() < 4) {
                    Toast.makeText(this, "密码至少 4 位", Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.edit()
                        .putString("parental_lock", pin)
                        .putBoolean("parental_enabled", true)
                        .apply();
                Toast.makeText(this, "家长模式已开启", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void promptPin(String title, java.util.function.Consumer<String> onPin) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setTextColor(0xFFFFFFFF);
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> onPin.accept(input.getText().toString().trim()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSleepTimerDialog() {
        new AlertDialog.Builder(this)
                .setTitle("定时关机")
                .setItems(new String[]{"30 分钟", "1 小时", "2 小时", "4 小时", "关闭定时"},
                        (dialog, which) -> {
                            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                            if (which == 4) {
                                prefs.edit().remove("sleep_timer_minutes").apply();
                                Toast.makeText(this, "定时关机已取消", Toast.LENGTH_SHORT).show();
                            } else {
                                int[] mins = {30, 60, 120, 240};
                                long endTime = System.currentTimeMillis() + mins[which] * 60_000L;
                                prefs.edit().putLong("sleep_timer_minutes", endTime).apply();
                                Toast.makeText(this, "将在 " + mins[which] + " 分钟后关机", Toast.LENGTH_SHORT).show();
                            }
                            dialog.dismiss();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshSource() {
        // 通知主界面刷新频道（通过发送结果码，主界面 onActivityResult 处理）
        Toast.makeText(this, "频道源更新已触发", Toast.LENGTH_SHORT).show();
        setResult(1001);
    }

    private void checkUpdate() {
        UpdateChecker checker = new UpdateChecker(this);
        checker.checkForUpdate(false);
    }

    private void showCustomSourceDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String saved = prefs.getString("custom_sources", "");
        List<String> urls = new ArrayList<>();
        if (saved != null && !saved.isEmpty()) {
            String[] parts = saved.split("\n");
            for (String u : parts) if (!u.isEmpty()) urls.add(u);
        }

        new AlertDialog.Builder(this)
                .setTitle("自定义源 (" + urls.size() + ")")
                .setPositiveButton("添加源", (dialog, which) -> showAddCustomSourceInput())
                .setNegativeButton("清除全部", (dialog, which) -> {
                    prefs.edit().remove("custom_sources").apply();
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
                .setView(input)
                .setPositiveButton("添加", (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) return;
                    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    String saved = prefs.getString("custom_sources", "");
                    if (!saved.contains(url)) {
                        String newVal = saved.isEmpty() ? url : saved + "\n" + url;
                        prefs.edit().putString("custom_sources", newVal).apply();
                        Toast.makeText(this, "自定义源已添加", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "该源已存在", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showServerUrlDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String current = prefs.getString("server_url", "http://192.168.1.100:8000");

        EditText input = new EditText(this);
        input.setText(current);
        input.setTextColor(0xFFFFFFFF);
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle("服务器地址")
                .setMessage("输入后端服务器的 IP:端口")
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) return;
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "http://" + url;
                    }
                    prefs.edit().putString("server_url", url).apply();
                    Toast.makeText(this, "服务器: " + url, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAboutDialog() {
        String msg = "帽子TV v2.2.0\n\n"
                + "本应用仅提供播放器功能，不包含任何内容。\n"
                + "直播源来自公开的开源仓库（GitHub/Gitee），\n"
                + "版权归原权利人所有。";
        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setMessage(msg)
                .setPositiveButton("确定", null)
                .show();
    }
}
