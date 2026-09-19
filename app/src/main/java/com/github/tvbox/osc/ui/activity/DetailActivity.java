package com.github.tvbox.osc.ui.activity;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Rational;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.blankj.utilcode.util.AppUtils;

import xyz.doikki.videoplayer.player.VideoView;
import com.github.tvbox.osc.player.MyVideoView;
import com.blankj.utilcode.util.LogUtils;
import com.blankj.utilcode.util.NotificationUtils;
import com.blankj.utilcode.util.ScreenUtils;
import com.blankj.utilcode.util.ServiceUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseVbActivity;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.constant.IntentKey;
import com.github.tvbox.osc.databinding.ActivityDetailBinding;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.receiver.BatteryReceiver;
import com.github.tvbox.osc.service.PlayService;
import com.github.tvbox.osc.ui.adapter.ParseAdapter;
import com.github.tvbox.osc.ui.adapter.SeriesAdapter;
import com.github.tvbox.osc.ui.adapter.SeriesFlagAdapter;
import com.github.tvbox.osc.ui.dialog.AllVodSeriesBottomDialog;
import com.github.tvbox.osc.ui.dialog.AllVodSeriesRightDialog;
import com.github.tvbox.osc.ui.dialog.QuickSearchDialog;
import com.github.tvbox.osc.ui.dialog.VideoDetailDialog;
import com.github.tvbox.osc.ui.fragment.PlayFragment;
import com.github.tvbox.osc.ui.widget.LinearSpacingItemDecoration;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.GsonUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ReceiverCompat;
import com.github.tvbox.osc.util.ScreenShotListenManager;
import com.github.tvbox.osc.util.SearchHelper;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.util.Utils;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.gyf.immersionbar.ImmersionBar;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.enums.PopupPosition;
import com.lxj.xpopup.interfaces.OnSelectListener;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author pj567
 * @date :2020/12/22
 * @description:
 */

public class DetailActivity extends BaseVbActivity<ActivityDetailBinding> {
    private PlayFragment playFragment = null;
    private SourceViewModel sourceViewModel;
    private Movie.Video mVideo;
    private VodInfo vodInfo;
    // AI 助手自动播放意图参数
    private boolean autoPlay = false;
    private int aiPlayIndex = -1;     // -1=续播/history, -2=最新, >=0=指定集(0基)
    private String aiPlayFlag = null;
    public SeriesFlagAdapter seriesFlagAdapter;
    public SeriesAdapter seriesAdapter;
    public String vodId;
    public String sourceKey;
    private View seriesFlagFocus = null;
    private boolean isReverse;
    private String preFlag = "";
    private HashMap<String, String> mCheckSources = null;
    BatteryReceiver mBatteryReceiver = new BatteryReceiver();
    //改为view模式无法自动响应返回键操作,onBackPress时手动dismiss
    private BasePopupView mAllSeriesRightDialog;
    private BasePopupView mAllSeriesBottomDialog;
    /**
     * Home键广播,用于触发后台服务
     */
    private BroadcastReceiver mHomeKeyReceiver;
    /**
     * 是否开启后台播放标记,不在广播开启,onPause根据标记开启
     */
    boolean openBackgroundPlay;
    private BroadcastReceiver mRemoteActionReceiver;
    /**
     * 进入 PiP 浮窗前的 video scale(PiP 期间会临时改成 CENTER_CROP,退出时还原)。
     */
    private int mSavedScreenScaleBeforePip = VideoView.SCREEN_SCALE_DEFAULT;
    /**
     * 记录上次 applyPreviewPlayerRatio 使用的宽高,避免重复设置导致布局抖动。
     */
    private int mLastPreviewWidth = 0;
    private int mLastPreviewVideoW = 0;
    private int mLastPreviewVideoH = 0;

    /**
     * 截屏监听
     */
    ScreenShotListenManager screenShotListenManager;

