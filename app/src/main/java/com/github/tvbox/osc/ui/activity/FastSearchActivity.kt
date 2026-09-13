package com.github.tvbox.osc.ui.activity

import android.content.ContentResolver
import android.content.DialogInterface
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.util.TypedValue
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.angcyo.tablayout.DslTabLayout
import com.angcyo.tablayout.DslTabLayoutConfig
import com.blankj.utilcode.util.KeyboardUtils
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.ToastUtils
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.catvod.crawler.JsLoader
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.databinding.ActivityFastSearchBinding
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.event.ServerEvent
import com.github.tvbox.osc.ui.adapter.FastSearchAdapter
import com.github.tvbox.osc.ui.adapter.SearchWordAdapter
import com.github.tvbox.osc.ui.dialog.SearchCheckboxDialog
import com.github.tvbox.osc.ui.dialog.SearchSuggestionsDialog
import com.github.tvbox.osc.ui.widget.LinearSpacingItemDecoration
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.SearchHelper
import com.github.tvbox.osc.viewmodel.SourceViewModel
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.interfaces.OnSelectListener
import com.lxj.xpopup.interfaces.SimpleCallback
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import com.orhanobut.hawk.Hawk
import com.zhy.view.flowlayout.FlowLayout
import com.zhy.view.flowlayout.TagAdapter
import okhttp3.Response
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class FastSearchActivity : BaseVbActivity<ActivityFastSearchBinding>(), TextWatcher {

    companion object {
        private var mCheckSources: HashMap<String, String>? = null
        fun setCheckedSourcesForSearch(checkedSources: HashMap<String, String>?) {
            mCheckSources = checkedSources
        }
    }

    private lateinit var sourceViewModel : SourceViewModel
    private var searchAdapter = FastSearchAdapter()
    private var searchAdapterFilter = FastSearchAdapter()
    private var searchTitle: String? = ""
    private var spNames = HashMap<String, String>()
    private var isFilterMode = false
    private var searchFilterKey: String? = "" // 过滤的key
    private var resultVods = HashMap<String, MutableList<Movie.Video>>()
    private var pauseRunnable: MutableList<Runnable>? = null
    private var mSearchSuggestionsDialog: SearchSuggestionsDialog? = null
    override fun useImmersionBar(): Boolean = false

    /**
     * ★ 调试日志开关:打开后会输出 FastSearchActivity 的方向变化全链路。
     * Tag = "FastSearchDir",便于 adb logcat | grep FastSearchDir 单独过滤。
     * 保留为 true 直到问题修复完毕,可后续改成 false 或删除。
     */
    private val DIR_TAG = "FastSearchDir"

    /**
     * ContentObserver:监听系统级"自动旋转"开关(ACCELEROMETER_ROTATION)的变化。
     * 用户在系统设置里切换"自动旋转"后,这里会接到 onChange 回调,然后再调一次
     * applySystemRotationLock(),让 Activity 的方向立刻跟随新的系统策略走。
     *
     * 不要在主线程 register 时使用 notifyForDescendants=true 否则可能死锁。
     */
    private val rotationObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            Log.d(DIR_TAG, "rotationObserver.onChange uri=$uri selfChange=$selfChange")
            applySystemRotationLock()
        }
    }

    /**
     * ★ 核心策略:根据系统级"自动旋转"开关状态,动态调整 Activity 的方向。
     *
     * 用户需求是:系统锁竖屏时 → 保持竖屏(不被横放手机的物理方向短暂触发);系统解锁 → 跟着物理方向旋转。
     *
     * Settings.System.ACCELEROMETER_ROTATION:
     *   0 = 系统级旋转关闭(用户在系统设置里锁了竖屏/横屏)
     *   1 = 系统级旋转开启(根据传感器物理方向自动旋转)
     *
     * 不能简单地信任 manifest 配置——
     *   - `portrait` 太绝对,系统解锁也锁竖屏(用户不希望这样)
     *   - `sensor` 在某些 ROM 上会被物理方向短暂触发为横屏(用户也不希望)
     * 所以运行时动态切换:
     *   - 系统锁(ACCELEROMETER_ROTATION=0)→ requestedOrientation=PORTRAIT(绝对竖屏)
     *   - 系统开(ACCELEROMETER_ROTATION=1)→ requestedOrientation=SENSOR(跟随物理方向)
     */
    private fun applySystemRotationLock() {
        val sysRotLocked = try {
            Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1)
        } catch (e: Settings.SettingNotFoundException) { -1 }
        try {
            requestedOrientation = when (sysRotLocked) {
                0 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        } catch (e: Throwable) {
            Log.w(DIR_TAG, "applySystemRotationLock setRequestedOrientation failed", e)
        }
        Log.d(DIR_TAG, "applySystemRotationLock: accelerometerRotation=$sysRotLocked"
                + " requestedOrientation=" + requestedOrientation)
    }

    override fun init() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // ★ 注册监听:系统级 ACCELEROMETER_ROTATION 变化时,自动重新计算方向策略
        try {
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
                false,
                rotationObserver)
        } catch (e: Throwable) {
            Log.w(DIR_TAG, "registerContentObserver failed", e)
        }

        // ★ 入口第一次:按当前系统状态决定方向
        applySystemRotationLock()

        // ★ 调试:打印当前真实方向状态(给用户定位"系统锁竖屏但还是跟着旋转"问题用)
        val cfg = resources.configuration
        val sysRotLocked = try {
            Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1)
        } catch (e: Settings.SettingNotFoundException) { -1 }
        val rotMgr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                window.windowManager?.defaultDisplay?.rotation ?: -1
            } catch (e: Throwable) { -1 }
        } else -1
        // 用 PackageManager 读 manifest 里配的方向(等价 activityInfo.screenOrientation,
        // 但 Kotlin 调用 activityInfo 这个 protected 属性有时编译报错——用 PackageManager 兼容)
        val manifestOrient = try {
            packageManager.getActivityInfo(componentName, 0).screenOrientation
        } catch (e: Throwable) { -1 }
        Log.d(DIR_TAG, "init: orientation=" + cfg.orientation
                + " screenW=" + cfg.screenWidthDp + " screenH=" + cfg.screenHeightDp
                + " requestedOrientation=" + requestedOrientation
                + " accelerometerRotation=" + sysRotLocked
                + " display.rotation=" + rotMgr
                + " manifestOrient=" + manifestOrient)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            window.attributes = lp
        }
        sourceViewModel = ViewModelProvider(this).get(SourceViewModel::class.java)
        initView()
        hideSystemBars()

        // 视觉适配:搜索页横屏时系统/IME 无法真正铺满挖孔长边,
        // 改为让根布局统一按挖孔安全区内缩,左右边距对称,视觉上更平衡。
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.root) { v, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(cutout.left, maxOf(statusBar, cutout.top), cutout.right, maxOf(navBar, ime))
            insets
        }
        initData()
        //历史搜索
        initHistorySearch()
        // 热门搜索
        hotWords
    }

    override fun onResume() {
        super.onResume()
        // ★ 回到前台时重新应用方向策略:用户可能在其他 Activity 期间改了系统级"自动旋转"开关,
        // 或者 Activity 被系统重建,这里再调一次让方向跟随当前系统状态走。
        applySystemRotationLock()

        // ★ 调试:每次回到前台都打一次方向,因为 Activity 可能在后台被系统 recreate 时
        // 没有重新 init(),但方向变化会通过 onConfigurationChanged 进入(manifest 配了
        // configChanges),所以 onResume 也能兜底记录一次真实状态。
        val cfg = resources.configuration
        Log.d(DIR_TAG, "onResume: orientation=" + cfg.orientation
                + " width(dp)=" + cfg.screenWidthDp + "x" + cfg.screenHeightDp
                + " requestedOrientation=" + requestedOrientation)

        if (pauseRunnable != null && pauseRunnable!!.size > 0) {
            searchExecutorService = Executors.newFixedThreadPool(10)
            allRunCount.set(pauseRunnable!!.size)
            for (runnable: Runnable? in pauseRunnable!!) {
                searchExecutorService!!.execute(runnable)
            }
            pauseRunnable!!.clear()
            pauseRunnable = null
        }
    }

    private fun initView() {
        mBinding.etSearch.setOnEditorActionListener { _: TextView?, actionId: Int, _: KeyEvent? ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(mBinding.etSearch.text.toString())
                return@setOnEditorActionListener true
            }
            false
        }
        mBinding.etSearch.addTextChangedListener(this)
        mBinding.ivFilter.setOnClickListener { filterSearchSource() }
        mBinding.ivBack.setOnClickListener { finish() }
        mBinding.ivSearch.setOnClickListener {
            search(mBinding.etSearch.text.toString())
        }
        mBinding.tabLayout.configTabLayoutConfig {
            onSelectViewChange  = { _, selectViewList, _, _ ->
                    val tvItem: TextView = selectViewList.first() as TextView
                    filterResult(tvItem.text.toString())
                }
        }
        mBinding.mGridView.setHasFixedSize(true)
        mBinding.mGridView.setLayoutManager(LinearLayoutManager(this))
        mBinding.mGridView.adapter = searchAdapter
        searchAdapter.setOnItemClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            val video = searchAdapter.data[position]
            try {
                if (searchExecutorService != null) {
                    pauseRunnable = searchExecutorService!!.shutdownNow()
                    searchExecutorService = null
                    JsLoader.stopAll()
                }
            } catch (th: Throwable) {
                th.printStackTrace()
            }
            val bundle = Bundle()
            bundle.putString("id", video.id)
            bundle.putString("sourceKey", video.sourceKey)
            jumpActivity(DetailActivity::class.java, bundle)
        }
        mBinding.mGridViewFilter.setLayoutManager(LinearLayoutManager(this))

        mBinding.mGridViewFilter.adapter = searchAdapterFilter
        searchAdapterFilter.setOnItemClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            val video = searchAdapterFilter.data[position]
            if (video != null) {
                try {
                    if (searchExecutorService != null) {
                        pauseRunnable = searchExecutorService!!.shutdownNow()
                        searchExecutorService = null
                        JsLoader.stopAll()
                    }
                } catch (th: Throwable) {
                    th.printStackTrace()
                }
                val bundle = Bundle()
                bundle.putString("id", video.id)
                bundle.putString("sourceKey", video.sourceKey)
                jumpActivity(DetailActivity::class.java, bundle)
            }
        }

        // 空状态注册到整个搜索结果容器(llSearchResult),这样"暂无数据"在全屏居中;
        // 而非只居中在右侧 llLayout 区域(120dp 竖排 tab 占了左侧 1/3,视觉不平衡)。
        setLoadSir(mBinding.llSearchResult)
    }

    /**
     * 指定搜索源(过滤)
     */
    private fun filterSearchSource() {
        val allSourceBean = ApiConfig.get().sourceBeanList
        if (allSourceBean.isNotEmpty()) {
            val searchAbleSource: MutableList<SourceBean> = ArrayList()
            for (sourceBean: SourceBean in allSourceBean) {
                if (sourceBean.isSearchable) {
                    searchAbleSource.add(sourceBean)
                }
            }
            val mSearchCheckboxDialog = SearchCheckboxDialog(this@FastSearchActivity, searchAbleSource, mCheckSources)
            mSearchCheckboxDialog.show()
        }

    }

    private fun filterResult(spName: String) {
        if (spName === "全部显示") {
            mBinding.mGridView.visibility = View.VISIBLE
            mBinding.mGridViewFilter.visibility = View.GONE
            return
        }
        mBinding.mGridView.visibility = View.GONE
        mBinding.mGridViewFilter.visibility = View.VISIBLE
        val key = spNames[spName]
        if (key.isNullOrEmpty()) return
        if (searchFilterKey === key) return
        searchFilterKey = key
        val list: List<Movie.Video> = (resultVods[key])!!
        searchAdapterFilter.setNewData(list)
    }

    private fun initData() {
        mCheckSources = SearchHelper.getSourcesForSearch()
        if (intent != null && intent.hasExtra("title")) {
            val title = intent.getStringExtra("title")
            if (!TextUtils.isEmpty(title)) {
                showLoading()
                search(title)
            }
        }
    }

    private fun hideHotAndHistorySearch(isHide: Boolean) {
        if (isHide) {
            mBinding.llSearchSuggest.visibility = View.GONE
            mBinding.llSearchResult.visibility = View.VISIBLE
        } else {
            mBinding.llSearchSuggest.visibility = View.VISIBLE
            mBinding.llSearchResult.visibility = View.GONE
        }
    }

    private fun initHistorySearch() {
        val mSearchHistory: List<String> = Hawk.get(HawkConfig.HISTORY_SEARCH, ArrayList())
        mBinding.llHistory.visibility = if (mSearchHistory.isNotEmpty()) View.VISIBLE else View.GONE
        mBinding.flHistory.adapter = object : TagAdapter<String?>(mSearchHistory) {
            override fun getView(parent: FlowLayout, position: Int, s: String?): View {
                val tv: TextView = LayoutInflater.from(this@FastSearchActivity).inflate(
                    R.layout.item_search_word_hot,
                    mBinding.flHistory, false
                ) as TextView
                tv.text = s
                return tv
            }
        }
        mBinding.flHistory.setOnTagClickListener { _: View?, position: Int, _: FlowLayout? ->
            search(mSearchHistory[position])
            true
        }
        findViewById<View>(R.id.iv_clear_history).setOnClickListener { view: View ->
            Hawk.put(HawkConfig.HISTORY_SEARCH, ArrayList<Any>())
            //FlowLayout及其adapter貌似没有清空数据的api,简单粗暴重置
            view.postDelayed({ initHistorySearch() }, 300)
        }
    }

    /**
     * 热门搜索
     */
    private val hotWords: Unit
        get() {
            // 加载热词
            OkGo.get<String>("https://node.video.qq.com/x/api/hot_search")
                .params("channdlId", "0")
                .params("_", System.currentTimeMillis())
                .execute(object : AbsCallback<String?>() {
                    override fun onSuccess(response: com.lzy.okgo.model.Response<String?>) {
                        try {
                            val hots = ArrayList<String>()
                            val itemList =
                                JsonParser.parseString(response.body()).asJsonObject["data"].asJsonObject["mapResult"].asJsonObject["0"].asJsonObject["listInfo"].asJsonArray
                            //                            JsonArray itemList = JsonParser.parseString(response.body()).getAsJsonObject().get("data").getAsJsonArray();
                            for (ele: JsonElement in itemList) {
                                val obj = ele as JsonObject
                                hots.add(obj["title"].asString.trim { it <= ' ' }
                                    .replace("<|>|《|》|-".toRegex(), "").split(" ".toRegex())
                                    .dropLastWhile { it.isEmpty() }
                                    .toTypedArray()[0])
                            }
                            mBinding.flHot.adapter = object : TagAdapter<String?>(hots as List<String?>?) {
                                override fun getView(
                                    parent: FlowLayout,
                                    position: Int,
                                    s: String?
                                ): View {
                                    val tv: TextView =
                                        LayoutInflater.from(this@FastSearchActivity).inflate(
                                            R.layout.item_search_word_hot,
                                            mBinding.flHot, false
                                        ) as TextView
                                    tv.text = s
                                    return tv
                                }
                            }
                            mBinding.flHot.setOnTagClickListener { _: View?, position: Int, _: FlowLayout? ->
                                search(hots.get(position))
                                true
                            }
                        } catch (th: Throwable) {
                            th.printStackTrace()
                        }
                    }

                    @Throws(Throwable::class)
                    override fun convertResponse(response: Response): String {
                        return response.body()!!.string()
                    }
                })
        }

    /**
     * 联想搜索
     */
    private fun getSuggest(text: String) {
        // 加载热词
        OkGo.get<String>("https://suggest.video.iqiyi.com/?if=mobile&key=$text")
            .execute(object : AbsCallback<String?>() {
                override fun onSuccess(response: com.lzy.okgo.model.Response<String?>) {
                    val titles: MutableList<String> = ArrayList()
                    try {
                        val json = JsonParser.parseString(response.body()).asJsonObject
                        val datas = json["data"].asJsonArray
                        for (data: JsonElement in datas) {
                            val item = data as JsonObject
                            titles.add(item["name"].asString.trim { it <= ' ' })
                        }
                    } catch (th: Throwable) {
                        LogUtils.d(th.toString())
                    }
                    if (titles.isNotEmpty()) {
                        showSuggestDialog(titles)
                    }
                }

                @Throws(Throwable::class)
                override fun convertResponse(response: Response): String {
                    return response.body()!!.string()
                }
            })
    }

    private fun showSuggestDialog(list: List<String>) {
        if (mSearchSuggestionsDialog == null) {
            mSearchSuggestionsDialog =
                SearchSuggestionsDialog(this@FastSearchActivity, list
                ) { _, text ->
                    LogUtils.d("搜索:$text")
                    mSearchSuggestionsDialog!!.dismissWith { search(text) }
                }
            XPopup.Builder(this@FastSearchActivity)
                .atView(mBinding.etSearch)
                .notDismissWhenTouchInView(mBinding.etSearch)
                .isViewMode(true) //开启View实现
                .isRequestFocus(false) //不强制焦点
                .setPopupCallback(object : SimpleCallback() {
                    override fun onDismiss(popupView: BasePopupView) { // 弹窗关闭了就置空对象,下次重新new
                        super.onDismiss(popupView)
                        mSearchSuggestionsDialog = null
                    }
                })
                .asCustom(mSearchSuggestionsDialog)
                .show()
        } else { // 不为空说明弹窗为打开状态(关闭就置空了).直接刷新数据
            mSearchSuggestionsDialog!!.updateSuggestions(list)
        }
    }

    private fun saveSearchHistory(searchWord: String?) {
        if (!searchWord.isNullOrEmpty()) {
            val history = Hawk.get(HawkConfig.HISTORY_SEARCH, ArrayList<String?>())
            if (!history.contains(searchWord)) {
                history.add(0, searchWord)
            } else {
                history.remove(searchWord)
                history.add(0, searchWord)
            }
            if (history.size > 30) {
                history.removeAt(30)
            }
            Hawk.put(HawkConfig.HISTORY_SEARCH, history)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun server(event: ServerEvent) {
        if (event.type == ServerEvent.SERVER_SEARCH) {
            val title = event.obj as String
            showLoading()
            search(title)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    override fun refresh(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_SEARCH_RESULT) {
            try {
                searchData(if (event.obj == null) null else event.obj as AbsXml)
            } catch (e: Exception) {
                searchData(null)
            }
        }
    }

    private fun search(title: String?) {
        if (title.isNullOrEmpty()) {
            ToastUtils.showShort("请输入搜索内容")
            return
        }

        //先移除监听,避免重新设置要搜索的文字触发搜索建议并弹窗
        mBinding.etSearch.removeTextChangedListener(this)
        mBinding.etSearch.setText(title)
        mBinding.etSearch.setSelection(title.length)
        mBinding.etSearch.addTextChangedListener(this)
        if (mSearchSuggestionsDialog != null && mSearchSuggestionsDialog!!.isShow) {
            mSearchSuggestionsDialog!!.dismiss()
        }
        if (!Hawk.get(HawkConfig.PRIVATE_BROWSING, false)) { //无痕浏览不存搜索历史
            saveSearchHistory(title)
        }
        hideHotAndHistorySearch(true)
        KeyboardUtils.hideSoftInput(this)
        cancel()
        showLoading()
        searchTitle = title
        //fenci();
        mBinding.mGridView.visibility = View.INVISIBLE
        mBinding.mGridViewFilter.visibility = View.GONE
        searchAdapter!!.setNewData(ArrayList())
        searchAdapterFilter!!.setNewData(ArrayList())
        resultVods.clear()
        searchFilterKey = ""
        isFilterMode = false
        spNames.clear()
        mBinding.tabLayout.removeAllViews()
        searchResult()
    }

    private var searchExecutorService: ExecutorService? = null
    private val allRunCount = AtomicInteger(0)
    private fun getSiteTextView(text: String): TextView {
        val textView = TextView(this)
        textView.text = text
        textView.gravity = Gravity.CENTER
        val params = DslTabLayout.LayoutParams(-2, -2)
        params.topMargin = 20
        params.bottomMargin = 20
        textView.setPadding(20, 10, 20, 10)
        textView.layoutParams = params
        return textView
    }

    private fun searchResult() {
        try {
            if (searchExecutorService != null) {
                searchExecutorService!!.shutdownNow()
                searchExecutorService = null
                JsLoader.stopAll()
            }
        } catch (th: Throwable) {
            th.printStackTrace()
        } finally {
            searchAdapter.setNewData(ArrayList())
            searchAdapterFilter.setNewData(ArrayList())
            allRunCount.set(0)
        }
        searchExecutorService = Executors.newFixedThreadPool(10)
        val searchRequestList: MutableList<SourceBean> = ArrayList()
        searchRequestList.addAll(ApiConfig.get().sourceBeanList)
        val home = ApiConfig.get().homeSourceBean
        searchRequestList.remove(home)
        searchRequestList.add(0, home)
        val siteKey = ArrayList<String>()
        mBinding.tabLayout.addView(getSiteTextView("全部显示"))
        mBinding.tabLayout.setCurrentItem(0, true, false)
        for (bean: SourceBean in searchRequestList) {
            if (!bean.isSearchable) {
                continue
            }
            if (mCheckSources != null && !mCheckSources!!.containsKey(bean.key)) {
                continue
            }
            siteKey.add(bean.key)
            spNames[bean.name] = bean.key
            allRunCount.incrementAndGet()
        }
        for (key: String in siteKey) {
            searchExecutorService!!.execute {
                try {
                    sourceViewModel.getSearch(key, searchTitle)
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * 添加到最后面并返回最后一个key
     * @param key
     * @return
     */
    private fun addWordAdapterIfNeed(key: String): String {
        try {
            var name = ""
            for (n: String in spNames.keys) {
                if ((spNames[n] == key)) {
                    name = n
                }
            }
            if ((name == "")) return key
            for (i in 0 until mBinding.tabLayout.childCount) {
                val item = mBinding.tabLayout.getChildAt(i) as TextView
                if ((name == item.text.toString())) {
                    return key
                }
            }
            mBinding.tabLayout.addView(getSiteTextView(name))
            return key
        } catch (e: Exception) {
            return key
        }
    }

    private fun matchSearchResult(name: String, searchTitle: String?): Boolean {
        var searchTitle = searchTitle
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(searchTitle)) return false
        searchTitle = searchTitle!!.trim { it <= ' ' }
        val arr = searchTitle.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        var matchNum = 0
        for (one: String in arr) {
            if (name.contains(one)) matchNum++
        }
        return if (matchNum == arr.size) true else false
    }

    private fun searchData(absXml: AbsXml?) {
        var lastSourceKey = ""
        if ((absXml != null) && (absXml.movie != null) && (absXml.movie.videoList != null) && (absXml.movie.videoList.size > 0)) {
            val data: MutableList<Movie.Video> = ArrayList()
            for (video: Movie.Video in absXml.movie.videoList) {
                if (!matchSearchResult(video.name, searchTitle)) continue
                data.add(video)
                if (!resultVods.containsKey(video.sourceKey)) {
                    resultVods[video.sourceKey] = ArrayList()
                }
                resultVods[video.sourceKey]!!.add(video)
                if (video.sourceKey !== lastSourceKey) { // 添加到最后面并记录最后一个key用于下次判断
                    lastSourceKey = addWordAdapterIfNeed(video.sourceKey)
                }
            }
            if (searchAdapter.data.size > 0) {
                searchAdapter.addData(data)
            } else {
                showSuccess()
                if (!isFilterMode) mBinding.mGridView.visibility = View.VISIBLE
                searchAdapter.setNewData(data)
            }
        }
        val count = allRunCount.decrementAndGet()
        if (count <= 0) {
            if (searchAdapter.data.size <= 0) {
                showEmpty()
            }
            cancel()
        }
    }

    private fun cancel() {
        OkGo.getInstance().cancelTag("search")
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.statusBars())
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
        try {
            if (searchExecutorService != null) {
                searchExecutorService!!.shutdownNow()
                searchExecutorService = null
                JsLoader.load()
            }
        } catch (th: Throwable) {
            th.printStackTrace()
        }
        // ★ 注销系统旋转设置监听,避免内存泄漏
        try {
            contentResolver.unregisterContentObserver(rotationObserver)
        } catch (th: Throwable) {
            th.printStackTrace()
        }
    }

    /**
     * ★ 调试:manifest 已经配了 configChanges="orientation|screenSize|keyboardHidden",
     * 所以旋转时不会重建 Activity,而是进这个回调。
     * 把每次方向变化的输入/输出都记录下来,帮用户定位"系统锁竖屏但还是跟着旋转"的真正根因。
     *
     * 之前这里有一段强制拉回 PORTRAIT 的兜底,现在删掉——方向策略改由
     * applySystemRotationLock() 根据系统级 ACCELEROMETER_ROTATION 决定:
     *   - 系统锁竖屏 → PORTRAIT
     *   - 系统解锁 → SENSOR(跟随物理方向)
     * 这里的 onConfigurationChanged 只负责打印日志,不再做扳回动作。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val sysRotLocked = try {
            Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1)
        } catch (e: Settings.SettingNotFoundException) { -1 }
        Log.d(DIR_TAG, "onConfigurationChanged: orientation=" + newConfig.orientation
                + " newSize=" + newConfig.screenWidthDp + "x" + newConfig.screenHeightDp
                + " accelerometerRotation=" + sysRotLocked
                + " requestedOrientation=" + requestedOrientation)
    }

    override fun onPause() {
        super.onPause()
        Log.d(DIR_TAG, "onPause: orientation=" + resources.configuration.orientation
                + " requestedOrientation=" + requestedOrientation)
    }

    override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
    override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
    override fun afterTextChanged(editable: Editable) {
        val text = editable.toString()
        if (TextUtils.isEmpty(text)) {
            mSearchSuggestionsDialog?.dismiss()
            hideHotAndHistorySearch(false)
        } else {
            getSuggest(text)
        }
    }
}