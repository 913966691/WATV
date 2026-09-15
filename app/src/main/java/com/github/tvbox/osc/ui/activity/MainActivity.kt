package com.github.tvbox.osc.ui.activity

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.os.Process
import android.speech.RecognizerIntent
import android.view.KeyEvent
import java.util.Locale
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.blankj.utilcode.util.ActivityUtils
import com.blankj.utilcode.util.ToastUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.base.MainTabHost
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.databinding.ActivityMainBinding
import com.github.tvbox.osc.ui.fragment.GridFragment
import com.github.tvbox.osc.ui.fragment.HomeFragment
import com.github.tvbox.osc.ui.fragment.LiveFragment
import com.github.tvbox.osc.ui.fragment.MyFragment
import com.github.tvbox.osc.ui.fragment.SubFragment
import com.github.tvbox.osc.ai.AiAssistantDialog
import kotlin.system.exitProcess

class MainActivity : BaseVbActivity<ActivityMainBinding>(), MainTabHost {

    companion object {
        const val EXTRA_START_DESTINATION = "main_start_destination"
        private const val REQ_SPEECH_RECOGNITION = 1001
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        // Manifest 里挂的是 AppTheme.Launcher(仅用于启动画面),这里切回正常主题。
        // 必须在 super.onCreate 之前调用,否则窗口背景/状态栏配色会沿用启动主题。
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
    }

    private val fragments = listOf(HomeFragment(), LiveFragment(), SubFragment(), MyFragment())
    var useCacheConfig = false
    private var exitTime = 0L
    private var currentAiDialog: AiAssistantDialog? = null

    /**
     * 底部导航 tab 定义。
     * 自绘导航(见 include_bottom_navigation.xml),每个 tab 用 weight=1 等宽,
     * 规避 Material BottomNavigationView 在横屏大屏下 item 不铺满、左右边距不对称的问题。
     *
     * 注意:ViewPager 仍保持 4 页(首页/直播/订阅/我的),AI 助手 tab 仅触发对话框,不占用 ViewPager 页。
     */
    private enum class BottomTab { HOME, LIVE, AI, SUBSCRIBE, MY }

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

        // 注意:这里刻意不设 offscreenPageLimit。
        // 曾经设过 fragments.size(4 页常驻)想消除快速切 tab 的 fragment 重建抖动,结果:
        //  1) LiveFragment 在 App 启动瞬间创建,ApiConfig 还没就绪 → 误报"暂无直播频道";
        //  2) HomeFragment 再也不重建,切订阅源后没人重新 loadConfig → 必须重启 App。
        // 页面重建是本项目"重新读取配置"的既有机制,不要堵死它。

        // 自绘底部导航:4 个 tab 用 weight=1 等宽,横屏大屏下也必定铺满容器、左右边距对称
        setupBottomNav()
        mBinding.vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // 4 页:0=首页 1=直播 2=订阅 3=我的
                val tab = when (position) {
                    0 -> BottomTab.HOME
                    1 -> BottomTab.LIVE
                    2 -> BottomTab.SUBSCRIBE
                    3 -> BottomTab.MY
                    else -> BottomTab.HOME
                }
                selectTab(tab, false)
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

    /**
     * 直播可见性去重后再下发。
     *
     * selectTab 与 ViewPager2 的 onPageSelected 会形成回环,一次点按会连续两次下发
     * 相同的可见状态(日志里就是两行一模一样的 setPageVisible)。重复下发会重置
     * LiveFragment 的起播去抖计时,也可能把上一次的释放任务冲掉。
     */
    private var lastLiveVisible: Boolean? = null

    private fun applyLiveVisible(visible: Boolean) {
        if (lastLiveVisible == visible) return
        lastLiveVisible = visible
        (fragments[1] as? LiveFragment)?.setPageVisible(visible)
    }

    override fun onPause() {
        super.onPause()
        // 离开主界面(最常见的就是打开点播播放页):彻底停掉直播并释放解码器。
        // 这是最直接的一层兜底 —— 只要 MainActivity 进后台,直播就不允许再占着 MediaCodec。
        applyLiveVisible(false)
    }

