package com.tv.live;

import android.os.SystemClock;
import android.util.Log;

import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实时网速统计器。
 *
 * 通过 ExoPlayer 的 TransferListener 回调统计网络下载的字节数，
 * 而非 bandwidthMeter 的平均比特率估算（那个值用于 ABR 决策，直播时几乎不变）。
 *
 * 实现：
 * - 后台线程（下载线程）通过 onBytesTransferred 累加当前窗口字节数
 * - UI 线程每秒调用 tick() 采样一次，写入滑动窗口
 * - 展示最近 3 秒的平均速度，平滑 HLS 分片下载的突发性
 */
public class SpeedMeter implements TransferListener {

    private static final String TAG = "SpeedMeter";
    /** 采样间隔：1 秒 */
    private static final long SAMPLE_MS = 1000;
    /** 滑动平均窗口：3 秒 */
    private static final int WINDOW_SIZE = 3;

    /** 当前窗口累计字节（下载线程写，UI 线程读） */
    private final AtomicLong bytesInWindow = new AtomicLong();
    /** 当前窗口开始时间 */
    private volatile long windowStartMs;
    /** 最近 N 个采样的字节数（滑动窗口） */
    private final Deque<Long> samples = new ArrayDeque<>(WINDOW_SIZE + 1);

    /** 最近一次估算的网速（字节/秒），UI 读取 */
    private volatile long lastSpeedBps = 0;

    public SpeedMeter() {
        windowStartMs = SystemClock.elapsedRealtime();
    }

    // ── TransferListener 回调（下载线程）──────────────────

    @Override
    public void onTransferInitializing(DataSource dataSource, DataSpec dataSpec, boolean isNetwork) {}

    @Override
    public void onTransferStart(DataSource dataSource, DataSpec dataSpec, boolean isNetwork) {}

    @Override
    public void onBytesTransferred(DataSource dataSource, DataSpec dataSpec,
                                   boolean isNetwork, int bytesTransferred) {
        if (isNetwork) {
            bytesInWindow.addAndGet(bytesTransferred);
        }
    }

    @Override
    public void onTransferEnd(DataSource dataSource, DataSpec dataSpec, boolean isNetwork) {}

    // ── UI 采样 ───────────────────────────────────────────

    /**
     * 每秒调用一次：将当前窗口字节数写入滑动窗口，计算平均速度。
     * 返回最近一次估算的网速（字节/秒）。
     */
    public long tick() {
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - windowStartMs;
        long bytes = bytesInWindow.getAndSet(0);
        windowStartMs = now;

        // 当前窗口采样（字节/秒）
        long currentBps = elapsed > 0 ? (long) (bytes * 1000.0 / elapsed) : 0;

        // 写入滑动窗口
        synchronized (samples) {
            samples.addLast(currentBps);
            while (samples.size() > WINDOW_SIZE) {
                samples.pollFirst();
            }
            // 计算窗口内平均（平滑 HLS 分片下载的突发性）
            long sum = 0;
            for (long s : samples) {
                sum += s;
            }
            lastSpeedBps = samples.size() > 0 ? sum / samples.size() : 0;
        }

        // 每秒日志（调试用，可移除）
        Log.d(TAG, "实时下载: " + format(currentBps) + " | 平滑: " + format(lastSpeedBps));
        return lastSpeedBps;
    }

    /** 获取最近一次估算的网速（字节/秒） */
    public long getSpeedBps() {
        return lastSpeedBps;
    }

    /** 重置（切台时清空历史，避免旧数据污染新台的测速） */
    public void reset() {
        bytesInWindow.set(0);
        windowStartMs = SystemClock.elapsedRealtime();
        synchronized (samples) {
            samples.clear();
        }
        lastSpeedBps = 0;
    }

    private static String format(long bps) {
        if (bps <= 0) return "0";
        if (bps < 1024) return bps + " B/s";
        if (bps < 1024 * 1024) return String.format("%.1f KB/s", bps / 1024.0);
        return String.format("%.1f MB/s", bps / (1024.0 * 1024));
    }
}
