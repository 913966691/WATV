package com.github.tvbox.osc.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import com.angcyo.tablayout.delegate.ViewPager1Delegate.Companion.install
import com.blankj.utilcode.util.ConvertUtils
import com.blankj.utilcode.util.ScreenUtils
import com.blankj.utilcode.util.ToastUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.api.ApiConfig.LoadConfigCallback
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseLazyFragment
import com.github.tvbox.osc.base.BaseVbFragment
import com.github.tvbox.osc.base.MainTabHost
import com.github.tvbox.osc.bean.AbsSortXml
import com.github.tvbox.osc.callback.TimeoutCallback
import com.kingja.loadsir.core.LoadSir
import com.github.tvbox.osc.bean.MovieSort.SortData
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.databinding.FragmentHomeBinding
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.ui.activity.CollectActivity
import com.github.tvbox.osc.ui.activity.FastSearchActivity
import com.github.tvbox.osc.ui.activity.HistoryActivity
import com.github.tvbox.osc.ui.activity.MainActivity
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter.SelectDialogInterface
import com.github.tvbox.osc.ui.dialog.LastViewedDialog
import com.github.tvbox.osc.ui.dialog.SelectDialog
import com.github.tvbox.osc.ui.dialog.TipDialog
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.viewmodel.SourceViewModel
import com.lxj.xpopup.XPopup
import com.orhanobut.hawk.Hawk
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : BaseVbFragment<FragmentHomeBinding>() {

    /**
     * 提供给主页返回操作
     */
    val tabIndex: Int
        get() = mBinding.tabLayout.currentItemIndex

    /**
     * 首页资源冷加载看门狗:进入加载后 60s 仍未出结果(成功)则提示超时并可手动重刷。
     * 切走再切回时 onPause 会清掉所有 Handler 消息(看门狗一并被清),onResume 视情况重启。
     */
    private val LOAD_TIMEOUT_MS = 60_000L
    private val mLoadTimeoutRunnable = Runnable {
        if (!isVisible) return@Runnable
        showLoadTimeout("首页资源加载超时，请检查网络后点击重新加载")
    }

    private fun startLoadWatchdog() {
        mHandler.removeCallbacks(mLoadTimeoutRunnable)
        mHandler.postDelayed(mLoadTimeoutRunnable, LOAD_TIMEOUT_MS)
    }

    private fun cancelLoadWatchdog() {
        mHandler.removeCallbacks(mLoadTimeoutRunnable)
    }

    /**
     * 冷加载重刷:重置配置/jar 就绪标志,从头重新拉取首页资源。供 LoadSir 超时页点击触发。
     */
    private fun reloadHome() {
        dataInitOk = false
        jarInitOk = false
        pendingInitData = false
        initData()
    }

    /**
     * 统一"超时/空态"提示:居中一行字 + 刷新按钮(点击任意位置经 LoadSir OnReload 触发 reloadHome)。
     */
    private fun showLoadTimeout(msg: String) {
        cancelLoadWatchdog()
        showCallback(TimeoutCallback::class.java)
        mLoadService?.loadLayout?.findViewById<TextView>(R.id.tv_timeout_tip)?.text = msg
    }

    /**
     * 提供给主页返回操作
     */
    val allFragments: List<BaseLazyFragment>
        get() = fragments

    private var sourceViewModel: SourceViewModel? = null
    private val fragments: MutableList<BaseLazyFragment> = ArrayList()
    private val mHandler = Handler()

    /**
     * 顶部tabs分类集合,用于渲染tab页,每个tab对应fragment内的数据
     */
    private var mSortDataList: List<SortData> = ArrayList()
    private var dataInitOk = false
    private var jarInitOk = false

    /**
     * 标识：loadConfig/loadJar 成功后通过 Handler 延迟继续 initData() 的任务是否还在队列中。
     * 当用户在 Home 加载过程中切到其它 tab 时，onPause 会 remove 所有 Handler 回调，
     * 导致这个延迟任务丢失；切回 Home 的 onResume 里通过此标志补调 initData()，避免首页永远卡加载。
     */
    private var pendingInitData = false

    var errorTipDialog: TipDialog? = null

    /**
     * true: 配置变更重载
     * false: 全部重载(api变更、重启app等)
     */
    var onlyConfigChanged = false

    override fun init() {
        ControlManager.get().startServer()
        mBinding.nameContainer.setOnClickListener {
            if (dataInitOk && jarInitOk) {
                showSiteSwitch()
            } else {
                ToastUtils.showShort("数据源未加载，长按刷新或切换订阅")
            }
        }
        mBinding.nameContainer.setOnLongClickListener {
            refreshHomeSources()
            true
        }
        mBinding.search.setOnClickListener {
            jumpActivity(FastSearchActivity::class.java)
        }
        mBinding.ivHistory.setOnClickListener {
            jumpActivity(HistoryActivity::class.java)
        }
        mBinding.ivCollect.setOnClickListener {
            jumpActivity(CollectActivity::class.java)
        }
        setLoadSir(mBinding.contentLayout)
        initViewModel()
        initData()
    }

    /**
     * 覆写基类注册:超时/空态页点击任意位置 → 冷加载重刷首页资源。
     */
    override fun setLoadSir(view: View?) {
        if (mLoadService == null && view != null) {
            mLoadService = LoadSir.getDefault().register(view) { reloadHome() }
        }
    }


    private fun initViewModel() {
        sourceViewModel = ViewModelProvider(this).get(SourceViewModel::class.java)
        sourceViewModel?.sortResult?.observe(this) { absXml: AbsSortXml? ->
            showSuccess()
            cancelLoadWatchdog()
            mSortDataList =
                if (absXml?.classes != null && absXml.classes.sortList != null) {
                    DefaultConfig.adjustSort(
                        ApiConfig.get().homeSourceBean.key,
                        absXml.classes.sortList,
                        true
                    )
                } else {
                    DefaultConfig.adjustSort(ApiConfig.get().homeSourceBean.key, ArrayList(), true)
                }
            initViewPager(absXml)
        }
    }

    /**
     * 将 initData() 投递到 Handler，并标记为“待执行”。
     * 若 Fragment 在任务执行前进入 onPause，该任务会被 remove；onResume 里通过 pendingInitData 补调。
     */
    private fun postInitData(delay: Long = 0) {
        pendingInitData = true
        if (delay > 0) {
            mHandler.postDelayed({ initData() }, delay)
        } else {
            mHandler.post { initData() }
        }
    }

    private fun initData() {
        pendingInitData = false
        val mainActivity = mActivity as MainActivity
        onlyConfigChanged = mainActivity.useCacheConfig

        val home = ApiConfig.get().homeSourceBean
        if (home != null && !home.name.isNullOrEmpty()) {
            mBinding.tvName.text = home.name
            mBinding.tvName.postDelayed({ mBinding.tvName.isSelected = true }, 2000)
        }

        showLoading()
        startLoadWatchdog()
        when{
            dataInitOk && jarInitOk -> {
                //正常初始化会先加载,最终到这,此时数据有以下几种情况
                // 1. api/jar/spider等均加载完,正常显示数据。2. 缺失spider(存疑?)/api配置有问题同样加载(最后空布局 或 只有豆瓣首页)
                sourceViewModel?.getSort(ApiConfig.get().homeSourceBean.key)
            }
            dataInitOk && !jarInitOk -> {
                loadJar()
            }
            else -> {
                loadConfig()
            }
        }
    }

    /**
     * 订阅页切换数据源后由 MainActivity 调用:清掉"配置已就绪"标志,强制重新 loadConfig。
     *
     * 视图还在时立刻重载;视图若已被 ViewPager 销毁,标志会保留到下次
     * onViewCreated → init() → initData() 再生效,两条路都能覆盖。
     */
    fun resetForSourceChange() {
        dataInitOk = false
        jarInitOk = false
        onlyConfigChanged = false
        if (isAdded && view != null) {
            initData()
        }
    }

    private fun loadConfig(){
        ApiConfig.get().loadConfig(onlyConfigChanged, object : LoadConfigCallback {

            override fun retry() {
                postInitData()
            }

            override fun success() {
                dataInitOk = true
                if (ApiConfig.get().spider.isEmpty()) {
                    jarInitOk = true
                }
                postInitData(50)
            }

            override fun error(msg: String) {
                if (msg.equals("-1", ignoreCase = true)) {
                    dataInitOk = true
                    jarInitOk = true
                    postInitData()
                } else {
                    showTipDialog(msg)
                    cancelLoadWatchdog()
                }
            }
        }, activity)
    }

    private fun loadJar(){
        if (!ApiConfig.get().spider.isNullOrEmpty()) {
            ApiConfig.get().loadJar(
                onlyConfigChanged,
                ApiConfig.get().spider,
                object : LoadConfigCallback {
                    override fun success() {
                        jarInitOk = true
                        if (!onlyConfigChanged) {
                            queryHistory()
                        }
                        postInitData(50)
                    }

                    override fun retry() {}
                    override fun error(msg: String) {
                        jarInitOk = true
                        // 显示真实失败原因(网络异常/jar损坏等),便于排查订阅地址是否可达
                        ToastUtils.showLong("更新订阅失败: $msg")
                        postInitData()
                    }
                })
        }
    }

    private fun showTipDialog(msg: String) {
        if (errorTipDialog == null) {
            errorTipDialog =
                TipDialog(requireActivity(), msg, "重试", "取消", object : TipDialog.OnListener {
                    override fun left() {
                        postInitData()
                        errorTipDialog?.hide()
                    }

                    override fun right() {
                        dataInitOk = true
                        jarInitOk = true
                        postInitData()
                        errorTipDialog?.hide()
                    }

                    override fun cancel() {
                        dataInitOk = true
                        jarInitOk = true
                        postInitData()
                        errorTipDialog?.hide()
                    }

                    override fun onTitleClick() {
                        errorTipDialog?.hide()
                        (requireActivity() as MainTabHost).switchToTab(MainTabHost.TAB_SUBSCRIBE)
                    }
                })
        }
        if (!errorTipDialog!!.isShowing) errorTipDialog!!.show()
    }

    private fun getTabTextView(text: String): TextView {
        val textView = TextView(mContext)
        textView.text = text
        textView.gravity = Gravity.CENTER
        textView.setPadding(
            ConvertUtils.dp2px(20f),
            ConvertUtils.dp2px(10f),
            ConvertUtils.dp2px(5f),
            ConvertUtils.dp2px(10f)
        )
        return textView
    }

    private fun initViewPager(absXml: AbsSortXml?) {
        if (mSortDataList.isNotEmpty()) {
            mBinding.tabLayout.removeAllViews()
            fragments.clear()
            for (data in mSortDataList) {
                mBinding.tabLayout.addView(getTabTextView(data.name))
                if (data.id == "my0") { //tab是主页,添加主页fragment 根据设置项显示豆瓣热门/站点推荐(每个源不一样)/历史记录
                    if (Hawk.get(
                            HawkConfig.HOME_REC,
                            0
                        ) == 1 && absXml != null && absXml.videoList != null && absXml.videoList.size > 0
                    ) { //站点推荐
                        fragments.add(UserFragment.newInstance(absXml.videoList))
                    } else { //豆瓣热门/历史记录
                        fragments.add(UserFragment.newInstance(null))
                    }
                } else { //来自源的分类
                    fragments.add(GridFragment.newInstance(data))
                }
            }
            if (Hawk.get(HawkConfig.HOME_REC, 0) == 2) { //关闭主页
                mBinding.tabLayout.removeViewAt(0)
                fragments.removeAt(0)
            }

            //重新渲染vp
            mBinding.mViewPager.adapter =
                object : FragmentStatePagerAdapter(getChildFragmentManager()) {
                    override fun getItem(position: Int): Fragment {
                        return fragments[position]
                    }

                    override fun getCount(): Int {
                        return fragments.size
                    }
                }
            //tab和vp绑定
            install(mBinding.mViewPager, mBinding.tabLayout, true)
            // 重建后强制让内部子 Fragment 收到可见分发并重新布局,
            // 避免"空白需点击才出内容"(外层 ViewPager2 销毁重建导致内层懒加载 init 漏触发)
            mBinding.mViewPager.post {
                mBinding.mViewPager.requestLayout()
                dispatchInnerVisible()
            }
        }
    }

    /**
     * 让内部 ViewPager 当前可见的 BaseLazyFragment 重新收到可见分发(幂等),
     * 覆盖父 Fragment 被外层 ViewPager2 销毁重建后子 Fragment 漏触发 init 的边界。
     */
    private fun dispatchInnerVisible() {
        childFragmentManager.fragments.forEach { f ->
            if (f is BaseLazyFragment && f.isVisible) {
                f.reattachVisibleIfNeeded()
            }
        }
    }

    /**
     * 提供给主页返回操作
     */
    fun scrollToFirstTab(): Boolean {
        return if (mBinding.tabLayout.currentItemIndex != 0) {
            mBinding.mViewPager.setCurrentItem(0, false)
            true
        } else {
            false
        }
    }

    override fun onPause() {
        super.onPause()
        mHandler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        // 如果在加载过程中切走再切回，loadConfig/loadJar 成功后 post 的 initData() 可能已被 onPause 移除，
        // 这里通过 pendingInitData 标志补调一次，避免首页永远卡在 loading。
        if (isAdded && view != null && pendingInitData) {
            initData()
        } else if (isAdded && view != null && (!dataInitOk || !jarInitOk)) {
            // 回来时仍在加载(或已超时未重刷)，重启 60s 看门狗，确保超时提示能再次出现
            startLoadWatchdog()
        }
        // 切回首页时,确保内部 ViewPager 子 Fragment 已触发加载并显示(重建边界兜底)
        mBinding.mViewPager?.post { dispatchInnerVisible() }
    }

    private fun showSiteSwitch() {
        val sites = ApiConfig.get().sourceBeanList
        if (sites.size > 0) {
            val dialog = SelectDialog<SourceBean>(requireActivity())
            val tvRecyclerView = dialog.findViewById<TvRecyclerView>(R.id.list)
            tvRecyclerView.setLayoutManager(V7GridLayoutManager(dialog.context, 2))
            dialog.setTip("请选择首页数据源")
            dialog.setAdapter(object : SelectDialogInterface<SourceBean?> {
                override fun click(value: SourceBean?, pos: Int) {
                    ApiConfig.get().setSourceBean(value)
                    refreshHomeSources()
                }

                override fun getDisplay(source: SourceBean?): String {
                    return if (source == null) "" else source.name
                }
            }, object : DiffUtil.ItemCallback<SourceBean>() {
                override fun areItemsTheSame(oldItem: SourceBean, newItem: SourceBean): Boolean {
                    return oldItem === newItem
                }

                override fun areContentsTheSame(oldItem: SourceBean, newItem: SourceBean): Boolean {
                    return oldItem.key.contentEquals(newItem.key)
                }
            }, sites, sites.indexOf(ApiConfig.get().homeSourceBean))
            dialog.show()
        } else {
            ToastUtils.showLong("暂无可用数据源")
        }
    }

    private fun refreshHomeSources() {
        val intent = Intent(App.getInstance(), MainActivity::class.java)
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val bundle = Bundle()
        bundle.putBoolean(IntentKey.CACHE_CONFIG_CHANGED, true)
        intent.putExtras(bundle)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注意:不要在此 stopServer()。本地代理(RemoteServer)是直播/点播流的统一入口,
        // 必须随 App 常驻;一旦随首页销毁而被 stop,且 ControlManager.startServer 曾因 mServer!=null
        // 误判"已在运行"而无法重启,会导致"先播直播再点播"连不上代理(ExoPlayer 2001)。
        // 代理生命周期已上移到 MainActivity,此处不再停服。
    }

    private fun queryHistory() {
        lifecycleScope.launch {
            val vodInfoList = withContext(Dispatchers.IO) {
                val allVodRecord = RoomDataManger.getAllVodRecord(100)
                val vodInfoList: MutableList<VodInfo?> = ArrayList()
                for (vodInfo in allVodRecord) {
                    if (vodInfo.playNote != null && !vodInfo.playNote.isEmpty()) vodInfo.note =
                        vodInfo.playNote
                    vodInfoList.add(vodInfo)
                }
                vodInfoList
            }

            // 查询完成后更新UI
            if (vodInfoList.isNotEmpty() && vodInfoList[0] != null) {
                XPopup.Builder(context)
                    .hasShadowBg(false)
                    .isDestroyOnDismiss(true)
                    .isCenterHorizontal(true)
                    .isTouchThrough(true)
                    .offsetY(ScreenUtils.getAppScreenHeight() - 360)
                    .asCustom(LastViewedDialog(requireContext(), vodInfoList[0]))
                    .show()
                    .delayDismiss(4000)
            }
        }
    }
}
