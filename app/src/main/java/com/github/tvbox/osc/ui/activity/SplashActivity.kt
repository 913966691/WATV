package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivitySplashBinding
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions

class SplashActivity : BaseVbActivity<ActivitySplashBinding>() {

    private val handler = Handler(Looper.getMainLooper())
    private var countdown = 10
    private var hasOpenedMain = false

    override fun init() {
        App.getInstance().isNormalStart = true
        Log.d("WATV_SPLASH", "SplashActivity launched as entry")
        setupDanmaku()
        setupSkipButton()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !XXPermissions.isGranted(this, POST_NOTIFICATIONS_PERMISSION)
        ) {
            XXPermissions.with(this)
                .permission(POST_NOTIFICATIONS_PERMISSION)
                .request(object : OnPermissionCallback {
                    override fun onGranted(permissions: List<String>, all: Boolean) {
                        startCountdown()
                    }

                    override fun onDenied(permissions: List<String>, never: Boolean) {
                        startCountdown()
                    }
                })
        } else {
            startCountdown()
        }
    }

    private fun setupDanmaku() {
        val danmakuList = listOf(
            "蛙TV牛逼",
            "这个app好好用",
            "牛逼plus",
            "我天天用",
            "白嫖之星",
            "画质真香",
            "频道好多",
            "加载速度起飞",
            "追剧神器",
            "电视自由",
            "摸鱼必备",
            "真·免费",
            "界面清爽",
            "换台丝滑",
            "全家都在用",
            "源很稳",
            "聚合真方便",
            "观影体验拉满",
            "开源万岁",
            "弹幕护体",
            "剧荒不存在的",
            "4K 流畅",
            "投屏好评",
            "手机电视都能看",
            "收藏夹好用",
            "片源更新快",
            "无广告爽到",
            "遥控器也能操作",
            "一直在维护",
            "良心开发者",
            "冲就完了",
            "蛙TV yyds",
            "白嫖党的福音",
            "看片不花一分钱",
            "这界面太顶了",
            "朋友都在用",
            "更新真勤快",
            "弹幕比视频还精彩",
            "我的快乐源泉",
            "深夜追剧神器",
            "源多就是任性",
            "再也不开会员了",
            "钱包保住了",
            "这 App 绝了",
            "用过就回不去",
            "安利给全公司",
            "爸妈都说好",
            "闭眼入不亏"
        )
        mBinding.danmakuView.setDanmakuList(danmakuList)
        mBinding.danmakuView.start()
    }

    private fun setupSkipButton() {
        mBinding.tvSkip.setOnClickListener {
            openMain()
        }
    }

    private fun startCountdown() {
        updateSkipText()
        handler.postDelayed(countdownRunnable, 1000)
    }

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (hasOpenedMain) return
            countdown--
            updateSkipText()
            if (countdown <= 0) {
                openMain()
            } else {
                handler.postDelayed(this, 1000)
            }
        }
    }

    private fun updateSkipText() {
        mBinding.tvSkip.text = getString(R.string.splash_skip, countdown)
    }

    private fun openMain() {
        if (hasOpenedMain) return
        hasOpenedMain = true
        handler.removeCallbacks(countdownRunnable)
        mBinding.danmakuView.stop()
        startActivity(Intent(this@SplashActivity, MainActivity::class.java))
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(countdownRunnable)
        mBinding.danmakuView.stop()
    }

    private companion object {
        const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"
    }
}