    override fun onResume() {
        super.onResume()
        // 回到主界面:只有当前 tab 确实是直播才恢复(带 300ms 去抖)
        applyLiveVisible(currentTab == BottomTab.LIVE)
    }

    /**
     * 订阅页切换了数据源后的统一重载入口。
     *
     * 原 SubscriptionActivity 的做法是源一变就 startActivity(CLEAR_TASK) 整个重启 App
     * (见 SubscriptionActivity.finish()),改造成 Fragment 后这段被丢掉,只剩"首页被
     * ViewPager 销毁重建时顺带重新 loadConfig"这一条隐式路径 —— 太脆弱。
     * 这里改成显式通知:清掉各页已缓存的配置态,让它们重新加载。
     */
    fun onSubscriptionSourceChanged() {
        (fragments[0] as? HomeFragment)?.resetForSourceChange()
        (fragments[1] as? LiveFragment)?.resetForSourceChange()
    }

    private fun openDestination(destination: Int) {
        when (destination) {
            R.id.navigation_dashboard -> selectTab(BottomTab.MY)
            R.id.navigation_live -> selectTab(BottomTab.LIVE)
            R.id.navigation_subscription -> selectTab(BottomTab.SUBSCRIBE)
            else -> selectTab(BottomTab.HOME)
        }
    }

