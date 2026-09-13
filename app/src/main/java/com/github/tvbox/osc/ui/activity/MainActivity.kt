package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.os.Process
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.blankj.utilcode.util.ActivityUtils
import com.blankj.utilcode.util.ToastUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.databinding.ActivityMainBinding
import com.github.tvbox.osc.ui.fragment.GridFragment
import com.github.tvbox.osc.ui.fragment.HomeFragment
import com.github.tvbox.osc.ui.fragment.MyFragment
import kotlin.system.exitProcess

class MainActivity : BaseVbActivity<ActivityMainBinding>() {

    companion object {
        const val EXTRA_START_DESTINATION = "main_start_destination"
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        // Manifest 里挂的是 AppTheme.Launcher(仅用于启动画面),这里切回正常主题。
        // 必须在 super.onCreate 之前调用,否则窗口背景/状态栏配色会沿用启动主题。
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
    }

    private val fragments = listOf(HomeFragment(), MyFragment())
    var useCacheConfig = false
    private var exitTime = 0L

    /**
     * 底部导航 tab 定义。
     * 自绘导航(见 include_bottom_navigation.xml),每个 tab 用 weight=1 等宽,
     * 规避 Material BottomNavigationView 在横屏大屏下 item 不铺满、左右边距不对称的问题。
     *
     * 注意 tab 顺序要和 ViewPager 的 2 个 fragment (HomeFragment / MyFragment) 对应:
     *  - 首页 (vp 0) / 我的 (vp 1) 是可切换的 fragment
     *  - 直播 / 订阅 是跳转独立 Activity,不占 ViewPager 页
     */
    private enum class BottomTab { HOME, LIVE, SUBSCRIBE, MY }

    private var currentTab: BottomTab = BottomTab.HOME

