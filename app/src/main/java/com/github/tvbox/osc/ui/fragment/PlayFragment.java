package com.github.tvbox.osc.ui.fragment;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;

import com.blankj.utilcode.util.ColorUtils;
import com.blankj.utilcode.util.LogUtils;
import com.blankj.utilcode.util.RegexUtils;
import com.blankj.utilcode.util.ScreenUtils;
import com.blankj.utilcode.util.SpanUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.Subtitle;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.player.EXOmPlayer;
import com.github.tvbox.osc.player.MyVideoView;

import xyz.doikki.videoplayer.player.VideoView;
import com.github.tvbox.osc.player.TrackInfo;
import com.github.tvbox.osc.player.TrackInfoBean;
import com.github.tvbox.osc.player.controller.VodController;
import com.github.tvbox.osc.server.RemoteServer;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.adapter.ParseAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.PlayingControlDialog;
import com.github.tvbox.osc.ui.dialog.PlayingControlRightDialog;
import com.github.tvbox.osc.ui.dialog.SearchSubtitleDialog;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.dialog.SubtitleDialog;
import com.github.tvbox.osc.util.AdBlocker;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.VideoParseRuler;
import com.github.tvbox.osc.util.VodTrace;
import com.github.tvbox.osc.util.thunder.Jianpian;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.text.Cue;
import com.gyf.immersionbar.BarHide;
import com.gyf.immersionbar.ImmersionBar;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.enums.PopupPosition;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.HttpHeaders;
import com.lzy.okgo.model.Response;
import com.obsez.android.lib.filechooser.ChooserDialog;
import com.orhanobut.hawk.Hawk;

import org.apache.commons.lang3.StringUtils;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import me.jessyan.autosize.AutoSize;
import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.ProgressManager;

public class PlayFragment extends BaseLazyFragment {
    private MyVideoView mVideoView;
    private TextView mPlayLoadTip;
    private ImageView mPlayLoadErr;
    private com.airbnb.lottie.LottieAnimationView mPlayLoading;
    private VodController mController;
    private SourceViewModel sourceViewModel;
    private Handler mHandler;
    /** 主线程 handler:起播超时提示 / m3u8去广告超时的兜底调度 */
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    /**
     * m3u8 去广告需要额外整份下载一次 m3u8,大文件或慢 CDN 时这一趟很贵(实测 401KB 多花 4.7s)。
     * 给个硬上限:超时就取消去广告,直接拿原地址起播 —— 宁可带广告也别让人干等。
     */
    private static final long M3U8_PURIFY_TIMEOUT_MS = 3000L;
    /** 起播后多久还没出画面,就给一次文字反馈(此前 UI 只有一个转圈,用户分不清是慢还是死) */
    private static final long SLOW_START_TIP_MS = 15000L;
    /**
     * 起播超时提醒:到点还没出画面就再提醒一次换线路,但【不停止播放】——
     * 网络慢时源最终可能播出来,停掉就前功尽弃。提示交给 15s 那一档,这里只加强语气。
     */
    private static final long START_HARD_TIMEOUT_MS = 25000L;
    /** 起播期间的心跳间隔:卡住时每 4s 打一行,日志里能看出守护是不是活着、卡了多久 */
    private static final long START_HEARTBEAT_MS = 4000L;
    private Runnable mPurifyTimeoutTask;
    private Runnable mSlowStartTipTask;
    private Runnable mStartHardTimeoutTask;
    private Runnable mStartHeartbeatTask;
    private long startWatchBeginMs = 0L;
    /**
     * 本次点播的令牌。每次 play() 递增,只有携带当前令牌的解析结果才会被采纳,
     * 用来丢弃迟到的旧结果(详见 mObserverPlayResult 中的说明)。
     */
    private int playToken = 0;

    /**
     * 去广告"白跑一趟"的域名黑名单:某个 m3u8 下载完却没识别出广告(removeMinorityUrl 返回 null),
     * 说明这趟下载纯属浪费(播放器随后还要再下一次),记下 host,下次直接跳过去广告。
     */
    private static final java.util.Set<String> PURIFY_NO_AD_HOSTS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * 中转(去广告本地代理 / 去BOM远程代理)播放失败的域名黑名单。
     * 这类源的 m3u8 是带签名的一次性地址,我们把它下载下来再从 127.0.0.1 或第三方
     * 中转出去,播放器再拉分片时签名往往已失效 → 必报错;而直连原始地址一切正常。
     * 持久化是必须的:否则每次重启 App 都要先失败一次才学得会。
     */
    private static final String HAWK_KEY_PURIFY_BROKEN = "PURIFY_BROKEN_HOSTS";
    private static final java.util.Set<String> PURIFY_BROKEN_HOSTS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 本次起播若走了中转,记录被中转的那个真实域名(用于失败时定位) */
    private String lastProxySourceHost = null;
    /** 该域名已被判定"经不起中转",本次强制直连(连去BOM那条兜底也不再套) */
    private boolean forceDirectPlay = false;

    private void markProxyBroken(String host) {
        if (host == null || host.isEmpty()) return;
        VodTrace.mark("PROXY_PLAY_BROKEN", "中转播放失败,该域名后续一律直连 host=" + host);
        if (PURIFY_BROKEN_HOSTS.add(host)) {
            // 首次发现该 host 经不起中转,给用户一个直白的提示:之前那次失败是因为走了中转,
            // 接下来这个域名会跳过中转直接连,避免用户困惑"为什么播放失败了还要等"
            try {
                ToastUtils.showShort("代理播放失败,正在重试尝试直连播放");
            } catch (Throwable ignored) {
            }
            try {
                Hawk.put(HAWK_KEY_PURIFY_BROKEN, new java.util.ArrayList<>(PURIFY_BROKEN_HOSTS));
            } catch (Throwable th) {
                // 持久化失败只影响"下次是否记得住",不影响本次播放
            }
        }
    }

    private static void loadProxyBrokenHosts() {
        try {
            java.util.List<String> saved = Hawk.get(HAWK_KEY_PURIFY_BROKEN, null);
            if (saved != null) PURIFY_BROKEN_HOSTS.addAll(saved);
        } catch (Throwable th) {
            // 读不到就当没有,退化为运行期学习
        }
    }

    /** 去广告"下不动"的域名黑名单:m3u8 连 3 秒都下不完的 CDN。
     * 重试时若还去下一趟,每次都白白多等 3 秒才拿到同一个地址。
     */
    private static final java.util.Set<String> PURIFY_SLOW_HOSTS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static String hostOf(String url) {
        if (url == null) return "";
        int s = url.indexOf("://");
        if (s < 0) return "";
        int e = url.indexOf('/', s + 3);
        return e < 0 ? url.substring(s + 3) : url.substring(s + 3, e);
    }
    /** 去广告流程序号:切线路/换集后旧请求的回调一律作废 */
    private int purifySeq = 0;
    /** 当前这一轮去广告是否已经完成起播(超时回调与 OkGo 回调会竞争,只放行第一个) */
    private boolean purifySettled = false;

    /** 取消本轮 m3u8 去广告的超时兜底 */
    private void cancelPurifyTimeout() {
        if (mPurifyTimeoutTask != null) {
            mUiHandler.removeCallbacks(mPurifyTimeoutTask);
            mPurifyTimeoutTask = null;
        }
    }

    /**
     * 去广告流程的唯一出口:一轮只起播一次。
     * 超时任务、m3u8-1 回调、m3u8-2(跳转)回调三者会互相竞争,这里用 seq + settled 双保险拦住重复起播。
     */
    private void purifyStartPlay(int token, String url, HashMap<String, String> headers) {
        if (token != purifySeq || purifySettled) return;
        purifySettled = true;
        cancelPurifyTimeout();
        startPlayUrl(url, headers);
    }

    /** 取消起播守护:文字提示 + 硬超时 + 心跳 */
    private void cancelStartWatchers() {
        if (mSlowStartTipTask != null) {
            mUiHandler.removeCallbacks(mSlowStartTipTask);
            mSlowStartTipTask = null;
        }
        if (mStartHardTimeoutTask != null) {
            mUiHandler.removeCallbacks(mStartHardTimeoutTask);
            mStartHardTimeoutTask = null;
        }
        if (mStartHeartbeatTask != null) {
            mUiHandler.removeCallbacks(mStartHeartbeatTask);
            mStartHeartbeatTask = null;
        }
    }

    /** 起播后是否还没出画面(判定放宽:只看首帧,不依赖播放器状态码,避免状态取值意外把守护挡掉) */
    private boolean isStillWaitingForFrame() {
        return isAdded() && mVideoView != null && !firstFrameLogged;
    }

    /**
     * 起播守护:8s 给一次"源可能有问题"的文字反馈,25s 直接判死报错。
     * 此前 UI 只有一个转圈,用户分不清"源慢"还是"已经死了",只能干等或盲切线路。
     */
    private void scheduleStartWatchers() {
        cancelStartWatchers();
        startWatchBeginMs = System.currentTimeMillis();
        // 心跳:卡住期间持续输出,日志里一看就知道守护活着、已经卡了多久、播放器什么状态
        mStartHeartbeatTask = new Runnable() {
            @Override
            public void run() {
                if (!isStillWaitingForFrame()) {
                    mStartHeartbeatTask = null;
                    return;
                }
                VodTrace.mark("PREPARING_WAIT", "已等待 "
                        + (System.currentTimeMillis() - startWatchBeginMs) + "ms state="
                        + playStateName(mVideoView.getCurrentPlayerState())
                        + " url=" + mCurrentUrl);
                mUiHandler.postDelayed(this, START_HEARTBEAT_MS);
            }
        };
        mUiHandler.postDelayed(mStartHeartbeatTask, START_HEARTBEAT_MS);
        mSlowStartTipTask = () -> {
            mSlowStartTipTask = null;
            if (!isStillWaitingForFrame()) return;
            VodTrace.mark("SLOW_START_TIP", "起播超过 " + SLOW_START_TIP_MS
                    + "ms 仍未出画面,提示用户可换线路");
            // 屏幕提示 + Toast 双发:TV 上 Toast 可能被系统弱化,屏幕文字一定有
            setTip("视频加载较慢,可能是源的问题,建议切换线路或解析", true, false);
            ToastUtils.showShort("视频加载较慢,建议切换线路或解析");
        };
        mUiHandler.postDelayed(mSlowStartTipTask, SLOW_START_TIP_MS);
        mStartHardTimeoutTask = () -> {
            mStartHardTimeoutTask = null;
            if (!isStillWaitingForFrame()) return;
            VodTrace.mark("START_TIMEOUT", "起播超过 " + START_HARD_TIMEOUT_MS
                    + "ms 仍未出画面,提醒用户换线路(不停止播放,源可能只是慢) url=" + mCurrentUrl);
            // 只提醒、不动播放器:万一是网络慢,源后面还是能播出来的,停掉就前功尽弃了
            setTip("已等待较久,建议切换线路或解析;当前线路仍在尝试加载", true, false);
            ToastUtils.showShort("已等待较久,建议切换线路或解析");
        };
        mUiHandler.postDelayed(mStartHardTimeoutTask, START_HARD_TIMEOUT_MS);
    }

