package com.tv.live;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Maozi TV — 双模式 Android TV 播放器
 *
 * 模式一 (Server)：连接后端 API 服务
 * 模式二 (Standalone)：从 Gitee/GitHub 拉取 channels.json，本地播放
 *
 * 默认使用 Standalone 模式，优先从 Gitee 获取，失败则回退到 GitHub。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MaoziTV";

    // ── Standalone 模式：JSON 源地址 ──────────────────────────
    private static final String[] JSON_URLS = {
            "https://gitee.com/liuxue5213/maozi-tv/raw/master/channels.json",
            "https://raw.githubusercontent.com/liuxue5213/Maozi-TV/gh-pages/channels.json",
    };

    // ── SharedPreferences 缓存 ───────────────────────────────
    private static final String PREFS_NAME = "maozi_tv_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_STANDALONE_URL = "standalone_url";
    private static final String KEY_CACHED_JSON = "cached_channels_json";
    private static final String KEY_CACHED_VERSION = "cached_version";
    // last generated_at_ts seen for the cached JSON — the real update signal.
    // (EXPORT_VERSION is a constant, so comparing it alone never detects updates.)
    private static final String KEY_CACHED_TS = "cached_generated_ts";
    private static final String KEY_MODE = "mode"; // "server" or "standalone"
    private static final String DEFAULT_SERVER_URL = "http://192.168.1.100:8000";

    private WebView webView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Lifecycle ──────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            // 全屏沉浸式
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );

            webView = new WebView(this);
            setContentView(webView);
            setupWebView();

            // 延迟加载，确保 WebView 就绪
            webView.post(this::startLoading);
        } catch (Exception e) {
            Log.e(TAG, "onCreate failed: " + e.getMessage(), e);
            Toast.makeText(this, "启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    // ── WebView 配置 ───────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        // Standalone mode loads the UI from file:///android_asset/, so file
        // scheme access must be enabled. Keep these consistent (previously the
        // first two were disabled while the *FromFileURLs flags were enabled,
        // which is contradictory). Content access is not needed here.
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        // Stream URLs are https; allow https content when the page itself is
        // loaded over file:// (standalone mode).
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " MaoziTV/1.0");

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        // 暴露 Android bridge 给 JS
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 页面加载完成后，如果有缓存的 JSON，先注入
                injectCachedData();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                                         String description, String failingUrl) {
                Log.e(TAG, "WebView error: " + description + " (" + failingUrl + ")");
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
    }

    // ── 启动加载 ───────────────────────────────────────────

    private void startLoading() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String mode = prefs.getString(KEY_MODE, "standalone");

        if ("server".equals(mode)) {
            String url = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
            Log.i(TAG, "Server mode: " + url);
            webView.loadUrl(url);
        } else {
            // Standalone 模式：从本地 assets 加载 Web UI
            Log.i(TAG, "Standalone mode: loading from local assets");
            webView.loadUrl("file:///android_asset/index.html");
        }
    }

    // ── 缓存注入 (在 WebView 加载完后调用) ──────────────────

    private void injectCachedData() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String mode = prefs.getString(KEY_MODE, "standalone");

        if ("standalone".equals(mode)) {
            // 在后台线程拉取 JSON
            executor.execute(this::fetchChannelsJson);
        }
    }

    // ── JSON 拉取 (Gitee 优先 → GitHub 备用) ───────────────

    private void fetchChannelsJson() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        long cachedTs = prefs.getLong(KEY_CACHED_TS, 0);
        int cachedVersion = prefs.getInt(KEY_CACHED_VERSION, 0);
        String cachedJson = prefs.getString(KEY_CACHED_JSON, null);

        // 遍历所有 URL，尝试拉取
        for (String urlStr : JSON_URLS) {
            try {
                Log.i(TAG, "Fetching channels from: " + urlStr);

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36");

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    Log.w(TAG, urlStr + " returned " + responseCode + ", trying next...");
                    conn.disconnect();
                    continue;
                }

                // 读取响应
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

                // 解析时间戳 — 这是判断更新的真正依据（EXPORT_VERSION 是常量，
                // 永远不变，不能用来判断 channels.json 是否有更新）
                JSONObject obj = new JSONObject(jsonData);
                long remoteTs = obj.optLong("generated_at_ts", 0);
                int remoteVersion = obj.optInt("version", 0);
                Log.i(TAG, "Remote ts=" + remoteTs + " v" + remoteVersion
                        + ", cached ts=" + cachedTs + " v" + cachedVersion);

                boolean noCache = (cachedJson == null);
                boolean hasNewerData = (remoteTs > cachedTs)
                        || (remoteTs == 0 && remoteVersion > cachedVersion);

                // 时间戳有更新（或无缓存）→ 缓存并注入
                if (hasNewerData || noCache) {
                    prefs.edit()
                            .putString(KEY_CACHED_JSON, jsonData)
                            .putInt(KEY_CACHED_VERSION, remoteVersion)
                            .putLong(KEY_CACHED_TS, remoteTs)
                            .apply();
                    Log.i(TAG, "New data cached: v" + remoteVersion + " ts=" + remoteTs);

                    // 注入到 WebView (必须在主线程)
                    String injectData = jsonData;
                    mainHandler.post(() -> {
                        injectJsonToWebView(injectData);
                        Toast.makeText(MainActivity.this,
                                "频道已更新", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    Log.i(TAG, "Data unchanged, using cached data");
                    // 数据没变，用缓存的
                    useCachedJson(cachedJson);
                }

                return; // 拉取成功，退出

            } catch (Exception e) {
                Log.w(TAG, "Failed to fetch from " + urlStr + ": " + e.getMessage());
            }
        }

        // 所有源都失败 → 用缓存
        Log.w(TAG, "All sources failed, falling back to cache");
        useCachedJson(cachedJson);
    }

    /**
     * Safely inject a JSON string into the WebView by passing it as a string
     * argument rather than string-concatenating into JS source. Avoids edge
     * cases around quotes / {@code </script>} sequences in the payload.
     */
    private void injectJsonToWebView(String jsonData) {
        // Escape backslash and single-quote, then wrap in single quotes so JS
        // parses it as a literal string; initFromJson JSON.parses it itself.
        String safe = jsonData.replace("\\", "\\\\").replace("'", "\\'");
        String js = "(function(){try{initFromJson(JSON.parse('" + safe + "'));}"
                + "catch(e){console.error('initFromJson failed:',e);}})();";
        webView.evaluateJavascript(js, null);
    }

    private void useCachedJson(String cachedJson) {
        if (cachedJson == null) {
            mainHandler.post(() ->
                    Toast.makeText(MainActivity.this, "无法获取频道列表", Toast.LENGTH_LONG).show());
            return;
        }
        mainHandler.post(() -> {
            injectJsonToWebView(cachedJson);
            Toast.makeText(MainActivity.this, "使用缓存的频道列表", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * 强制重新拉取频道 JSON（忽略缓存版本）。供"更新频道源"入口和
     * AndroidBridge.refreshChannels() JS 接口调用。在后台线程执行。
     */
    private void refreshChannels() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // 清空缓存时间戳，使 fetchChannelsJson 视为"必然有更新"而重新拉取
        prefs.edit().putLong(KEY_CACHED_TS, 0).apply();
        mainHandler.post(() ->
                Toast.makeText(MainActivity.this, "正在更新频道源...", Toast.LENGTH_SHORT).show());
        executor.execute(this::fetchChannelsJson);
    }

    // ── Android → JavaScript Bridge ─────────────────────────

    private class AndroidBridge {
        @JavascriptInterface
        public String getCachedJson() {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            return prefs.getString(KEY_CACHED_JSON, "{}");
        }

        /** JS 调用：强制重新拉取频道源（"更新频道源"按钮触发）。 */
        @JavascriptInterface
        public void refreshChannels() {
            MainActivity.this.refreshChannels();
        }
    }

    // ── 模式切换 / 设置 ─────────────────────────────────────

    private void showModeDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String currentMode = prefs.getString(KEY_MODE, "standalone");

        new AlertDialog.Builder(this)
                .setTitle("设置 (MENU 键打开)")
                .setItems(new String[]{
                        "🔄 更新频道源 (重新拉取)",
                        "🌐 独立模式 (Gitee/GitHub 拉取)",
                        "🖥️ 服务器模式 (连接后端 API)"
                }, (dialog, which) -> {
                    if (which == 0) {
                        // 更新频道源 — 强制重新拉取最新 channels.json
                        refreshChannels();
                    } else if (which == 1) {
                        // 独立模式
                        prefs.edit().putString(KEY_MODE, "standalone").apply();
                        Toast.makeText(this, "已切换到独立模式", Toast.LENGTH_SHORT).show();
                        webView.loadUrl("file:///android_asset/index.html");
                        webView.post(this::fetchChannelsJson);
                    } else {
                        // 服务器模式 — 输入地址
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
                        webView.loadUrl(newUrl);
                        Toast.makeText(this, "已切换到: " + newUrl, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ── 遥控器按键 ─────────────────────────────────────────

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        // 长按 OK/Enter = 收藏/取消收藏当前焦点频道（注入 JS 调用 toggleFavorite）
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            mainHandler.post(() ->
                    webView.evaluateJavascript(
                            "(function(){if(typeof toggleFavorite==='function'){toggleFavorite();}})();",
                            null));
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // MENU 键 = 打开设置（模式切换 / 更新源）
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showModeDialog();
            return true;
        }
        // 对 OK/Enter 启用长按追踪，使 onKeyLongPress 能可靠触发。
        // 必须在 ACTION_DOWN 时调 startTracking() 并 return true，否则长按判定不生效。
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_SELECT)
                && event.getRepeatCount() == 0) {
            event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // OK/Enter 短按（非长按）= 播放当前焦点频道。直接调用 JS 的播放逻辑，
        // 避免 onKeyDown return true 后 WebView 收不到按键。
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (!event.isLongPress()) {
                mainHandler.post(() ->
                        webView.evaluateJavascript(
                                "(function(){if(typeof playFocusedChannel==='function'){playFocusedChannel();}})();",
                                null));
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        executor.shutdown();
        super.onDestroy();
    }
}
