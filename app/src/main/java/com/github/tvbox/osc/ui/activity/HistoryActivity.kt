package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.databinding.ActivityHistoryBinding
import com.github.tvbox.osc.ui.adapter.HistoryListAdapter
import com.github.tvbox.osc.ui.adapter.HistoryRow
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.Utils
import com.lxj.xpopup.XPopup
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryActivity : BaseVbActivity<ActivityHistoryBinding>() {
    private var historyAdapter: HistoryListAdapter? = null
    /** 最近的观看记录原始数据(用于长按删除后重建分组列表) */
    private var sourceList: List<VodInfo> = ArrayList()

    override fun init() {
        initView()
        initData()
    }

    private fun initView() {
        setLoadSir(mBinding.mGridView)

        mBinding.mGridView.setHasFixedSize(true)
        mBinding.mGridView.setLayoutManager(V7LinearLayoutManager(this, RecyclerView.VERTICAL, false))
        historyAdapter = HistoryListAdapter(ArrayList())
        mBinding.mGridView.setAdapter(historyAdapter)

        historyAdapter!!.onItemLongClickListener =
            BaseQuickAdapter.OnItemLongClickListener { _: BaseQuickAdapter<*, *>?, view: View?, position: Int ->
                FastClickCheckUtil.check(view)
                val row = historyAdapter!!.data[position]
                if (row.vodInfo != null) {
                    RoomDataManger.deleteVodRecord(row.vodInfo.sourceKey, row.vodInfo)
                    sourceList = sourceList.filterNot { it === row.vodInfo }
                    rebuildRows()
                }
                true
            }

        mBinding.titleBar.rightView.setOnClickListener { view: View? ->
            XPopup.Builder(this)
                .isDarkTheme(Utils.isDarkTheme())
                .asConfirm("提示", "确定清空?") {

                    showLoadingDialog()
                    lifecycleScope.launch(Dispatchers.IO) {
                        RoomDataManger.deleteVodRecordAll()
                        // 在主线程更新数据
                        withContext(Dispatchers.Main) {
                            dismissLoadingDialog()
                            sourceList = ArrayList()
                            rebuildRows()
                            showEmpty()
                        }
                    }

                }.show()
        }

        historyAdapter!!.onItemClickListener =
            BaseQuickAdapter.OnItemClickListener { _: BaseQuickAdapter<*, *>?, view: View?, position: Int ->
                FastClickCheckUtil.check(view)
                val row = historyAdapter!!.data[position]
                val vodInfo = row.vodInfo ?: return@OnItemClickListener
                val bundle = Bundle()
                bundle.putString("id", vodInfo.id)
                bundle.putString("sourceKey", vodInfo.sourceKey)
                bundle.putString("vodName", vodInfo.name)
                jumpActivity(DetailActivity::class.java, bundle)
            }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    private fun rebuildRows() {
        val rows = HistoryRow.buildGroups(sourceList)
        historyAdapter!!.setNewData(ArrayList(rows))
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
            // getAllVodRecord 已按观看时间倒序返回,并把 record.updateTime 回填进 info.watchTime
            val allVodRecord = RoomDataManger.getAllVodRecord(100)
            withContext(Dispatchers.Main) {
                sourceList = allVodRecord
                rebuildRows()
            }
        }
    }
}
