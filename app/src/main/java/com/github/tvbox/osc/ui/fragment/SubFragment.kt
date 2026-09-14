package com.github.tvbox.osc.ui.fragment

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.blankj.utilcode.util.ClipboardUtils
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.ToastUtils
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.base.MainTabHost
import com.github.tvbox.osc.bean.Source
import com.github.tvbox.osc.bean.Subscription
import com.github.tvbox.osc.databinding.FragmentSubscriptionBinding
import com.github.tvbox.osc.ui.adapter.SubscriptionAdapter
import com.github.tvbox.osc.ui.dialog.ChooseSourceDialog
import com.github.tvbox.osc.ui.dialog.SubsTipDialog
import com.github.tvbox.osc.ui.dialog.SubsciptionDialog
import com.github.tvbox.osc.ui.dialog.SubsciptionDialog.OnSubsciptionListener
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.Utils
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lxj.xpopup.XPopup
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import com.lzy.okgo.model.Response
import com.orhanobut.hawk.Hawk
import java.util.Locale
import java.util.function.Consumer

/**
 * 订阅页 Fragment 版(单 Activity 架构)。
 *
 * 原 SubscriptionActivity 是独立 Activity,底部导航点击会 finish() 并回跳 MainActivity 对应页,
 * 导致切换时整屏重建。重构后作为 MainActivity ViewPager 内的 Fragment:底部导航永远静止,
 * tab 点击直接回调 MainActivity.switchToTab 切到对应页。文件选择改用 registerForActivityResult。
 */
class SubFragment : Fragment() {

    private var _binding: FragmentSubscriptionBinding? = null
    private val binding get() = _binding!!

