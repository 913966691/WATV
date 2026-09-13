package com.github.tvbox.osc.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;

/**
 * Android 14 (API 34) 兼容工具:动态注册 BroadcastReceiver 时必须显式
 * 声明 RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED,否则抛 SecurityException。
 *
 * 之前各 Activity 里的 registerReceiver(receiver, filter) 两参调用在
 * Android 14 上会直接崩溃,这里统一处理,保持 minSdk 兼容性。
 */
public final class ReceiverCompat {

    private ReceiverCompat() {}

    /**
     * 注册一个 receiver。Android 14+ 用 NOT_EXPORTED(仅本应用消费),
     * 低版本走两参版本。
     */
    public static android.content.Intent registerSafe(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            return context.registerReceiver(receiver, filter);
        }
    }
}