    @Override
    protected void init() {
        initReceiver();
        initView();
        initViewModel();
        initData();
        // Android 14 (API 34) 兼容:registerReceiver 必须显式 exported
        ReceiverCompat.registerSafe(this, mBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        ImmersionBar.with(this)
                .statusBarColor(R.color.bili_bg_card)
                .navigationBarColor(R.color.bili_bg_card)
                .fitsSystemWindows(true)
                .statusBarDarkFont(false)
                .init();
        toggleScreenShotListen(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        openBackgroundPlay = false;
        playServerSwitch(false);
        mBinding.ivPrivateBrowsing.postDelayed(NotificationUtils::cancelAll, 800);
    }

    private void initView() {
        // 安装 content frame 的 OnApplyWindowInsetsListener,根据 fullWindows 动态决定是否
        // 消耗 insets。这是为了解决 Android 15+ edge-to-edge 下 content frame 会被按
        // statusBarHeight 自动加 paddingTop,旋转/沉浸/PiP 后系统会再次 dispatch insets
        // 把 padding 灌回来。光靠 setPadding(0,0,0,0) 会被反复覆盖,必须装 listener 拦截。
        installContentInsetsListener();
        mBinding.ivPrivateBrowsing.setVisibility(Hawk.get(HawkConfig.PRIVATE_BROWSING, false) ? View.VISIBLE : View.GONE);
        mBinding.ivPrivateBrowsing.setOnClickListener(view -> ToastUtils.showShort("当前为无痕浏览"));
        mBinding.previewPlayerPlace.setVisibility(showPreview ? View.VISIBLE : View.GONE);

        mBinding.mGridView.setHasFixedSize(true);
        mBinding.mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        mBinding.mGridView.addItemDecoration(new LinearSpacingItemDecoration(20, false));

        seriesAdapter = new SeriesAdapter(false);
        mBinding.mGridView.setAdapter(seriesAdapter);
        mBinding.mGridViewFlag.setHasFixedSize(true);
        seriesFlagAdapter = new SeriesFlagAdapter();
        mBinding.mGridViewFlag.setAdapter(seriesFlagAdapter);
        isReverse = false;
        preFlag = "";
        if (showPreview) {
            playFragment = new PlayFragment();
            getSupportFragmentManager().beginTransaction().add(R.id.previewPlayer, playFragment).commit();
            getSupportFragmentManager().beginTransaction().show(playFragment).commitAllowingStateLoss();
            applyPreviewPlayerRatio();
        }

        findViewById(R.id.ll_title).setOnClickListener(view -> {
            new XPopup.Builder(this)
                    .isViewMode(true)
                    .hasNavigationBar(false)
                    .asCustom(new VideoDetailDialog(this, vodInfo))
                    .show();
        });
        findViewById(R.id.tvDownload).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                use1DMDownload();
            }
        });
        mBinding.tvSort.setOnClickListener(new View.OnClickListener() {
            @SuppressLint("NotifyDataSetChanged")
            @Override
            public void onClick(View v) {
                sortSeries();
            }
        });
        mBinding.tvCast.setVisibility(View.GONE);
        mBinding.tvCollect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = mBinding.tvCollect.getText().toString();
                if ("加入收藏".equals(text)) {
                    RoomDataManger.insertVodCollect(sourceKey, vodInfo);
                    ToastUtils.showShort("已加入收藏夹");
                    mBinding.tvCollect.setText("取消收藏");
                } else {
                    RoomDataManger.deleteVodCollect(sourceKey, vodInfo);
                    ToastUtils.showShort("已移除收藏夹");
                    mBinding.tvCollect.setText("加入收藏");
                }
            }
        });

        seriesFlagAdapter.setOnItemClickListener((adapter, view, position) -> {
            chooseFlag(position);
        });

        seriesAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                chooseSeries(position, false);
            }
        });

        mBinding.tvAllSeries.setOnClickListener(view -> {
            showAllSeriesDialog();
        });

        mBinding.tvSite.setOnClickListener(view -> {
            startQuickSearch();
            QuickSearchDialog quickSearchDialog = new QuickSearchDialog(DetailActivity.this);
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH, quickSearchData));
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, quickSearchWord));
            quickSearchDialog.show();
            if (pauseRunnable != null && pauseRunnable.size() > 0) {
                searchExecutorService = Executors.newFixedThreadPool(5);
                for (Runnable runnable : pauseRunnable) {
                    searchExecutorService.execute(runnable);
                }
                pauseRunnable.clear();
                pauseRunnable = null;
            }
            quickSearchDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    try {
                        if (searchExecutorService != null) {
                            pauseRunnable = searchExecutorService.shutdownNow();
                            searchExecutorService = null;
                        }
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                }
            });
        });
        mBinding.tvChangeLine.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            quickLineChange();
        });
        setLoadSir(mBinding.llLayout);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (openBackgroundPlay) {
            playServerSwitch(true);
        }
    }

    private void initReceiver() {
        // 注册广播接收器
        if (mHomeKeyReceiver == null) {
            mHomeKeyReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action != null && action.equals(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) {
                        openBackgroundPlay = Hawk.get(HawkConfig.BACKGROUND_PLAY_TYPE, 0) == 1 && playFragment.getPlayer() != null && playFragment.getPlayer().isPlaying();
                    }
                }
            };
            // Android 14 (API 34) 兼容
            ReceiverCompat.registerSafe(this, mHomeKeyReceiver, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        }
    }

    /**
     * 排序(倒序)
     */
    public void sortSeries() {
        if (vodInfo != null && vodInfo.seriesMap.size() > 0) {
            vodInfo.reverseSort = !vodInfo.reverseSort;
            isReverse = !isReverse;
            vodInfo.reverse();
            vodInfo.playIndex = (vodInfo.seriesMap.get(vodInfo.playFlag).size() - 1) - vodInfo.playIndex;
//                    insertVod(sourceKey, vodInfo);

            seriesAdapter.notifyDataSetChanged();
        }
    }

    public void showAllSeriesDialog() {
        if (fullWindows) {
            mAllSeriesRightDialog = new XPopup.Builder(this)
                    .isViewMode(true)//隐藏导航栏(手势条)在dialog模式下会闪一下,改为view模式,但需处理onBackPress的隐藏,下方同理
                    .hasNavigationBar(false)
                    .popupHeight(ScreenUtils.getScreenHeight())
                    .popupPosition(PopupPosition.Right)
                    .enableDrag(false)//禁用拖拽,内部有横向rv
                    .asCustom(new AllVodSeriesRightDialog(this));
            mAllSeriesRightDialog.show();
        } else {
            mAllSeriesBottomDialog = new XPopup.Builder(this)
                    .isViewMode(true)
                    .hasNavigationBar(false)
                    .maxHeight(ScreenUtils.getScreenHeight() - (ScreenUtils.getScreenHeight() / 4))
                    .asCustom(new AllVodSeriesBottomDialog(this, seriesAdapter.getData(), (position, text) -> {
                        chooseSeries(position, false);
                    }));
            mAllSeriesBottomDialog.show();
        }
    }

    private void chooseFlag(int position) {
        //新选中的flag
        String newFlag = seriesFlagAdapter.getData().get(position).name;
        if (vodInfo != null && !vodInfo.playFlag.equals(newFlag)) {
            for (int i = 0; i < vodInfo.seriesFlags.size(); i++) {//遍历flag集合
                VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(i);
                if (flag.name.equals(vodInfo.playFlag)) {//取消当前播放的选中状态
                    flag.selected = false;
                    seriesFlagAdapter.notifyItemChanged(i);
                    break;
                }
            }
            //新选中的flag
            VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(position);
            flag.selected = true;
            //清除上一个线路集数的选中状态
            List<VodInfo.VodSeries> currentSeriesList = vodInfo.seriesMap.get(vodInfo.playFlag);
            if (currentSeriesList.size() > vodInfo.playIndex) {//有效集数
                currentSeriesList.get(vodInfo.playIndex).selected = false;
            }
            vodInfo.playFlag = newFlag;
            seriesFlagAdapter.notifyItemChanged(position);
            refreshList();
        }
    }

    private void chooseSeries(int position, boolean reloadWithChangeLine) {
        if (vodInfo != null && vodInfo.seriesMap.get(vodInfo.playFlag).size() > 0) {
            boolean reload = false;
            for (int j = 0; j < vodInfo.seriesMap.get(vodInfo.playFlag).size(); j++) {
                seriesAdapter.getData().get(j).selected = false;
                seriesAdapter.notifyItemChanged(j);
            }
            //解决倒叙不刷新
            if (vodInfo.playIndex != position) {
                seriesAdapter.getData().get(position).selected = true;
                seriesAdapter.notifyItemChanged(position);
                vodInfo.playIndex = position;

                reload = true;
            }
            //解决当前集不刷新的BUG
            if (!preFlag.isEmpty() && !vodInfo.playFlag.equals(preFlag)) {
                reload = true;
            }

            seriesAdapter.getData().get(vodInfo.playIndex).selected = true;
            seriesAdapter.notifyItemChanged(vodInfo.playIndex);

            //选集全屏 想选集不全屏的注释下面一行
            if (!showPreview || reload || reloadWithChangeLine) {
                jumpToPlay();
            }
        }
    }

    /**
     * AI 助手自动播放时，根据 aiPlayIndex 设置播放集数并同步高亮。
     * aiPlayIndex: -1=续播(保持 history 值) / -2=最新一集 / >=0=指定集(0基)
     */
    private void applyAiPlayIndex() {
        if (vodInfo == null || seriesAdapter == null) return;
        int size = seriesAdapter.getData().size();
        if (size == 0) return;
        int idx;
        if (aiPlayIndex == -2) {
            idx = size - 1;
        } else if (aiPlayIndex >= 0) {
            idx = Math.min(aiPlayIndex, size - 1);
        } else {
            return; // -1 续播：沿用 history 已设置的 playIndex
        }
        vodInfo.playIndex = idx;
        for (int j = 0; j < size; j++) {
            seriesAdapter.getData().get(j).selected = false;
        }
        seriesAdapter.getData().get(idx).selected = true;
        seriesAdapter.notifyDataSetChanged();
    }

    private void initCheckedSourcesForSearch() {        mCheckSources = SearchHelper.getSourcesForSearch();
    }

    private List<Runnable> pauseRunnable = null;

    private void jumpToPlay() {
        if (vodInfo != null && vodInfo.seriesMap.get(vodInfo.playFlag).size() > 0) {
            preFlag = vodInfo.playFlag;
            //更新播放地址
            Bundle bundle = new Bundle();
            //保存历史
            insertVod(sourceKey, vodInfo);
            bundle.putString("sourceKey", sourceKey);
//            bundle.putSerializable("VodInfo", vodInfo);
            App.getInstance().setVodInfo(vodInfo);
            if (previewVodInfo == null) {
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(bos);
                    oos.writeObject(vodInfo);
                    oos.flush();
                    oos.close();
                    ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()));
                    previewVodInfo = (VodInfo) ois.readObject();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (previewVodInfo != null) {
                previewVodInfo.playerCfg = vodInfo.playerCfg;
                previewVodInfo.playFlag = vodInfo.playFlag;
                previewVodInfo.playIndex = vodInfo.playIndex;
                previewVodInfo.seriesMap = vodInfo.seriesMap;
//                    bundle.putSerializable("VodInfo", previewVodInfo);
                App.getInstance().setVodInfo(previewVodInfo);
            }
            playFragment.setData(bundle);

            //定位选集
            mBinding.mGridView.scrollToPosition(vodInfo.playIndex);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    void refreshList() {
        int seriesSize = vodInfo.seriesMap.get(vodInfo.playFlag).size();
        if (seriesSize > 0 && seriesSize <= vodInfo.playIndex) {//当前集数大于新选线路的总集数,设置为最后一集
            vodInfo.playIndex = seriesSize - 1;
        }

        if (vodInfo.seriesMap.get(vodInfo.playFlag) != null) {
            boolean canSelect = true;
            for (int j = 0; j < vodInfo.seriesMap.get(vodInfo.playFlag).size(); j++) {
                if (vodInfo.seriesMap.get(vodInfo.playFlag).get(j).selected) {
                    canSelect = false;
                    break;
                }
            }
            if (canSelect)
                vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex).selected = true;
        }
        seriesAdapter.setNewData(vodInfo.seriesMap.get(vodInfo.playFlag));

    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.detailResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                    showSuccess();
                    mVideo = absXml.movie.videoList.get(0);
                    vodInfo = new VodInfo();
                    vodInfo.setVideo(mVideo);
                    vodInfo.sourceKey = mVideo.sourceKey;
                    // 已进入详情页(看到了最新剧集):同步收藏集数基线并清掉收藏页的「更新」角标
                    markCollectViewedAsync();

                    mBinding.tvName.setText(TextUtils.isEmpty(mVideo.name) ? "暂无信息" : mVideo.name);
                    String srcName = ApiConfig.get().getSource(mVideo.sourceKey).getName();
                    mBinding.tvSite.setText("来源：" + (TextUtils.isEmpty(srcName) ? "未知" : srcName));

                    if (vodInfo.seriesMap != null && vodInfo.seriesMap.size() > 0) {//线路
                        mBinding.mGridViewFlag.setVisibility(View.VISIBLE);
                        mBinding.mGridView.setVisibility(View.VISIBLE);
                        mBinding.mEmptyPlaylist.setVisibility(View.GONE);

                        VodInfo vodInfoRecord = RoomDataManger.getVodInfo(sourceKey, vodId);
                        // 读取历史记录
                        if (vodInfoRecord != null) {
                            vodInfo.playIndex = Math.max(vodInfoRecord.playIndex, 0);
                            vodInfo.playFlag = vodInfoRecord.playFlag;
                            vodInfo.playerCfg = vodInfoRecord.playerCfg;
                            vodInfo.reverseSort = vodInfoRecord.reverseSort;
                        } else {
                            vodInfo.playIndex = 0;
                            vodInfo.playFlag = null;
                            vodInfo.playerCfg = "";
                            vodInfo.reverseSort = false;
                        }

                        if (vodInfo.reverseSort) {
                            vodInfo.reverse();
                        }

                        if (vodInfo.playFlag == null || !vodInfo.seriesMap.containsKey(vodInfo.playFlag))
                            vodInfo.playFlag = (String) vodInfo.seriesMap.keySet().toArray()[0];

                        // AI 助手指定线路：覆盖默认线路并让上方循环高亮
                        if (aiPlayFlag != null && vodInfo.seriesMap.containsKey(aiPlayFlag)) {
                            vodInfo.playFlag = aiPlayFlag;
                        }

                        int flagScrollTo = 0;
                        for (int j = 0; j < vodInfo.seriesFlags.size(); j++) {
                            VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(j);
                            if (flag.name.equals(vodInfo.playFlag)) {
                                flagScrollTo = j;
                                flag.selected = true;
                            } else
                                flag.selected = false;
                        }
//                        setTextShow(tvPlayUrl, "播放地址：", vodInfo.seriesMap.get(vodInfo.playFlag).get(0).url);
                        //设置线路数据
                        seriesFlagAdapter.setNewData(vodInfo.seriesFlags);
                        mBinding.mGridViewFlag.scrollToPosition(flagScrollTo);

                        refreshList();
                        // AI 助手自动播放：根据意图设置集数后直接起播
                        if (autoPlay) {
                            applyAiPlayIndex();
                        }
                        if (autoPlay || showPreview) {
                            jumpToPlay();
                            mBinding.previewPlayer.setVisibility(View.VISIBLE);
                            toggleSubtitleTextSize();
                        }
                        // startQuickSearch();
                    } else {//空布局
                        mBinding.mGridViewFlag.setVisibility(View.GONE);
                        mBinding.mGridView.setVisibility(View.GONE);
                        mBinding.mEmptyPlaylist.setVisibility(View.VISIBLE);
                    }
                } else {
                    showEmpty();
                    mBinding.previewPlayer.setVisibility(View.GONE);
                }
            }
        });
    }

    private String getHtml(String label, String content) {
        if (content == null) {
            content = "";
        }
        return label + "<font color=\"#FFFFFF\">" + content + "</font>";
    }

    private void initData() {
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            Bundle bundle = intent.getExtras();
            autoPlay = bundle.getBoolean("autoPlay", false);
            aiPlayIndex = bundle.getInt("playIndex", -1);
            aiPlayFlag = bundle.getString("playFlag");
            loadDetail(bundle.getString("id", null), bundle.getString("sourceKey", ""));
        }
    }

    private void loadDetail(String vid, String key) {
        if (vid != null) {
            vodId = vid;
            sourceKey = key;
            showLoading();
            sourceViewModel.getDetail(sourceKey, vodId);
            boolean isVodCollect = RoomDataManger.isVodCollect(sourceKey, vodId);
            if (isVodCollect) {
                mBinding.tvCollect.setText("取消收藏");
            } else {
                mBinding.tvCollect.setText("加入收藏");
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_REFRESH) {
            if (event.obj != null) {
                if (event.obj instanceof Integer) {
                    int index = (int) event.obj;
                    for (int j = 0; j < vodInfo.seriesMap.get(vodInfo.playFlag).size(); j++) {
                        seriesAdapter.getData().get(j).selected = false;
                        seriesAdapter.notifyItemChanged(j);
                    }
                    seriesAdapter.getData().get(index).selected = true;
                    seriesAdapter.notifyItemChanged(index);
                    //mBinding.mGridView.setSelection(index);
                    vodInfo.playIndex = index;
                    //保存历史
                    insertVod(sourceKey, vodInfo);
                } else if (event.obj instanceof JSONObject) {
                    vodInfo.playerCfg = ((JSONObject) event.obj).toString();
                    //保存历史
                    insertVod(sourceKey, vodInfo);
                }

            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_SELECT) {
            if (event.obj != null) {
                Movie.Video video = (Movie.Video) event.obj;
                loadDetail(video.id, video.sourceKey);
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_WORD_CHANGE) {
            if (event.obj != null) {
                String word = (String) event.obj;
                switchSearchWord(word);
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_RESULT) {
            try {
                searchData(event.obj == null ? null : (AbsXml) event.obj);
            } catch (Exception e) {
                searchData(null);
            }
        }
    }

    private String searchTitle = "";
    private boolean hadQuickStart = false;
    private final List<Movie.Video> quickSearchData = new ArrayList<>();
    private final List<String> quickSearchWord = new ArrayList<>();
    private ExecutorService searchExecutorService = null;

    private void switchSearchWord(String word) {
        OkGo.getInstance().cancelTag("quick_search");
        quickSearchData.clear();
        searchTitle = word;
        searchResult();
    }

    private void startQuickSearch() {
        initCheckedSourcesForSearch();
        if (hadQuickStart)
            return;
        hadQuickStart = true;
        OkGo.getInstance().cancelTag("quick_search");
        quickSearchWord.clear();
        searchTitle = mVideo.name;
        quickSearchData.clear();
        quickSearchWord.addAll(SearchHelper.splitWords(searchTitle));
        // 分词
        OkGo.<String>get("http://api.pullword.com/get.php?source=" + URLEncoder.encode(searchTitle) + "&param1=0&param2=0&json=1")
                .tag("fenci")
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
                            for (JsonElement je : GsonUtil.get().fromJson(json, JsonArray.class)) {
                                quickSearchWord.add(je.getAsJsonObject().get("t").getAsString());
                            }
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                        List<String> words = new ArrayList<>(new HashSet<>(quickSearchWord));
                        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, words));
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                    }
                });

        searchResult();
    }

    private void searchResult() {
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        searchExecutorService = Executors.newFixedThreadPool(5);
        List<SourceBean> searchRequestList = new ArrayList<>();
        searchRequestList.addAll(ApiConfig.get().getSourceBeanList());
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        searchRequestList.remove(home);
        searchRequestList.add(0, home);

        ArrayList<String> siteKey = new ArrayList<>();
        for (SourceBean bean : searchRequestList) {
            if (!bean.isSearchable() || !bean.isQuickSearch()) {
                continue;
            }
            if (mCheckSources != null && !mCheckSources.containsKey(bean.getKey())) {
                continue;
            }
            siteKey.add(bean.getKey());
        }
        for (String key : siteKey) {
            searchExecutorService.execute(new Runnable() {
                @Override
                public void run() {
                    sourceViewModel.getQuickSearch(key, searchTitle);
                }
            });
        }
    }

    private void searchData(AbsXml absXml) {
        if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
            List<Movie.Video> data = new ArrayList<>();
            for (Movie.Video video : absXml.movie.videoList) {
                // 去除当前相同的影片
                if (video.sourceKey.equals(sourceKey) && video.id.equals(vodId))
                    continue;
                data.add(video);
            }
            quickSearchData.addAll(data);
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH, data));
        }
    }

    private void insertVod(String sourceKey, VodInfo vodInfo) {
        if (Hawk.get(HawkConfig.PRIVATE_BROWSING, false)) {//无痕浏览
            return;
        }
        try {
            vodInfo.playNote = vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex).name;
        } catch (Throwable th) {
            vodInfo.playNote = "";
        }
        RoomDataManger.insertVodRecord(sourceKey, vodInfo);
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH));
    }

    /**
     * 详情页加载成功后,把该剧在收藏表里的"集数基线"同步为当前最新,并清除「更新」角标。
     * 纯增强功能,放后台线程执行,任何异常都不影响正常播放。
     */
    private void markCollectViewedAsync() {
        final String sk = sourceKey;
        final VodInfo vi = vodInfo;
        if (sk == null || vi == null) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    RoomDataManger.markCollectViewed(sk, vi);
                } catch (Throwable th) {
                    // 收藏更新标记是增强能力,失败静默忽略
                }
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        registerActionReceiver(false);
        super.onDestroy();
        unregisterReceiver(mBatteryReceiver);
        // 注销广播接收器
        if (mHomeKeyReceiver != null) {
            unregisterReceiver(mHomeKeyReceiver);
            mHomeKeyReceiver = null;
        }

        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        OkGo.getInstance().cancelTag("fenci");
        OkGo.getInstance().cancelTag("detail");
        OkGo.getInstance().cancelTag("quick_search");
        toggleScreenShotListen(false);
    }

    @Override
    public void onBackPressed() {
        if (mAllSeriesRightDialog != null && mAllSeriesRightDialog.isShow()) {
            mAllSeriesRightDialog.dismiss();
            return;
        }
        if (mAllSeriesBottomDialog != null && mAllSeriesBottomDialog.isShow()) {
            mAllSeriesBottomDialog.dismiss();
            return;
        }
        if (playFragment.hideAllDialogSuccess()) {//fragment有弹窗隐藏并拦截返回
            return;
        }
        if (fullWindows) {
            toggleFullPreview();
            mBinding.mGridView.requestFocus();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && playFragment != null && fullWindows) {
            if (playFragment.dispatchKeyEvent(event)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // preview
    VodInfo previewVodInfo = null;
    boolean showPreview = Hawk.get(HawkConfig.SHOW_PREVIEW, true);
    ; // true 开启 false 关闭
    boolean fullWindows = false;
    /**
     * 对外暴露全屏状态,供 PlayFragment 等其他包的类判断当前是否已经处于全屏,
     * 避免重复调用 toggleFullPreview 造成状态错乱。
     */
    public boolean isFullWindows() { return fullWindows; }

    ViewGroup.LayoutParams windowsPreview = null;
    ViewGroup.LayoutParams windowsFull = null;

    /**
     * 预览播放器高度按视频真实比例动态计算(替代固定 16:9),消除超宽/超竖视频的上下/左右黑边。
     * 同时设置 min/max 上限,避免竖屏小视频或过窄视频把预览区撑得过高/过矮。
     * 同步占位 view 高度,避免内容被遮挡或出现空白。
     */
    public void applyPreviewPlayerRatio() {
        if (!showPreview) return;
        // 全屏状态下容器必须保持 MATCH_PARENT,不能按 16:9 重算。
        // 横屏时屏宽(如 2340) * 9/16 = 1316 > 屏高(如 1080),会把容器撑得比屏幕还高,
        // 导致视频下半部分落到屏幕可见区域之外(表现为"上面留空、下面播放不完整")。
        if (fullWindows) return;
        // 注意:width 必须用"当前真实宽度"重算,不能直接复用上一次缓存的 windowsPreview。
        // 否则横屏下退出全屏时会套用竖屏时代的 1080x607,导致画面缩成一小块、四周黑边。
        int width = mBinding.previewPlayer.getWidth();
        if (width <= 0) {
            width = ScreenUtils.getScreenWidth();
        }

        // 优先按视频实际比例计算高度;未获取到视频尺寸时回退 16:9
        int height;
        MyVideoView player = (playFragment != null) ? playFragment.getPlayer() : null;
        int[] videoSize = (player != null) ? player.getVideoSize() : null;
        int videoW = 0, videoH = 0;
        if (videoSize != null && videoSize[0] > 0 && videoSize[1] > 0) {
            videoW = videoSize[0];
            videoH = videoSize[1];
            height = Math.round((float) width * videoH / videoW);
            // 竖屏视频(宽高比 < 1)限制最大高度,避免预览区占满整屏
            if (videoW < videoH) {
                int maxH = Math.round(ScreenUtils.getScreenHeight() * 0.55f);
                if (height > maxH) height = maxH;
            }
        } else {
            height = Math.round(width * 9f / 16f);
        }

        // 统一上下限:最小 200dp,最大 55% 屏高
        int minHeight = (int) (getResources().getDisplayMetrics().density * 200);
        int maxHeight = Math.round(ScreenUtils.getScreenHeight() * 0.55f);
        if (height < minHeight) height = minHeight;
        if (height > maxHeight) height = maxHeight;

        // 与上次设置相同时跳过,避免状态切换时反复 requestLayout
        if (width == mLastPreviewWidth && videoW == mLastPreviewVideoW && videoH == mLastPreviewVideoH) {
            return;
        }
        ViewGroup.LayoutParams playerLp = mBinding.previewPlayer.getLayoutParams();
        if (playerLp != null) {
            playerLp.height = height;
            playerLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            mBinding.previewPlayer.setLayoutParams(playerLp);
            // 用重算后的参数刷新缓存,保证退出全屏时恢复的是与当前屏幕匹配的尺寸
            windowsPreview = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, height);
            android.util.Log.d("TVBoxDiag", "applyPreviewPlayerRatio width=" + width + " height=" + height
                    + " video=" + (videoSize != null ? videoSize[0] + "x" + videoSize[1] : "null")
                    + " screen=" + ScreenUtils.getScreenWidth() + "x" + ScreenUtils.getScreenHeight()
                    + " orient=" + getResources().getConfiguration().orientation
                    + " previewW=" + mBinding.previewPlayer.getWidth() + " previewH=" + mBinding.previewPlayer.getHeight());
            diagLayout("preview-ratio");
        }
        ViewGroup.LayoutParams placeLp = mBinding.previewPlayerPlace.getLayoutParams();
        if (placeLp != null) {
            placeLp.height = height;
            mBinding.previewPlayerPlace.setLayoutParams(placeLp);
        }
        mLastPreviewWidth = width;
        mLastPreviewVideoW = videoW;
        mLastPreviewVideoH = videoH;
    }

    /**
     * 诊断:打印 previewPlayer / videoView / window insets 的真实屏幕落点,定位"居中不对称"根因。
     * 仅日志,不改变任何布局行为。
     */
    private void diagLayout(String tag) {
        try {
            mBinding.previewPlayer.post(() -> {
                try {
                    View decor = getWindow().getDecorView();
                    int[] decorLoc = new int[2];
                    decor.getLocationOnScreen(decorLoc);
                    int[] ppLoc = new int[2];
                    mBinding.previewPlayer.getLocationOnScreen(ppLoc);
                    android.graphics.Rect ppVis = new android.graphics.Rect();
                    mBinding.previewPlayer.getGlobalVisibleRect(ppVis);
                    View dv = (playFragment != null) ? playFragment.getPlayer() : null;
                    int[] dvLoc = new int[2];
                    if (dv != null) dv.getLocationOnScreen(dvLoc);
                    StringBuilder insetsInfo = new StringBuilder("no-insets");
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        android.view.WindowInsets insets = decor.getRootWindowInsets();
                        if (insets != null) {
                            insetsInfo.setLength(0);
                            insetsInfo.append("sys L").append(insets.getSystemWindowInsetLeft())
                                    .append(" T").append(insets.getSystemWindowInsetTop())
                                    .append(" R").append(insets.getSystemWindowInsetRight())
                                    .append(" B").append(insets.getSystemWindowInsetBottom());
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                android.view.DisplayCutout dc = insets.getDisplayCutout();
                                if (dc != null) {
                                    insetsInfo.append(" cutoutSafe L").append(dc.getSafeInsetLeft())
                                            .append(" T").append(dc.getSafeInsetTop())
                                            .append(" R").append(dc.getSafeInsetRight())
                                            .append(" B").append(dc.getSafeInsetBottom());
                                }
                            }
                        }
                    }
                    int winFlags = getWindow().getAttributes().flags;
                    boolean hasNoLimits = (winFlags & android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS) != 0;
                    String videoInfo = "null";
                    if (dv instanceof MyVideoView) {
                        MyVideoView mv = (MyVideoView) dv;
                        videoInfo = "loc=" + dvLoc[0] + "," + dvLoc[1]
                                + " size=" + dv.getWidth() + "x" + dv.getHeight()
                                + " scale=" + mv.getCurrentScreenScaleType()
                                + " childCount=" + mv.getChildCount();
                        if (mv.getChildCount() > 0) {
                            View rv = mv.getChildAt(0);
                            int[] rvLoc = new int[2];
                            rv.getLocationOnScreen(rvLoc);
                            videoInfo += " renderLoc=" + rvLoc[0] + "," + rvLoc[1]
                                    + " renderSize=" + rv.getWidth() + "x" + rv.getHeight();
                        }
                    }
                    android.util.Log.d("TVBoxDiag", tag
                            + " decorLoc=" + decorLoc[0] + "," + decorLoc[1]
                            + " decorSize=" + decor.getWidth() + "x" + decor.getHeight()
                            + " noLimits=" + hasNoLimits
                            + " ppLoc=" + ppLoc[0] + "," + ppLoc[1]
                            + " ppSize=" + mBinding.previewPlayer.getWidth() + "x" + mBinding.previewPlayer.getHeight()
                            + " ppVisibleTop=" + ppVis.top + " ppVisibleBottom=" + ppVis.bottom
                            + " " + videoInfo
                            + " " + insetsInfo);
                    // 扩展诊断:content frame / root / llLayout / previewPlayer layoutParams,
                    // 用于定位 PiP 小窗里 previewPlayer 尺寸异常(MATCH_PARENT 没拿到完整 decor 尺寸)。
                    try {
                        View contentFrame = findViewById(android.R.id.content);
                        View root = mBinding.getRoot();
                        ViewGroup.LayoutParams ppLp = mBinding.previewPlayer.getLayoutParams();
                        String lpInfo = "null";
                        if (ppLp instanceof FrameLayout.LayoutParams) {
                            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) ppLp;
                            lpInfo = "w=" + flp.width + " h=" + flp.height
                                    + " top=" + flp.topMargin + " grav=0x" + Integer.toHexString(flp.gravity);
                        } else if (ppLp != null) {
                            lpInfo = "w=" + ppLp.width + " h=" + ppLp.height + " (non-FrameLp)";
                        }
                        android.util.Log.d("TVBoxDiag", tag + " EXT"
                                + " content=" + (contentFrame != null ? contentFrame.getWidth() + "x" + contentFrame.getHeight() + " padTop=" + contentFrame.getPaddingTop() : "null")
                                + " root=" + root.getWidth() + "x" + root.getHeight() + " padT=" + root.getPaddingTop()
                                + " llVis=" + (mBinding.llLayout != null ? mBinding.llLayout.getVisibility() : "?")
                                + " llBounds=" + (mBinding.llLayout != null ? mBinding.llLayout.getWidth() + "x" + mBinding.llLayout.getHeight() + "@" + mBinding.llLayout.getTop() : "?")
                                + " ppLp=" + lpInfo);
                    } catch (Throwable t2) {
                        android.util.Log.d("TVBoxDiag", tag + " extErr " + t2);
                    }
                } catch (Throwable t) {
                    android.util.Log.d("TVBoxDiag", tag + " diagErr " + t);
                }
            });
        } catch (Throwable t) {
            android.util.Log.d("TVBoxDiag", tag + " diagErrOuter " + t);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        android.util.Log.d("TVBoxDiag", "onConfigurationChanged orient=" + newConfig.orientation + " fullWindows=" + fullWindows);
        // 屏幕方向变了,之前缓存的非全屏尺寸(按旧方向的屏宽算的 16:9)已经作废,
        // 必须清掉,避免退出全屏时套用旧尺寸导致画面缩成一小块。
        windowsPreview = null;
        // 延迟到布局完成后再判断窗口真实尺寸,避免拿到旋转中间态。
        mBinding.previewPlayer.post(() -> {
            int winW = mBinding.getRoot().getWidth();
            int screenW = com.blankj.utilcode.util.ScreenUtils.getScreenWidth();
            boolean isSmallWindow = winW > 0 && screenW > 0 && winW < screenW * 0.85;
            android.util.Log.d("TVBoxDiag", "onConfigurationChanged post winW=" + winW
                    + " screenW=" + screenW + " isSmallWindow=" + isSmallWindow);
            if (isSmallWindow) {
                // 系统小窗/freeform:Activity 被缩成浮窗,全屏残留尺寸(撑满屏)会超出浮窗被裁,
                // 只显示中间一块。按当前窗口宽重算 16:9 适配小窗,并退出沉浸
                // (FLAG_LAYOUT_NO_LIMITS 会把内容推出浮窗边界,必须清除)。
                //
                // 但:进入 PiP 模式时,Activity 也会被系统缩小到浮窗尺寸,但 PiP 的 resize
                // 由 onPictureInPictureModeChanged 处理(它还要隐藏非视频 UI),这里如果也
                // resize 会互相覆盖、导致视频容器被设回 460x892 出现上下黑条。
                // 因此:PiP 模式下跳过,只处理真正的 freeform multi-window。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode()) {
                    android.util.Log.d("TVBoxDiag", "onConfigurationChanged skip isSmallWindow=PiP");
                    return;
                }
                // freeform 多窗口(非 PiP):previewPlayer 撑满整个浮窗,video CENTER_CROP 让
                // 视频占满 previewPlayer(避免 aspect 不匹配导致的 letterbox)。
                // 浮窗实际尺寸已经通过 onConfigurationChanged 的 winW 参数传入。
                View videoViewFreeRaw = playFragment != null ? playFragment.getPlayer() : null;
                final MyVideoView videoViewFree = videoViewFreeRaw instanceof MyVideoView ? (MyVideoView) videoViewFreeRaw : null;
                if (videoViewFree != null) {
                    videoViewFree.setScreenScaleType(VideoView.SCREEN_SCALE_CENTER_CROP);
                }
                ViewGroup.LayoutParams lp = mBinding.previewPlayer.getLayoutParams();
                if (lp == null) lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                else { lp.width = ViewGroup.LayoutParams.MATCH_PARENT; lp.height = ViewGroup.LayoutParams.MATCH_PARENT; }
                mBinding.previewPlayer.setLayoutParams(lp);
                ViewGroup.LayoutParams placeLp = mBinding.previewPlayerPlace.getLayoutParams();
                if (placeLp != null) { placeLp.height = ViewGroup.LayoutParams.MATCH_PARENT; mBinding.previewPlayerPlace.setLayoutParams(placeLp); }
                if (playFragment != null) playFragment.showSystemBars();
                mBinding.previewPlayer.post(() -> {
                    int w2 = mBinding.previewPlayer.getWidth();
                    int h2 = mBinding.previewPlayer.getHeight();
                    if (videoViewFree != null && w2 > 0 && h2 > 0) {
                        videoViewFree.measure(
                                View.MeasureSpec.makeMeasureSpec(w2, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(h2, View.MeasureSpec.EXACTLY));
                    }
                    mBinding.previewPlayer.requestLayout();
                    if (videoViewFree != null) videoViewFree.requestLayout();
                });
                return;
            }
            if (fullWindows) {
                // 全屏状态:容器继续撑满可视区,重新进入沉浸(可能从小窗退回),并触发重测居中。
                // 这里不能用 MATCH_PARENT(理由见 toggleFullPreview 注释):
                // Android 14/MIUI 上 WindowInsets 仍会扣掉状态栏/导航栏的高度,
                // 必须显式按屏幕方向取真实物理高度,视频才能占满整屏不被上下裁切。
                int screenH = getOrientationAdjustedScreenHeight();
                // 容器高度必须等于可视区高度,而不是视频比例高度。
                // 之前按"视频宽高比"缩小容器后,用 Gravity.CENTER 居中,但 FLAG_LAYOUT_NO_LIMITS
                // 会让根视图延伸到刘海/手势条区域,导致"屏幕中心"和"可见区域中心"不一致,
                // 黑边看起来上下不对称。
                // 改成容器撑满可视高度,把视频比例交给播放器内部缩放(默认 fit center),
                // 这样 letterbox 黑边由播放器自动对称添加,不受容器居中偏移影响。
                int newH = screenH;
                int w = mBinding.previewPlayer.getWidth();
                if (w <= 0) w = mBinding.getRoot().getWidth();
                android.util.Log.d("TVBoxDiag", "onConfigurationChanged fullWindows screenW=" + w
                        + " screenH=" + screenH + " previewPlayerH=" + newH);
                // 同步刷新缓存的 windowsFull
                if (windowsFull == null || windowsFull.height != newH) {
                    windowsFull = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, newH);
                }
                FrameLayout.LayoutParams ppLp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, newH);
                ppLp.gravity = android.view.Gravity.TOP;
                mBinding.previewPlayer.setLayoutParams(ppLp);
                // 清除 content frame padding(Android 15+ edge-to-edge 自动加 statusBarHeight 的 paddingTop,
                // rotation 后沉浸态残留导致容器被顶到 y=134,见 toggleFullPreview 注释)
                clearContentFramePadding();
                if (mBinding.llLayout != null) mBinding.llLayout.setVisibility(View.GONE);
                if (playFragment != null) playFragment.hideSystemBars();

                // 强制 video view 按新尺寸重新 measure
                View videoView = playFragment != null ? playFragment.getPlayer() : null;
                if (videoView != null && w > 0 && newH > 0) {
                    videoView.measure(
                            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(newH, View.MeasureSpec.EXACTLY));
                    android.util.Log.d("TVBoxDiag", "onConfigurationChanged force measure video "
                            + w + "x" + newH);
                }
                mBinding.previewPlayer.requestLayout();
                if (videoView != null) videoView.requestLayout();
                diagLayout("LAND-fullscreen");
            } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
                // ★ Bug 修复兜底:onConfigurationChanged 收到 PORTRAIT 通知时,
                // 如果 fullWindows 标志位还在 true(说明用户走的是 PlayerTitleView 的返回按钮,
                // 那条路径完全没调 toggleFullPreview),就在这里强制复位。
                android.util.Log.d("TVBoxDiag", "onConfigurationChanged PORTRAIT but fullWindows=true, force exit fullscreen");
                fullWindows = false;
                if (playFragment != null) playFragment.changedLandscape(false);
                exitFullscreenLayout();
                mBinding.previewPlayer.post(this::applyPreviewPlayerRatio);
            } else {
                // 非全屏:旋转/分屏后重新按 16:9 计算预览播放器高度
                applyPreviewPlayerRatio();
            }
        });
    }

    public void toggleFullPreview() {
        // previewPlayer 高度按视频 aspect 计算(不是屏幕高度)。
        // 原因见 onConfigurationChanged fullWindows 分支的注释:
        // 超宽视频按宽度 fit 后高度 < 屏幕高度,如果 previewPlayer = 屏幕高度,
        // 视频上下会留黑条。改成 previewPlayer.height = width * vH/vW 让视频 1:1 填满。
        //
        // 进入全屏前 Activity 还是竖屏,退出全屏前还是横屏,必须用"目标方向"的宽度计算,
        // 否则会出现 505/1124 这种过渡高度,导致方向切换瞬间视频被压扁/撑大。
        boolean enteringFull = !fullWindows;
        int screenH = getOrientationAdjustedScreenHeight();
        int newH = enteringFull ? screenH : screenH; // 退出全屏后由 applyPreviewPlayerRatio 重算
        int sw = ScreenUtils.getScreenWidth();
        int sh = ScreenUtils.getScreenHeight();
        int w = enteringFull
                ? Math.max(sw, sh)   // 目标横屏宽度
                : Math.min(sw, sh);  // 目标竖屏宽度
        if (w <= 0) w = mBinding.previewPlayer.getWidth();
        if (w <= 0) w = mBinding.getRoot().getWidth();
        android.util.Log.d("TVBoxDiag", "toggleFullPreview enteringFull=" + enteringFull
                + " screenW=" + w + " newH=" + newH);
        if (windowsFull == null || windowsFull.height != newH) {
            windowsFull = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, newH);
        }
        fullWindows = !fullWindows;
        android.util.Log.d("TVBoxDiag", "toggleFullPreview -> fullWindows=" + fullWindows);

        // ★ 进入全屏时,根据用户意图决定是否强制横屏:
        // - 横屏全屏按钮 → 强制横屏
        // - 竖屏全屏按钮 / 系统返回键 → 不强制,尊重当前 Activity 方向
        // (默认是横屏全屏按钮的语义,外部通过 toggleFullPreviewWithIntent 显式指定)
        playFragment.changedLandscape(fullWindows);
        if (fullWindows) {
            // 进入全屏:根布局 padding 清零 + 隐藏 llLayout + 容器 TOP gravity 铺满。
            // 之前 Gravity.CENTER + MATCH_PARENT 在有 paddingTop 的父布局里会被顶到 y=67,
            // 视频 letterbox 在容器内对称,但容器本身没贴在屏幕顶部 → 看起来"上黑下白"不对称。
            // 同时 llLayout(详情面板)在 toggleFullPreview 只 GONE 了 mGridView/mGridViewFlag,
            // 整个 LinearLayout 仍占 wrap_content 高度,在某些设备上残留可见条带。
            // 关键:134px 的偏移不是 mBinding.getRoot() 的 padding(那本来就是 0),
            // 而是 android.R.id.content 这个 content frame 在 Android 15+ edge-to-edge
            // 下被系统按 statusBarHeight 加的 paddingTop,rotation 后沉浸态残留。
            // 必须清掉 content frame 的 padding 才能让容器贴到屏幕 y=0。
            clearContentFramePadding();
            if (mBinding.llLayout != null) mBinding.llLayout.setVisibility(View.GONE);
            FrameLayout.LayoutParams ppLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, newH);
            ppLp.gravity = android.view.Gravity.TOP;
            mBinding.previewPlayer.setLayoutParams(ppLp);
        } else {
            // 退出全屏:清掉可能过期的缓存,按"当前真实屏幕方向"重新算 16:9,
            // 避免横屏下套用竖屏缓存的 1080x607 导致画面缩成一块。
            //
            // ★ Bug 修复:之前漏写 llLayout.setVisibility(VISIBLE) 导致从全屏返回后
            // 整个详情面板(标题/线路/选集/简介)永远消失,只看到底部一片黑屏。
            // 同时强制把 previewPlayerPlace 占位 LinearLayout 高度重置回 wrap_content,
            // 避免上次全屏残留的 MATCH_PARENT 把占位区域撑爆覆盖整个布局。
            exitFullscreenLayout();
            mBinding.previewPlayer.post(this::applyPreviewPlayerRatio);
        }
        mBinding.mGridView.setVisibility(fullWindows ? View.GONE : View.VISIBLE);
        mBinding.mGridViewFlag.setVisibility(fullWindows ? View.GONE : View.VISIBLE);

        //全屏下禁用详情页几个按键的焦点 防止上键跑过来
        mBinding.tvSort.setFocusable(!fullWindows);
        mBinding.tvCollect.setFocusable(!fullWindows);
        toggleSubtitleTextSize();
    }

    /**
     * 恢复非全屏状态下的详情页面布局。
     * <p>
     * 该方法抽出来是为了让 PlayerTitleView(播放器左上角返回按钮)和
     * onConfigurationChanged(屏幕旋转)两条路径都能调用,确保无论从哪条路径
     * 退出全屏,详情面板都能正确恢复显示。
     * <p>
     * BUG 历史:之前这个逻辑只在 toggleFullPreview 的 else 分支写了一半,
     * 详情面板在进入全屏时被 GONE 了但退出全屏时没人把它设回 VISIBLE,
     * 导致用户每次从全屏返回都看到空白的详情页。
     */
    public void exitFullscreenLayout() {
        if (mBinding.llLayout != null) {
            mBinding.llLayout.setVisibility(View.VISIBLE);
        }
        // previewPlayerPlace 是详情面板里的占位 LinearLayout,
        // 全屏状态下被人改成了 MATCH_PARENT(见 toggleFullPreview 进入全屏分支),
        // 退出时必须还原成 wrap_content,否则会把整个 ScrollView 挤出去。
        if (mBinding.previewPlayerPlace != null) {
            ViewGroup.LayoutParams placeLp = mBinding.previewPlayerPlace.getLayoutParams();
            if (placeLp != null) {
                placeLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                mBinding.previewPlayerPlace.setLayoutParams(placeLp);
            }
        }
        // 清掉全屏缓存,让 applyPreviewPlayerRatio 按当前视频比例重新计算
        windowsPreview = null;
        // previewPlayer 容器还原成 wrap_content,等比例尺寸由 applyPreviewPlayerRatio 写入
        mBinding.previewPlayer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 重新恢复详情页的系统栏状态(状态栏背景色 / 导航栏颜色 / ImmersionBar)
        if (playFragment != null) {
            playFragment.showSystemBars();
        }
        android.util.Log.d("TVBoxDiag", "exitFullscreenLayout: llLayout=VISIBLE previewPlayerPlace=WRAP");
    }

    /**
     * 返回"按当前 Activity 方向调整过的屏幕高度"。
     * 横屏时 ScreenUtils.getScreenHeight() 仍是 portrait 高度(竖屏的较长边),
     * 但我们此时需要的是横屏可视高度(=物理高度里较短的边)。
     * 不能直接用 Configuration.orientation 判断,因为 MIUI 上 ImmersionBar /
     * 物理旋转有时还没同步过来。
     */
    private int getOrientationAdjustedScreenHeight() {
        Configuration cfg = getResources().getConfiguration();
        int w = com.blankj.utilcode.util.ScreenUtils.getScreenWidth();
        int h = com.blankj.utilcode.util.ScreenUtils.getScreenHeight();
        // Configuration.ORIENTATION_LANDSCAPE == 2,PORTRAIT == 1
        if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // 横屏:可视高度是较短的那一边
            return Math.min(w, h);
        }
        // 竖屏(或传感器自由旋转中):取较长边,确保竖屏预览也能撑满
        return Math.max(w, h);
    }

    /**
     * 清除 android.R.id.content(content frame)的 padding。
     * Android 15+ edge-to-edge 下系统会按 statusBarHeight 自动给 content frame 加
     * paddingTop。竖屏时这是正确的(避免内容被状态栏遮挡);但横屏沉浸态 / PiP 小窗
     * 状态下状态栏已隐藏,该 padding 不会被自动清掉,残留把我们的预览容器顶到 y=134,
     * 导致横屏/PiP 视频 letterbox 在屏幕坐标系里不对称(上黑下白/上白下黑)。
     *
     * 退出全屏/小窗后会重新触发 onApplyWindowInsets,系统会按需恢复 padding,
     * 所以这里不用手动还原。
     */
    private void clearContentFramePadding() {
        try {
            View content = findViewById(android.R.id.content);
            if (content != null) {
                content.setPadding(0, 0, 0, 0);
                android.util.Log.d("TVBoxDiag", "clearContentFramePadding paddingTop="
                        + content.getPaddingTop() + " -> 0");
            }
        } catch (Throwable t) {
            android.util.Log.d("TVBoxDiag", "clearContentFramePadding err " + t);
        }
    }

    /**
     * 在 android.R.id.content 上装一个永久的 OnApplyWindowInsetsListener。
     * - 全屏 / PiP 状态:消耗 insets 并清零 padding,避免系统把 statusBarHeight 当 paddingTop
     *   灌回 content frame(实测横屏→小窗切换时会被反复覆盖成 134)。
     * - 非全屏状态:不消耗,让 insets 正常流动,系统按默认行为应用 padding
     *   (状态栏/导航栏 inset 仍能正确避让)。
     */
    private void installContentInsetsListener() {
        try {
            View content = findViewById(android.R.id.content);
            if (content == null) return;
            content.setOnApplyWindowInsetsListener((v, insets) -> {
                // 全屏 或 PiP 小窗 状态下都清零 padding,防止 ImmersionBar / edge-to-edge
                // 把 statusBarHeight 灌回 content frame paddingTop(=134px),
                // 导致视频容器被顶到浮窗底部(横屏则是顶部出现大黑边)。
                if (fullWindows || isInPictureInPictureMode()) {
                    v.setPadding(0, 0, 0, 0);
                    return android.view.WindowInsets.CONSUMED;
                }
                return insets;
            });
        } catch (Throwable t) {
            android.util.Log.d("TVBoxDiag", "installContentInsetsListener err " + t);
        }
    }

    void toggleSubtitleTextSize() {
        int subtitleTextSize = SubtitleHelper.getTextSize(this);
        if (!fullWindows) {
            subtitleTextSize *= 0.6;
        }
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE, subtitleTextSize));
    }

    public void use1DMDownload() {
        if (vodInfo != null && vodInfo.seriesMap.get(vodInfo.playFlag).size() > 0) {
            VodInfo.VodSeries vod = vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex);
            String url = TextUtils.isEmpty(playFragment.getFinalUrl()) ? vod.url : playFragment.getFinalUrl();
            // 创建Intent对象，启动1DM App
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setDataAndType(Uri.parse(url), "video/mp4");
            intent.putExtra("title", vodInfo.name + " " + vod.name); // 传入文件保存名
//            intent.setClassName("idm.internet.download.manager.plus", "idm.internet.download.manager.MainActivity");
            intent.setClassName("idm.internet.download.manager.plus", "idm.internet.download.manager.Downloader");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // 检查1DM App是否已安装
            PackageManager pm = getPackageManager();
            List<ResolveInfo> activities = pm.queryIntentActivities(intent, 0);
            boolean isIntentSafe = activities.size() > 0;

            if (isIntentSafe) {
                startActivity(intent); // 启动1DM App
            } else {
                // 如果1DM App未安装，提示用户安装1DM App
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("请先安装1DM+下载管理器");
                builder.setMessage("为了下载视频，请先安装1DM+下载管理器。是否现在安装？");
                builder.setPositiveButton("立即下载", new DialogInterface.OnClickListener() {

                    public void onClick(DialogInterface dialog, int which) {
                        // 跳转到下载链接
                        Intent downloadIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://od.lk/d/MzRfMTg0NTcxMDdf/1DM _v15.6.apk"));
                        startActivity(downloadIntent);
                    }
                });
                builder.setNegativeButton("取消", null);
                builder.show();
            }
        } else {
            ToastUtils.showShort("资源异常,请稍后重试");
        }
    }

    /**
     * DLNA 投屏
     */
    public void onCastClick() {
        if (playFragment == null) {
            ToastUtils.showShort("播放器未就绪");
            return;
        }
        String url = playFragment.getFinalUrl();
        if (url == null || url.isEmpty()) {
            ToastUtils.showShort("无法获取播放地址");
            return;
        }
        String[] title = {""};
        if (vodInfo != null && vodInfo.name != null) {
            title[0] = vodInfo.name;
            if (vodInfo.seriesMap != null) {
                for (java.util.Map.Entry<String, java.util.List<VodInfo.VodSeries>> entry : vodInfo.seriesMap.entrySet()) {
                    if (entry.getValue() != null) {
                        for (VodInfo.VodSeries series : entry.getValue()) {
                            if (series != null && series.selected) {
                                title[0] = vodInfo.name + " - " + series.name;
                                break;
                            }
                        }
                    }
                }
            }
        }
        final String castTitle = title[0];
        com.github.tvbox.osc.dlna.DlnaCastDialog dialog = new com.github.tvbox.osc.dlna.DlnaCastDialog(this,
                new com.github.tvbox.osc.dlna.DlnaCastDialog.OnCastListener() {
                    @Override
                    public void onDeviceSelected(com.github.tvbox.osc.dlna.DlnaDevice device) {
                        // 设备选择后在 dialog 内部处理
                    }

                    @Override
                    public void onStopRequested(com.github.tvbox.osc.dlna.DlnaDevice device) {
                        // 停止投屏
                    }

                    @Override
                    public String getPlayUrl() {
                        return playFragment != null ? playFragment.getFinalUrl() : null;
                    }

                    @Override
                    public String getVideoTitle() {
                        return castTitle;
                    }
                });
        dialog.show();
    }

    /**
     * 画中画模式
     */
    public void enterPip() {
        if (Utils.supportsPiPMode()) {
            // 创建一个Intent对象，模拟按下Home键
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            startActivity(intent);

            // Calculate Video Resolution
            MyVideoView pipPlayer = (playFragment != null) ? playFragment.getPlayer() : null;
            int vWidth = 0, vHeight = 0;
            if (pipPlayer != null) {
                int[] vs = pipPlayer.getVideoSize();
                if (vs != null && vs.length >= 2) {
                    vWidth = vs[0];
                    vHeight = vs[1];
                }
            }
            Rational ratio;
            if (vWidth != 0 && vHeight != 0) {
                // 超宽电影(>2.39:1)按 2.35:1 收敛,避免 PiP 窗口过扁无法操作。
                // 其余视频保持原始宽高比,让 PiP 窗口与视频比例一致,视频能 1:1 填满,
                // 避免 16:9 窗口里装 2.39:1 视频出现上下不对称黑边。
                if ((((double) vWidth) / ((double) vHeight)) > 2.39) {
                    vHeight = (int) (((double) vWidth) / 2.35);
                }
                ratio = new Rational(vWidth, vHeight);
            } else {
                ratio = new Rational(16, 9);
            }
            List<RemoteAction> actions = new ArrayList<>();
            actions.add(generateRemoteAction(android.R.drawable.ic_media_previous, IntentKey.BROADCAST_ACTION_PREV, "Prev", "Play Previous"));
            actions.add(generateRemoteAction(android.R.drawable.ic_media_play, IntentKey.BROADCAST_ACTION_PLAYPAUSE, "Play", "Play/Pause"));
            actions.add(generateRemoteAction(android.R.drawable.ic_media_next, IntentKey.BROADCAST_ACTION_NEXT, "Next", "Play Next"));
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(ratio)
                    .setActions(actions).build();
            playFragment.getPlayer().postDelayed(() -> {//代码模拟home键时会立即执行,toggleFullPreview中竖屏有切换横屏操作,
                if (!fullWindows) {
                    toggleFullPreview();
                }
            }, 300);
            enterPictureInPictureMode(params);
            playFragment.getController().hideBottom();

            playFragment.getPlayer().postDelayed(() -> {
                if (!playFragment.getPlayer().isPlaying()) {
                    playFragment.getController().togglePlay();
                }
            }, 400);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private RemoteAction generateRemoteAction(int iconResId, int actionCode, String title, String desc) {
        // Android 12 (API 31) 起必须显式指定 FLAG_IMMUTABLE 或 FLAG_MUTABLE,否则抛 IllegalArgumentException。
        // 此处 PendingIntent 仅用于触发固定 action 的广播,无需被修改,故用 FLAG_IMMUTABLE。
        // setPackage 让隐式广播只定向到本应用,避免被其它应用拦截。
        final PendingIntent intent =
                PendingIntent.getBroadcast(
                        DetailActivity.this,
                        actionCode,
                        new Intent(IntentKey.BROADCAST_ACTION)
                                .putExtra("action", actionCode)
                                .setPackage(getPackageName()),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final Icon icon = Icon.createWithResource(DetailActivity.this, iconResId);
        return (new RemoteAction(icon, title, desc, intent));
    }

    /**
     * 事件接收广播(画中画/后台播放点击事件)
     * @param isRegister 注册/注销
     */
    private void registerActionReceiver(boolean isRegister) {
        if (isRegister) {
            mRemoteActionReceiver = new BroadcastReceiver() {

                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !intent.getAction().equals(IntentKey.BROADCAST_ACTION) || playFragment.getController() == null) {
                        return;
                    }

                    int currentStatus = intent.getIntExtra("action", 1);
                    if (currentStatus == IntentKey.BROADCAST_ACTION_PREV) {
                        playFragment.playPrevious();
                    } else if (currentStatus == IntentKey.BROADCAST_ACTION_PLAYPAUSE) {
                        playFragment.getController().togglePlay();
                    } else if (currentStatus == IntentKey.BROADCAST_ACTION_NEXT) {
                        playFragment.playNext(false);
                    } else if (currentStatus == IntentKey.BROADCAST_ACTION_CLOSE) {
                        playServerSwitch(false);
                        finish();
                        NotificationUtils.cancelAll();
                    }
                }
            };
            // Android 14 (API 34) 兼容
            ReceiverCompat.registerSafe(this, mRemoteActionReceiver, new IntentFilter(IntentKey.BROADCAST_ACTION));
        } else {
            if (mRemoteActionReceiver != null) {
                unregisterReceiver(mRemoteActionReceiver);
                mRemoteActionReceiver = null;
            }
            if (playFragment.getPlayer().isPlaying()) {// 退出画中画时,暂停播放(画中画的全屏也会触发,但全屏后会自动播放)
                playFragment.getController().togglePlay();
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        android.util.Log.d("TVBoxDiag", "onPictureInPictureModeChanged isPiP=" + isInPictureInPictureMode
                + " fullWindows=" + fullWindows);
        registerActionReceiver(Utils.supportsPiPMode() && isInPictureInPictureMode);
        if (isInPictureInPictureMode) {
            // 保存进入 PiP 前的 video scale,退出 PiP 时恢复(用户可能选过 16:9/4:3 等)
            View videoViewForSave = playFragment != null ? playFragment.getPlayer() : null;
            if (videoViewForSave instanceof MyVideoView) {
                mSavedScreenScaleBeforePip = ((MyVideoView) videoViewForSave).getCurrentScreenScaleType();
            }
            // PiP 浮窗 = 系统把 Activity 全部 view 渲染到 overlay 窗口上。
            // 之前所有改动都"按视频 aspect 算 previewPlayer 高度" → 实际上视频 aspect
            // (2.388) 跟浮窗 aspect (16:9=1.78) 不一致,无论怎么布局都会有 letterbox:
            //   - previewPlayer = MATCH_PARENT: video 按宽度 fit 后上下留 letterbox
            //   - previewPlayer.height = width * vH/vW: previewPlayer 居中后 FrameLayout
            //     上下留 letterbox,视觉效果跟上面完全一样
            // 唯一真正消除 letterbox 的方案:SCREEN_SCALE_CENTER_CROP,video 撑满浮窗,
            // 视频按"较短边裁切"显示(超宽视频左右被裁)。
            //
            // PiP 浮窗本身也是用户可控的(可以拖动改变大小),无论选哪个 scale 都无法
            // 保证 100% 完美匹配视频比例,只能选一个"视觉上最合理"的策略。
            if (mBinding != null && mBinding.llLayout != null) {
                mBinding.llLayout.setVisibility(View.GONE);
            }
            // 切到 CENTER_CROP 模式,video 撑满浮窗
            final View videoViewRaw = playFragment != null ? playFragment.getPlayer() : null;
            final MyVideoView videoView = videoViewRaw instanceof MyVideoView ? (MyVideoView) videoViewRaw : null;
            if (videoView != null) {
                videoView.setScreenScaleType(VideoView.SCREEN_SCALE_CENTER_CROP);
            }
            // previewPlayer MATCH_PARENT 撑满浮窗(MATCH_PARENT 高度 = 浮窗高度)
            if (mBinding != null && mBinding.previewPlayer != null) {
                // 清除 content frame padding,防止 Android 15+ edge-to-edge 的 statusBar paddingTop
                // 把容器顶到浮窗底部(实测残留 134px,导致视频贴在小窗底部偏下)。
                clearContentFramePadding();
                // 暴力 fix:MATCH_PARENT 在 PiP 下会拿到 decor 高度但实际 root 还没重新 measure,
                // 导致 previewPlayer 只拿到 190(残留旧尺寸)而非 324(浮窗真实高度)。
                // 直接用 decor 实际宽高做精确像素设置,等布局稳定后再做一次(在 post 里)。
                final View decorView = getWindow().getDecorView();
                final FrameLayout.LayoutParams pipLp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                pipLp.gravity = android.view.Gravity.TOP;
                mBinding.previewPlayer.setLayoutParams(pipLp);
                mBinding.previewPlayer.requestLayout();
                // 第二轮:在 post 里用 decor 真实像素强制覆盖,确保拿到完整浮窗高度
                mBinding.previewPlayer.post(() -> {
                    int dw = decorView.getWidth();
                    int dh = decorView.getHeight();
                    if (dw > 0 && dh > 0) {
                        FrameLayout.LayoutParams lp2 = new FrameLayout.LayoutParams(dw, dh);
                        lp2.gravity = android.view.Gravity.TOP;
                        mBinding.previewPlayer.setLayoutParams(lp2);
                        mBinding.previewPlayer.measure(
                                View.MeasureSpec.makeMeasureSpec(dw, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(dh, View.MeasureSpec.EXACTLY));
                        mBinding.previewPlayer.requestLayout();
                        android.util.Log.d("TVBoxDiag", "PiP-forced previewPlayer=" + dw + "x" + dh);
                    }
                });
            }
            // previewPlayerPlace 占位也设成 MATCH_PARENT,避免留空
            if (mBinding != null && mBinding.previewPlayerPlace != null) {
                ViewGroup.LayoutParams placeLp = mBinding.previewPlayerPlace.getLayoutParams();
                if (placeLp != null) {
                    placeLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    mBinding.previewPlayerPlace.setLayoutParams(placeLp);
                }
            }
            // 触发 video view 重新 measure
            mBinding.previewPlayer.post(() -> {
                if (videoView != null) {
                    int w = mBinding.previewPlayer.getWidth();
                    int h = mBinding.previewPlayer.getHeight();
                    if (w > 0 && h > 0) {
                        videoView.measure(
                                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                    }
                    videoView.requestLayout();
                }
                android.util.Log.d("TVBoxDiag", "PiP previewPlayer=" + mBinding.previewPlayer.getWidth()
                        + "x" + mBinding.previewPlayer.getHeight()
                        + " scale=CENTER_CROP videoOut="
                        + (videoView != null && videoView.getMeasuredWidth() > 0
                            ? videoView.getMeasuredWidth() + "x" + videoView.getMeasuredHeight()
                            : "n/a"));
                diagLayout("PiP");
            });
        } else {
            // 退出 PiP:恢复 detail UI
            if (mBinding != null && mBinding.llLayout != null) {
                mBinding.llLayout.setVisibility(View.VISIBLE);
            }
            // 恢复用户选择的 video scale(VodController 里用户可能选了"默认/16:9/4:3"等)。
            // 这里恢复的是进入 PiP 前保存的 scale(BaseVideoController 没有暴露 getCurrentScale,
            // 所以我们用一个成员变量在 VodController 改 scale 时记下来,见 VodController 回调)。
            View videoView2 = playFragment != null ? playFragment.getPlayer() : null;
            if (videoView2 instanceof MyVideoView) {
                ((MyVideoView) videoView2).setScreenScaleType(mSavedScreenScaleBeforePip);
            }
            // 重新按当前方向计算预览尺寸
            applyPreviewPlayerRatio();
        }
    }

    /**
     * 后台播放服务开关,开启时注册操作广播,关闭时注销
     */
    private void playServerSwitch(boolean open) {
        if (open) {
            VodInfo.VodSeries vod = vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex);
            PlayService.start(playFragment.getPlayer(), vodInfo.name + "&&" + vod.name);
            registerActionReceiver(true);
        } else {
            if (ServiceUtils.isServiceRunning(PlayService.class)) {
                PlayService.stop();
                registerActionReceiver(false);
            }
        }
    }

    public String getCurrentVodUrl() {
        return playFragment == null ? "" : playFragment.getFinalUrl();
    }

    public void quickLineChange() {
        List<VodInfo.VodSeriesFlag> flags = seriesFlagAdapter.getData();
        if (flags.size() > 1) {
            int currentIndex = 0;
            for (int i = 0; i < flags.size(); i++) {
                if (flags.get(i).selected) {
                    currentIndex = i;
                }
            }
            currentIndex += 1;
            if (currentIndex >= flags.size()) {
                currentIndex = 0;
            }
            mBinding.mGridViewFlag.smoothScrollToPosition(currentIndex);
            chooseFlag(currentIndex);
            mBinding.mGridView.postDelayed(() -> chooseSeries(vodInfo.playIndex, true), 300);
        }
    }

    public void showParseRoot(boolean show, ParseAdapter adapter) {
        mBinding.rvParse.setAdapter(adapter);
        int defaultIndex = 0;
        for (int i = 0; i < adapter.getData().size(); i++) {
            if (adapter.getData().get(i).isDefault()) {
                defaultIndex = i;
                break;
            }
        }
        if (defaultIndex != 0) {
            mBinding.rvParse.scrollToPosition(defaultIndex);
        }
        mBinding.parseRoot.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void toggleScreenShotListen(boolean open) {
        if (open){
            if (screenShotListenManager == null){
                screenShotListenManager = ScreenShotListenManager.newInstance(this);
            }
            screenShotListenManager.setListener(imagePath -> {

                if (playFragment.getPlayer().isInPlaybackState())return;

                new XPopup.Builder(this)
                        .isDarkTheme(Utils.isDarkTheme())
                        .asCenterList("",new String[]{"跳转阿狸","跳转优汐","跳转夸父","关闭"}, null, (position, text) -> {
                            String pkg = "";
                            String cls = "";
                            switch (position){
                                case 0:
                                    pkg = "com.alicloud.databox";
                                    cls = "com.alicloud.databox.launcher.splash.SplashActivity";
                                    break;
                                case 1:
                                    pkg = "com.UCMobile";
                                    cls = "com.uc.browser.InnerUCMobile";
                                    break;
                                case 2:
                                    pkg = "com.quark.browser";
                                    cls = "com.ucpro.MainActivity";
                                    break;
                                case 3:
                                    return;
                            }
                            try {
                                startActivity(new Intent().setComponent(new ComponentName(pkg, cls)));
                            }catch (Exception e){
                                ToastUtils.showShort("未找到应用");
                            }
                        })
                        .show();
            });
            screenShotListenManager.startListen();
        }else {
            if (screenShotListenManager != null) {
                screenShotListenManager.stopListen();
            }
        }
    }
}
