package com.github.tvbox.osc.util;

import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 点播播放链路耗时埋点。
 *
 * 从"点击某一集"到"画面出来"要经历 5 个可独立计时的阶段,慢在哪一段决定了该找谁:
 *
 *   1. SRC_PARSE    源解析:调用站点 Spider / API 拿到播放页地址  —— 慢 = 源的问题
 *   2. JX_PARSE     解析/嗅探:把播放页地址变成视频直链          —— 慢 = 解析站 / 源的问题
 *   3. M3U8_PURIFY  m3u8 去广告:额外下载一遍 m3u8 文本          —— 慢 = 程序 + 源服务器
 *   4. PLAYER_xx    播放器:PREPARED / BUFFERED / 首帧            —— 慢 = 播放器 + 源服务器带宽
 *   5. FAIL         失败与重试
 *
 * 抓日志: adb logcat -s VodTrace
 *
 * 输出格式(一条同时给出"距起点总耗时"和"距上一个点的增量"):
 *   [#12 | +812ms | Δ807ms] SRC_PARSE_DONE | type=3 cost=807ms
 *     │       │        └─ 这中间卡了多久(最关键的数字)
 *     │       └─ 距本次播放起点的总耗时
 *     └─ 同一次播放的会话号,用来把散落在不同类的日志串起来
 */
public final class VodTrace {

    public static final String TAG = "VodTrace";

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private static volatile int sSession = 0;
    private static volatile long sStart = 0L;
    private static volatile long sLast = 0L;
    private static volatile String sTitle = "";

    private VodTrace() {
    }

    public static long now() {
        return SystemClock.elapsedRealtime();
    }

    /** 开启一次新的播放会话(在 PlayFragment.play() 里调用) */
    public static void begin(String title) {
        sSession = SEQ.incrementAndGet();
        sTitle = title == null ? "" : title;
        sStart = now();
        sLast = sStart;
        Log.d(TAG, String.format("[#%d] ============ PLAY_START | %s ============", sSession, sTitle));
    }

    /** 打一个阶段点,自动带出距上一个点的增量 */
    public static void mark(String stage, String msg) {
        log(stage, -1L, msg);
    }

    /**
     * 打一个阶段点,并显式给出该阶段自身的耗时(网络请求这类有明确 start/end 的片段)。
     *
     * @param startMs 该阶段开始时的 {@link #now()} 快照
     */
    public static void markCost(String stage, long startMs, String msg) {
        log(stage, startMs, msg);
    }

    /** 失败 / 错误统一入口 */
    public static void fail(String stage, String msg) {
        log(stage, -1L, "[FAIL] " + (msg == null ? "" : msg));
    }

    private static void log(String stage, long startMs, String msg) {
        long t = now();
        long abs = t - sStart;
        long rel = t - sLast;
        sLast = t;
        String cost = startMs >= 0 ? String.format(" cost=%dms |", t - startMs) : "";
        Log.d(TAG, String.format("[#%d | +%dms | Δ%sms] %-16s |%s %s",
                sSession, abs, rel, stage, cost, msg == null ? "" : msg));
    }
}