    private var mBeforeUrl = Hawk.get(HawkConfig.API_URL, "")
    private var mSelectedUrl = ""
    private var mSubscriptions: MutableList<Subscription> = Hawk.get(HawkConfig.SUBSCRIPTIONS, ArrayList())
    private var mSubscriptionAdapter = SubscriptionAdapter()
    private val mSources: MutableList<Source> = ArrayList()
    private var pendingLocalChecked = false
    private lateinit var mTabHost: MainTabHost

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val flags = requireContext().contentResolver.persistedUriPermissions
            val takeFlags =
                requireActivity().intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            // 部分 provider 不支持持久授权,当前会话已有读权限即可
            if (takeFlags != 0) {
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        takeFlags
                    )
                } catch (_: SecurityException) {
                }
            }
        } catch (_: Exception) {
        }
        val url = uri.toString()
        if (hasDuplicateSubscription(url)) {
            ToastUtils.showLong("订阅地址已存在")
            return@registerForActivityResult
        }
        addSubscription(getDocumentName(uri), url, pendingLocalChecked)
        mSubscriptionAdapter.setNewData(mSubscriptions)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        mTabHost = requireActivity() as MainTabHost
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: android.os.Bundle?
    ): View {
        _binding = FragmentSubscriptionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rv.adapter = mSubscriptionAdapter
        mSubscriptions.forEach(Consumer { item: Subscription ->
            if (item.isChecked) {
                mSelectedUrl = item.url
            }
        })

        mSubscriptionAdapter.setNewData(mSubscriptions)
        binding.ivUseTip.setOnClickListener {
            XPopup.Builder(requireContext())
                .asCustom(SubsTipDialog(requireContext()))
                .show()
        }
        binding.ivCleanupSubscriptions.setOnClickListener {
            confirmSubscriptionCleanup()
        }

        binding.titleBar.rightView.setOnClickListener {
            XPopup.Builder(requireContext())
                .autoFocusEditText(false)
                .asCustom(
                    SubsciptionDialog(
                        requireContext(),
                        "订阅: " + (mSubscriptions.size + 1),
                        object : OnSubsciptionListener {
                            override fun onConfirm(
                                name: String,
                                url: String,
                                checked: Boolean
                            ) {
                                if (hasDuplicateSubscription(url)) {
                                    ToastUtils.showShort("订阅地址已存在")
                                    return
                                }
                                addSubscription(name, url, checked)
                            }

                            override fun chooseLocal(checked: Boolean) {
                                pickFile(checked)
                            }
                        })
                ).show()
        }

        mSubscriptionAdapter.setOnItemChildClickListener { _: BaseQuickAdapter<*, *>?, view: View, position: Int ->
            LogUtils.d("删除订阅")
            if (view.id == R.id.iv_del) {
                val subscription = mSubscriptions[position]
                if (subscription.isBuiltIn) {
                    ToastUtils.showShort("内置订阅不能删除")
                    return@setOnItemChildClickListener
                }
                if (subscription.isChecked || subscription.url == mSelectedUrl) {
                    ToastUtils.showShort("不能删除当前使用的订阅")
                    return@setOnItemChildClickListener
                }
                XPopup.Builder(requireContext())
                    .asConfirm("删除订阅", "确定删除订阅吗？") {
                        mSubscriptions.removeAt(position)
                        mSubscriptionAdapter.notifyDataSetChanged()
                    }.show()
            }
        }

        mSubscriptionAdapter.setOnItemClickListener { _: BaseQuickAdapter<*, *>?, _: View?, position: Int ->
            for (i in mSubscriptions.indices) {
                val subscription = mSubscriptions[i]
                if (i == position) {
                    subscription.setChecked(true)
                    mSelectedUrl = subscription.url
                } else {
                    subscription.setChecked(false)
                }
            }
            mSubscriptionAdapter.notifyDataSetChanged()
        }

        mSubscriptionAdapter.onItemLongClickListener =
            BaseQuickAdapter.OnItemLongClickListener { adapter: BaseQuickAdapter<*, *>?, view: View, position: Int ->
                val item = mSubscriptions[position]
                XPopup.Builder(requireContext())
                    .atView(view.findViewById(R.id.tv_name))
                    .hasShadowBg(false)
                    .asAttachList(
                        arrayOf(
                            if (item.isTop) "取消置顶" else "置顶",
                            "重命名",
                            "复制地址"
                        ), null
                    ) { index: Int, _: String? ->
                        when (index) {
                            0 -> {
                                item.isTop = !item.isTop
                                mSubscriptions[position] = item
                                mSubscriptionAdapter.setNewData(mSubscriptions)
                            }
                            1 -> {
                                XPopup.Builder(requireContext())
                                    .asInputConfirm(
                                        "更改为",
                                        "",
                                        item.name,
                                        "新的订阅名",
                                        { text ->
                                            if (!TextUtils.isEmpty(text)) {
                                                if (text.trim { it <= ' ' }.length > 8) {
                                                    ToastUtils.showShort("不要过长,不方便记忆")
                                                } else {
                                                    item.name = text.trim { it <= ' ' }
                                                    mSubscriptionAdapter.notifyItemChanged(position)
                                                }
                                            }
                                        },
                                        null,
                                        R.layout.dialog_input
                                    ).show()
                            }
                            2 -> {
                                ClipboardUtils.copyText(mSubscriptions[position].url)
                                ToastUtils.showLong("已复制")
                            }
                        }
                    }.show()
                true
            }
    }

    private fun pickFile(checked: Boolean) {
        pendingLocalChecked = checked
        pickFileLauncher.launch(arrayOf("application/json", "text/plain"))
    }

    private fun getDocumentName(uri: Uri): String {
        requireContext().contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) {
                    return cursor.getString(column)
                }
            }
        }
        return uri.lastPathSegment ?: "本地订阅"
    }

    private fun addSubscription(name: String, url: String, checked: Boolean) {
        if (url.startsWith("clan://") || url.startsWith("content://")) {
            addSub2List(name, url, checked)
            mSubscriptionAdapter.setNewData(mSubscriptions)
        } else if (url.startsWith("http")) {
            (requireActivity() as BaseActivity).showLoadingDialog()
            OkGo.get<String>(url)
                .tag("get_subscription")
                .execute(object : AbsCallback<String?>() {
                    override fun onSuccess(response: Response<String?>) {
                        (requireActivity() as BaseActivity).dismissLoadingDialog()
                        try {
                            val json = JsonParser.parseString(response.body()).asJsonObject
                            val urls = json["urls"]
                            val storeHouse = json["storeHouse"]
                            if (urls != null && urls.isJsonArray) {
                                if (checked) {
                                    ToastUtils.showLong("多条线路请主动选择")
                                }
                                val urlList = urls.asJsonArray
                                if (urlList != null && urlList.size() > 0 && urlList[0].isJsonObject
                                    && urlList[0].asJsonObject.has("url")
                                    && urlList[0].asJsonObject.has("name")
                                ) {
                                    for (i in 0 until urlList.size()) {
                                        val obj = urlList[i] as JsonObject
                                        val name = obj["name"].asString.trim { it <= ' ' }
                                            .replace("<|>|《|》|-".toRegex(), "")
                                        val url = obj["url"].asString.trim { it <= ' ' }
                                        addSub2List(name, url, false)
                                    }
                                }
                            } else if (storeHouse != null && storeHouse.isJsonArray) {
                                val storeHouseList = storeHouse.asJsonArray
                                if (storeHouseList != null && storeHouseList.size() > 0 && storeHouseList[0].isJsonObject
                                    && storeHouseList[0].asJsonObject.has("sourceName")
                                    && storeHouseList[0].asJsonObject.has("sourceUrl")
                                ) {
                                    mSources.clear()
                                    for (i in 0 until storeHouseList.size()) {
                                        val obj = storeHouseList[i] as JsonObject
                                        val name = obj["sourceName"].asString.trim { it <= ' ' }
                                            .replace("<|>|《|》|-".toRegex(), "")
                                        val url = obj["sourceUrl"].asString.trim { it <= ' ' }
                                        mSources.add(Source(name, url))
                                    }
                                    XPopup.Builder(requireContext())
                                        .asCustom(
                                            ChooseSourceDialog(
                                                requireContext(),
                                                mSources
                                            ) { position: Int, _: String? ->
                                                addSubscription(
                                                    mSources[position].sourceName,
                                                    mSources[position].sourceUrl,
                                                    checked
                                                )
                                            })
                                        .show()
                                }
                            } else {
                                addSub2List(name, url, checked)
                            }
                        } catch (th: Throwable) {
                            addSub2List(name, url, checked)
                        }
                        mSubscriptionAdapter.setNewData(mSubscriptions)
                    }

                    @Throws(Throwable::class)
                    override fun convertResponse(response: okhttp3.Response): String {
                        return response.body()!!.string()
                    }

                    override fun onError(response: Response<String?>) {
                        super.onError(response)
                        (requireActivity() as BaseActivity).dismissLoadingDialog()
                        ToastUtils.showLong("订阅失败,请检查地址或网络状态")
                    }
                })
        } else {
            ToastUtils.showShort("订阅格式不正确")
        }
    }

    private fun addSub2List(name: String, url: String, checkNewest: Boolean): Boolean {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isEmpty() || hasDuplicateSubscription(normalizedUrl)) {
            return false
        }
        if (checkNewest) {
            for (subscription in mSubscriptions) {
                if (subscription.isChecked) {
                    subscription.setChecked(false)
                }
            }
            mSelectedUrl = normalizedUrl
            mSubscriptions.add(Subscription(name, normalizedUrl).setChecked(true))
        } else {
            mSubscriptions.add(Subscription(name, normalizedUrl).setChecked(false))
        }
        return true
    }

    private fun confirmSubscriptionCleanup() {
        XPopup.Builder(requireContext())
            .asConfirm(
                "清理订阅",
                "将删除重复、格式错误和返回空内容的导入订阅。内置订阅、当前使用中的订阅及网络请求失败的订阅会保留。"
            ) {
                cleanupSubscriptions()
            }
            .show()
    }

    private fun cleanupSubscriptions() {
        var removed = removeDuplicateSubscriptions()
        val remoteSubscriptions = ArrayList<Subscription>()
        for (subscription in mSubscriptions.toList()) {
            if (isProtectedSubscription(subscription) || isLocalSubscription(subscription.url)) {
                continue
            }
            if (!isRemoteSubscription(subscription.url)) {
                mSubscriptions.remove(subscription)
                removed++
            } else {
                remoteSubscriptions.add(subscription)
            }
        }
        if (remoteSubscriptions.isEmpty()) {
            finishSubscriptionCleanup(removed, 0)
            return
        }
        ToastUtils.showShort("正在检查 ${remoteSubscriptions.size} 个订阅")
        (requireActivity() as BaseActivity).showLoadingDialog()
        checkRemoteSubscriptions(remoteSubscriptions, 0, removed, 0)
    }

    private fun checkRemoteSubscriptions(
        subscriptions: List<Subscription>,
        index: Int,
        removed: Int,
        unavailable: Int
    ) {
        if (index >= subscriptions.size) {
            (requireActivity() as BaseActivity).dismissLoadingDialog()
            finishSubscriptionCleanup(removed, unavailable)
            return
        }
        val subscription = subscriptions[index]
        OkGo.get<String>(subscription.url)
            .tag("cleanup_subscriptions")
            .execute(object : AbsCallback<String?>() {
                override fun convertResponse(response: okhttp3.Response): String {
                    return response.body()!!.string()
                }

                override fun onSuccess(response: Response<String?>) {
                    val isEmpty = response.body().isNullOrBlank()
                    val removedNow = if (isEmpty && canRemoveSubscription(subscription)) {
                        mSubscriptions.remove(subscription)
                        1
                    } else {
                        0
                    }
                    checkRemoteSubscriptions(subscriptions, index + 1, removed + removedNow, unavailable)
                }

                override fun onError(response: Response<String?>) {
                    super.onError(response)
                    checkRemoteSubscriptions(subscriptions, index + 1, removed, unavailable + 1)
                }
            })
    }

    private fun finishSubscriptionCleanup(removed: Int, unavailable: Int) {
        mSubscriptionAdapter.setNewData(mSubscriptions)
        Hawk.put<List<Subscription>?>(HawkConfig.SUBSCRIPTIONS, mSubscriptions)
        val message = if (unavailable > 0) {
            "已清理 $removed 项，$unavailable 项网络异常已保留"
        } else {
            "已清理 $removed 项订阅"
        }
        ToastUtils.showLong(message)
    }

    private fun removeDuplicateSubscriptions(): Int {
        val retained = LinkedHashMap<String, Subscription>()
        var removed = 0
        for (subscription in mSubscriptions.toList()) {
            val key = subscriptionKey(subscription.url)
            if (key.isEmpty()) {
                continue
            }
            val existing = retained[key]
            if (existing == null) {
                retained[key] = subscription
            } else if (!isProtectedSubscription(subscription)) {
                mSubscriptions.remove(subscription)
                removed++
            } else if (!isProtectedSubscription(existing)) {
                mSubscriptions.remove(existing)
                retained[key] = subscription
                removed++
            }
        }
        return removed
    }

    private fun hasDuplicateSubscription(url: String): Boolean {
        val key = subscriptionKey(url)
        return key.isNotEmpty() && mSubscriptions.any { subscriptionKey(it.url) == key }
    }

    private fun isProtectedSubscription(subscription: Subscription): Boolean {
        val selectedKey = subscriptionKey(mSelectedUrl)
        return subscription.isBuiltIn || subscription.isChecked ||
            (selectedKey.isNotEmpty() && subscriptionKey(subscription.url) == selectedKey)
    }

    private fun canRemoveSubscription(subscription: Subscription): Boolean {
        return mSubscriptions.contains(subscription) && !isProtectedSubscription(subscription)
    }

    private fun isLocalSubscription(url: String): Boolean {
        val value = url.trim()
        return value.startsWith("clan://") || value.startsWith("content://")
    }

    private fun isRemoteSubscription(url: String): Boolean {
        val uri = Uri.parse(url.trim())
        return (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrEmpty()
    }

    private fun subscriptionKey(url: String): String {
        val value = url.trim()
        val uri = Uri.parse(value)
        val scheme = uri.scheme?.toLowerCase(Locale.ROOT) ?: return value
        val host = uri.host?.toLowerCase(Locale.ROOT) ?: return value
        if (scheme != "http" && scheme != "https") {
            return value
        }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.encodedPath?.trimEnd('/') ?: ""
        val query = uri.encodedQuery?.let { "?$it" } ?: ""
        return "$scheme://$host$port$path$query"
    }

    override fun onPause() {
        super.onPause()
        Hawk.put(HawkConfig.API_URL, mSelectedUrl)
        Hawk.put<List<Subscription>?>(HawkConfig.SUBSCRIPTIONS, mSubscriptions)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        OkGo.getInstance().cancelTag("get_subscription")
        OkGo.getInstance().cancelTag("cleanup_subscriptions")
        _binding = null
    }
}
