package com.github.tvbox.osc.ui.fragment;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.IntEvaluator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import android.graphics.PorterDuff;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.blankj.utilcode.util.ConvertUtils;
import com.blankj.utilcode.util.ScreenUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.LiveHost;
import com.github.tvbox.osc.base.MainTabHost;
import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.bean.LivePlayerManager;
import com.github.tvbox.osc.bean.LiveSettingGroup;
import com.github.tvbox.osc.bean.LiveSettingItem;
import com.github.tvbox.osc.player.controller.LiveNewController;
import com.github.tvbox.osc.ui.adapter.LiveChannelGroupNewAdapter;
import com.github.tvbox.osc.ui.adapter.LiveChannelItemNewAdapter;
import com.github.tvbox.osc.ui.adapter.LiveSettingGroupAdapter;
import com.github.tvbox.osc.ui.adapter.LiveSettingItemAdapter;
import com.github.tvbox.osc.ui.dialog.AllChannelsRightDialog;
import com.github.tvbox.osc.ui.dialog.LivePasswordDialog;
import com.github.tvbox.osc.ui.dialog.LiveSettingDialog;
import com.github.tvbox.osc.ui.dialog.LiveSettingRightDialog;
import com.github.tvbox.osc.ui.tv.widget.ViewObj;
import com.github.tvbox.osc.ui.widget.LinearSpacingItemDecoration;
import com.github.tvbox.osc.ui.widget.PlayerMenuView;
import com.github.tvbox.osc.ui.widget.PlayerTitleView;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.live.TxtSubscribe;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.kingja.loadsir.core.LoadLayout;
import com.kingja.loadsir.core.LoadService;
import com.kingja.loadsir.core.LoadSir;
import com.google.gson.JsonArray;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.enums.PopupPosition;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import xyz.doikki.videocontroller.component.LiveControlView;
import xyz.doikki.videocontroller.component.TitleView;
import xyz.doikki.videoplayer.player.VideoView;

/**
 * 直播页 Fragment 版(单 Activity 架构)。
 *
 * 原 LiveActivity 是独立 Activity,导致底部导航切换时整屏重建。重构后直播页作为 MainActivity
 * ViewPager 内的 Fragment:底部导航永远静止,只有内容区切换。播放器生命周期托管到 Fragment
 * 的 onResume/onPause/onDestroyView;遥控器按键(onBackPressed/dispatchKeyEvent)由 MainActivity
 * 在切到直播 tab 时转发给本 Fragment(handleKeyEvent/handleBackPressed)。
 */
public class LiveFragment extends Fragment implements LiveHost {
    private VideoView mVideoView;
    private TextView tvChannelInfo;
    private LinearLayout tvLeftChannelListLayout;
    private RecyclerView mChannelGroupView;
    private RecyclerView mLiveChannelView;
    public LiveChannelGroupNewAdapter liveChannelGroupAdapter;
    public LiveChannelItemNewAdapter liveChannelItemAdapter;

    private LinearLayout tvRightSettingLayout;
    private TvRecyclerView mSettingGroupView;
    private TvRecyclerView mSettingItemView;
    private LiveSettingGroupAdapter liveSettingGroupAdapter;
    private LiveSettingItemAdapter liveSettingItemAdapter;
    private List<LiveSettingGroup> liveSettingGroupList = new ArrayList<>();

    private int currentChannelGroupIndex = 0;
    private Handler mHandler = new Handler(Looper.getMainLooper());

    private List<LiveChannelGroup> liveChannelGroupList = new ArrayList<>();
    private int currentLiveChannelIndex = -1;
    private int currentLiveChangeSourceTimes = 0;
    private LiveChannelItem currentLiveChannelItem = null;
    private LivePlayerManager livePlayerManager = new LivePlayerManager();
    private ArrayList<Integer> channelGroupPasswordConfirmed = new ArrayList<>();

    private static LiveChannelItem channel_Name = null;
    private static Hashtable hsEpg = new Hashtable();
    private CountDownTimer countDownTimer;
    TextView tv_channelnum;
    TextView tip_chname;

    TextView tv_srcinfo;
    public String epgStringAddress = "";

    private boolean isSHIYI = false;
    private boolean isBack = false;
    public static String playUrl;
    private ImageView imgLiveIcon;
    SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd");
    private CountDownTimer countDownTimer3;
    private int videoWidth = 1920;
    private int videoHeight = 1080;
    private boolean show = false;
    private PlayerTitleView mPlayerTitleView;
    private BasePopupView mSettingRightDialog;
    private BasePopupView mSettingBottomDialog;
    private BasePopupView mAllChannelRightDialog;
    private boolean noLiveChannelsShown;

