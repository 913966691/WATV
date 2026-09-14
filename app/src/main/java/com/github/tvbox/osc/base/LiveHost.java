package com.github.tvbox.osc.base;

import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.bean.LivePlayerManager;
import com.github.tvbox.osc.ui.adapter.LiveChannelGroupNewAdapter;
import com.github.tvbox.osc.ui.adapter.LiveChannelItemNewAdapter;

/**
 * 直播页宿主接口。
 *
 * 直播播放页原来是独立 Activity(LiveActivity),多个弹窗(LiveSettingDialog 等)通过
 * `(LiveActivity) context` 强转调用其公开方法。重构为单 Activity 架构后,直播页变成
 * MainActivity 内的 Fragment(LiveFragment),Fragment 不是 Context 子类,无法被弹窗强转为
 * Activity。因此抽此接口,让 LiveActivity(保留作回退)与 LiveFragment 都实现它,弹窗只依赖
 * 接口而非具体 Activity。
 */
public interface LiveHost {
    LiveChannelItem getCurrentLiveChannelItem();

    LivePlayerManager getLivePlayerManager();

    void switchingLine2Replay(int position);

    void changeScale(int position);

    void changePlayer(int position);

    LiveChannelGroupNewAdapter getLiveChannelGroupAdapter();

    LiveChannelItemNewAdapter getLiveChannelItemAdapter();
}
