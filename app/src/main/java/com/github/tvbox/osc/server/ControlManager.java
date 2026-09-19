package com.github.tvbox.osc.server;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.receiver.SearchReceiver;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author pj567
 * @date :2021/1/4
 * @description:
 */
public class ControlManager {
    private static ControlManager instance;
    private RemoteServer mServer = null;
    public static Context mContext;

    private ControlManager() {

    }

    public static ControlManager get() {
        if (instance == null) {
            synchronized (ControlManager.class) {
                if (instance == null) {
                    instance = new ControlManager();
                }
            }
        }
        return instance;
    }

    public static void init(Context context) {
        mContext = context;
    }

    public String getAddress(boolean local) {
        return local ? mServer.getLoadAddress() : mServer.getServerAddress();
    }

    public void startServer() {
        // 仅在代理确实已在运行时跳过;若已被 stop 过(对象还在但未启动),必须重建
        if (mServer != null && mServer.isStarted()) {
            return;
        }
        do {
            // 清理可能残留(已停止)的旧实例后再建新实例
            if (mServer != null) {
                try {
                    mServer.stop();
                } catch (Exception ignored) {
                }
            }
            mServer = new RemoteServer(RemoteServer.serverPort, mContext);
            mServer.setDataReceiver(new DataReceiver() {
                @Override
                public void onTextReceived(String text) {
                    if (!TextUtils.isEmpty(text)) {
                        Intent intent = new Intent();
                        Bundle bundle = new Bundle();
                        bundle.putString("title", text);
                        intent.setAction(SearchReceiver.action);
                        intent.setPackage(mContext.getPackageName());
                        intent.setComponent(new ComponentName(mContext, SearchReceiver.class));
                        intent.putExtras(bundle);
                        mContext.sendBroadcast(intent);
                    }
                }

                @Override
                public void onApiReceived(String url) {
                    EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_API_URL_CHANGE, url));
                }

                @Override
                public void onPushReceived(String url) {
                    EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_PUSH_URL, url));
                }
            });
            try {
                mServer.start();
                break;
            } catch (IOException ex) {
                // 端口被占用(罕见):递增后重试,避免代理彻底起不来
                RemoteServer.serverPort++;
            }
        } while (RemoteServer.serverPort < 9999);
    }

    public void stopServer() {
        if (mServer != null) {
            try {
                mServer.stop();
            } catch (Exception ignored) {
            }
        }
        // 关键:停掉后必须把引用置空,否则下一次 startServer 会因 mServer!=null 而误判"已在运行"、
        // 导致代理永远无法重启(直播→点播失败、本地代理连接 2001 的根因)
        mServer = null;
    }
}