    private MainTabHost mTabHost;
    private View mRootView;
    private LoadService mLoadService;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mTabHost = (MainTabHost) requireActivity();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // LoadSir 不能用 fragment 的根视图当注册目标。
        // ViewTarget.replaceView() 会把目标 view 从它的 parent 里摘出来,再换成一个 LoadLayout 塞回去;
        // 若目标就是根视图,fragment 的 mView 就不再是容器的直接子 view,
        // FragmentManager 销毁视图时 container.removeView(mView) 会变成空操作 —— 旧的 LoadLayout
        // 永远留在 ViewPager2 的容器里,它持有的 callback view 也一直挂着 parent,
        // 下次 showCallback() 再 addView 就会抛 "The specified child already has a parent"。
        // 这里套一层空容器,让 live_root 始终有 parent,LoadLayout 挂在容器里、随视图一起销毁。
        View inner = inflater.inflate(R.layout.fragment_live, container, false);
        FrameLayout wrapper = new FrameLayout(inner.getContext());
        wrapper.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrapper.addView(inner, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mRootView = wrapper;
        return mRootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        sInstance = new WeakReference<>(this);
        // 诊断:统计视图创建次数。快速切 tab 时若这里疯狂自增,说明 ViewPager2 在
        // 反复销毁重建本 fragment(每次重建都会重新拉直播源 + 重新绑定播放器)。
        Log.d(TAG_LIVEVIS, "onViewCreated #" + (++sCreateCount));
        epgStringAddress = Hawk.get(HawkConfig.EPG_URL, "");
        if (epgStringAddress == null || epgStringAddress.length() < 5)
            epgStringAddress = "http://epg.51zmt.top:8000/api/diyp/";

        setLoadSir(mRootView.findViewById(R.id.live_root));
        mVideoView = mRootView.findViewById(R.id.mVideoView);

        tvLeftChannelListLayout = mRootView.findViewById(R.id.tvLeftChannnelListLayout);

        mChannelGroupView = mRootView.findViewById(R.id.mGroupGridView);
        mLiveChannelView = mRootView.findViewById(R.id.mChannelGridView);
        mChannelGroupView.addItemDecoration(new LinearSpacingItemDecoration(20, true));
        mLiveChannelView.addItemDecoration(new LinearSpacingItemDecoration(20, true));

        tvRightSettingLayout = mRootView.findViewById(R.id.tvRightSettingLayout);
        mSettingGroupView = mRootView.findViewById(R.id.mSettingGroupView);
        mSettingItemView = mRootView.findViewById(R.id.mSettingItemView);
        tvChannelInfo = mRootView.findViewById(R.id.tvChannel);

        tip_chname = mRootView.findViewById(R.id.tv_channel_bar_name);
        tip_chname.setOnClickListener(v -> {
            mChannelGroupView.scrollToPosition(currentChannelGroupIndex);
            mLiveChannelView.scrollToPosition(currentLiveChannelIndex);
        });
        tv_channelnum = mRootView.findViewById(R.id.tv_channel_bottom_number);

        tv_srcinfo = mRootView.findViewById(R.id.tv_source);

        mRootView.findViewById(R.id.ic_pre_source).setOnClickListener(v -> playPreSource());
        mRootView.findViewById(R.id.ic_next_source).setOnClickListener(v -> playNextSource());
        tv_srcinfo.setOnClickListener(v -> playNextSource());
        mRootView.findViewById(R.id.ic_setting).setOnClickListener(v -> showSettingDialog(false));
        mRootView.findViewById(R.id.ic_cast).setVisibility(View.GONE);

        initVideoView();
        initChannelGroupView();
        initLiveChannelView();
        initSettingGroupView();
        initSettingItemView();
        // 注意:initLiveChannelList() 不在这里无条件调用。
        // 若本 fragment 在 ApiConfig 就绪前就被创建(例如冷启动直达直播页),
        // getChannelGroupList() 会是空的,会误判成"暂无直播频道"。
        // 改为:数据已加载过就直接重新绑定到新 view,否则等首次切到直播页再拉。
        initLiveSettingGroupList();
        if (!liveChannelGroupList.isEmpty()) {
            showSuccess();
            initLiveState();
        } else {
            channelUiBound = false;
        }
    }

    private void showBottomEpg() {
        if (isSHIYI)
            return;
        if (channel_Name.getChannelName() != null) {
            mPlayerTitleView.setTitle(channel_Name.getChannelName());
            tip_chname.setText(channel_Name.getChannelName());
            tv_channelnum.setText("" + channel_Name.getChannelNum());
            if (countDownTimer != null) {
                countDownTimer.cancel();
            }

            if (channel_Name == null || channel_Name.getSourceNum() <= 0) {
                tv_srcinfo.setText("1/1");
            } else {
                tv_srcinfo.setText("线路" + (channel_Name.getSourceIndex() + 1) + "/" + channel_Name.getSourceNum());
            }

            Handler handler = new Handler(Looper.getMainLooper());

        }
    }

