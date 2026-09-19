package com.github.tvbox.osc.cache;

import android.text.TextUtils;

import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.data.AppDataManager;
import com.google.gson.ExclusionStrategy;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import com.orhanobut.hawk.Hawk;
import java.util.ArrayList;
import java.util.List;

/**
 * @author pj567
 * @date :2021/1/7
 * @description:
 */
public class RoomDataManger {
    static ExclusionStrategy vodInfoStrategy = new ExclusionStrategy() {
        @Override
        public boolean shouldSkipField(FieldAttributes field) {
            if (field.getDeclaringClass() == VodInfo.class && field.getName().equals("seriesFlags")) {
                return true;
            }
            if (field.getDeclaringClass() == VodInfo.class && field.getName().equals("seriesMap")) {
                return true;
            }
            return false;
        }

        @Override
        public boolean shouldSkipClass(Class<?> clazz) {
            return false;
        }
    };

    private static Gson getVodInfoGson() {
        return new GsonBuilder().addSerializationExclusionStrategy(vodInfoStrategy).create();
    }

    public static void insertVodRecord(String sourceKey, VodInfo vodInfo) {
        insertVodRecord(sourceKey, vodInfo, null);
    }

    /**
     * 写入观看历史。TeaOS/B站采集等源会把传入的原始 id (如 "?ac=detail&ids=24820") 归一化成
     * 纯数字后再写进 vodInfo.id,这样从历史再点进去时,spider 看到纯数字 id 不知道怎么处理
     * (内部 API 协议不匹配),直接返回空数据 {"list":[{}],"parse":0,"jx":0}。
     * 因此新增 overrideVodId:DetailActivity 把自己收到的"入口原始 id"原封不动写进 record.vodId,
     * 从历史再进入时 DetailActivity 拿到的 vodId 跟第一次进来时一致,spider 才能正常解析。
     * 同时,如果同一视频历史上曾用业务 id(vodInfo.id)写过记录,而本次入口形式不同,
     * 需要先把旧记录删掉,避免出现"同一部片子两条历史"。
     */
    public static void insertVodRecord(String sourceKey, VodInfo vodInfo, String overrideVodId) {
        if (vodInfo == null) return;
        String recordVodId = !TextUtils.isEmpty(overrideVodId) ? overrideVodId : vodInfo.id;
        if (TextUtils.isEmpty(recordVodId)) return;
        // id 形式变化去重:用业务 id 查旧记录,如果跟新的入口 id 不同,删掉避免重复
        if (!TextUtils.isEmpty(vodInfo.id) && !vodInfo.id.equals(recordVodId)) {
            VodRecord oldByBizId = AppDataManager.get().getVodRecordDao().getVodRecord(sourceKey, vodInfo.id);
            if (oldByBizId != null) {
                AppDataManager.get().getVodRecordDao().delete(oldByBizId);
            }
        }
        VodRecord record = AppDataManager.get().getVodRecordDao().getVodRecord(sourceKey, recordVodId);
        if (record == null) {
            record = new VodRecord();
        }
        record.sourceKey = sourceKey;
        record.vodId = recordVodId;
        record.updateTime = System.currentTimeMillis();
        record.dataJson = getVodInfoGson().toJson(vodInfo);
        AppDataManager.get().getVodRecordDao().insert(record);
    }

