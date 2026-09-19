package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.databinding.ActivityCollectBinding
import com.github.tvbox.osc.ui.adapter.HistoryListAdapter
import com.github.tvbox.osc.ui.adapter.HistoryRow
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.Utils
import com.lxj.xpopup.XPopup
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CollectActivity : BaseVbActivity<ActivityCollectBinding>() {

    private var collectAdapter = HistoryListAdapter(ArrayList())
    /** 收藏列表(已联动观看记录)的原始数据,长按删除后用于重建分组 */
    private var sourceList: List<com.github.tvbox.osc.bean.VodInfo> = ArrayList()

    override fun init() {
        initView()
        initData()
    }

    private fun initView() {
        setLoadSir(mBinding.mGridView)

        mBinding.mGridView.setHasFixedSize(true)
        mBinding.mGridView.setLayoutManager(V7LinearLayoutManager(this, RecyclerView.VERTICAL, false))
        mBinding.mGridView.setAdapter(collectAdapter)
        mBinding.titleBar.rightView.setOnClickListener {
            XPopup.Builder(this)
                .isDarkTheme(Utils.isDarkTheme())
                .asConfirm("提示", "确定清空?") {
                    showLoadingDialog()
                    lifecycleScope.launch(Dispatchers.IO){
                        RoomDataManger.deleteVodCollectAll()
                        withContext(Dispatchers.Main){
                            dismissLoadingDialog()
                            sourceList = ArrayList()
                            rebuildRows()
                        }
                    }
                }.show()
        }
        collectAdapter.onItemLongClickListener =
            BaseQuickAdapter.OnItemLongClickListener { adapter: BaseQuickAdapter<*, *>?, view: View?, position: Int ->
                val row = collectAdapter.data[position]
                val vodInfo = row.vodInfo
                if (vodInfo != null) {
                    RoomDataManger.deleteVodCollect(vodInfo.sourceKey, vodInfo)
                    sourceList = sourceList.filterNot { it === vodInfo }
                    rebuildRows()
                }
                true
            }
        collectAdapter.onItemClickListener =
            BaseQuickAdapter.OnItemClickListener { adapter, view, position ->
                FastClickCheckUtil.check(view)
                val row = collectAdapter.data[position]
                val vodInfo = row.vodInfo ?: return@OnItemClickListener
                if (ApiConfig.get().getSource(vodInfo.sourceKey) != null) {
                    val bundle = Bundle()
                    bundle.putString("id", vodInfo.id)
                    bundle.putString("sourceKey", vodInfo.sourceKey)
                    bundle.putString("vodName", vodInfo.name)
                    jumpActivity(DetailActivity::class.java, bundle)
                } else {
//                            Intent newIntent = new Intent(mContext, SearchActivity.class);
                    val newIntent = Intent(mContext, FastSearchActivity::class.java)
                    newIntent.putExtra("title", vodInfo.name)
                    newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(newIntent)
                }
            }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    private fun rebuildRows() {
        // 收藏页不分组,按收藏时间(newest 在前)平铺
        val rows = ArrayList<HistoryRow>(sourceList.size)
        val sorted = sourceList.sortedByDescending { it.watchTime }
        for (info in sorted) rows.add(HistoryRow.item(info))
        collectAdapter.setNewData(rows)
        if (rows.isNotEmpty()) {
            showSuccess()
            mBinding.topTip.visibility = View.VISIBLE
        } else {
            showEmpty()
            mBinding.topTip.visibility = View.GONE
        }
    }

    private fun initData() {
        lifecycleScope.launch(Dispatchers.IO) {
            // 每条收藏联动观看记录:拿到"看到第X集"(playNote)与"更新至X集"(note);没看过的为最小化信息
            val displayList = RoomDataManger.getCollectDisplayList()
            withContext(Dispatchers.Main) {
                sourceList = displayList
                rebuildRows()
            }
        }
    }
}