    private final long videoDuration = -1;
    /**
     * 记录当前播放url
     */
    private String mCurrentUrl;
    private boolean mFullWindows;
    /**
     * 非全屏下的设置弹窗
     */
    private BasePopupView mPlayingControlDialog;
    /**
     * 全屏下的设置弹窗
     */
    private BasePopupView mPlayingControlRightDialog;
    /**
     * 视频播放出错时,自动切换另一个播放器,这个开关避免多次切换
     */
    boolean retriedSwitchPlayer = false;
    @Override
    protected int getLayoutResID() {
        return R.layout.activity_play;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE) {
            mController.mSubtitleView.setTextSize((int) event.obj);
        } else if (event.type == RefreshEvent.TYPE_BATTERY_CHANGE && mController.mMyBatteryView!=null){
            mController.mMyBatteryView.updateBattery((int) event.obj);
        }
    }

    @Override
    protected void init() {
        initView();
        initViewModel();
        initData();
    }

    public long getSavedProgress(String url) {
        int st = 0;
        try {
            st = mVodPlayerCfg.getInt("st");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        long skip = st * 1000L;
        Object theCache=CacheManager.getCache(MD5.string2MD5(url));
        if (theCache == null) {
            return skip;
        }
        long rec = 0;
        if (theCache instanceof Long) {
            rec = (Long) theCache;
        } else if (theCache instanceof String) {
            try {
                rec = Long.parseLong((String) theCache);
            } catch (NumberFormatException e) {
                // Cache value is not a valid long, use default
            }
        }
        return Math.max(rec, skip);
    }

    private void initView() {
        EventBus.getDefault().register(this);
        mHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(@NonNull Message msg) {
                switch (msg.what) {
                    case 100:
                        VodTrace.fail("JX_SNIFF_TIMEOUT", "嗅探满 20s 仍未抓到直链,放弃");
                        stopParse();
                        errorWithRetry("嗅探错误", false);
                        break;
                }
                return false;
            }
        });
        mVideoView = findViewById(R.id.mVideoView);
        mPlayLoadTip = findViewById(R.id.play_load_tip);
        mPlayLoading = findViewById(R.id.play_loading);
        mPlayLoadErr = findViewById(R.id.play_load_error);
        mController = new VodController(requireContext());
        mController.showParse(false);
        mController.setCanChangePosition(true);
        mController.setEnableInNormal(true);
        mController.setGestureEnabled(true);
        ProgressManager progressManager = new ProgressManager() {
            @Override
            public void saveProgress(String url, long progress) {
                CacheManager.save(MD5.string2MD5(url), progress);
            }

            @Override
            public long getSavedProgress(String url) {
                return PlayFragment.this.getSavedProgress(url);
            }
        };
        mVideoView.setProgressManager(progressManager);
        mController.setListener(new VodController.VodControlListener() {
            final DetailActivity activity = (DetailActivity) mActivity;
            @Override
            public void chooseSeries() {
                //activity中已处理
                activity.showAllSeriesDialog();
            }

            @Override
            public void playNext(boolean rmProgress) {
                String preProgressKey = progressKey;
                PlayFragment.this.playNext(rmProgress);
                if (rmProgress && preProgressKey != null)
                    CacheManager.delete(MD5.string2MD5(preProgressKey), 0);
            }

            @Override
            public void playPre() {
                PlayFragment.this.playPrevious();
            }

            @Override
            public void changeParse(ParseBean pb) {
                autoRetryCount = 0;
                doParse(pb);
            }

            @Override
            public void updatePlayerCfg() {
                mVodInfo.playerCfg = mVodPlayerCfg.toString();
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, mVodPlayerCfg));
            }

            @Override
            public void replay(boolean replay) {
                autoRetryCount = 0;
                play(replay);
            }

            @Override
            public void errReplay() {
                errorWithRetry("视频播放出错", false);
            }

            @Override
            public void selectSubtitle() {
                try {
                    selectMySubtitle();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void selectAudioTrack() {
                selectMyAudioTrack();
            }

            @Override
            public void prepared() {
                initSubtitleView();
            }

            @Override
            public void toggleFullScreen() {
                // 旧入口保留,行为同 toggleLandscapeFullScreen(默认就是横屏全屏)。
                // 之前由 Activity 内部根据视频宽高比自动选方向,容易把宽屏视频
                // (1920x808)留在竖屏导致上下大黑条,所以改成统一走横屏。
                toggleLandscapeFullScreen();
            }

            @Override
            public void toggleLandscapeFullScreen() {
                // ★ 强制横屏全屏(B 站"四角向外"图标):
                // 1) 先把 Activity 旋转到 SENSOR_LANDSCAPE,确保视频占满宽屏画布
                // 2) 再调 toggleFullPreview 把容器撑满可视区并隐藏详情面板
                // 之前 toggleFullScreen() 由 Activity 根据视频宽高比自动判断方向,
                // 1920x808 这种宽屏视频在竖屏会被压扁留大黑条,所以固定走横屏。
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                if (!activity.isFullWindows()) {
                    activity.toggleFullPreview();
                }
            }

            @Override
            public void togglePortraitFullScreen() {
                // ★ 强制竖屏全屏(B 站"上下双横线"图标):
                // Activity 保持 PORTRAIT 方向不动,只调 toggleFullPreview 把预览容器撑满。
                // 视频画面占满整个屏幕宽度,适合竖屏短视频(9:16/3:4)或用户想保留顶部状态栏
                // 习惯性操作的应用场景。
                //
                // 不再 post 延迟:PORTRAIT 模式下不会触发 SurfaceView 重建,无需等一帧。
                // 之前 post 延迟会引入 ~16ms 卡顿,反而造成点按不跟手。
                if (activity.getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                }
                if (!activity.isFullWindows()) {
                    activity.toggleFullPreview();
                }
            }

            @Override
            public void exit() {
                activity.onBackPressed();
            }

            @Override
            public void onHideBottom() {
                // 不要用 ImmersionBar 重新 init —— 它每次 .init() 都会按 fitsSystemWindows
                // 重设 content frame 的 paddingTop(=statusBarHeight=134px),
                // 把之前在 DetailActivity 里清掉的全屏布局 padding 又灌回来,
                // 表现:用户点击屏幕(触发 control bar 隐藏 → onHideBottom)后视频立刻下移,
                // 底部露出一条大黑边。hideSystemBars() 已经用原生 WindowInsetsController
                // 正确处理了系统栏隐藏,这里不需要 ImmersionBar 再插手。
            }

            @Override
            public void showSetting() {
                if (mFullWindows){
                    mPlayingControlRightDialog = new XPopup.Builder(activity)
                            .isViewMode(true)//改为view模式无法自动响应返回键操作,onBackPress时手动dismiss
                            .hasNavigationBar(false)
                            .popupHeight(ScreenUtils.getScreenHeight())
                            .popupPosition(PopupPosition.Right)
                            .asCustom(new PlayingControlRightDialog(activity,mController,mVideoView));
                    mPlayingControlRightDialog.show();
                }else {
                    mPlayingControlDialog = new XPopup.Builder(activity)
                            .isViewMode(true)
                            .hasNavigationBar(false)
                            .asCustom(new PlayingControlDialog(activity,mController,mVideoView));
                    mPlayingControlDialog.show();
                }
            }

            @Override
            public void pip() {
                activity.enterPip();
            }

            @Override
            public void showParseRoot(boolean show, ParseAdapter adapter) {
                DetailActivity activity = (DetailActivity)mActivity;
                activity.showParseRoot(show,adapter);
            }

            @Override
            public void onCastClick() {
                // 投屏功能由 DetailActivity 处理
                if (mActivity instanceof DetailActivity) {
                    ((DetailActivity) mActivity).onCastClick();
                }
            }
        });
        mVideoView.setVideoController(mController);
        // 视频准备好/开始播放时,通知 Activity 按视频实际比例重算预览区高度
        mVideoView.addOnStateChangeListener(new VideoView.SimpleOnStateChangeListener() {
            @Override
            public void onPlayStateChanged(int playState) {
                android.util.Log.d("WATV_PLAY", "PlayFragment onPlayStateChanged="
                        + playState + " name=" + playStateName(playState)
                        + " vv@" + System.identityHashCode(mVideoView));
                VodTrace.mark("PLAYER_STATE", playStateName(playState));
                if (playState == VideoView.STATE_PREPARING || playState == VideoView.STATE_BUFFERING) {
                    // 冗余挂载(幂等):start() 之后挂过一次,这里再挂一次,
                    // 保证只要播放器报了 PREPARING,守护就一定在场
                    scheduleStartWatchers();
                }
                if (playState == VideoView.STATE_BUFFERING) {
                    // 一直停在这里 = 源服务器推流慢 / 带宽不足,属于源的问题
                    VodTrace.mark("PLAYER_BUFFERING", "开始缓冲,等数据");
                } else if (playState == VideoView.STATE_ERROR) {
                    VodTrace.fail("PLAYER_ERROR", "播放器报错");
                    // 走了中转(去广告本地代理 / 去BOM远程代理)却没出画面:说明这个源的地址
                    // 经不起中转(签名地址有时效,中转后再取就失效)。记下真实域名,
                    // 下次直接直连播放,不用用户手动切线路碰运气。
                    if (!firstFrameLogged) markProxyBroken(lastProxySourceHost);
                    cancelStartWatchers();
                } else if (playState == VideoView.STATE_PLAYING && !firstFrameLogged) {
                    firstFrameLogged = true;
                    cancelStartWatchers();
                    VodTrace.mark("FIRST_FRAME", "出画面 ✅ 到这里就是用户感知的'能看了'");
                } else if (playState == VideoView.STATE_PLAYBACK_COMPLETED
                        || playState == VideoView.STATE_START_ABORT) {
                    cancelStartWatchers();
                }
                if (playState == VideoView.STATE_PREPARED || playState == VideoView.STATE_PLAYING) {
                    if (mActivity instanceof DetailActivity) {
                        mActivity.runOnUiThread(() -> ((DetailActivity) mActivity).applyPreviewPlayerRatio());
                    }
                }
            }
        });
    }

    private String playStateName(int s) {
        switch (s) {
            case VideoView.STATE_IDLE: return "IDLE";
            case VideoView.STATE_PREPARING: return "PREPARING";
            case VideoView.STATE_PREPARED: return "PREPARED";
            case VideoView.STATE_PLAYING: return "PLAYING";
            case VideoView.STATE_PAUSED: return "PAUSED";
            case VideoView.STATE_BUFFERING: return "BUFFERING";
            case VideoView.STATE_BUFFERED: return "BUFFERED";
            case VideoView.STATE_PLAYBACK_COMPLETED: return "COMPLETED";
            case VideoView.STATE_ERROR: return "ERROR";
            case VideoView.STATE_START_ABORT: return "START_ABORT";
            default: return "UNKNOWN(" + s + ")";
        }
    }

    public boolean hideAllDialogSuccess(){
        if (mPlayingControlRightDialog!=null && mPlayingControlRightDialog.isShow()){
            mPlayingControlRightDialog.dismiss();
            return true;
        }
        if (mPlayingControlDialog!=null && mPlayingControlDialog.isShow()){
            mPlayingControlDialog.dismiss();
            return true;
        }
        return false;
    }

    /**
     * activity返回/点击播放器切换全屏操作等
     *
     * @param forceLandscape 进入全屏时如果为 true,无论视频宽高比都强制横屏(横屏全屏按钮);
     *                       如果为 false(默认)且视频宽>高,Activity 自动旋转到横屏
     *                       —— 这正是旧逻辑被吐槽的"竖屏按钮点了却变横屏"的根因。
     */
    public void changedLandscape(boolean fullWindows, boolean forceLandscape) {
        mFullWindows = fullWindows;
        if (fullWindows){
            int[] size = mVideoView.getVideoSize();
            int width = size[0];
            int height = size[1];
            if (forceLandscape) {
                // ★ 用户明确点了横屏全屏按钮:Activity 强制 SENSOR_LANDSCAPE,
                // 不再被视频宽高比牵着走。
                mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            } else if (mActivity.getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                // ★ 用户明确点了竖屏全屏按钮:即使视频是宽屏(1920x808 这种),
                // 也尊重用户意图,保持 PORTRAIT 不动。
                // 之前这里会被 width>height 强转横屏,导致竖屏全屏按钮"有概率"变成横屏。
                // 不做任何 setRequestedOrientation 调用。
                android.util.Log.d("TVBoxDiag", "changedLandscape: respect user PORTRAIT choice, skip rotation");
            } else if (width > height) {
                // 进入全屏前已经是 LANDSCAPE/SENSOR_LANDSCAPE 且视频是宽屏:保持当前方向
                mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }
            // 竖屏视频(width<=height)且用户在 LANDSCAPE:不主动改方向,沿用当前

            // 进入全屏:用 Android 原生 WindowInsetsController 真正隐藏状态栏+导航栏,
            // ImmersionBar 的 hideBar(FLAG_HIDE_BAR) 在某些 ROM / Android 14 上并不真生效,
            // 会留下系统 UI 悬浮在视频上(尤其是状态栏和手势条),导致视频"上下被吃掉一段"看着不居中。
            hideSystemBars();
            // BaseActivity.initStatusBar 用 ImmersionBar 强制设了 statusBarColor=@color/bili_bg_card,
            // 单纯隐藏状态栏在某些 ROM 上会被该 background 顶回显示。这里把 statusBarColor / navigationBarColor
            // 显式设为透明,让"隐藏"真正生效。退出全屏时再恢复成 bili_bg_card。
            try {
                mActivity.getWindow().setStatusBarColor(0);
                mActivity.getWindow().setNavigationBarColor(0);
            } catch (Throwable ignore) {}
        }else {//非全屏统一设置竖屏,activity处理为小的预览尺寸
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

            // 退出全屏:恢复系统栏 + 状态栏背景色
            showSystemBars();
            try {
                mActivity.getWindow().setStatusBarColor(
                        androidx.core.content.ContextCompat.getColor(
                                mActivity, com.github.tvbox.osc.R.color.bili_bg_card));
                mActivity.getWindow().setNavigationBarColor(
                        androidx.core.content.ContextCompat.getColor(
                                mActivity, com.github.tvbox.osc.R.color.bili_bg_card));
            } catch (Throwable ignore) {}

            ImmersionBar.with(mActivity)
                    .hideBar(BarHide.FLAG_SHOW_BAR)
                    .navigationBarColor(R.color.bili_bg_card)
                    .fitsSystemWindows(true)
                    .init();
        }

        mController.changedLandscape(fullWindows);
    }

    /**
     * 兼容旧调用:不指定 forceLandscape 时按"视频宽>高自动横屏"的旧行为走。
     * 但当 Activity 当前明确处于 PORTRAIT 状态(竖屏全屏按钮刚触发),尊重用户意图。
     */
    public void changedLandscape(boolean fullWindows) {
        boolean forceLandscape;
        if (fullWindows) {
            // Activity 已处于 PORTRAIT → 用户明确选竖屏,不要被视频尺寸覆盖
            forceLandscape = mActivity.getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        } else {
            forceLandscape = false;
        }
        changedLandscape(fullWindows, forceLandscape);
    }

    /**
     * 焦点恢复时(从 PIP 切回 / 从后台回前台 / 旋转后)重新 apply 全屏/非全屏状态。
     * Android 14 在 ConfigurationChanged 时会重置 WindowInsetsController,需要在 focus 恢复时再隐藏一次。
     * 见 onResume() 内的实现。
     */

    //设置字幕
    void setSubtitle(String path) {
        if (path != null && path.length() > 0) {
            // 设置字幕
            mController.mSubtitleView.setVisibility(View.GONE);
            mController.mSubtitleView.setSubtitlePath(path);
            mController.mSubtitleView.setVisibility(View.VISIBLE);
        }
    }

    void selectMySubtitle() throws Exception {
        SubtitleDialog subtitleDialog = new SubtitleDialog(getActivity());
        subtitleDialog.setSubtitleViewListener(new SubtitleDialog.SubtitleViewListener() {
            @Override
            public void setTextSize(int size) {
                mController.mSubtitleView.setTextSize(size);
            }

            @Override
            public void setSubtitleDelay(int milliseconds) {
                mController.mSubtitleView.setSubtitleDelay(milliseconds);
            }

            @Override
            public void selectInternalSubtitle() {
                selectMyInternalSubtitle();
            }

            @Override
            public void setTextStyle(int style) {
                setSubtitleViewTextStyle(style);
            }

            @Override
            public void subtitleOpen(boolean b) {
                mController.openSubtitle(b);
            }
        });
        subtitleDialog.setSearchSubtitleListener(new SubtitleDialog.SearchSubtitleListener() {
            @Override
            public void openSearchSubtitleDialog() {
                SearchSubtitleDialog searchSubtitleDialog = new SearchSubtitleDialog(getActivity());
                searchSubtitleDialog.setSubtitleLoader(new SearchSubtitleDialog.SubtitleLoader() {
                    @Override
                    public void loadSubtitle(Subtitle subtitle) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                String zimuUrl = subtitle.getUrl();
                                LOG.i("Remote Subtitle Url: " + zimuUrl);
                                setSubtitle(zimuUrl);//设置字幕
                                searchSubtitleDialog.dismiss();
                            }
                        });
                    }
                });
                if (mVodInfo.playFlag.contains("Ali") || mVodInfo.playFlag.contains("parse")) {
                    searchSubtitleDialog.setSearchWord(mVodInfo.playNote);
                } else {
                    searchSubtitleDialog.setSearchWord(mVodInfo.name);
                }
                searchSubtitleDialog.show();
            }
        });
        subtitleDialog.setLocalFileChooserListener(new SubtitleDialog.LocalFileChooserListener() {
            @Override
            public void openLocalFileChooserDialog() {
                new ChooserDialog(getActivity(),R.style.FileChooser)
                        .withFilter(false, false, "srt", "ass", "scc", "stl", "ttml")
                        .withStartFile("/storage/emulated/0/Download")
                        .withChosenListener(new ChooserDialog.Result() {
                            @Override
                            public void onChoosePath(String path, File pathFile) {
                                LOG.i("Local Subtitle Path: " + path);
                                setSubtitle(path);//设置字幕
                            }
                        })
                        .build()
                        .show();
            }
        });
        subtitleDialog.show();
    }

    @SuppressLint("UseCompatLoadingForColorStateLists")
    void setSubtitleViewTextStyle(int style) {
        if (style == 0) {
            mController.mSubtitleView.setTextColor(getContext().getResources().getColorStateList(R.color.color_FFFFFF));
        } else if (style == 1) {
            mController.mSubtitleView.setTextColor(getContext().getResources().getColorStateList(R.color.color_FFB6C1));
        }
    }

    void selectMyAudioTrack() {
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();

        TrackInfo trackInfo = null;
        if (mediaPlayer instanceof EXOmPlayer) {
            trackInfo = ((EXOmPlayer) mediaPlayer).getTrackInfo();
        }

        if (trackInfo == null) {
            ToastUtils.showShort("没有音轨");
            return;
        }
        List<TrackInfoBean> bean = trackInfo.getAudio();
        if (bean.size() < 1) return;
        SelectDialog<TrackInfoBean> dialog = new SelectDialog<>(getActivity());
        dialog.setTip("切换音轨");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<TrackInfoBean>() {
            @Override
            public void click(TrackInfoBean value, int pos) {
                try {
                    for (TrackInfoBean audio : bean) {
                        audio.selected = audio.trackId == value.trackId;
                    }
                    mediaPlayer.pause();
                    long progress = mediaPlayer.getCurrentPosition();
                    ((EXOmPlayer) mediaPlayer).selectExoTrack(value);
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mediaPlayer.seekTo(progress);
                            mediaPlayer.start();
                        }
                    }, 800);
                    dialog.dismiss();
                } catch (Exception e) {
                    LOG.e("切换音轨出错");
                }
            }

            @Override
            public String getDisplay(TrackInfoBean val) {
                String name = val.name.replace("AUDIO,", "");
                name = name.replace("N/A,", "");
                name = name.replace(" ", "");
                return name + (TextUtils.isEmpty(val.language) ? "" : " " + val.language);
            }
        }, new DiffUtil.ItemCallback<TrackInfoBean>() {
            @Override
            public boolean areItemsTheSame(@NonNull @NotNull TrackInfoBean oldItem, @NonNull @NotNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }

            @Override
            public boolean areContentsTheSame(@NonNull @NotNull TrackInfoBean oldItem, @NonNull @NotNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }
        }, bean, trackInfo.getAudioSelected(false));
        dialog.show();
    }

    void selectMyInternalSubtitle() {
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        TrackInfo trackInfo = null;
        if (mediaPlayer instanceof EXOmPlayer) {
            trackInfo = ((EXOmPlayer)mediaPlayer).getTrackInfo();
        }

        if (trackInfo == null) {
            ToastUtils.showShort("没有内置字幕");
            return;
        }
        List<TrackInfoBean> bean = trackInfo.getSubtitle();
        if (bean.size() < 1) return;
        SelectDialog<TrackInfoBean> dialog = new SelectDialog<>(mActivity);
        dialog.setTip("切换内置字幕");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<TrackInfoBean>() {
            @Override
            public void click(TrackInfoBean value, int pos) {
                mController.mSubtitleView.setVisibility(View.VISIBLE);
                try {
                    for (TrackInfoBean subtitle : bean) {
                        subtitle.selected =subtitle.trackGroupId == value.trackGroupId && subtitle.trackId == value.trackId;
                    }
                    mediaPlayer.pause();
                    long progress = mediaPlayer.getCurrentPosition();
                    mController.mSubtitleView.destroy();
                    mController.mSubtitleView.clearSubtitleCache();
                    mController.mSubtitleView.isInternal = true;

                    ((EXOmPlayer)mediaPlayer).selectExoTrack(value);
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mediaPlayer.seekTo(progress);
                            mediaPlayer.start();
                            mController.startProgress();
                        }
                    }, 800);
                    dialog.dismiss();
                } catch (Exception e) {
                    LOG.e("切换内置字幕出错");
                }
            }

            @Override
            public String getDisplay(TrackInfoBean val) {
                return val.name + (TextUtils.isEmpty(val.language)? "": " " + val.language);
            }
        }, new DiffUtil.ItemCallback<TrackInfoBean>() {
            @Override
            public boolean areItemsTheSame(@NonNull @NotNull TrackInfoBean oldItem, @NonNull @NotNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }

            @Override
            public boolean areContentsTheSame(@NonNull @NotNull TrackInfoBean oldItem, @NonNull @NotNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }
        }, bean, trackInfo.getSubtitleSelected(false));
        dialog.show();
    }

    void setTip(String msg, boolean loading, boolean err) {
        if (!isAdded()) return;
        //影魔
        requireActivity().runOnUiThread(() -> {
            mPlayLoadTip.setText(msg);
            mPlayLoadTip.setVisibility(View.VISIBLE);
            mPlayLoadErr.setVisibility(err ? View.VISIBLE : View.GONE);
            if (loading) {
                mPlayLoading.setVisibility(View.VISIBLE);
                if (!mPlayLoading.isAnimating()) mPlayLoading.playAnimation();
            } else {
                mPlayLoading.setVisibility(View.GONE);
            }

            if ("视频播放出错".equals(msg)){
                if (!retriedSwitchPlayer){
                    ToastUtils.showShort("播放出错,正在尝试重连");
                    retriedSwitchPlayer = true;
                    mController.mPlayRetry.performClick();
                }
            }
        });
    }

    void hideTip() {
        mPlayLoadTip.setVisibility(View.GONE);
        mPlayLoading.cancelAnimation();
        mPlayLoading.setVisibility(View.GONE);
        mPlayLoadErr.setVisibility(View.GONE);
    }

    void errorWithRetry(String err, boolean finish) {
        VodTrace.fail("ERROR", err + " finish=" + finish + " autoRetryCount=" + autoRetryCount);
        if (!autoRetry() && isAdded()) {
            requireActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (finish) {
                        ToastUtils.showShort(err);
                    } else {
                        setTip(err, false, true);
                    }
                }
            });
        }
    }

    private String removeMinorityUrl(String tsUrlPre, String m3u8content) {
        if (!m3u8content.startsWith("#EXTM3U")) return null;
        String linesplit = "\n";
        if (m3u8content.contains("\r\n"))
            linesplit = "\r\n";
        String[] lines = m3u8content.split(linesplit);

        HashMap<String, Integer> preUrlMap = new HashMap<>();
        for (String line : lines) {
            if (line.length() == 0 || line.charAt(0) == '#') {
                continue;
            }
            int ilast = line.lastIndexOf('.');
            if (ilast <= 4) {
                continue;
            }
            String preUrl = line.substring(0, ilast - 4);
            Integer cnt = preUrlMap.get(preUrl);
            if (cnt != null) {
                preUrlMap.put(preUrl, cnt + 1);
            } else {
                preUrlMap.put(preUrl, 1);
            }
        }
        if (preUrlMap.size() <= 1) return null;
        if (preUrlMap.size() > 5) return null;//too many different url, can not identify ads url
        int maxTimes = 0;
        String maxTimesPreUrl = "";
        for (Map.Entry<String, Integer> entry : preUrlMap.entrySet()) {
            if (entry.getValue() > maxTimes) {
                maxTimesPreUrl = entry.getKey();
                maxTimes = entry.getValue();
            }
        }
        if (maxTimes == 0) return null;

        boolean dealedExtXKey = false;
        for (int i = 0; i < lines.length; ++i) {
            if (!dealedExtXKey && lines[i].startsWith("#EXT-X-KEY")) {
                String keyUrl = StringUtils.substringBetween(lines[i], "URI=\"", "\"");
                if (keyUrl != null && !keyUrl.startsWith("http://") && !keyUrl.startsWith("https://")) {
                    String newKeyUrl;
                    if (keyUrl.charAt(0) == '/') {
                        int ifirst = tsUrlPre.indexOf('/', 9);//skip https://, http://
                        newKeyUrl = tsUrlPre.substring(0, ifirst) + keyUrl;
                    } else
                        newKeyUrl = tsUrlPre + keyUrl;
                    lines[i] = lines[i].replace("URI=\"" + keyUrl + "\"", "URI=\"" + newKeyUrl + "\"");
                }
                dealedExtXKey = true;
            }
            if (lines[i].length() == 0 || lines[i].charAt(0) == '#') {
                continue;
            }
            if (lines[i].startsWith(maxTimesPreUrl)) {
                if (!lines[i].startsWith("http://") && !lines[i].startsWith("https://")) {
                    if (lines[i].charAt(0) == '/') {
                        int ifirst = tsUrlPre.indexOf('/', 9);//skip https://, http://
                        lines[i] = tsUrlPre.substring(0, ifirst) + lines[i];
                    } else
                        lines[i] = tsUrlPre + lines[i];
                }
            } else {
                if (i > 0 && lines[i - 1].length() > 0 && lines[i - 1].charAt(0) == '#') {
                    lines[i - 1] = "";
                }
                lines[i] = "";
            }
        }
        return StringUtils.join(lines, linesplit);
    }

    void playUrl(String url, HashMap<String, String> headers) {
        mCurrentUrl = url;
        VodTrace.mark("PLAY_URL", "准备起播 url=" + url);
        // 源返回了空播放地址。此时若照常丢给播放器,它只会静默失败:
        // 不会有任何 onPlayStateChanged 回调,UI 就一直转圈到天荒地老 —— 这正是
        // "一直加载不出来"最长见的样子。必须在这里拦掉并给出可操作的提示。
        if (url == null || url.trim().isEmpty()) {
            VodTrace.fail("PLAY_URL_EMPTY", "源返回空播放地址,放弃起播(建议换线路/换源)");
            errorWithRetry("该线路无播放地址,请换线路", false);
            return;
        }
        if (!Hawk.get(HawkConfig.VIDEO_PURIFY, true)) {
            startPlayUrl(url, headers);
            return;
        }
        // 本地代理(127.0.0.1:9978)已在服务端把 m3u8 处理好,客户端再整份下载一遍做
        // 去广告纯属浪费 —— 401KB 的 m3u8 实测要多花 4.7 秒,且播放器随后还得再下一次。
        // 原写法匹配的是 "://127.0.0.1/"(隐含 80 端口),实际地址带 :9978 端口永远匹配不上,
        // 于是本地代理的 m3u8 反被拉去做了去广告,同一个源能差出 10 秒。去掉尾部斜杠即可。
        if (url.contains("://127.0.0.1") || !url.contains(".m3u8")) {
            startPlayUrl(url, headers);
            return;
        }
        // 这个域名之前下过 m3u8 却没识别出广告 —— 那一趟是纯浪费(播放器随后还要再下一次),
        // 慢 CDN 上可能就是好几秒,记下来之后直接跳过。
        if (PURIFY_NO_AD_HOSTS.contains(hostOf(url)) || PURIFY_SLOW_HOSTS.contains(hostOf(url))
                || PURIFY_BROKEN_HOSTS.contains(hostOf(url))) {
            VodTrace.mark("M3U8_PURIFY_SKIP", "该域名此前去广告无效/超时/中转失败,跳过本次下载 host=" + hostOf(url));
            // 已被判定"经不起中转":连去BOM那条兜底也别再套上去了
            forceDirectPlay = PURIFY_BROKEN_HOSTS.contains(hostOf(url));
            startPlayUrl(url, headers);
            return;
        }
        OkGo.getInstance().cancelTag("m3u8-1");
        OkGo.getInstance().cancelTag("m3u8-2");
        //remove ads in m3u8
        HttpHeaders hheaders = new HttpHeaders();
        if(headers != null){
            for (Map.Entry<String, String> s : headers.entrySet()) {
                hheaders.put(s.getKey(), s.getValue());
            }
        }

        // m3u8 去广告:起播前额外多一次完整 HTTP 往返,m3u8 大或服务器慢时这里很可观
        final long tM3u8 = VodTrace.now();
        VodTrace.mark("M3U8_PURIFY_REQ", "下载m3u8(去广告) url=" + url);
        // 去广告只是锦上添花,不能让它把起播卡死:
        // 超过上限就取消这一趟,直接拿原地址起播(宁可带广告,也别让人干等)
        final int purifyToken = ++purifySeq;
        purifySettled = false;
        cancelPurifyTimeout();
        mPurifyTimeoutTask = () -> {
            mPurifyTimeoutTask = null;
            if (purifyToken != purifySeq || purifySettled) return;
            VodTrace.mark("M3U8_PURIFY_TIMEOUT", "去广告耗时超过 " + M3U8_PURIFY_TIMEOUT_MS
                    + "ms,放弃去广告直接用原地址起播 url=" + url);
            // 这个 CDN 连 m3u8 都下不动,重试时别再陪它耗 3 秒了
            PURIFY_SLOW_HOSTS.add(hostOf(url));
            OkGo.getInstance().cancelTag("m3u8-1");
            OkGo.getInstance().cancelTag("m3u8-2");
            purifyStartPlay(purifyToken, url, headers);
        };
        mUiHandler.postDelayed(mPurifyTimeoutTask, M3U8_PURIFY_TIMEOUT_MS);
        OkGo.<String>get(url)
                .tag("m3u8-1")
                .headers(hheaders)
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(com.lzy.okgo.model.Response<String> response) {
                        String content = response.body();
                        VodTrace.markCost("M3U8_PURIFY_DONE", tM3u8,
                                "m3u8下载完成 size=" + (content == null ? 0 : content.length()) + "B");
                        if (content == null || !content.startsWith("#EXTM3U")) {
                            purifyStartPlay(purifyToken, url, headers);
                            return;
                        }

                        String[] lines = null;
                        if (content.contains("\r\n"))
                            lines = content.split("\r\n", 10);
                        else
                            lines = content.split("\n", 10);
                        String forwardurl = "";
                        boolean dealedFirst = false;
                        for (String line : lines) {
                            if (!"".equals(line) && line.charAt(0) != '#') {
                                if (dealedFirst) {
                                    //跳转行后还有内容，说明不需要跳转
                                    forwardurl = "";
                                    break;
                                }
                                if (line.endsWith(".m3u8") || line.contains(".m3u8?")) {
                                    if (line.startsWith("http://") || line.startsWith("https://")) {
                                        forwardurl = line;
                                    } else if (line.charAt(0)=='/' ) {
                                        int ifirst = url.indexOf('/', 9);//skip https://, http://
                                        forwardurl = url.substring(0, ifirst) + line;
                                    } else {
                                        int ilast = url.lastIndexOf('/');
                                        forwardurl = url.substring(0, ilast + 1) + line;
                                    }
                                }
                                dealedFirst = true;
                            }
                        }
                        if ("".equals(forwardurl)) {
                            int ilast = url.lastIndexOf('/');

                            RemoteServer.m3u8Content = removeMinorityUrl(url.substring(0, ilast + 1), content);
                            if (RemoteServer.m3u8Content == null) {
                                PURIFY_NO_AD_HOSTS.add(hostOf(url));
                                purifyStartPlay(purifyToken, url, headers);
                            } else {
                                purifyStartPlay(purifyToken, "http://127.0.0.1:" + RemoteServer.serverPort + "/m3u8", headers);
                                //Toast.makeText(getContext(), "已移除视频广告", Toast.LENGTH_SHORT).show();
                            }
                            return;
                        }
                        final String finalforwardurl = forwardurl;
                        OkGo.<String>get(forwardurl)
                                .tag("m3u8-2")
                                .headers(hheaders)
                                .execute(new AbsCallback<String>() {
                                    @Override
                                    public void onSuccess(com.lzy.okgo.model.Response<String> response) {
                                        String content = response.body();
                                        int ilast = finalforwardurl.lastIndexOf('/');
                                        RemoteServer.m3u8Content = removeMinorityUrl(finalforwardurl.substring(0, ilast + 1), content);

                                        if (RemoteServer.m3u8Content == null) {
                                            PURIFY_NO_AD_HOSTS.add(hostOf(finalforwardurl));
                                            purifyStartPlay(purifyToken, finalforwardurl, headers);
                                        } else {
                                            purifyStartPlay(purifyToken, "http://127.0.0.1:" + RemoteServer.serverPort + "/m3u8", headers);
                                            //Toast.makeText(getContext(), "已移除视频广告", Toast.LENGTH_SHORT).show();
                                        }
                                    }

                                    @Override
                                    public String convertResponse(okhttp3.Response response) throws Throwable {
                                        return response.body().string();
                                    }

                                    @Override
                                    public void onError(com.lzy.okgo.model.Response<String> response) {
                                        super.onError(response);
                                        purifyStartPlay(purifyToken, url, headers);
                                    }
                                });
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(com.lzy.okgo.model.Response<String> response) {
                        super.onError(response);
                        VodTrace.fail("M3U8_PURIFY_FAIL", "m3u8-1 下载失败 cost=" + (VodTrace.now() - tM3u8)
                                + "ms,直接原地址起播 msg="
                                + (response.getException() == null ? "" : response.getException().getMessage()));
                        purifyStartPlay(purifyToken, url, headers);
                    }
                });
    }

    void startPlayUrl(String url, HashMap<String, String> headers) {
        LOG.i("playUrl:" + url);
        // 本次是否走了中转(本地去广告代理 / 远程去BOM)。中转失败时要能溯源到真实域名。
        boolean viaProxy = url.contains("://127.0.0.1") || url.contains("unBom.php");
        lastProxySourceHost = viaProxy ? hostOf(mCurrentUrl) : null;
        if (autoRetryCount > 0 && !forceDirectPlay && url.contains(".m3u8")) {
            url = "http://home.jundie.top:666/unBom.php?m3u8=" + url;//尝试去bom头再次播放
            lastProxySourceHost = hostOf(mCurrentUrl);
        }
        String finalUrl = url;
        if (mActivity == null || !isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            stopParse();
            if (mVideoView != null) {
                android.util.Log.d("WATV_PLAY", "点播起播 startPlayUrl url=" + finalUrl
                        + " mVideoView@" + System.identityHashCode(mVideoView)
                        + " thread=" + Thread.currentThread().getName());
                mVideoView.release();

                if (finalUrl != null) {
                    try {
                        int playerType = mVodPlayerCfg.getInt("pl");
                        if (playerType >= 10) {
                            VodInfo.VodSeries vs = mVodInfo.seriesMap.get(mVodInfo.playFlag).get(mVodInfo.playIndex);
                            String playTitle = mVodInfo.name + " " + vs.name;
                            setTip("调用外部播放器" + PlayerHelper.getPlayerName(playerType) + "进行播放", true, false);
                            boolean callResult = false;
                            long progress = getSavedProgress(progressKey);
                            callResult = PlayerHelper.runExternalPlayer(playerType, requireActivity(), finalUrl, playTitle, playSubtitle, headers, progress);
                            setTip("调用外部播放器" + PlayerHelper.getPlayerName(playerType) + (callResult ? "成功" : "失败"), callResult, !callResult);
                            return;
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    hideTip();
                    PlayerHelper.updateCfg(mVideoView, mVodPlayerCfg);
                    mVideoView.setProgressKey(progressKey);
                    String ua = headers == null ? "-" : String.valueOf(headers.get("User-Agent"));
                    VodTrace.mark("PLAYER_SETURL", "pl=" + mVodPlayerCfg.optInt("pl", -1)
                            + " ua=" + ua + " hasHeaders=" + (headers != null)
                            + " setUrl=" + finalUrl);
                    if (headers != null) {
                        mVideoView.setUrl(finalUrl, headers);
                    } else {
                        mVideoView.setUrl(finalUrl);
                    }
                    VodTrace.mark("PLAYER_START", "调用 start(),等播放器回调");
                    mVideoView.start();
                    mController.resetSpeed();
                    // 起播后迟迟不出画面就先提示、再判死,别让用户对着转圈干等
                    scheduleStartWatchers();
                }
            }
        });
    }

    private void initSubtitleView() {
        TrackInfo trackInfo = null;
        if (mVideoView.getMediaPlayer() instanceof EXOmPlayer) {
            trackInfo = ((EXOmPlayer) (mVideoView.getMediaPlayer())).getTrackInfo();
            if (trackInfo != null && trackInfo.getSubtitle().size() > 0) {
                mController.mSubtitleView.hasInternal = true;
            }
            ((EXOmPlayer) (mVideoView.getMediaPlayer())).setOnTimedTextListener(new Player.Listener() {
                @Override
                public void onCues(@NonNull List<Cue> cues) {
                    if (cues.size() > 0) {
                        CharSequence ss = cues.get(0).text;
                        if (ss != null && mController.mSubtitleView.isInternal) {
                            com.github.tvbox.osc.subtitle.model.Subtitle subtitle = new com.github.tvbox.osc.subtitle.model.Subtitle();
                            subtitle.content = ss.toString();
                            mController.mSubtitleView.onSubtitleChanged(subtitle);
                        }
                    } else{
                        mController.mSubtitleView.onSubtitleChanged(null);
                    }
                }
            });
        }

        mController.mSubtitleView.bindToMediaPlayer(mVideoView.getMediaPlayer());
        mController.mSubtitleView.setPlaySubtitleCacheKey(subtitleCacheKey);
        String subtitlePathCache = (String) CacheManager.getCache(MD5.string2MD5(subtitleCacheKey));
        if (subtitlePathCache != null && !subtitlePathCache.isEmpty()) {
            mController.mSubtitleView.setSubtitlePath(subtitlePathCache);
        } else {
            if (playSubtitle != null && playSubtitle.length() > 0) {
                mController.mSubtitleView.setSubtitlePath(playSubtitle);
            } else {
                if (mController.mSubtitleView.hasInternal) {//有则使用内置字幕
                    mController.mSubtitleView.isInternal = true;
                    if (trackInfo != null && !trackInfo.getSubtitle().isEmpty()) {
                        List<TrackInfoBean> subtitleTrackList = trackInfo.getSubtitle();
                        int selectedIndex = trackInfo.getSubtitleSelected(true);
                        boolean hasCh =false;
                        for(TrackInfoBean subtitleTrackInfoBean : subtitleTrackList) {
                            String lowerLang = subtitleTrackInfoBean.language.toLowerCase();
                            if (lowerLang.contains("zh") || lowerLang.contains("ch")) {
                                hasCh=true;
                                if (selectedIndex != subtitleTrackInfoBean.trackId) {
                                    ((EXOmPlayer)(mVideoView.getMediaPlayer())).selectExoTrack(subtitleTrackInfoBean);
                                    break;
                                }
                            }
                        }
                        if(!hasCh){
                            ((EXOmPlayer)(mVideoView.getMediaPlayer())).selectExoTrack(subtitleTrackList.get(0));
                        }
                    }
                }
            }
        }
    }

    private void initViewModel() {
        loadProxyBrokenHosts();
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.playResult.observeForever(mObserverPlayResult);
    }

    private final Observer<JSONObject> mObserverPlayResult= new Observer<JSONObject>() {
        @Override
        public void onChanged(JSONObject info) {
            if (info != null) {
                // 关键闸门:解析可能耗时十几秒(慢源 + 单线程爬虫池排队),期间用户早换了线路/换集。
                // 旧结果迟到后被照单全收,就会出现"串线":上一条线路的 url 接管当前播放 ——
                // 表现正是刚出画面就被打断、随后报错。带令牌的过期结果一律丢弃。
                int seq = info.optInt("__seq", -1);
                if (seq != playToken) {
                    VodTrace.mark("SRC_PARSE_STALE", "丢弃过期的解析结果 seq=" + seq
                            + " 期望=" + playToken + " flag=" + info.optString("flag")
                            + " url=" + info.optString("url"));
                    return;
                }
                if (info.optInt("__fail", 0) == 1) {
                    VodTrace.fail("SRC_PARSE_RESULT", "源解析失败(该线路可能已失效)");
                    errorWithRetry("源未返回播放信息(该线路可能已失效),请换线路或换源", true);
                    return;
                }
                try {
                    // 打全量原始返回:url 为空时,靠这行判断到底是"源压根没给地址"
                    // 还是"字段名叫别的/取值代码写错",否则只能干猜。
                    String raw = info.toString();
                    VodTrace.mark("SRC_PARSE_RAW", raw.length() > 800 ? raw.substring(0, 800) + "...(截断)" : raw);
                    boolean parse = info.optString("parse", "1").equals("1");
                    boolean jx = info.optString("jx", "0").equals("1");
                    playSubtitle = info.optString("subt", /*"https://dash.akamaized.net/akamai/test/caption_test/ElephantsDream/ElephantsDream_en.vtt"*/"");
                    subtitleCacheKey = info.optString("subtKey", null);
                    String playUrl = info.optString("playUrl", "");
                    String flag = info.optString("flag");
                    String url = info.getString("url");
                    HashMap<String, String> headers = null;
                    webUserAgent = null;
                    webHeaderMap = null;
                    if (info.has("header")) {
                        try {
                            JSONObject hds = new JSONObject(info.getString("header"));
                            Iterator<String> keys = hds.keys();
                            while (keys.hasNext()) {
                                String key = keys.next();
                                if (headers == null) {
                                    headers = new HashMap<>();
                                }
                                headers.put(key, hds.getString(key));
                                if (key.equalsIgnoreCase("user-agent")) {
                                    webUserAgent = hds.getString(key).trim();
                                }
                            }
                            webHeaderMap = headers;
                        } catch (Throwable th) {

                        }
                    }
                    if (parse || jx) {
                        boolean userJxList = (playUrl.isEmpty() && ApiConfig.get().getVipParseFlags().contains(flag)) || jx;
                        VodTrace.mark("JX_PARSE_REQ", "需要二次解析 parse=" + parse + " jx=" + jx
                                + " 用解析列表=" + userJxList + " flag=" + flag + " playUrl=" + playUrl);
                        initParse(flag, userJxList, playUrl, url);
                    } else {
                        mController.showParse(false);
                        VodTrace.mark("DIRECT_PLAY", "直连播放,无需解析 url=" + (playUrl + url));
                        playUrl(playUrl + url, headers);
                    }
                } catch (Throwable th) {
                    LogUtils.e(th.toString());
                    VodTrace.fail("SRC_PARSE_RESULT", "处理播放信息异常 " + th);
                }
            } else {
                VodTrace.fail("SRC_PARSE_RESULT", "播放信息为空(null):源未返回可解析的播放信息");
                errorWithRetry("源未返回播放信息(该线路可能已失效),请换线路或换源", true);
            }
        }
    };

    public void setData(Bundle bundle) {
//        mVodInfo = (VodInfo) bundle.getSerializable("VodInfo");
        mVodInfo = App.getInstance().getVodInfo();
        sourceKey = bundle.getString("sourceKey");
        sourceBean = ApiConfig.get().getSource(sourceKey);
        initPlayerCfg();
        play(false);
    }

    private void initData() {
        /*Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {

        }*/
    }

    void initPlayerCfg() {
        try {
            mVodPlayerCfg = new JSONObject(mVodInfo.playerCfg);
        } catch (Throwable th) {
            mVodPlayerCfg = new JSONObject();
        }
        try {
            if (!mVodPlayerCfg.has("pl")) {
                int playerType = sourceBean.getPlayerType();
                // 已移除 IJK/系统播放器：内置播放统一使用 Exo(2)，外部播放器(>=10)保持不变
                if (playerType == -1 || playerType < 10) playerType = 2;
                mVodPlayerCfg.put("pl", playerType);
            }
            if (!mVodPlayerCfg.has("pr")) {
                mVodPlayerCfg.put("pr", Hawk.get(HawkConfig.PLAY_RENDER, 0));
            }
            if (!mVodPlayerCfg.has("sc")) {
                mVodPlayerCfg.put("sc", Hawk.get(HawkConfig.PLAY_SCALE, 0));
            }
            if (!mVodPlayerCfg.has("sp")) {
                mVodPlayerCfg.put("sp", 1.0f);
            }
            if (!mVodPlayerCfg.has("st")) {
                mVodPlayerCfg.put("st", 0);
            }
            if (!mVodPlayerCfg.has("et")) {
                mVodPlayerCfg.put("et", 0);
            }
        } catch (Throwable th) {

        }
        mController.setPlayerConfig(mVodPlayerCfg);
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null) {
            if (mController.onKeyEvent(event)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mVideoView != null) {
            mVideoView.pause();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mVideoView != null) {
            mVideoView.resume();
        }
        // Android 14 在 ConfigurationChanged(横竖屏切换)时可能重置 WindowInsetsController,
        // 焦点恢复时重新 apply 全屏隐藏,确保状态栏/导航栏不会"复活"显示。
        if (mFullWindows && mActivity != null) {
            mActivity.getWindow().getDecorView().post(this::hideSystemBars);
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (hidden) {
            if (mVideoView != null) {
                mVideoView.pause();
            }
        } else {
            if (mVideoView != null) {
                mVideoView.resume();
            }
        }
        super.onHiddenChanged(hidden);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 页面销毁:取消所有兜底定时器,避免回调打到已销毁的视图
        cancelStartWatchers();
        cancelPurifyTimeout();
        purifySeq++;
        purifySettled = false;
        //手动注销
        sourceViewModel.playResult.removeObserver(mObserverPlayResult);

        EventBus.getDefault().unregister(this);
        if (mVideoView != null) {
            mVideoView.release();
            mVideoView = null;
        }
        stopLoadWebView(true);
        stopParse();
        Thunder.stop(true);//停止磁力下载
        Jianpian.finish();//停止p2p下载
    }

    private VodInfo mVodInfo;
    private JSONObject mVodPlayerCfg;
    private String sourceKey;
    private SourceBean sourceBean;

    public void playNext(boolean isProgress) {
        boolean hasNext;
        if (mVodInfo == null || mVodInfo.seriesMap.get(mVodInfo.playFlag) == null) {
            hasNext = false;
        } else {
            hasNext = mVodInfo.playIndex + 1 < mVodInfo.seriesMap.get(mVodInfo.playFlag).size();
        }
        if (!hasNext) {
            ToastUtils.showShort("已经是最后一集了!");
            return;
        } else {
            mVodInfo.playIndex++;
        }
        play(false);
    }

    public void playPrevious() {
        boolean hasPre = true;
        if (mVodInfo == null || mVodInfo.seriesMap.get(mVodInfo.playFlag) == null) {
            hasPre = false;
        } else {
            hasPre = mVodInfo.playIndex - 1 >= 0;
        }
        if (!hasPre) {
            ToastUtils.showShort("已经是第一集了!");
            return;
        }
        mVodInfo.playIndex--;
        play(false);
    }

    private int autoRetryCount = 0;

    boolean autoRetry() {
        if (loadFoundVideoUrls != null && loadFoundVideoUrls.size() > 0) {
            autoRetryFromLoadFoundVideoUrls();
            return true;
        }
        if (autoRetryCount < 1) {
            autoRetryCount++;
            play(false);
            return true;
        } else {
            autoRetryCount = 0;
            return false;
        }
    }

    void autoRetryFromLoadFoundVideoUrls() {
        String videoUrl = loadFoundVideoUrls.poll();
        HashMap<String, String> header = loadFoundVideoUrlsHeader.get(videoUrl);
        playUrl(videoUrl, header);
    }

    void initParseLoadFound() {
        loadFoundCount.set(0);
        loadFoundVideoUrls = new LinkedList<String>();
        loadFoundVideoUrlsHeader = new HashMap<String, HashMap<String, String>>();
    }

    public void play(boolean reset) {
        if (mVodInfo == null) return;
        VodInfo.VodSeries vs = mVodInfo.seriesMap.get(mVodInfo.playFlag).get(mVodInfo.playIndex);
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, mVodInfo.playIndex));
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH_NOTIFY, mVodInfo.name + "&&" + vs.name));
        String playTitleInfo = mVodInfo.name + " " + vs.name;
        // 点播链路耗时埋点起点,之后每一段都会打出 Δ 增量,用于定位"慢在哪一环"
        VodTrace.begin(playTitleInfo
                + " [源=" + (sourceBean == null ? "?" : sourceBean.getName())
                + " type=" + (sourceBean == null ? "?" : sourceBean.getType())
                + " 线路=" + mVodInfo.playFlag + " 集=" + mVodInfo.playIndex + "]");
        firstFrameLogged = false;
        // 版本自证:日志里出现这一行才说明装的是带起播守护的包
        VodTrace.mark("FEATURE", "起播守护:去广告超时=" + M3U8_PURIFY_TIMEOUT_MS
                + "ms 慢提示=" + SLOW_START_TIP_MS + "ms 超时提醒=" + START_HARD_TIMEOUT_MS
                + "ms(仅提醒不停止播放)");
        // 新一轮播放:清掉上一轮的兜底定时器,并让上一轮去广告的迟到回调作废
        cancelStartWatchers();
        cancelPurifyTimeout();
        purifySeq++;
        purifySettled = false;
        setTip("正在获取播放信息", true, false);
        mController.setTitle(playTitleInfo);

        stopParse();
        initParseLoadFound();
        // 兜底:确保直播已停播并释放解码器,避免两个播放器同时占用资源导致起播失败
        LiveFragment.stopLivePlayback();
        if (mVideoView != null) mVideoView.release();
        String subtitleCacheKey = mVodInfo.sourceKey + "-" + mVodInfo.id + "-" + mVodInfo.playFlag + "-" + mVodInfo.playIndex + "-" + vs.name + "-subt";
        String progressKey = mVodInfo.sourceKey + mVodInfo.id + mVodInfo.playFlag + mVodInfo.playIndex + vs.name;
        // 同步给字段 this.progressKey:startPlayUrl 里 setProgressKey/getSavedProgress 用的是字段,
        // 否则字段一直是 null,所有集进度都存到同一个 null key 下,切集就会读出上一集的进度
        this.progressKey = progressKey;
        //重新播放清除现有进度
        if (reset) {
            CacheManager.delete(MD5.string2MD5(progressKey), 0);
            CacheManager.delete(MD5.string2MD5(subtitleCacheKey), 0);
        }
        if (Jianpian.isJpUrl(vs.url)) {//荐片地址特殊判断
            String jp_url = vs.url;
            mController.showParse(false);
            if (vs.url.startsWith("tvbox-xg:")) {
                playUrl(Jianpian.JPUrlDec(jp_url.substring(9)), null);
            } else {
                playUrl(Jianpian.JPUrlDec(jp_url), null);
            }
            return;
        }
        if (Thunder.play(vs.url, new Thunder.ThunderCallback() {
            @Override
            public void status(int code, String info) {
                if (code < 0) {
                    setTip(info, false, true);
                } else {
                    setTip(info, true, false);
                }
            }

            @Override
            public void list(Map<Integer, String> urlMap) {
            }

            @Override
            public void play(String url) {
                playUrl(url, null);
            }
        })) {
            mController.showParse(false);
            return;
        }
        // 新一轮点播:递增令牌,此前所有在飞的解析结果即便返回也会被丢弃
        playToken++;
        forceDirectPlay = false;
        sourceViewModel.getPlay(playToken, sourceKey, mVodInfo.playFlag, progressKey, vs.url, subtitleCacheKey);
    }

    private String playSubtitle;
    private String subtitleCacheKey;
    private String progressKey;
    private String parseFlag;
    /** 本次二次解析(嗅探/json解析)的开始时刻,用于打出该段自身耗时 */
    private long jxStartMs = 0L;
    /** 首帧是否已打过点(PLAYING 会在每次恢复播放时重复触发,只记第一次) */
    private boolean firstFrameLogged = false;
    private String webUrl;
    private String webUserAgent;
    private Map<String, String> webHeaderMap;

    private void initParse(String flag, boolean useParse, String playUrl, final String url) {
        parseFlag = flag;
        webUrl = url;
        ParseBean parseBean = null;
        mController.showParse(useParse);
        if (useParse) {
            parseBean = ApiConfig.get().getDefaultParse();
        } else {
            if (playUrl.startsWith("json:")) {
                parseBean = new ParseBean();
                parseBean.setType(1);
                parseBean.setUrl(playUrl.substring(5));
            } else if (playUrl.startsWith("parse:")) {
                String parseRedirect = playUrl.substring(6);
                for (ParseBean pb : ApiConfig.get().getParseBeanList()) {
                    if (pb.getName().equals(parseRedirect)) {
                        parseBean = pb;
                        break;
                    }
                }
            }
            if (parseBean == null) {
                parseBean = new ParseBean();
                parseBean.setType(0);
                parseBean.setUrl(playUrl);
            }
        }
        doParse(parseBean);
    }

    JSONObject jsonParse(String input, String json) throws JSONException {
        JSONObject jsonPlayData = new JSONObject(json);
        //小窗版解析方法改到这了  之前那个位置data解析无效
        String url;
        if (jsonPlayData.has("data")) {
            url = jsonPlayData.getJSONObject("data").getString("url");
        } else {
            url = jsonPlayData.getString("url");
        }
        if (url.startsWith("//")) {
            url = "http:" + url;
        }
        if (!url.startsWith("http")) {
            return null;
        }
        JSONObject headers = new JSONObject();
        String ua = jsonPlayData.optString("user-agent", "");
        if (ua.trim().length() > 0) {
            headers.put("User-Agent", " " + ua);
        }
        String referer = jsonPlayData.optString("referer", "");
        if (referer.trim().length() > 0) {
            headers.put("Referer", " " + referer);
        }
        JSONObject taskResult = new JSONObject();
        taskResult.put("header", headers);
        taskResult.put("url", url);
        return taskResult;
    }

    void stopParse() {
        mHandler.removeMessages(100);
        stopLoadWebView(false);
        OkGo.getInstance().cancelTag("json_jx");
        if (parseThreadPool != null) {
            try {
                parseThreadPool.shutdown();
                parseThreadPool = null;
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    ExecutorService parseThreadPool;

    private void doParse(ParseBean pb) {
        stopParse();
        initParseLoadFound();
        jxStartMs = VodTrace.now();
        VodTrace.mark("JX_PARSE_START", "解析方式 type=" + pb.getType() + " 解析地址=" + pb.getUrl());
        if (pb.getType() == 0) {
            setTip("正在嗅探播放地址", true, false);
            mHandler.removeMessages(100);
            mHandler.sendEmptyMessageDelayed(100, 20 * 1000);
            if (pb.getExt() != null) {
                // 解析ext
                try {
                    HashMap<String, String> reqHeaders = new HashMap<>();
                    JSONObject jsonObject = new JSONObject(pb.getExt());
                    if (jsonObject.has("header")) {
                        JSONObject headerJson = jsonObject.optJSONObject("header");
                        Iterator<String> keys = headerJson.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            if (key.equalsIgnoreCase("user-agent")) {
                                webUserAgent = headerJson.getString(key).trim();
                            } else {
                                reqHeaders.put(key, headerJson.optString(key, ""));
                            }
                        }
                        if (reqHeaders.size() > 0) webHeaderMap = reqHeaders;
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
            String sniffUrl = pb.getUrl() + webUrl;
            VodTrace.mark("JX_SNIFF_LOAD", "webview嗅探开始,超时20s url=" + sniffUrl);
            loadWebView(sniffUrl);

        } else if (pb.getType() == 1) { // json 解析
            setTip("正在解析播放地址", true, false);
            // 解析ext
            HttpHeaders reqHeaders = new HttpHeaders();
            try {
                JSONObject jsonObject = new JSONObject(pb.getExt());
                if (jsonObject.has("header")) {
                    JSONObject headerJson = jsonObject.optJSONObject("header");
                    Iterator<String> keys = headerJson.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        reqHeaders.put(key, headerJson.optString(key, ""));
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
            final long tHttp = VodTrace.now();
            String jsonJxUrl = pb.getUrl() + encodeUrl(webUrl);
            VodTrace.mark("JX_JSON_REQ", "解析接口请求 url=" + jsonJxUrl);
            OkGo.<String>get(jsonJxUrl)
                    .tag("json_jx")
                    .headers(reqHeaders)
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            String json = response.body();
                            try {
                                JSONObject rs = jsonParse(webUrl, json);
                                HashMap<String, String> headers = null;
                                if (rs.has("header")) {
                                    try {
                                        JSONObject hds = rs.getJSONObject("header");
                                        Iterator<String> keys = hds.keys();
                                        while (keys.hasNext()) {
                                            String key = keys.next();
                                            if (headers == null) {
                                                headers = new HashMap<>();
                                            }
                                            headers.put(key, hds.getString(key));
                                        }
                                    } catch (Throwable th) {

                                    }
                                }
                                VodTrace.markCost("JX_JSON_DONE", tHttp, "解析到直链 " + rs.getString("url"));
                                playUrl(rs.getString("url"), headers);
                            } catch (Throwable e) {
                                e.printStackTrace();
                                VodTrace.fail("JX_JSON_FAIL", "解析响应异常 " + e);
                                errorWithRetry("解析错误", false);
//                                setTip("解析错误", false, true);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            VodTrace.fail("JX_JSON_FAIL", "解析接口网络失败 cost=" + (VodTrace.now() - tHttp)
                                    + "ms msg=" + (response.getException() == null ? "" : response.getException().getMessage()));
                            errorWithRetry("解析错误", false);
//                            setTip("解析错误", false, true);
                        }
                    });
        } else if (pb.getType() == 2) { // json 扩展
            setTip("正在解析播放地址", true, false);
            parseThreadPool = Executors.newSingleThreadExecutor();
            LinkedHashMap<String, String> jxs = new LinkedHashMap<>();
            for (ParseBean p : ApiConfig.get().getParseBeanList()) {
                if (p.getType() == 1) {
                    jxs.put(p.getName(), p.mixUrl());
                }
            }
            parseThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    long tExt = VodTrace.now();
                    VodTrace.mark("JX_EXT_REQ", "jsonExt 开始");
                    JSONObject rs = ApiConfig.get().jsonExt(pb.getUrl(), jxs, webUrl);
                    if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
                        VodTrace.fail("JX_EXT_FAIL", "jsonExt 未拿到直链 cost=" + (VodTrace.now() - tExt) + "ms");
//                        errorWithRetry("解析错误", false);
                        setTip("解析错误", false, true);
                    } else {
                        VodTrace.markCost("JX_EXT_DONE", tExt, "jsonExt 返回直链 " + rs.optString("url", ""));
                        HashMap<String, String> headers = null;
                        if (rs.has("header")) {
                            try {
                                JSONObject hds = rs.getJSONObject("header");
                                Iterator<String> keys = hds.keys();
                                while (keys.hasNext()) {
                                    String key = keys.next();
                                    if (headers == null) {
                                        headers = new HashMap<>();
                                    }
                                    headers.put(key, hds.getString(key));
                                }
                            } catch (Throwable th) {

                            }
                        }
                        if (rs.has("jxFrom")) {
                            ToastUtils.showShort("解析来自:" + rs.optString("jxFrom"));
                        }
                        boolean parseWV = rs.optInt("parse", 0) == 1;
                        if (parseWV) {
                            String wvUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""));
                            loadUrl(wvUrl);
                        } else {
                            playUrl(rs.optString("url", ""), headers);
                        }
                    }
                }
            });
        } else if (pb.getType() == 3) { // json 聚合
            setTip("正在解析播放地址", true, false);
            parseThreadPool = Executors.newSingleThreadExecutor();
            LinkedHashMap<String, HashMap<String, String>> jxs = new LinkedHashMap<>();
            String extendName = "";
            for (ParseBean p : ApiConfig.get().getParseBeanList()) {
                HashMap data = new HashMap<String, String>();
                data.put("url", p.getUrl());
                if (p.getUrl().equals(pb.getUrl())) {
                    extendName = p.getName();
                }
                data.put("type", p.getType() + "");
                data.put("ext", p.getExt());
                jxs.put(p.getName(), data);
            }
            String finalExtendName = extendName;
            parseThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    long tExt = VodTrace.now();
                    VodTrace.mark("JX_EXTMIX_REQ", "jsonExtMix 开始");
                    JSONObject rs = ApiConfig.get().jsonExtMix(parseFlag + "111", pb.getUrl(), finalExtendName, jxs, webUrl);
                    if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
                        VodTrace.fail("JX_EXTMIX_FAIL", "jsonExtMix 未拿到直链 cost=" + (VodTrace.now() - tExt) + "ms");
//                        errorWithRetry("解析错误", false);
                        setTip("解析错误", false, true);
                    } else {
                        VodTrace.markCost("JX_EXTMIX_DONE", tExt, "jsonExtMix 返回 " + rs.optString("url", ""));
                        if (rs.has("parse") && rs.optInt("parse", 0) == 1) {
                            if (rs.has("ua")) {
                                webUserAgent = rs.optString("ua").trim();
                            }
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    String mixParseUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""));
                                    stopParse();
                                    setTip("正在嗅探播放地址", true, false);
                                    mHandler.removeMessages(100);
                                    mHandler.sendEmptyMessageDelayed(100, 20 * 1000);
                                    VodTrace.mark("JX_SNIFF_LOAD", "聚合解析转webview嗅探,超时20s url=" + mixParseUrl);
                                    loadWebView(mixParseUrl);
                                }
                            });
                        } else {
                            HashMap<String, String> headers = null;
                            if (rs.has("header")) {
                                try {
                                    JSONObject hds = rs.getJSONObject("header");
                                    Iterator<String> keys = hds.keys();
                                    while (keys.hasNext()) {
                                        String key = keys.next();
                                        if (headers == null) {
                                            headers = new HashMap<>();
                                        }
                                        headers.put(key, hds.getString(key));
                                    }
                                } catch (Throwable th) {
                                    th.printStackTrace();
                                }
                            }
                            if (rs.has("jxFrom")) {
                                ToastUtils.showShort("解析来自:" + rs.optString("jxFrom"));
                            }
                            playUrl(rs.optString("url", ""), headers);
                        }
                    }
                }
            });
        }
    }

    private String encodeUrl(String url) {
        try {
            return URLEncoder.encode(url, "UTF-8");
        } catch (Exception e) {
            return url;
        }
    }

    private WebView mSysWebView;
    private final Map<String, Boolean> loadedUrls = new HashMap<>();
    private LinkedList<String> loadFoundVideoUrls = new LinkedList<>();
    private HashMap<String, HashMap<String, String>> loadFoundVideoUrlsHeader = new HashMap<>();
    private final AtomicInteger loadFoundCount = new AtomicInteger(0);

    void loadWebView(String url) {
        if (mSysWebView == null) {
            mSysWebView = new MyWebView(mContext);
            configWebViewSys(mSysWebView);
            loadUrl(url);
        } else {
            loadUrl(url);
        }
    }

    void loadUrl(String url) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (mSysWebView != null) {
                mSysWebView.stopLoading();
                if (webUserAgent != null) {
                    mSysWebView.getSettings().setUserAgentString(webUserAgent);
                }
                //mSysWebView.clearCache(true);
                if (webHeaderMap != null) {
                    mSysWebView.loadUrl(url, webHeaderMap);
                } else {
                    mSysWebView.loadUrl(url);
                }
            }
        });
    }

    void stopLoadWebView(boolean destroy) {
        if (mActivity == null || !isAdded()) return;
        requireActivity().runOnUiThread(() -> {

            if (mSysWebView != null) {
                mSysWebView.stopLoading();
                mSysWebView.loadUrl("about:blank");
                if (destroy) {
//                        mSysWebView.clearCache(true);
                    mSysWebView.removeAllViews();
                    mSysWebView.destroy();
                    mSysWebView = null;
                }
            }
        });
    }

    public String getFinalUrl(){
        return TextUtils.isEmpty(mCurrentUrl) || !RegexUtils.isURL(mCurrentUrl) ?"":mCurrentUrl;
    }

    boolean checkVideoFormat(String url) {
        try {
            if (url.contains("url=http") || url.contains(".html")) {
                return false;
            }
            if (sourceBean.getType() == 3) {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                if (sp != null && sp.manualVideoCheck()) {
                    return sp.isVideoFormat(url);
                }
            }
            return VideoParseRuler.checkIsVideoForParse(webUrl, url);
        } catch (Exception e) {
            return false;
        }
    }

    class MyWebView extends WebView {
        public MyWebView(@NonNull Context context) {
            super(context);
        }

        @Override
        public void setOverScrollMode(int mode) {
            super.setOverScrollMode(mode);
            if (mContext instanceof Activity)
                AutoSize.autoConvertDensityOfCustomAdapt((Activity) mContext, PlayFragment.this);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            return false;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configWebViewSys(WebView webView) {
        if (webView == null) {
            return;
        }
        ViewGroup.LayoutParams layoutParams = Hawk.get(HawkConfig.DEBUG_OPEN, false)
                ? new ViewGroup.LayoutParams(800, 400) :
                new ViewGroup.LayoutParams(1, 1);
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);
        webView.clearFocus();
        webView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        requireActivity().addContentView(webView, layoutParams);
        /* 添加webView配置 */
        final WebSettings settings = webView.getSettings();
        settings.setNeedInitialFocus(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setDatabaseEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptEnabled(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
        if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
            settings.setBlockNetworkImage(false);
        } else {
            settings.setBlockNetworkImage(true);
        }
        settings.setUseWideViewPort(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setSupportZoom(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
//        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        /* 添加webView配置 */
        //设置编码
        settings.setDefaultTextEncodingName("utf-8");
        settings.setUserAgentString(webView.getSettings().getUserAgentString());
//         settings.setUserAgentString(ANDROID_UA);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return false;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                return true;
            }
        });
        SysWebClient mSysWebClient = new SysWebClient();
        webView.setWebViewClient(mSysWebClient);
        webView.setBackgroundColor(Color.BLACK);
    }

    private class SysWebClient extends WebViewClient {

        @SuppressLint("WebViewClientOnReceivedSslError")
        @Override
        public void onReceivedSslError(WebView webView, SslErrorHandler sslErrorHandler, SslError sslError) {
            sslErrorHandler.proceed();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            String click = sourceBean.getClickSelector();
            LOG.i("onPageFinished url:" + url);

            if (!click.isEmpty()) {
                String selector;
                if (click.contains(";")) {
                    if (!url.contains(click.split(";")[0])) return;
                    selector = click.split(";")[1];
                } else {
                    selector = click.trim();
                }
                String js = "$(\"" + selector + "\").click();";
                LOG.i("javascript:" + js);
                mSysWebView.loadUrl("javascript:" + js);
            }
        }

        WebResourceResponse checkIsVideo(String url, HashMap<String, String> headers) {
            if (url.endsWith("/favicon.ico")) {
                if (url.startsWith("http://127.0.0.1")) {
                    return new WebResourceResponse("image/x-icon", "UTF-8", null);
                }
                return null;
            }

            boolean isFilter = VideoParseRuler.isFilter(webUrl, url);
            if (isFilter) {
                LOG.i("shouldInterceptLoadRequest filter:" + url);
                return null;
            }

            boolean ad;
            if (!loadedUrls.containsKey(url)) {
                ad = AdBlocker.isAd(url);
                loadedUrls.put(url, ad);
            } else {
                ad = Boolean.TRUE.equals(loadedUrls.get(url));
            }

            if (!ad) {
                if (checkVideoFormat(url)) {
                    loadFoundVideoUrls.add(url);
                    loadFoundVideoUrlsHeader.put(url, headers);
                    LOG.i("loadFoundVideoUrl:" + url);
                    VodTrace.markCost("JX_SNIFF_FOUND", jxStartMs,
                            "嗅探到第" + (loadFoundCount.get() + 1) + "个直链 " + url);
                    if (loadFoundCount.incrementAndGet() == 1) {
                        url = loadFoundVideoUrls.poll();
                        mHandler.removeMessages(100);
                        String cookie = CookieManager.getInstance().getCookie(url);
                        if (!TextUtils.isEmpty(cookie))
                            headers.put("Cookie", " " + cookie);//携带cookie
                        playUrl(url, headers);
                        stopLoadWebView(false);
                    }
                }
            }

            return ad || loadFoundCount.get() > 0 ?
                    AdBlocker.createEmptyResource() :
                    null;
        }

        @Nullable
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
//            WebResourceResponse response = checkIsVideo(url, new HashMap<>());
            return null;
        }

        @Nullable
        @Override
        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            LOG.i("shouldInterceptRequest url:" + url);
            HashMap<String, String> webHeaders = new HashMap<>();
            Map<String, String> hds = request.getRequestHeaders();
            if (hds != null && hds.keySet().size() > 0) {
                for (String k : hds.keySet()) {
                    if (k.equalsIgnoreCase("user-agent")
                            || k.equalsIgnoreCase("referer")
                            || k.equalsIgnoreCase("origin")) {
                        webHeaders.put(k, " " + hds.get(k));
                    }
                }
            }
            return checkIsVideo(url, webHeaders);
        }

        @Override
        public void onLoadResource(WebView webView, String url) {
            super.onLoadResource(webView, url);
        }
    }

    public MyVideoView getPlayer() {
        return mVideoView;
    }
    public VodController getController() {
        return mController;
    }

    /**
     * 全屏时:使用 Android 原生 WindowInsetsController 真正隐藏状态栏 + 导航栏 + 手势条,
     * 并允许内容延伸到刘海/挖孔区,让视频真正占满整块屏幕,避免系统 UI 悬浮在视频上。
     *
     * ImmersionBar.hideBar(FLAG_HIDE_BAR) 在某些 ROM(尤其 Android 14)上并不能真生效,
     * 表现为状态栏和手势条仍然显示在视频之上,视频"上下被吃一段",视觉上不居中。
     *
     * API 30+(Android 11+)用 WindowInsetsController,API 24-29 回退到 setSystemUiVisibility。
     */
    @SuppressLint("WrongConstant")
    public void hideSystemBars() {
        if (mActivity == null) return;
        Window window = mActivity.getWindow();
        if (window == null) return;
        // 允许内容延伸到系统栏区域(刘海/手势条/状态栏背后)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
            // 小米/MIUI(Android 14)上仅靠 setDecorFitsSystemWindows(false) 仍可能保留状态栏偏移,
            // 加 FLAG_LAYOUT_NO_LIMITS 让内容真正延伸到状态栏区,视频占满整屏(修复全屏偏下)。
            // 系统小窗(freeform/multi-window)模式不能加此 FLAG,否则内容会被推出浮窗边界被裁。
            if (!mActivity.isInMultiWindowMode()) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            }
        } else {
            // API 24-29 回退路径
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    /**
     * 退出全屏:恢复状态栏 + 导航栏显示。
     */
    @SuppressLint("WrongConstant")
    public void showSystemBars() {
        if (mActivity == null) return;
        Window window = mActivity.getWindow();
        if (window == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true);
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

}