    public static VodInfo getVodInfo(String sourceKey, String vodId) {
        VodRecord record = AppDataManager.get().getVodRecordDao().getVodRecord(sourceKey, vodId);
        try {
            if (record != null && record.dataJson != null && !TextUtils.isEmpty(record.dataJson)) {
                VodInfo vodInfo = getVodInfoGson().fromJson(record.dataJson, new TypeToken<VodInfo>() {
                }.getType());
                if (vodInfo.name == null)
                    return null;
                return vodInfo;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void deleteVodRecord(String sourceKey, VodInfo vodInfo) {
        VodRecord record = AppDataManager.get().getVodRecordDao().getVodRecord(sourceKey, vodInfo.id);
        if (record != null) {
            AppDataManager.get().getVodRecordDao().delete(record);
        }
    }

    public static List<VodInfo> getAllVodRecord(int limit) {
        int count = AppDataManager.get().getVodRecordDao().getCount();
        Integer index = Hawk.get(HawkConfig.HISTORY_NUM, 0);
        Integer hisNum = HistoryHelper.getHisNum(index);
        if ( count > hisNum ) {
            AppDataManager.get().getVodRecordDao().reserver(hisNum);
        }
        List<VodRecord> recordList = AppDataManager.get().getVodRecordDao().getAll(limit);
        List<VodInfo> vodInfoList = new ArrayList<>();
        if (recordList != null) {
            for (VodRecord record : recordList) {
                VodInfo info = null;
                try {
                    if (record.dataJson != null && !TextUtils.isEmpty(record.dataJson)) {
                        info = getVodInfoGson().fromJson(record.dataJson, new TypeToken<VodInfo>() {
                        }.getType());
                        info.sourceKey = record.sourceKey;
                        info.watchTime = record.updateTime;
                        // 观看历史与当前订阅无关:即便 sourceKey 已不在当前 ApiConfig 里也必须保留展示,
                        // 否则用户切换订阅后旧记录会"消失"(点进去时再由 DetailActivity 提示原因)。
                        if (info.name == null || info.name.trim().isEmpty()) {
                            info.name = "未知影片";
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (info != null)
                    vodInfoList.add(info);
            }
        }
        return vodInfoList;
    }

    public static void insertVodCollect(String sourceKey, VodInfo vodInfo) {
        VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodInfo.id);
        if (record != null) {
            return;
        }
        record = new VodCollect();
        record.sourceKey = sourceKey;
        record.vodId = vodInfo.id;
        record.updateTime = System.currentTimeMillis();
        record.name = vodInfo.name;
        record.pic = vodInfo.pic;
        AppDataManager.get().getVodCollectDao().insert(record);
    }

    public static VodCollect getVodCollect(String sourceKey, String vodId) {
        return AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodId);
    }

    public static void deleteVodCollect(int id) {
        AppDataManager.get().getVodCollectDao().delete(id);
    }

    public static void deleteVodCollect(String sourceKey, VodInfo vodInfo) {
        VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodInfo.id);
        if (record != null) {
            AppDataManager.get().getVodCollectDao().delete(record);
        }
    }

    public static boolean isVodCollect(String sourceKey, String vodId) {
        VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodId);
        return record != null;
    }

    public static List<VodCollect> getAllVodCollect() {
        return AppDataManager.get().getVodCollectDao().getAll();
    }

    /**
     * 收藏页横版卡片数据:每条收藏联动观看记录(拿到"看到第X集" playNote 与"更新至X集" note),
     * 返回 VodInfo 列表(仅填充展示字段),按收藏时间倒序。
     * 收藏了但没看过(历史里没有)的返回最小化 VodInfo,playNote 为空,展示层显示"未观看"。
     */
    public static List<VodInfo> getCollectDisplayList() {
        List<VodCollect> collects = getAllVodCollect();
        List<VodInfo> list = new ArrayList<>();
        if (collects == null || collects.isEmpty()) {
            return list;
        }
        for (VodCollect c : collects) {
            if (c == null || c.vodId == null) continue;
            VodInfo info = null;
            try {
                info = getVodInfo(c.sourceKey, c.vodId);
            } catch (Throwable ignored) {
            }
            if (info == null) {
                info = new VodInfo();
                info.id = c.vodId;
                info.name = c.name;
                info.pic = c.pic;
                info.sourceKey = c.sourceKey;
            }
            info.watchTime = c.updateTime;
            list.add(info);
        }
        return list;
    }

    /**
     * 删除全部收藏
     */
    public static void deleteVodCollectAll() {
        AppDataManager.get().getVodCollectDao().deleteAll();
    }

    /**
     * 删除全部历史记录
     */
    public static void deleteVodRecordAll() {
        AppDataManager.get().getVodRecordDao().deleteAll();
    }

}