    /**
     * 导航栏颜色守卫:
     * 首页冷启动加载(配置/jar/豆瓣数据)期间,底部手势条区域会瞬间由深灰变黑再变回深灰。
     * 手势条区域是 bottomNavRoot 的透明 padding,直接透出 window.navigationBarColor,
     * 说明加载过程中窗口级导航栏颜色被某个运行时组件临时改成了黑色/透明。
     * 全局布局回调里监测颜色,一旦被篡改立即恢复为 bili_bg_card,同时输出 logcat 便于定位真凶。
     */
    private fun installNavBarGuard() {
        val expected = androidx.core.content.ContextCompat.getColor(this, R.color.bili_bg_card)
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener {
            try {
                val cur = window.navigationBarColor
                if (cur != expected) {
                    android.util.Log.e(
                        "NavBarGuard",
                        "检测到导航栏颜色被运行时篡改: ${String.format("#%08X", cur)} → 恢复为 #1C1F23"
                    )
                    window.navigationBarColor = expected
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun init() {

        useCacheConfig = intent.extras?.getBoolean(IntentKey.CACHE_CONFIG_CHANGED, false) ?: false

        // 导航栏颜色守卫,防止首页加载期间手势条区域闪黑
        installNavBarGuard()

        mBinding.vp.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = fragments.size

            override fun createFragment(position: Int): Fragment = fragments[position]
        }

        // 自绘底部导航:4 个 tab 用 weight=1 等宽,横屏大屏下也必定铺满容器、左右边距对称
        setupBottomNav()
        mBinding.vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // ViewPager 只有 2 页,0=首页 1=我的;直播/订阅是跳转 Activity 不占页
                selectTab(if (position == 0) BottomTab.HOME else BottomTab.MY, false)
            }
        })
        openDestination(intent.getIntExtra(EXTRA_START_DESTINATION, R.id.navigation_home))

        // 全面屏适配:ViewPager 顶部动态加状态栏高度,Fragment 内不再 paddingTop 状态栏
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.vp) { v, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            v.setPadding(0, maxOf(statusBar, cutout), 0, 0)
            insets
        }
        // 底部导航底部加手势条高度,避免 Home indicator 覆盖最后一行 tab
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.bottomNavRoot.root) { v, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(0, 0, 0, navBar)
            insets
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openDestination(intent.getIntExtra(EXTRA_START_DESTINATION, R.id.navigation_home))
    }

    private fun openDestination(destination: Int) {
        when (destination) {
            R.id.navigation_dashboard -> selectTab(BottomTab.MY)
            R.id.navigation_live -> jumpActivity(LiveActivity::class.java)
            R.id.navigation_subscription -> jumpActivity(SubscriptionActivity::class.java)
            else -> selectTab(BottomTab.HOME)
        }
    }

    override fun onBackPressed() {
        if (mBinding.vp.currentItem == 1) {
            mBinding.vp.currentItem = 0
            return
        }
        val homeFragment = fragments[0] as HomeFragment
        if (!homeFragment.isAdded) { // 资源不足销毁重建时未挂载到activity时getChildFragmentManager会崩溃
            confirmExit()
            return
        }
        val childFragments = homeFragment.allFragments
        if (childFragments.isEmpty()) { //加载中(没有tab)
            confirmExit()
            return
        }
        val fragment: Fragment = childFragments[homeFragment.tabIndex]
        if (fragment is GridFragment) { // 首页数据源动态加载的tab
            if (!fragment.restoreView()) { // 有回退的view,先回退(AList等文件夹列表),没有可回退的,返到主页tab
                if (!homeFragment.scrollToFirstTab()) {
                    confirmExit()
                }
            }
        } else {
            confirmExit()
        }
    }

    private fun confirmExit() {
        if (System.currentTimeMillis() - exitTime > 2000) {
            ToastUtils.showShort("再按一次退出程序")
            exitTime = System.currentTimeMillis()
        } else {
            ActivityUtils.finishAllActivities(true)
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }
    }

    /**
     * 自绘底部导航:绑定 4 个 tab 点击事件 + 初始选中态。
     *
     * 用 weight=1 的 LinearLayout 子项实现等宽,横屏大屏下 4 个 tab 必定铺满容器宽度,
     * 左右边距严格对称,不再出现 Material BottomNavigationView "tab 挤在中间"的问题。
     */
    private fun setupBottomNav() {
        val nav = mBinding.bottomNavRoot

        nav.tabHome.setOnClickListener { selectTab(BottomTab.HOME) }
        nav.tabLive.setOnClickListener {
            // 直播/订阅是跳转独立 Activity,不切换 ViewPager,也不改变当前选中态
            jumpActivity(LiveActivity::class.java)
        }
        nav.tabSubscribe.setOnClickListener {
            jumpActivity(SubscriptionActivity::class.java)
        }
        nav.tabMy.setOnClickListener { selectTab(BottomTab.MY) }

        selectTab(BottomTab.HOME, false)
    }

    /**
     * 切换底部 tab 选中态。
     * @param tab 目标 tab
     * @param switchPage 是否同步切换 ViewPager 页(从 ViewPager 回调过来时传 false 避免回环)
     */
    private fun selectTab(tab: BottomTab, switchPage: Boolean = true) {
        currentTab = tab
        val nav = mBinding.bottomNavRoot
        val selected = androidx.core.content.ContextCompat.getColor(
            this, R.color.bili_pink)
        val normal = androidx.core.content.ContextCompat.getColor(
            this, R.color.bili_text_tertiary)

        // 首页 (ViewPager 第 0 页)
        val homeActive = tab == BottomTab.HOME
        nav.ivHome.setColorFilter(if (homeActive) selected else normal)
        nav.tvHome.setTextColor(if (homeActive) selected else normal)

        // 我的 (ViewPager 第 1 页)
        val myActive = tab == BottomTab.MY
        nav.ivMy.setColorFilter(if (myActive) selected else normal)
        nav.tvMy.setTextColor(if (myActive) selected else normal)

        // 直播/订阅是跳转 Activity,永远保持未选中态
        nav.ivLive.setColorFilter(normal)
        nav.tvLive.setTextColor(normal)
        nav.ivSubscribe.setColorFilter(normal)
        nav.tvSubscribe.setTextColor(normal)

        if (switchPage) {
            val targetPage = if (tab == BottomTab.MY) 1 else 0
            if (mBinding.vp.currentItem != targetPage) {
                mBinding.vp.setCurrentItem(targetPage, false)
            }
        }
    }
}
