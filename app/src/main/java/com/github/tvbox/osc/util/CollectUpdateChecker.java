package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.cache.VodCollect;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 收藏剧集"更新检测"器。
 *
 * <p>职责:遍历收藏列表,逐个用该收藏记录的源(sourceKey)重新拉取详情,
 * 比较"最新集数"与本地记录的已知集数,若变多则该剧有更新,写回 hasUpdate 标记,
 * 收藏页据此在卡片右上角展示「更新」角标。
 *
 * <p>设计要点:
 * <ul>
 *   <li>电影/单集(集数 &lt;= 1)直接跳过,不参与更新检测。</li>
 *   <li>同一部剧默认 6 小时内不重复请求(节流),避免耗流量与触发源站风控。</li>
 *   <li>单个详情请求带 20 秒超时,防止某个 spider 卡死拖住整轮检查。</li>
 *   <li>仅依赖 App 进程:在打开 App / 进收藏页时触发,不使用系统级后台任务。</li>
 * </ul>
 */
public class CollectUpdateChecker {

    private static final String TAG = "WATV_UPDATE";

    /** 同一部剧的最小检查间隔:6 小时 */
    private static final long CHECK_INTERVAL = 6 * 60 * 60 * 1000L;

    /** 单个详情请求超时(秒) */
    private static final int FETCH_TIMEOUT_SEC = 20;

    /** 源配置未就绪时的重试间隔与次数(冷启动常见:配置还在异步拉取) */
    private static final long RETRY_DELAY_MS = 5000L;
    private static final int RETRY_MAX = 2;

    public interface OnUpdateListener {
        void onChecked(boolean hasUpdate);
    }

    private static volatile CollectUpdateChecker instance;

    /** 串行执行整轮检查 */
    private final ExecutorService checkExecutor = Executors.newSingleThreadExecutor();
    /** 单独线程池承载 spider 请求,便于超时后不阻塞检查线程 */
    private final ExecutorService fetchExecutor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean checking = new AtomicBoolean(false);

    private OnUpdateListener listener;

    private CollectUpdateChecker() {
    }

    public static CollectUpdateChecker get() {
        if (instance == null) {
            synchronized (CollectUpdateChecker.class) {
                if (instance == null) {
                    instance = new CollectUpdateChecker();
                }
            }
        }
        return instance;
    }

    public void setListener(OnUpdateListener listener) {
        this.listener = listener;
    }

    /**
     * 触发一轮检查(后台异步,重复调用会被忽略直到本轮结束)。
     *
     * @param force true=忽略 6 小时节流,强制重查
     */
    public void checkAll(boolean force) {
        checkAll(force, RETRY_MAX);
    }

    private void checkAll(final boolean force, final int retryLeft) {
        if (!checking.compareAndSet(false, true)) {
            Log.d(TAG, "check already running, skip");
            return;
        }
        checkExecutor.execute(new Runnable() {
            @Override
            public void run() {
                boolean retryScheduled = false;
                try {
                    boolean changed = doCheck(force);
                    if (changed) {
                        notifyChanged();
                    }
                    // 冷启动时源配置往往还没拉完,此时本轮必然查不到东西 —— 稍后重试,避免白跑一轮
                    if (!changed && retryLeft > 0 && !ApiConfig.get().isConfigLoaded()) {
                        retryScheduled = true;
                        mainHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                checking.set(false);
                                checkAll(force, retryLeft - 1);
                            }
                        }, RETRY_DELAY_MS);
                    }
                } catch (Throwable th) {
                    Log.e(TAG, "check failed", th);
                } finally {
                    if (!retryScheduled) {
                        checking.set(false);
                    }
                }
            }
        });
    }

    private boolean doCheck(boolean force) {
        // 源配置还没加载完,拿不到 spider,直接跳过(下次触发再查)
        if (!ApiConfig.get().isConfigLoaded()) {
            Log.d(TAG, "config not loaded yet, skip this round");
            return false;
        }
        List<VodCollect> list;
        try {
            list = RoomDataManger.getAllVodCollect();
        } catch (Throwable th) {
            Log.e(TAG, "read collect failed", th);
            return false;
        }
        if (list == null || list.isEmpty()) {
            return false;
        }

        boolean anyChanged = false;
        long now = System.currentTimeMillis();
        for (VodCollect collect : list) {
            if (collect == null || collect.sourceKey == null || collect.vodId == null) {
                continue;
            }
            if (!force && collect.lastCheckTime > 0 && (now - collect.lastCheckTime) < CHECK_INTERVAL) {
                continue;
            }
            EpisodeInfo info = fetchEpisodeInfo(collect.sourceKey, collect.vodId);
            if (info == null) {
                // 拉不到详情:保持原状,不改写基线,下次再试
                continue;
            }
            if (info.maxCount <= 1) {
                // 电影 / 单集:不参与更新检测,清掉可能的旧基线
                collect.lastEpisodeCount = 0;
                collect.hasUpdate = 0;
                collect.lastCheckTime = now;
                RoomDataManger.updateVodCollect(collect);
                continue;
            }
            if (collect.lastEpisodeCount <= 0) {
                // 首次建立基线(老收藏数据此前没记过集数),只记录不打扰
                collect.lastEpisodeCount = info.maxCount;
                collect.lastEpisodeName = info.lastName;
                collect.hasUpdate = 0;
            } else if (info.maxCount > collect.lastEpisodeCount) {
                collect.lastEpisodeCount = info.maxCount;
                collect.lastEpisodeName = info.lastName;
                collect.hasUpdate = 1;
                anyChanged = true;
                Log.d(TAG, "update found: " + collect.name + " -> " + info.maxCount + " 集");
            }
            collect.lastCheckTime = now;
            RoomDataManger.updateVodCollect(collect);
        }
        return anyChanged;
    }

    /**
     * 用收藏时记录的源重新拉一次详情,解析出"最新集数 / 最后一集名称"。
     * 失败或超时返回 null。
     */
    private EpisodeInfo fetchEpisodeInfo(String sourceKey, String vodId) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            return null;
        }
        Future<EpisodeInfo> future = null;
        try {
            future = fetchExecutor.submit(new java.util.concurrent.Callable<EpisodeInfo>() {
                @Override
                public EpisodeInfo call() throws Exception {
                    Spider sp = ApiConfig.get().getCSP(sourceBean);
                    List<String> ids = new ArrayList<>();
                    ids.add(vodId);
                    String json = sp.detailContent(ids);
                    if (json == null || json.trim().isEmpty()) {
                        return null;
                    }
                    AbsJson absJson = GsonUtil.get().fromJson(json, new TypeToken<AbsJson>() {
                    }.getType());
                    if (absJson == null) {
                        return null;
                    }
                    AbsXml absXml = absJson.toAbsXml();
                    if (absXml == null || absXml.movie == null
                            || absXml.movie.videoList == null || absXml.movie.videoList.isEmpty()) {
                        return null;
                    }
                    VodInfo vodInfo = new VodInfo();
                    vodInfo.setVideo(absXml.movie.videoList.get(0));
                    EpisodeInfo ei = new EpisodeInfo();
                    ei.maxCount = RoomDataManger.calcMaxEpisodeCount(vodInfo);
                    ei.lastName = RoomDataManger.calcLastEpisodeName(vodInfo);
                    return ei;
                }
            });
            return future.get(FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (Throwable th) {
            if (future != null) {
                future.cancel(true);
            }
            return null;
        }
    }

    private void notifyChanged() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onChecked(true);
                }
            }
        });
    }

    private static class EpisodeInfo {
        int maxCount;
        String lastName;
    }
}
