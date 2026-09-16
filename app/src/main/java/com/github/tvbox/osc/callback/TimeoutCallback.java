package com.github.tvbox.osc.callback;

import com.github.tvbox.osc.R;
import com.kingja.loadsir.callback.Callback;

/**
 * 加载超时 / 加载失败（空态）的统一状态页。
 * 居中展示一行提示文字 + 刷新图标，点击任意位置触发 LoadSir 的 OnReloadListener，
 * 由 Fragment 自行实现"重新冷加载"。
 */
public class TimeoutCallback extends Callback {
    @Override
    protected int onCreateView() {
        return R.layout.loadsir_timeout_layout;
    }
}
