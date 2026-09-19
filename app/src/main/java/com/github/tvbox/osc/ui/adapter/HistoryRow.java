package com.github.tvbox.osc.ui.adapter;

import com.chad.library.adapter.base.entity.MultiItemEntity;
import com.github.tvbox.osc.bean.VodInfo;

/**
 * 历史/收藏页横版列表的列表项:分组标题 或 影片行。
 * 统一包装成 MultiItemEntity 交给 HistoryListAdapter 渲染两种视图。
 */
public class HistoryRow implements MultiItemEntity {
    public static final int TYPE_HEADER = 1;
    public static final int TYPE_ITEM = 2;

    public int type;
    // TYPE_HEADER 用
    public String headerTitle;
    // TYPE_ITEM 用
    public VodInfo vodInfo;

    public static HistoryRow header(String title) {
        HistoryRow row = new HistoryRow();
        row.type = TYPE_HEADER;
        row.headerTitle = title;
        return row;
    }

    public static HistoryRow item(VodInfo info) {
        HistoryRow row = new HistoryRow();
        row.type = TYPE_ITEM;
        row.vodInfo = info;
        return row;
    }

    @Override
    public int getItemType() {
        return type;
    }

    /** 一周时间窗:7 天内的观看/收藏归"一周内",其余归"更早" */
    private static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000L;

    /**
     * 把影片列表按 watchTime 分组成"一周内 / 更早"两段带标题的行列表。
     * 空组不产出标题行;两段都空则返回空列表(展示层据此显示空态)。
     */
    public static java.util.List<HistoryRow> buildGroups(java.util.List<VodInfo> list) {
        java.util.List<VodInfo> week = new java.util.ArrayList<>();
        java.util.List<VodInfo> earlier = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        if (list != null) {
            for (VodInfo info : list) {
                if (info != null && info.watchTime > 0 && now - info.watchTime <= WEEK_MS) {
                    week.add(info);
                } else {
                    earlier.add(info);
                }
            }
        }
        java.util.List<HistoryRow> rows = new java.util.ArrayList<>();
        if (!week.isEmpty()) {
            rows.add(header("一周内"));
            for (VodInfo info : week) rows.add(item(info));
        }
        if (!earlier.isEmpty()) {
            rows.add(header("更早"));
            for (VodInfo info : earlier) rows.add(item(info));
        }
        return rows;
    }
}
