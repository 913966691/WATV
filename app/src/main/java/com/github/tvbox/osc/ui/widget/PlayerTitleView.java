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

    public PlayerTitleView(@NonNull Context context) {
        super(context);
    }

    public PlayerTitleView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public PlayerTitleView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
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
                        // 单 Activity 架构下,直播/其他 Fragment 的播放器宿主是 MainActivity,
                        // 旧代码会调 setRequestedOrientation(PORTRAIT) 强行把整个 MainActivity
                        // 旋成竖屏,导致 Fragment 重建、mVideoView 被 release、播放中断。
                        // 这里只退出全屏,不再动宿主 Activity 的方向。
                        if (activity instanceof com.github.tvbox.osc.ui.activity.MainActivity) {
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
