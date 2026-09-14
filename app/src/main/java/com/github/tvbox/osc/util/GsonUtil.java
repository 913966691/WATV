package com.github.tvbox.osc.util;

import com.google.gson.Gson;

/**
 * 全局 Gson 单例,供各模块统一使用。
 */
public class GsonUtil {

    private static final Gson GSON = new Gson();

    private GsonUtil() {
    }

    public static Gson get() {
        return GSON;
    }
}
