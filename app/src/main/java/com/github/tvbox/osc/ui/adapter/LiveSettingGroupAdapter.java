package com.github.tvbox.osc.ui.adapter;

import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveSettingGroup;

import java.util.ArrayList;


/**
 * @author pj567
 * @date :2021/1/12
 * @description:
 */
public class LiveSettingGroupAdapter extends BaseQuickAdapter<LiveSettingGroup, BaseViewHolder> {
    private int selectedGroupIndex = -1;
    private int focusedGroupIndex = -1;

    public LiveSettingGroupAdapter() {
        super(R.layout.item_live_setting_group, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder holder, LiveSettingGroup group) {
        TextView tvGroupName = holder.getView(R.id.tvSettingGroupName);
        tvGroupName.setText(group.getGroupName());
        int groupIndex = group.getGroupIndex();
        if (groupIndex == selectedGroupIndex && groupIndex != focusedGroupIndex) {
            // 选中态使用主题粉
            tvGroupName.setTextColor(ContextCompat.getColor(mContext, R.color.bili_pink));
        } else if (groupIndex == focusedGroupIndex) {
            // 聚焦态:粉色背景上的白字(父布局 selector 会处理背景)
            tvGroupName.setTextColor(ContextCompat.getColor(mContext, R.color.white));
        } else {
            // 默认态:深色文字,确保浅色背景可见
            tvGroupName.setTextColor(ContextCompat.getColor(mContext, R.color.bili_text_primary));
        }
    }

    public void setSelectedGroupIndex(int selectedGroupIndex) {
        int preSelectedGroupIndex = this.selectedGroupIndex;
        this.selectedGroupIndex = selectedGroupIndex;
        if (preSelectedGroupIndex != -1)
            notifyItemChanged(preSelectedGroupIndex);
        if (this.selectedGroupIndex != -1)
            notifyItemChanged(this.selectedGroupIndex);
    }

    public int getSelectedGroupIndex() {
        return selectedGroupIndex;
    }

    public void setFocusedGroupIndex(int focusedGroupIndex) {
        this.focusedGroupIndex = focusedGroupIndex;
        if (this.focusedGroupIndex != -1)
            notifyItemChanged(this.focusedGroupIndex);
        else if (this.selectedGroupIndex != -1)
            notifyItemChanged(this.selectedGroupIndex);
    }
}