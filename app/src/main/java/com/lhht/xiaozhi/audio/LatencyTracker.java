package com.lhht.xiaozhi.audio;

import android.content.Context;
import android.util.Log;

import com.lhht.xiaozhi.utils.LogUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 记录一次 TTS 播放周期内的关键延迟节点（tts_start / first_binary / first_decode / first_track_write 等），
 * 统一输出一条结构化日志，替代散落在各处的手工 emoji 时序打点。
 */
public class LatencyTracker {
    private static final String TAG = "XiaoZhi-Latency";

    private final Object lock = new Object();
    private final Map<String, Long> marks = new LinkedHashMap<>();
    private long startNanos = 0;

    public void reset() {
        synchronized (lock) {
            marks.clear();
            startNanos = System.nanoTime();
        }
    }

    /** 记录一个时间点，同一个 point 在一次 reset 周期内只记录第一次出现的时间 */
    public void mark(String point) {
        synchronized (lock) {
            if (startNanos == 0) startNanos = System.nanoTime();
            if (!marks.containsKey(point)) {
                marks.put(point, System.nanoTime());
            }
        }
    }

    public String summary() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Long> e : marks.entrySet()) {
                long ms = TimeUnit.NANOSECONDS.toMillis(e.getValue() - startNanos);
                if (sb.length() > 0) sb.append(' ');
                sb.append(e.getKey()).append('=').append(ms).append("ms");
            }
            return sb.toString();
        }
    }

    public void logSummary(Context context, String label) {
        String s = summary();
        Log.i(TAG, label + ": " + s);
        LogUtils.getInstance().d(context, TAG, label + ": " + s);
    }
}
