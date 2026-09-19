package com.github.tvbox.osc.cache;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

@Entity(tableName = "vodCollect")
public class VodCollect implements Serializable {
    @PrimaryKey(autoGenerate = true)
    private int id;
    @ColumnInfo(name = "vodId")
    public String vodId;
    @ColumnInfo(name = "updateTime")
    public long updateTime;
    @ColumnInfo(name = "sourceKey")
    public String sourceKey;
    @ColumnInfo(name = "name")
    public String name;
    @ColumnInfo(name = "pic")
    public String pic;

    /**
     * 收藏时记录的"已知总集数",用于后续比对是否更新了新一集。
     * 0 表示尚未建立基线(老数据或收藏时拿不到剧集信息)。
     */
    @ColumnInfo(name = "lastEpisodeCount", defaultValue = "0")
    public int lastEpisodeCount = 0;

    /**
     * 已知的最后一集名称(如"第12集"),仅作展示/排查用。
     */
    @ColumnInfo(name = "lastEpisodeName")
    public String lastEpisodeName;

    /**
     * 是否有更新:1=检测到更新了新一集(收藏页展示角标),0=无更新。
     * 用户进入详情页后会被清除。
     */
    @ColumnInfo(name = "hasUpdate", defaultValue = "0")
    public int hasUpdate = 0;

    /**
     * 上次真正发起详情请求的时间戳,用于节流(同部剧 6 小时内不重复请求)。
     */
    @ColumnInfo(name = "lastCheckTime", defaultValue = "0")
    public long lastCheckTime = 0;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }
}