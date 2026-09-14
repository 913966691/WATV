package com.github.tvbox.osc.base;

/**
 * 底部导航 tab 切换宿主接口。
 *
 * 单 Activity 架构下,直播/订阅页改为 MainActivity 内的 Fragment。这些 Fragment 内部底部导航
 * 的点击不再 finish/jumpActivity,而是回调 MainActivity 切到对应 ViewPager 页,保证底部导航
 * 永远静止。MainActivity 实现本接口,Fragment 在 onAttach 时拿到宿主并调用 switchToTab。
 */
public interface MainTabHost {
    int TAB_HOME = 0;
    int TAB_LIVE = 1;
    int TAB_SUBSCRIBE = 2;
    int TAB_MY = 3;

    void switchToTab(int tab);
}