    /**
     * 由 MainActivity 在切到直播 tab 时转发遥控器按键(原 LiveActivity.dispatchKeyEvent)。
     * 仅执行换台等副作用,不拦截事件(MainActivity 会继续 super 分发焦点)。
     */
    public void handleKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                //showSettingGroup();
            } else if (!isListOrSettingLayoutVisible()) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false))
                            playNext();
                        else
                            playPrevious();
                        break;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false))
                            playPrevious();
                        else
                            playNext();
                        break;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (isBack) {

                        } else {
                            //showSettingGroup();
                        }
                        break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (isBack) {

                        } else {
                            playNextSource();
                        }
                        break;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        showChannelList();
                        break;
                }
            }
        }
    }

    /**
     * 由 MainActivity 在直播 tab 时转发返回键(原 LiveActivity.onBackPressed)。
     * @return true 表示已被直播页消费(弹窗/播放器),false 表示未消费(MainActivity 处理退出/切首页)
     */
    public boolean handleBackPressed() {
        if (tvRightSettingLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideSettingLayoutRun);
            mHandler.post(mHideSettingLayoutRun);
            return true;
        } else if (isBack) {
            isBack = false;
            playPreSource();
            return true;
        } else if (mSettingBottomDialog != null && mSettingBottomDialog.isShow()) {
            mSettingBottomDialog.dismiss();
            return true;
        } else if (mSettingRightDialog != null && mSettingRightDialog.isShow()) {
            mSettingRightDialog.dismiss();
            return true;
        } else if (mAllChannelRightDialog != null && mAllChannelRightDialog.isShow()) {
            mAllChannelRightDialog.dismiss();
            return true;
        } else if (!mVideoView.onBackPressed()) {
            return false;
        }
        return true;
    }

    // 直播页不可见时已彻底释放播放器,回到该页需要重新起播
    private boolean liveReleased = false;
    /** 由 MainActivity 的 tab 切换显式驱动的可见状态,是起播的唯一依据 */
    private boolean pageVisible = false;
    private static WeakReference<LiveFragment> sInstance = null;
    /** 直播可见性诊断日志,复现"切走后仍在播"问题时抓 logcat 用 */
    private static final String TAG_LIVEVIS = "LiveVis";
    private static int sCreateCount = 0;
    private static int sDestroyCount = 0;
    /** 频道数据是否已绑定到当前 view。视图重建后会置 false,需要重新绑定/重新加载 */
    private boolean channelUiBound = false;

    /**
     * 唯一的"起播"任务,只有它允许被延迟和取消。
     *
     * 停播(释放)一律同步执行、绝不进消息队列:快速切 tab 时每次切换都会先
     * removeCallbacks,排队的释放任务会被后一次切换取消掉,直播就一直占着
     * 解码器,随后打开的点播拿不到 MediaCodec 实例而起播失败。
     */
    private final Runnable mStartRun = new Runnable() {
        @Override
        public void run() {
            if (pageVisible) applyPageVisible(true);
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        // 故意不在这里起播:ViewPager2 连续快速切页时 onResume 会滞后触发,
        // 会把"已经切走"的直播又拉起来。起播一律由 MainActivity.selectTab 显式驱动。
    }

    @Override
    public void onPause() {
        super.onPause();
        // 按 Home / 打开点播播放页:强制同步停播
        setPageVisible(false);
    }

    /**
     * 直播页可见性切换,由 MainActivity 的 tab 切换回调驱动。
     *
     * 不能只依赖生命周期回调:ViewPager2 连续/快速切页时 fragment 的 lifecycle 更新会滞后甚至丢失,
     * 直播就会在后台继续播放;更要命的是只 pause() 不 release() 会一直占用 MediaCodec 实例,
     * 导致随后打开的点播拿不到解码器而起播失败。
     */
    public void setPageVisible(boolean visible) {
        pageVisible = visible;
        mHandler.removeCallbacks(mStartRun);
        if (visible) {
            // 每次进入都重置:归位首个频段、清掉 Hawk 上次频道记忆、停播(不自动续播)。
            // 列表为空时随每次进入重试,配置晚到也能自愈。
            if (mLoadService != null) {
                if (liveChannelGroupList.isEmpty()) {
                    initLiveChannelList();
                } else {
                    showSuccess();
                    initLiveState();
                }
            }
            // 不再 postDelayed 起播:用户要求进入即停播,等手动选台
        } else {
            applyPageVisible(false);              // 同步释放,不可被取消
        }
        Log.d(TAG_LIVEVIS, "setPageVisible " + visible + " liveReleased=" + liveReleased);
    }

    /**
     * 订阅页切换数据源后由 MainActivity 调用:丢弃旧频道列表,下次进直播页重新加载。
     * 这里刻意不立即拉源 —— 此时 ApiConfig 的重载还是异步的,立刻取多半是空,
     * 交给下次 setPageVisible(true) 的重试逻辑更稳。
     */
    public void resetForSourceChange() {
        liveChannelGroupList.clear();
        channelUiBound = false;
        noLiveChannelsShown = false;
        mHandler.removeCallbacks(mStartRun);
    }

    public static void stopLivePlayback() {
        LiveFragment fragment = sInstance != null ? sInstance.get() : null;
        if (fragment != null) {
            fragment.setPageVisible(false); // 同步释放,保证点播起播前解码器已空出来
        }
    }

    private void applyPageVisible(boolean visible) {
        if (mVideoView == null) return;
        if (visible) {
            // liveReleased 由 restartCurrentChannel 自己维护:起播成功才置 false
            if (liveReleased) {
                Log.d(TAG_LIVEVIS, "applyPageVisible true -> restart channel");
                restartCurrentChannel();
            } else {
                Log.d(TAG_LIVEVIS, "applyPageVisible true -> resume");
                mVideoView.resume();
            }
        } else {
            if (!liveReleased) {
                Log.d(TAG_LIVEVIS, "applyPageVisible false -> release");
                mVideoView.release();
                liveReleased = true;
            }
        }
    }

    /**
     * 播放器被释放后重新起播当前频道
     */
    private void restartCurrentChannel() {
        if (mVideoView == null) return;
        if (currentLiveChannelItem == null || currentChannelGroupIndex < 0 || currentLiveChannelIndex < 0) {
            // 频道列表还没加载完就被切走了(它的回调会检查 pageVisible,不会偷偷起播)
            return;
        }
        if (liveChannelGroupList.isEmpty()) {
            // 源已切换、列表被清空但还没重新加载完 —— 此时任何下标都是旧的,不能拿来起播
            return;
        }
        livePlayerManager.getDefaultLiveChannelPlayer(mVideoView);
        int groupIndex = currentChannelGroupIndex;
        int channelIndex = currentLiveChannelIndex;
        currentLiveChannelIndex = -1; // 绕过 playChannel 中"同一频道"的短路判断
        playChannel(groupIndex, channelIndex, false);
    }

    @Override
    public void onDestroyView() {
        Log.d(TAG_LIVEVIS, "onDestroyView #" + (++sDestroyCount));
        channelUiBound = false;
        mHandler.removeCallbacksAndMessages(null);
        if (sInstance != null && sInstance.get() == this) sInstance = null;
        if (mVideoView != null) {
            mVideoView.release();
            mVideoView = null;
        }
        mLoadService = null;
        super.onDestroyView();
    }

    private void showChannelList() {
        if (tvRightSettingLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideSettingLayoutRun);
            mHandler.post(mHideSettingLayoutRun);
            return;
        }
        liveChannelItemAdapter.setNewData(getLiveChannels(currentChannelGroupIndex));
        if (currentLiveChannelIndex > -1) {
            mLiveChannelView.smoothScrollToPosition(currentLiveChannelIndex);
            if (currentChannelGroupIndex == 0) {
                mChannelGroupView.scrollToPosition(currentChannelGroupIndex);
            } else {
                mChannelGroupView.smoothScrollToPosition(currentChannelGroupIndex);
            }
        }
    }

    private void showChannelInfo() {
        tvChannelInfo.setText(String.format(Locale.getDefault(), "%d %s %s(%d/%d)", currentLiveChannelItem.getChannelNum(),
                currentLiveChannelItem.getChannelName(), currentLiveChannelItem.getSourceName(),
                currentLiveChannelItem.getSourceIndex() + 1, currentLiveChannelItem.getSourceNum()));

        FrameLayout.LayoutParams lParams = new FrameLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (tvRightSettingLayout.getVisibility() == View.VISIBLE) {
            lParams.gravity = Gravity.LEFT;
            lParams.leftMargin = 60;
            lParams.topMargin = 30;
        } else {
            lParams.gravity = Gravity.RIGHT;
            lParams.rightMargin = 60;
            lParams.topMargin = 30;
        }
        tvChannelInfo.setLayoutParams(lParams);

        tvChannelInfo.setVisibility(View.VISIBLE);
        mHandler.removeCallbacks(mHideChannelInfoRun);
        mHandler.postDelayed(mHideChannelInfoRun, 3000);
    }

    private Runnable mHideChannelInfoRun = new Runnable() {
        @Override
        public void run() {
            tvChannelInfo.setVisibility(View.INVISIBLE);
        }
    };

    private boolean playChannel(int channelGroupIndex, int liveChannelIndex, boolean changeSource) {
        if (mVideoView == null) return false;
        if (currentLiveChannelItem == null && changeSource) return false;
        if ((channelGroupIndex == currentChannelGroupIndex && liveChannelIndex == currentLiveChannelIndex && !changeSource)
                || (changeSource && currentLiveChannelItem.getSourceNum() == 1)) {
            return true;
        }
        mVideoView.release();
        liveReleased = true;
        if (!changeSource) {
            ArrayList<LiveChannelItem> channels = getLiveChannels(channelGroupIndex);
            // 切源后旧索引可能越界(新源的分组/频道数更少),越界就放弃本次起播,
            // 等下次进入直播页重新加载列表后再播 —— 绝不能拿越界下标去 get()。
            if (liveChannelIndex < 0 || liveChannelIndex >= channels.size()) {
                Log.w(TAG_LIVEVIS, "playChannel out of range: group=" + channelGroupIndex
                        + " ch=" + liveChannelIndex + " size=" + channels.size());
                currentChannelGroupIndex = channelGroupIndex;
                currentLiveChannelIndex = -1;
                currentLiveChannelItem = null;
                return false;
            }
            currentChannelGroupIndex = channelGroupIndex;
            currentLiveChannelIndex = liveChannelIndex;
            currentLiveChannelItem = channels.get(liveChannelIndex);
            Hawk.put(HawkConfig.LIVE_CHANNEL, currentLiveChannelItem.getChannelName());
            livePlayerManager.getLiveChannelPlayer(mVideoView, currentLiveChannelItem.getChannelName());
        }

        channel_Name = currentLiveChannelItem;
        isSHIYI = false;
        isBack = false;
        if (currentLiveChannelItem.getUrl().indexOf("PLTV/8888") != -1) {
            currentLiveChannelItem.setinclude_back(true);
        } else {
            currentLiveChannelItem.setinclude_back(false);
        }
        showBottomEpg();

        // 关键闸门:快速切 tab 时,频道列表的异步加载回调往往在"已经切走"之后才到达,
        // 这里必须挡一道 —— 页面不可见时只更新选中状态,绝不真正起播,
        // 否则直播会在别的 tab 后台出声并占住解码器,点播随后起播失败。
        if (!pageVisible) {
            Log.d(TAG_LIVEVIS, "playChannel blocked: page not visible");
            return true;
        }

        mVideoView.setUrl(currentLiveChannelItem.getUrl());
        mVideoView.start();
        liveReleased = false;
        return true;
    }

    private void playNext() {
        if (!isCurrentLiveChannelValid()) return;
        Integer[] groupChannelIndex = getNextChannel(1);
        if (groupChannelIndex == null) return;
        playChannel(groupChannelIndex[0], groupChannelIndex[1], false);
    }

    private void playPrevious() {
        if (!isCurrentLiveChannelValid()) return;
        Integer[] groupChannelIndex = getNextChannel(-1);
        playChannel(groupChannelIndex[0], groupChannelIndex[1], false);
    }

    public void playPreSource() {
        if (!isCurrentLiveChannelValid()) return;
        currentLiveChannelItem.preSource();
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
    }

    public void playNextSource() {
        if (!isCurrentLiveChannelValid()) return;
        currentLiveChannelItem.nextSource();
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
    }

    private void showSettingGroup() {

        if (tvRightSettingLayout.getVisibility() == View.INVISIBLE) {
            if (!isCurrentLiveChannelValid()) return;
            loadCurrentSourceList();
            liveSettingGroupAdapter.setNewData(liveSettingGroupList);
            selectSettingGroup(0, false);
            mSettingGroupView.smoothScrollToPosition(0);
            mSettingItemView.smoothScrollToPosition(currentLiveChannelItem.getSourceIndex());
            mHandler.postDelayed(mFocusAndShowSettingGroup, 200);
        } else {
            mHandler.removeCallbacks(mHideSettingLayoutRun);
            mHandler.post(mHideSettingLayoutRun);
        }
    }

    private Runnable mFocusAndShowSettingGroup = new Runnable() {
        @Override
        public void run() {
            if (mSettingGroupView.isScrolling() || mSettingItemView.isScrolling() || mSettingGroupView.isComputingLayout() || mSettingItemView.isComputingLayout()) {
                mHandler.postDelayed(this, 100);
            } else {
                RecyclerView.ViewHolder holder = mSettingGroupView.findViewHolderForAdapterPosition(0);
                if (holder != null)
                    holder.itemView.requestFocus();
                tvRightSettingLayout.setVisibility(View.VISIBLE);
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) tvRightSettingLayout.getLayoutParams();
                if (tvRightSettingLayout.getVisibility() == View.VISIBLE) {
                    ViewObj viewObj = new ViewObj(tvRightSettingLayout, params);
                    ObjectAnimator animator = ObjectAnimator.ofObject(viewObj, "marginRight", new IntEvaluator(), -tvRightSettingLayout.getLayoutParams().width, 0);
                    animator.setDuration(200);
                    animator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            super.onAnimationEnd(animation);
                            mHandler.postDelayed(mHideSettingLayoutRun, 5000);
                        }
                    });
                    animator.start();
                }
            }
        }
    };

    private Runnable mHideSettingLayoutRun = new Runnable() {
        @Override
        public void run() {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) tvRightSettingLayout.getLayoutParams();
            if (tvRightSettingLayout.getVisibility() == View.VISIBLE) {
                ViewObj viewObj = new ViewObj(tvRightSettingLayout, params);
                ObjectAnimator animator = ObjectAnimator.ofObject(viewObj, "marginRight", new IntEvaluator(), 0, -tvRightSettingLayout.getLayoutParams().width);
                animator.setDuration(200);
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        tvRightSettingLayout.setVisibility(View.INVISIBLE);
                        liveSettingGroupAdapter.setSelectedGroupIndex(-1);
                    }
                });
                animator.start();
            }
        }
    };

    private void initVideoView() {
        LiveNewController controller = new LiveNewController(requireContext());
        PlayerMenuView playerMenuView = getPlayerMenuView();
        controller.addControlComponent(playerMenuView);
        controller.addControlComponent(new LiveControlView(requireContext()));
        mPlayerTitleView = new PlayerTitleView(requireContext());
        controller.addControlComponent(mPlayerTitleView);
        controller.setListener(new LiveNewController.LiveControlListener() {

            @Override
            public void setting() {
                //showSettingGroup();
            }

            @Override
            public void playStateChanged(int playState) {
                switch (playState) {
                    case VideoView.STATE_IDLE:
                    case VideoView.STATE_PAUSED:
                        break;
                    case VideoView.STATE_PREPARED:
                    case VideoView.STATE_BUFFERED:
                    case VideoView.STATE_PLAYING:
                        currentLiveChangeSourceTimes = 0;
                        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
                        break;
                    case VideoView.STATE_ERROR:
                    case VideoView.STATE_PLAYBACK_COMPLETED:
                        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
                        mHandler.postDelayed(mConnectTimeoutChangeSourceRun, 2000);
                        break;
                    case VideoView.STATE_PREPARING:
                    case VideoView.STATE_BUFFERING:
                        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
                        mHandler.postDelayed(mConnectTimeoutChangeSourceRun, (Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1) + 1) * 5000);
                        break;
                }
            }

            @Override
            public void changeSource(int direction) {
                if (direction > 0) {
                    playNextSource();
                } else {
                    playPreSource();
                }
            }
        });
        controller.setCanChangePosition(false);
        controller.setEnableInNormal(true);
        controller.setGestureEnabled(true);
        controller.setDoubleTapTogglePlayEnabled(false);
        mVideoView.setVideoController(controller);
        mVideoView.setProgressManager(null);
    }

    @NonNull
    private PlayerMenuView getPlayerMenuView() {
        PlayerMenuView playerMenuView = new PlayerMenuView(requireContext());
        playerMenuView.setOnPlayerMenuClickListener(new PlayerMenuView.OnPlayerMenuClickListener() {
            @Override
            public void expand() {
                showAllChannelDialog();
            }

            @Override
            public void onSetting() {
                showSettingDialog(true);
            }

        });
        return playerMenuView;
    }

    private Runnable mConnectTimeoutChangeSourceRun = new Runnable() {
        @Override
        public void run() {
            currentLiveChangeSourceTimes++;
            if (currentLiveChannelItem.getSourceNum() == currentLiveChangeSourceTimes) {
                currentLiveChangeSourceTimes = 0;
                Integer[] groupChannelIndex = getNextChannel(Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false) ? -1 : 1);
                if (groupChannelIndex == null) return;
                playChannel(groupChannelIndex[0], groupChannelIndex[1], false);
            } else {
                playNextSource();
            }
        }
    };

    private void initChannelGroupView() {
        mChannelGroupView.setHasFixedSize(true);
        mChannelGroupView.setLayoutManager(new V7LinearLayoutManager(requireContext(), 1, false));

        liveChannelGroupAdapter = new LiveChannelGroupNewAdapter();
        mChannelGroupView.setAdapter(liveChannelGroupAdapter);

        liveChannelGroupAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                selectChannelGroup(position, false, -1);
            }
        });
    }

    private void selectChannelGroup(int groupIndex, boolean focus, int liveChannelIndex) {
        if (focus) {
            liveChannelGroupAdapter.setFocusedGroupIndex(groupIndex);
            liveChannelItemAdapter.setFocusedChannelIndex(-1);
        }
        if ((groupIndex > -1 && groupIndex != liveChannelGroupAdapter.getSelectedGroupIndex()) || isNeedInputPassword(groupIndex)) {
            liveChannelGroupAdapter.setSelectedGroupIndex(groupIndex);
            if (isNeedInputPassword(groupIndex)) {
                showPasswordDialog(groupIndex, liveChannelIndex);
                return;
            }
            loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex);
        }
    }

    private void initLiveChannelView() {
        mLiveChannelView.setHasFixedSize(true);
        mLiveChannelView.setLayoutManager(new V7LinearLayoutManager(requireContext(), 1, false));

        liveChannelItemAdapter = new LiveChannelItemNewAdapter();
        mLiveChannelView.setAdapter(liveChannelItemAdapter);

        liveChannelItemAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                clickLiveChannel(position);
            }
        });
    }

    private void clickLiveChannel(int position) {
        liveChannelItemAdapter.setSelectedChannelIndex(position);
        playChannel(liveChannelGroupAdapter.getSelectedGroupIndex(), position, false);
    }

    private void initSettingGroupView() {
        mSettingGroupView.setHasFixedSize(true);
        mSettingGroupView.setLayoutManager(new V7LinearLayoutManager(requireContext(), 1, false));

        liveSettingGroupAdapter = new LiveSettingGroupAdapter();
        mSettingGroupView.setAdapter(liveSettingGroupAdapter);
        mSettingGroupView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideSettingLayoutRun);
                mHandler.postDelayed(mHideSettingLayoutRun, 5000);
            }
        });

        liveSettingGroupAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                selectSettingGroup(position, false);
            }
        });
    }

    private void selectSettingGroup(int position, boolean focus) {
        if (!isCurrentLiveChannelValid()) return;
        if (focus) {
            liveSettingGroupAdapter.setFocusedGroupIndex(position);
            liveSettingItemAdapter.setFocusedItemIndex(-1);
        }
        if (position == liveSettingGroupAdapter.getSelectedGroupIndex() || position < -1)
            return;

        liveSettingGroupAdapter.setSelectedGroupIndex(position);
        liveSettingItemAdapter.setNewData(liveSettingGroupList.get(position).getLiveSettingItems());

        switch (position) {
            case 0:
                liveSettingItemAdapter.selectItem(currentLiveChannelItem.getSourceIndex(), true, false);
                break;
            case 1:
                liveSettingItemAdapter.selectItem(livePlayerManager.getLivePlayerScale(), true, true);
                break;
            case 2:
                liveSettingItemAdapter.selectItem(livePlayerManager.getLivePlayerType(), true, true);
                break;
        }
        int smoothScrollToPosition = liveSettingItemAdapter.getSelectedItemIndex();
        if (smoothScrollToPosition < 0) smoothScrollToPosition = 0;
        mSettingItemView.smoothScrollToPosition(smoothScrollToPosition);
        mHandler.removeCallbacks(mHideSettingLayoutRun);
        mHandler.postDelayed(mHideSettingLayoutRun, 5000);
    }

    private void initSettingItemView() {
        mSettingItemView.setHasFixedSize(true);
        mSettingItemView.setLayoutManager(new V7LinearLayoutManager(requireContext(), 1, false));

        liveSettingItemAdapter = new LiveSettingItemAdapter();
        mSettingItemView.setAdapter(liveSettingItemAdapter);
        mSettingItemView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideSettingLayoutRun);
                mHandler.postDelayed(mHideSettingLayoutRun, 5000);
            }
        });

        liveSettingItemAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                clickSettingItem(position);
            }
        });
    }

    private void clickSettingItem(int position) {
        int settingGroupIndex = liveSettingGroupAdapter.getSelectedGroupIndex();
        if (settingGroupIndex < 4) {
            if (position == liveSettingItemAdapter.getSelectedItemIndex())
                return;
            liveSettingItemAdapter.selectItem(position, true, true);
        }
        switch (settingGroupIndex) {
            case 0:
                currentLiveChannelItem.setSourceIndex(position);
                playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
                break;
            case 1:
                livePlayerManager.changeLivePlayerScale(mVideoView, position, currentLiveChannelItem.getChannelName());
                break;
            case 2:
                mVideoView.release();
                livePlayerManager.changeLivePlayerType(mVideoView, position, currentLiveChannelItem.getChannelName());
                mVideoView.setUrl(currentLiveChannelItem.getUrl());
                mVideoView.start();
                break;
            case 3:
                Hawk.put(HawkConfig.LIVE_CONNECT_TIMEOUT, position);
                break;
            case 4:
                boolean select = false;
                switch (position) {
                    case 0:
                        select = !Hawk.get(HawkConfig.LIVE_SHOW_TIME, false);
                        Hawk.put(HawkConfig.LIVE_SHOW_TIME, select);
                        break;
                    case 1:
                        select = !Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false);
                        Hawk.put(HawkConfig.LIVE_SHOW_NET_SPEED, select);
                        break;
                    case 2:
                        select = !Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false);
                        Hawk.put(HawkConfig.LIVE_CHANNEL_REVERSE, select);
                        break;
                    case 3:
                        select = !Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false);
                        Hawk.put(HawkConfig.LIVE_CROSS_GROUP, select);
                        break;
                }
                liveSettingItemAdapter.selectItem(position, select, false);
                break;
        }
        mHandler.removeCallbacks(mHideSettingLayoutRun);
        mHandler.postDelayed(mHideSettingLayoutRun, 5000);
    }

    private void initLiveChannelList() {
        List<LiveChannelGroup> list = ApiConfig.get().getChannelGroupList();
        if (list.isEmpty()) {
            showNoLiveChannels();
            return;
        }

        if (list.size() == 1 && list.get(0).getGroupName().startsWith("http://127.0.0.1")) {
            loadProxyLives(list.get(0).getGroupName());
        } else {
            liveChannelGroupList.clear();
            liveChannelGroupList.addAll(list);
            showSuccess();
            initLiveState();
        }
    }

    public void loadProxyLives(String url) {
        try {
            Uri parsedUrl = Uri.parse(url);
            url = new String(Base64.decode(parsedUrl.getQueryParameter("ext"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
        } catch (Throwable th) {
            showNoLiveChannels();
            return;
        }
        showLoading();
        if (url.startsWith("content://")) {
            final String localUrl = url;
            new Thread(() -> {
                try (InputStream input = requireContext().getContentResolver().openInputStream(Uri.parse(localUrl));
                     BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"))) {
                    StringBuilder content = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) content.append(line).append('\n');
                    requireActivity().runOnUiThread(() -> parseProxyLiveContent(content.toString()));
                } catch (Throwable error) {
                    requireActivity().runOnUiThread(this::showNoLiveChannels);
                }
            }).start();
            return;
        }
        OkGo.<String>get(url).execute(new AbsCallback<String>() {

            @Override
            public String convertResponse(okhttp3.Response response) throws Throwable {
                return response.body().string();
            }

            @Override
            public void onSuccess(Response<String> response) {
                parseProxyLiveContent(response.body());
            }

            @Override
            public void onError(Response<String> response) {
                super.onError(response);
                showNoLiveChannels();
            }
        });
    }

    private void parseProxyLiveContent(String content) {
        if (!isAdded()) return;
        LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>> linkedHashMap = new LinkedHashMap<>();
        TxtSubscribe.parse(linkedHashMap, content);
        ApiConfig.get().loadLives(TxtSubscribe.live2JsonArray(linkedHashMap));
        List<LiveChannelGroup> list = ApiConfig.get().getChannelGroupList();
        if (list.isEmpty()) {
            showNoLiveChannels();
            return;
        }
        liveChannelGroupList.clear();
        liveChannelGroupList.addAll(list);
        showSuccess();
        initLiveState();
    }

    private void showNoLiveChannels() {
        if (!isAdded()) return;
        if (noLiveChannelsShown) {
            return;
        }
        noLiveChannelsShown = true;
        showEmpty();
        new XPopup.Builder(requireContext())
                .asConfirm("暂无直播频道", "当前订阅未提供可用直播频道，请切换或导入包含直播内容的订阅。", () -> {
                    mTabHost.switchToTab(MainTabHost.TAB_SUBSCRIBE);
                })
                .show();
    }

    /**
     * 归位直播界面的初始状态:清掉 Hawk 上次频道记忆、回到首个(无密码)频段、停播。
     * 每次进入直播页都会调用,因此"离开再回来"必然从干净态开始,不再接上次的台。
     */
    private void initLiveState() {
        // 复位"暂无直播频道"的一次性标志,否则一旦被误触发就再也不会恢复
        noLiveChannelsShown = false;
        channelUiBound = true;

        // 彻底重置:清掉持久化的上次频道,冷启动和 tab 切换都不记忆
        Hawk.delete(HawkConfig.LIVE_CHANNEL);

        // 频段选择归零到首个无密码分组(左侧频道分组)
        currentChannelGroupIndex = getFirstNoPasswordChannelGroup();
        if (currentChannelGroupIndex == -1) currentChannelGroupIndex = 0;
        // 当前播放频道清空,停播(等用户手动选台)
        currentLiveChannelIndex = -1;
        currentLiveChannelItem = null;
        liveReleased = true;

        livePlayerManager.init(mVideoView);

        tvRightSettingLayout.setVisibility(View.INVISIBLE);

        // 强制触发 selectChannelGroup(否则选中项恰好等于当前值时会被短路跳过)
        liveChannelGroupAdapter.setSelectedGroupIndex(-1);
        liveChannelGroupAdapter.setNewData(liveChannelGroupList);
        selectChannelGroup(currentChannelGroupIndex, false, -1); // liveChannelIndex=-1 → 不起播
    }

    private boolean isListOrSettingLayoutVisible() {
        return tvLeftChannelListLayout.getVisibility() == View.VISIBLE || tvRightSettingLayout.getVisibility() == View.VISIBLE;
    }

    private void initLiveSettingGroupList() {
        ArrayList<String> groupNames = new ArrayList<>(Arrays.asList("线路选择", "画面比例", "播放解码", "超时换源", "偏好设置"));
        ArrayList<ArrayList<String>> itemsArrayList = new ArrayList<>();
        ArrayList<String> sourceItems = new ArrayList<>();
        ArrayList<String> scaleItems = new ArrayList<>(Arrays.asList("默认", "16:9", "4:3", "填充", "原始", "裁剪"));
        ArrayList<String> playerDecoderItems = new ArrayList<>(Arrays.asList("exo"));
        ArrayList<String> timeoutItems = new ArrayList<>(Arrays.asList("5s", "10s", "15s", "20s", "25s", "30s"));
        ArrayList<String> personalSettingItems = new ArrayList<>(Arrays.asList("显示时间", "显示网速", "换台反转", "跨选分类"));
        itemsArrayList.add(sourceItems);
        itemsArrayList.add(scaleItems);
        itemsArrayList.add(playerDecoderItems);
        itemsArrayList.add(timeoutItems);
        itemsArrayList.add(personalSettingItems);

        liveSettingGroupList.clear();
        for (int i = 0; i < groupNames.size(); i++) {
            LiveSettingGroup liveSettingGroup = new LiveSettingGroup();
            ArrayList<LiveSettingItem> liveSettingItemList = new ArrayList<>();
            liveSettingGroup.setGroupIndex(i);
            liveSettingGroup.setGroupName(groupNames.get(i));
            for (int j = 0; j < itemsArrayList.get(i).size(); j++) {
                LiveSettingItem liveSettingItem = new LiveSettingItem();
                liveSettingItem.setItemIndex(j);
                liveSettingItem.setItemName(itemsArrayList.get(i).get(j));
                liveSettingItemList.add(liveSettingItem);
            }
            liveSettingGroup.setLiveSettingItems(liveSettingItemList);
            liveSettingGroupList.add(liveSettingGroup);
        }
        liveSettingGroupList.get(3).getLiveSettingItems().get(Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1)).setItemSelected(true);
        liveSettingGroupList.get(4).getLiveSettingItems().get(0).setItemSelected(Hawk.get(HawkConfig.LIVE_SHOW_TIME, false));
        liveSettingGroupList.get(4).getLiveSettingItems().get(1).setItemSelected(Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false));
        liveSettingGroupList.get(4).getLiveSettingItems().get(2).setItemSelected(Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false));
        liveSettingGroupList.get(4).getLiveSettingItems().get(3).setItemSelected(Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false));
    }

    private void loadCurrentSourceList() {
        ArrayList<String> currentSourceNames = currentLiveChannelItem.getChannelSourceNames();
        ArrayList<LiveSettingItem> liveSettingItemList = new ArrayList<>();
        for (int j = 0; j < currentSourceNames.size(); j++) {
            LiveSettingItem liveSettingItem = new LiveSettingItem();
            liveSettingItem.setItemIndex(j);
            liveSettingItem.setItemName(currentSourceNames.get(j));
            liveSettingItemList.add(liveSettingItem);
        }
        liveSettingGroupList.get(0).setLiveSettingItems(liveSettingItemList);
    }

    private void showPasswordDialog(int groupIndex, int liveChannelIndex) {

        LivePasswordDialog dialog = new LivePasswordDialog(requireContext());
        dialog.setOnListener(new LivePasswordDialog.OnListener() {
            @Override
            public void onChange(String password) {
                if (password.equals(liveChannelGroupList.get(groupIndex).getGroupPassword())) {
                    channelGroupPasswordConfirmed.add(groupIndex);
                    loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex);
                } else {
                    ToastUtils.showShort("密码错误");
                }
            }

            @Override
            public void onCancel() {
                if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
                    int groupIndex = liveChannelGroupAdapter.getSelectedGroupIndex();
                    liveChannelItemAdapter.setNewData(getLiveChannels(groupIndex));
                }
            }
        });
        dialog.show();
    }

    private void loadChannelGroupDataAndPlay(int groupIndex, int liveChannelIndex) {
        liveChannelItemAdapter.setNewData(getLiveChannels(groupIndex));
        if (groupIndex == currentChannelGroupIndex) {
            if (currentLiveChannelIndex > -1)
                mLiveChannelView.smoothScrollToPosition(currentLiveChannelIndex);
            liveChannelItemAdapter.setSelectedChannelIndex(currentLiveChannelIndex);
        } else {
            mLiveChannelView.smoothScrollToPosition(0);
            liveChannelItemAdapter.setSelectedChannelIndex(-1);
        }

        if (liveChannelIndex > -1) {
            clickLiveChannel(liveChannelIndex);
            if (groupIndex == 0) {
                mChannelGroupView.scrollToPosition(groupIndex);
            } else {
                mChannelGroupView.smoothScrollToPosition(groupIndex);
            }

            mLiveChannelView.smoothScrollToPosition(liveChannelIndex);
            playChannel(groupIndex, liveChannelIndex, false);
        }
    }

    private boolean isNeedInputPassword(int groupIndex) {
        // 切源后列表会被清空重建,此时索引可能已越界 —— 没有分组就谈不上密码
        if (groupIndex < 0 || groupIndex >= liveChannelGroupList.size()) return false;
        return !liveChannelGroupList.get(groupIndex).getGroupPassword().isEmpty()
                && !isPasswordConfirmed(groupIndex);
    }

    private boolean isPasswordConfirmed(int groupIndex) {
        for (Integer confirmedNum : channelGroupPasswordConfirmed) {
            if (confirmedNum == groupIndex)
                return true;
        }
        return false;
    }

    private ArrayList<LiveChannelItem> getLiveChannels(int groupIndex) {
        // 越界一律返回空列表(切源后列表清空重建期间会发生),不能让它走到 get() 崩掉
        if (groupIndex < 0 || groupIndex >= liveChannelGroupList.size()) return new ArrayList<>();
        if (!isNeedInputPassword(groupIndex)) {
            return liveChannelGroupList.get(groupIndex).getLiveChannels();
        } else {
            return new ArrayList<>();
        }
    }

    private Integer[] getNextChannel(int direction) {
        // 列表为空(切源后尚未重新加载)时无从推算下一个频道
        if (liveChannelGroupList.isEmpty()) return null;
        int channelGroupIndex = currentChannelGroupIndex;
        int liveChannelIndex = currentLiveChannelIndex;

        if (direction > 0) {
            liveChannelIndex++;
            if (liveChannelIndex >= getLiveChannels(channelGroupIndex).size()) {
                liveChannelIndex = 0;
                if (Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false)) {
                    do {
                        channelGroupIndex++;
                        if (channelGroupIndex >= liveChannelGroupList.size())
                            channelGroupIndex = 0;
                    } while (!liveChannelGroupList.get(channelGroupIndex).getGroupPassword().isEmpty() || channelGroupIndex == currentChannelGroupIndex);
                }
            }
        } else {
            liveChannelIndex--;
            if (liveChannelIndex < 0) {
                if (Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false)) {
                    do {
                        channelGroupIndex--;
                        if (channelGroupIndex < 0)
                            channelGroupIndex = liveChannelGroupList.size() - 1;
                    } while (!liveChannelGroupList.get(channelGroupIndex).getGroupPassword().isEmpty() || channelGroupIndex == currentChannelGroupIndex);
                }
                liveChannelIndex = getLiveChannels(channelGroupIndex).size() - 1;
            }
        }

        Integer[] groupChannelIndex = new Integer[2];
        groupChannelIndex[0] = channelGroupIndex;
        groupChannelIndex[1] = liveChannelIndex;

        return groupChannelIndex;
    }

    private int getFirstNoPasswordChannelGroup() {
        for (LiveChannelGroup liveChannelGroup : liveChannelGroupList) {
            if (liveChannelGroup.getGroupPassword().isEmpty())
                return liveChannelGroup.getGroupIndex();
        }
        return -1;
    }

    private boolean isCurrentLiveChannelValid() {
        if (currentLiveChannelItem == null) {
            ToastUtils.showShort("请先选择频道");
            return false;
        }
        return true;
    }

    public static long getTime(String startTime, String endTime) {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        long eTime = 0;
        try {
            eTime = df.parse(endTime).getTime();
        } catch (ParseException e) {
            e.printStackTrace();
        }
        long sTime = 0;
        try {
            sTime = df.parse(startTime).getTime();
        } catch (ParseException e) {
            e.printStackTrace();
        }
        long diff = (eTime - sTime) / 1000;
        return diff;
    }

    private String durationToString(int duration) {
        String result = "";
        int dur = duration / 1000;
        int hour = dur / 3600;
        int min = (dur / 60) % 60;
        int sec = dur % 60;
        if (hour > 0) {
            if (min > 9) {
                if (sec > 9) {
                    result = hour + ":" + min + ":" + sec;
                } else {
                    result = hour + ":" + min + ":0" + sec;
                }
            } else {
                if (sec > 9) {
                    result = hour + ":" + "0" + min + ":" + sec;
                } else {
                    result = hour + ":" + "0" + min + ":0" + sec;
                }
            }
        } else {
            if (min > 9) {
                if (sec > 9) {
                    result = min + ":" + sec;
                } else {
                    result = min + ":0" + sec;
                }
            } else {
                if (sec > 9) {
                    result = "0" + min + ":" + sec;
                } else {
                    result = "0" + min + ":0" + sec;
                }
            }
        }
        return result;
    }

    public void showAllChannelDialog() {
        mAllChannelRightDialog = new XPopup.Builder(requireContext())
                .isViewMode(true)
                .hasNavigationBar(false)
                .hasShadowBg(false)
                .popupHeight(ScreenUtils.getScreenHeight())
                .popupPosition(PopupPosition.Right)
                .asCustom(new AllChannelsRightDialog(requireContext(), this));
        mAllChannelRightDialog.show();
    }

    public LivePlayerManager getLivePlayerManager() {
        return livePlayerManager;
    }

    public LiveChannelItem getCurrentLiveChannelItem() {
        return currentLiveChannelItem;
    }

    public void switchingLine2Replay(int position) {
        currentLiveChannelItem.setSourceIndex(position);
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
    }

    public void changeScale(int position) {
        livePlayerManager.changeLivePlayerScale(mVideoView, position, currentLiveChannelItem.getChannelName());
    }

    public void changePlayer(int position) {
        mVideoView.release();
        livePlayerManager.changeLivePlayerType(mVideoView, position, currentLiveChannelItem.getChannelName());
        mVideoView.setUrl(currentLiveChannelItem.getUrl());
        mVideoView.start();
    }

    private void showSettingDialog(boolean fullScreenStyle) {
        if (!isCurrentLiveChannelValid()) {
            ToastUtils.showShort("当前频道未加载");
            return;
        }
        if (fullScreenStyle) {
            mSettingRightDialog = new XPopup.Builder(requireContext())
                    .isViewMode(true)
                    .hasNavigationBar(false)
                    .hasShadowBg(false)
                    .popupHeight(ScreenUtils.getScreenHeight())
                    .popupWidth(ConvertUtils.dp2px(300))
                    .popupPosition(PopupPosition.Right)
                    .asCustom(new LiveSettingRightDialog(requireContext(), this));
            mSettingRightDialog.show();
        } else {
            mSettingBottomDialog = new XPopup.Builder(requireContext())
                    .isViewMode(true)
                    .popupHeight(ScreenUtils.getScreenHeight() / 2)
                    .hasNavigationBar(false)
                    .hasShadowBg(false)
                    .asCustom(new LiveSettingDialog(requireContext(), this));
            mSettingBottomDialog.show();
        }
    }

    // ---- LoadSir(Fragment 版,复制 BaseActivity 能力) ----
    private void setLoadSir(View view) {
        if (mLoadService == null && view != null) {
            // 二次注册前先把可能残留的旧 LoadLayout 摘掉,避免同一目标被嵌套包两层
            if (view.getParent() instanceof LoadLayout) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
            mLoadService = LoadSir.getDefault().register(view, new com.kingja.loadsir.callback.Callback.OnReloadListener() {
                @Override
                public void onReload(View v) {
                }
            });
        }
    }

    /**
     * 显示 LoadSir 状态页。
     *
     * LoadLayout 内部只做 removeViewAt(1),一旦视图层次里残留了旧的 callback view(比如
     * fragment 视图被重建过)就会抛 "The specified child already has a parent" 直接崩 App。
     * 这里在显示前把 LoadLayout 里除 successView 之外的子 view 全部摘掉(removeView 会清掉
     * child.mParent),再做兜底 catch —— 状态页显示失败不该让整个应用崩掉。
     */
    private void showCallbackSafe(Class<? extends com.kingja.loadsir.callback.Callback> callback) {
        if (mLoadService == null) return;
        try {
            ViewGroup loadLayout = mLoadService.getLoadLayout();
            if (loadLayout != null) {
                while (loadLayout.getChildCount() > 1) {
                    loadLayout.removeViewAt(loadLayout.getChildCount() - 1);
                }
            }
            mLoadService.showCallback(callback);
        } catch (Throwable th) {
            Log.w(TAG_LIVEVIS, "showCallback failed: " + callback.getSimpleName(), th);
        }
    }

    private void showLoading() {
        if (mLoadService != null) {
            showCallbackSafe(LoadingCallback.class);
        }
    }

    private void showEmpty() {
        if (null != mLoadService) {
            showCallbackSafe(EmptyCallback.class);
        }
    }

    private void showSuccess() {
        if (null != mLoadService) {
            try {
                ViewGroup loadLayout = mLoadService.getLoadLayout();
                if (loadLayout != null) {
                    while (loadLayout.getChildCount() > 1) {
                        loadLayout.removeViewAt(loadLayout.getChildCount() - 1);
                    }
                }
                mLoadService.showSuccess();
            } catch (Throwable th) {
                Log.w(TAG_LIVEVIS, "showSuccess failed", th);
            }
        }
    }

    @Override
    public LiveChannelGroupNewAdapter getLiveChannelGroupAdapter() {
        return liveChannelGroupAdapter;
    }

    @Override
    public LiveChannelItemNewAdapter getLiveChannelItemAdapter() {
        return liveChannelItemAdapter;
    }
}
