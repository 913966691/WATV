package com.github.tvbox.osc.cache;

import android.text.TextUtils;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
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
        VodRecord record = AppDataManager.get().getVodRecordDao().getVodRecord(sourceKey, vodInfo.id);
        if (record == null) {
            record = new VodRecord();
        }
        record.sourceKey = sourceKey;
        record.vodId = vodInfo.id;
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
                        SourceBean sourceBean = ApiConfig.get().getSource(info.sourceKey);
                        if (sourceBean == null || info.name == null)
                            info = null;
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
        // 建立"已知集数"基线:后续比对是否更新了新一集。
        // 电影/单集(max<=1)基线记 0,检查器会直接跳过,不参与更新检测。
        record.lastEpisodeCount = calcMaxEpisodeCount(vodInfo);
        record.lastEpisodeName = calcLastEpisodeName(vodInfo);
        record.hasUpdate = 0;
        record.lastCheckTime = System.currentTimeMillis();
        AppDataManager.get().getVodCollectDao().insert(record);
    }

    /**
     * 计算 VodInfo 所有线路中最大的集数(即"最新一集"所在线路的集数)。
     * 电影/单集返回 1,无剧集信息返回 0。
     */
    public static int calcMaxEpisodeCount(VodInfo vodInfo) {
        if (vodInfo == null || vodInfo.seriesMap == null || vodInfo.seriesMap.isEmpty()) {
            return 0;
        }
        int max = 0;
        for (List<VodInfo.VodSeries> seriesList : vodInfo.seriesMap.values()) {
            if (seriesList != null && seriesList.size() > max) {
                max = seriesList.size();
            }
        }
        return max;
    }

    /**
     * 取集数最多的那条线路的最后一集名称(如"第12集")。
     */
    public static String calcLastEpisodeName(VodInfo vodInfo) {
        if (vodInfo == null || vodInfo.seriesMap == null || vodInfo.seriesMap.isEmpty()) {
            return null;
        }
        String lastName = null;
        int max = 0;
        for (List<VodInfo.VodSeries> seriesList : vodInfo.seriesMap.values()) {
            if (seriesList != null && !seriesList.isEmpty() && seriesList.size() > max) {
                max = seriesList.size();
                lastName = seriesList.get(seriesList.size() - 1).name;
            }
        }
        return lastName;
    }

    /**
     * 用户已进入详情页(看到了最新剧集):把集数基线同步为当前最新,并清除「更新」角标。
     */
    public static void markCollectViewed(String sourceKey, VodInfo vodInfo) {
        if (sourceKey == null || vodInfo == null || vodInfo.id == null) return;
        VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodInfo.id);
        if (record == null) return;
        int max = calcMaxEpisodeCount(vodInfo);
        if (max > 0) {
            record.lastEpisodeCount = max;
            record.lastEpisodeName = calcLastEpisodeName(vodInfo);
        }
        record.hasUpdate = 0;
        record.lastCheckTime = System.currentTimeMillis();
        AppDataManager.get().getVodCollectDao().update(record);
    }

    /**
     * 写回收藏记录(用于更新集数基线 / 更新标记 / 节流时间)。
     */
    public static void updateVodCollect(VodCollect record) {
        if (record == null) return;
        AppDataManager.get().getVodCollectDao().update(record);
    }

    public static VodCollect getVodCollect(String sourceKey, String vodId) {
        return AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodId);
    }

    /**
     * 用户已进入详情页(看到了最新剧集),清除"有更新"角标并同步集数基线。
     */
    public static void clearCollectUpdateFlag(String sourceKey, String vodId) {
        VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodId);
        if (record == null) return;
        if (record.hasUpdate == 0) return;
        record.hasUpdate = 0;
        AppDataManager.get().getVodCollectDao().update(record);
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