    override fun onBackPressed() {
        when (currentTab) {
            BottomTab.LIVE -> {
                // 直播页的弹窗/播放器优先消费返回键
                if ((fragments[1] as? LiveFragment)?.handleBackPressed() == true) return
                selectTab(BottomTab.HOME)
            }
            BottomTab.SUBSCRIBE -> selectTab(BottomTab.HOME)
            BottomTab.AI -> selectTab(BottomTab.HOME)
            BottomTab.MY -> {
                selectTab(BottomTab.HOME)
            }
            BottomTab.HOME -> {
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
     * 自绘底部导航:绑定 5 个 tab 点击事件 + 初始选中态。
     *
     * 用 weight=1 的 LinearLayout 子项实现等宽,横屏大屏下 5 个 tab 必定铺满容器宽度,
     * 左右边距严格对称,不再出现 Material BottomNavigationView "tab 挤在中间"的问题。
     */
    private fun setupBottomNav() {
        val nav = mBinding.bottomNavRoot

        nav.tabHome.setOnClickListener { selectTab(BottomTab.HOME) }
        nav.tabLive.setOnClickListener { selectTab(BottomTab.LIVE) }
        nav.tabAi.setOnClickListener { selectTab(BottomTab.AI) }
        nav.tabSubscribe.setOnClickListener { selectTab(BottomTab.SUBSCRIBE) }
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

        // 直播页:一旦不是当前页就彻底停播并释放解码器。
        // 不能只靠 fragment 的 onPause —— ViewPager2 连续切换时 fragment lifecycle 更新会滞后/丢失,
        // 会出现"在别的 tab 还听得到直播声音",且解码器被占住导致点播起播失败。
        applyLiveVisible(tab == BottomTab.LIVE)

        val nav = mBinding.bottomNavRoot
        val selected = androidx.core.content.ContextCompat.getColor(
            this, R.color.bili_pink)
        val normal = androidx.core.content.ContextCompat.getColor(
            this, R.color.bili_text_tertiary)

        // 首页 (ViewPager 第 0 页)
        val homeActive = tab == BottomTab.HOME
        nav.ivHome.setColorFilter(if (homeActive) selected else normal)
        nav.tvHome.setTextColor(if (homeActive) selected else normal)
        nav.tvHome.setTypeface(null, if (homeActive) Typeface.BOLD else Typeface.NORMAL)

        // 直播 (ViewPager 第 1 页)
        val liveActive = tab == BottomTab.LIVE
        nav.ivLive.setColorFilter(if (liveActive) selected else normal)
        nav.tvLive.setTextColor(if (liveActive) selected else normal)
        nav.tvLive.setTypeface(null, if (liveActive) Typeface.BOLD else Typeface.NORMAL)

        // AI 助手 (不对应 ViewPager 页,点击弹出对话框)
        val aiActive = tab == BottomTab.AI
        nav.ivAi.setColorFilter(if (aiActive) selected else normal)
        nav.tvAi.setTextColor(if (aiActive) selected else normal)
        nav.tvAi.setTypeface(null, if (aiActive) Typeface.BOLD else Typeface.NORMAL)

        // 订阅 (ViewPager 第 2 页)
        val subActive = tab == BottomTab.SUBSCRIBE
        nav.ivSubscribe.setColorFilter(if (subActive) selected else normal)
        nav.tvSubscribe.setTextColor(if (subActive) selected else normal)
        nav.tvSubscribe.setTypeface(null, if (subActive) Typeface.BOLD else Typeface.NORMAL)

        // 我的 (ViewPager 第 3 页)
        val myActive = tab == BottomTab.MY
        nav.ivMy.setColorFilter(if (myActive) selected else normal)
        nav.tvMy.setTextColor(if (myActive) selected else normal)
        nav.tvMy.setTypeface(null, if (myActive) Typeface.BOLD else Typeface.NORMAL)

        if (switchPage) {
            val targetPage = when (tab) {
                BottomTab.HOME -> 0
                BottomTab.LIVE -> 1
                BottomTab.SUBSCRIBE -> 2
                BottomTab.MY -> 3
                BottomTab.AI -> -1 // AI 助手不对应 ViewPager 页,点击时弹出对话框
            }
            if (targetPage >= 0 && mBinding.vp.currentItem != targetPage) {
                mBinding.vp.setCurrentItem(targetPage, false)
            }
            if (tab == BottomTab.AI) {
                currentAiDialog = AiAssistantDialog(this).apply { show() }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentTab == BottomTab.LIVE) {
            (fragments[1] as? LiveFragment)?.handleKeyEvent(event)
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 启动系统语音输入 Activity（RecognizerIntent 兜底），供 AiAssistantDialog 在内联识别不可用时调用。
     * 返回 true 表示成功调起，false 表示设备上无可用识别 Activity。
     */
    fun startSpeechRecognition(): Boolean {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话，识别后会自动填入输入框")
        }
        // 先检查是否有 Activity 能处理该 Intent（澎湃 OS 等系统可能移除了识别 Activity）
        if (intent.resolveActivity(packageManager) == null) {
            android.util.Log.w("WATV_AI", "startSpeechRecognition: 无 Activity 处理 RecognizerIntent")
            return false
        }
        return try {
            startActivityForResult(intent, REQ_SPEECH_RECOGNITION)
            true
        } catch (e: ActivityNotFoundException) {
            android.util.Log.w("WATV_AI", "startSpeechRecognition: ActivityNotFoundException ${e.message}")
            false
        } catch (e: SecurityException) {
            android.util.Log.w("WATV_AI", "startSpeechRecognition: SecurityException ${e.message}")
            false
        } catch (e: Exception) {
            android.util.Log.e("WATV_AI", "startSpeechRecognition: 启动失败 ${e.message}", e)
            false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SPEECH_RECOGNITION) return
        when (resultCode) {
            Activity.RESULT_OK -> {
                val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val text = matches?.firstOrNull()
                if (!text.isNullOrEmpty()) {
                    currentAiDialog?.fillInput(text)
                } else {
                    ToastUtils.showShort("未识别到内容")
                }
            }
            Activity.RESULT_CANCELED -> {
                // 澎湃 OS 等系统语音服务内部初始化失败时通常会返回 CANCELED，此时给用户明确提示
                val error = data?.getIntExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, -1)
                android.util.Log.w("WATV_AI", "onActivityResult: 系统语音输入被取消/失败 errorExtra=$error")
                ToastUtils.showLong("系统语音输入失败，建议检查网络/权限，或使用键盘语音输入")
            }
            else -> {
                android.util.Log.w("WATV_AI", "onActivityResult: 系统语音输入返回未知结果码 $resultCode")
                ToastUtils.showShort("系统语音输入未返回结果")
            }
        }
    }

    override fun switchToTab(tab: Int) {
        val target = when (tab) {
            MainTabHost.TAB_HOME -> BottomTab.HOME
            MainTabHost.TAB_LIVE -> BottomTab.LIVE
            MainTabHost.TAB_SUBSCRIBE -> BottomTab.SUBSCRIBE
            MainTabHost.TAB_MY -> BottomTab.MY
            else -> BottomTab.HOME
        }
        selectTab(target)
    }
}
