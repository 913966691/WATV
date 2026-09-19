package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseMultiItemQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.util.DefaultConfig;
import com.squareup.picasso.Picasso;

import java.util.List;

/**
 * 历史/收藏页的横版卡片列表(B站风格):
 * 分组标题("一周内"/"更早") + 行卡片(左海报+左下备注角标,右侧片名+"看到第X集")。
 * 数据由 Activity 组装成 HistoryRow(头/行混合列表)。
 */
public class HistoryListAdapter extends BaseMultiItemQuickAdapter<HistoryRow, BaseViewHolder> {

    public HistoryListAdapter(List<HistoryRow> data) {
        super(data);
        addItemType(HistoryRow.TYPE_HEADER, R.layout.item_history_header);
        addItemType(HistoryRow.TYPE_ITEM, R.layout.item_history_row);
    }

    @Override
    protected void convert(BaseViewHolder helper, HistoryRow row) {
        if (row.type == HistoryRow.TYPE_HEADER) {
            helper.setText(R.id.tvHeader, row.headerTitle);
            return;
        }
        VodInfo item = row.vodInfo;
        // 副标题:看到第几集(历史记录的 playNote);没看过显示"未观看"
        TextView tvSub = helper.getView(R.id.tvSub);
        String sub = item.playNote == null ? "" : item.playNote.trim();
        tvSub.setText(sub.isEmpty() ? "未观看" : "看到 " + sub);
        // 海报左下角备注角标:源返回的备注(更新至第100集 / 全8集 / HD 等)
        TextView tvBadge = helper.getView(R.id.tvBadge);
        String badge = item.note == null ? "" : item.note.trim();
        if (badge.isEmpty()) {
            tvBadge.setVisibility(android.view.View.GONE);
        } else {
            tvBadge.setVisibility(android.view.View.VISIBLE);
            tvBadge.setText(badge);
        }
        helper.setText(R.id.tvName, item.name);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        //由于部分电视机使用glide报错
        if (!TextUtils.isEmpty(item.pic)) {
            Picasso.get()
                    .load(DefaultConfig.checkReplaceProxy(item.pic))
                    .placeholder(R.drawable.img_loading_placeholder)
                    .error(R.drawable.img_loading_placeholder)
                    .into(ivThumb);
        } else {
            ivThumb.setImageResource(R.drawable.img_loading_placeholder);
        }
    }
}
