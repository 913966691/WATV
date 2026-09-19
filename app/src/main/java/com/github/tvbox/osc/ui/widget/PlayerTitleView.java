package com.github.tvbox.osc.ui.widget;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.blankj.utilcode.util.ActivityUtils;
import com.github.tvbox.osc.ui.activity.LiveActivity;
import com.github.tvbox.osc.util.ReceiverCompat;

import xyz.doikki.videocontroller.R;
import xyz.doikki.videoplayer.controller.ControlWrapper;
import xyz.doikki.videoplayer.controller.IControlComponent;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.PlayerUtils;

/**
 * 播放器顶部标题栏
 */
public class PlayerTitleView extends FrameLayout implements IControlComponent {

    private ControlWrapper mControlWrapper;

    private final LinearLayout mTitleContainer;
    private final TextView mTitle;
    private final TextView mSysTime;//系统当前时间

    private final BatteryReceiver mBatteryReceiver;
    private boolean mIsRegister;//是否注册BatteryReceiver

    /**
     * 是否在竖屏模式下彻底隐藏标题栏(返回箭头+标题),仅在横屏显示。
     * 一些单 Activity 架构的入口(LiveFragment 所在的 MainActivity)竖屏时
     * 没有上一级页面需要"返回",让标题栏显示只会让人困惑。
     * 默认 false 兼容原有行为,需要时调用 {@link #setHideInPortraitMode} 启用。
     */
    private boolean mHideInPortraitMode = false;

    public PlayerTitleView(@NonNull Context context) {
        super(context);
    }

    public PlayerTitleView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public PlayerTitleView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * 竖屏模式下隐藏标题栏(返回箭头等),仅在横屏显示。
     */
    public PlayerTitleView setHideInPortraitMode(boolean hideInPortraitMode) {
        this.mHideInPortraitMode = hideInPortraitMode;
        // 如果当前已经在竖屏状态且不可见,直接同步一次状态,避免后续 onVisibilityChanged 被错过
        if (hideInPortraitMode
                && mControlWrapper != null
                && !mControlWrapper.isFullScreen()
                && getVisibility() != GONE) {
            setVisibility(GONE);
        }
        return this;
    }

    {
        setVisibility(GONE);
        LayoutInflater.from(getContext()).inflate(R.layout.dkplayer_layout_title_view, this, true);
        mTitleContainer = findViewById(R.id.title_container);
        ImageView back = findViewById(R.id.back);
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Activity activity = PlayerUtils.scanForActivity(getContext());
                if (activity != null) {
                    if (mControlWrapper.isFullScreen()){
                        // ★ Bug 修复:如果是 DetailActivity(详情+播放器共用),
                        // 必须先调 toggleFullPreview 让 Activity 把详情面板恢复显示、
                        // previewPlayerPlace 还原成 wrap_content,并翻转 fullWindows 标志位。
                        // 之前这条路径只调 setRequestedOrientation + stopFullScreen(),
                        // 完全绕过 DetailActivity 的状态机,导致从全屏返回后
                        // 详情面板(标题/线路/选集)永久 GONE,只剩顶部一小块视频画面。
                        if (activity instanceof com.github.tvbox.osc.ui.activity.DetailActivity) {
                            ((com.github.tvbox.osc.ui.activity.DetailActivity) activity).toggleFullPreview();
                            return;
                        }
                        // 单 Activity 架构下,直播/其他 Fragment 的播放器宿主是 MainActivity。
                        // 之前担心 setRequestedOrientation(PORTRAIT) 会触发 Fragment 重建,
                        // 但现在 MainActivity 已在 manifest 中声明 orientation|screenSize 等 configChanges,
                        // 方向切换只会走 onConfigurationChanged,不会重建 Activity/Fragment。
                        // 如果退出全屏时不把方向改回竖屏,MainActivity 会一直保持横屏。
                        if (activity instanceof com.github.tvbox.osc.ui.activity.MainActivity) {
                            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                            mControlWrapper.stopFullScreen();
                            return;
                        }
                        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                        mControlWrapper.stopFullScreen();
                    }else {
                        activity.finish();
                    }
                }
            }
        });
        mTitle = findViewById(R.id.title);
        mSysTime = findViewById(R.id.sys_time);
        //电量
        ImageView batteryLevel = findViewById(R.id.iv_battery);
        mBatteryReceiver = new BatteryReceiver(batteryLevel);
    }

    public void setTitle(String title) {
        mTitle.setText(title);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mIsRegister) {
            getContext().unregisterReceiver(mBatteryReceiver);
            mIsRegister = false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mIsRegister) {
            ReceiverCompat.registerSafe(getContext(), mBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            mIsRegister = true;
        }
    }

    @Override
    public void attach(@NonNull ControlWrapper controlWrapper) {
        mControlWrapper = controlWrapper;
    }

    @Override
    public View getView() {
        return this;
    }

    @Override
    public void onVisibilityChanged(boolean isVisible, Animation anim) {
        if (isVisible) {
            // 开了"竖屏隐藏"且当前确实是竖屏(mControlWrapper 未 attach 时也认作竖屏兜底)→ 强制 GONE
            if (mHideInPortraitMode
                    && (mControlWrapper == null || !mControlWrapper.isFullScreen())) {
                if (getVisibility() != GONE) {
                    setVisibility(GONE);
                    if (anim != null) {
                        startAnimation(anim);
                    }
                }
                return;
            }
            if (getVisibility() == GONE) {
                mSysTime.setText(PlayerUtils.getCurrentSystemTime());
                setVisibility(VISIBLE);
                if (anim != null) {
                    startAnimation(anim);
                }
            }
        } else {
            if (getVisibility() == VISIBLE) {
                setVisibility(GONE);
                if (anim != null) {
                    startAnimation(anim);
                }
            }
        }
    }

    @Override
    public void onPlayStateChanged(int playState) {
        switch (playState) {
            case VideoView.STATE_IDLE:
            case VideoView.STATE_START_ABORT:
            case VideoView.STATE_PREPARING:
            case VideoView.STATE_PREPARED:
            case VideoView.STATE_ERROR:
            case VideoView.STATE_PLAYBACK_COMPLETED:
                setVisibility(GONE);
                break;
        }
    }

    @Override
    public void onPlayerStateChanged(int playerState) {
        if (playerState == VideoView.PLAYER_FULL_SCREEN) {
            if (mControlWrapper.isShowing() && !mControlWrapper.isLocked()) {
                setVisibility(VISIBLE);
                mSysTime.setText(PlayerUtils.getCurrentSystemTime());
            }
            mTitle.setSelected(true);
        } else {
            setVisibility(GONE);
            mTitle.setSelected(false);
        }

        Activity activity = PlayerUtils.scanForActivity(getContext());
        if (activity != null && mControlWrapper.hasCutout()) {
            int orientation = activity.getRequestedOrientation();
            int cutoutHeight = mControlWrapper.getCutoutHeight();
            if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                mTitleContainer.setPadding(0, 0, 0, 0);
            } else if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                mTitleContainer.setPadding(cutoutHeight, 0, 0, 0);
            } else if (orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
                mTitleContainer.setPadding(0, 0, cutoutHeight, 0);
            }
        }
    }

    @Override
    public void setProgress(int duration, int position) {

    }

    @Override
    public void onLockStateChanged(boolean isLocked) {
        if (isLocked) {
            setVisibility(GONE);
        } else {
            // 开了"竖屏隐藏"且当前是竖屏 → 解锁也不显示标题栏
            if (mHideInPortraitMode
                    && (mControlWrapper == null || !mControlWrapper.isFullScreen())) {
                return;
            }
            setVisibility(VISIBLE);
            mSysTime.setText(PlayerUtils.getCurrentSystemTime());
        }
    }

    private static class BatteryReceiver extends BroadcastReceiver {
        private final ImageView pow;

        public BatteryReceiver(ImageView pow) {
            this.pow = pow;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle extras = intent.getExtras();
            if (extras == null) return;
            int current = extras.getInt("level");// 获得当前电量
            int total = extras.getInt("scale");// 获得总电量
            int percent = current * 100 / total;
            pow.getDrawable().setLevel(percent);
        }
    }
}
