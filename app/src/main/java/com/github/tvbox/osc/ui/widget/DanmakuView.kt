package com.github.tvbox.osc.ui.widget

import android.animation.Animator
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.random.Random

/**
 * 轻量级弹幕视图：随机位置、颜色、字号，水平飘过屏幕。
 */
class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val danmakuPool = mutableListOf<String>()
    private val runningAnimators = mutableListOf<ObjectAnimator>()
    private val activeLanes = mutableSetOf<Int>()
    private val random = Random(System.currentTimeMillis())

    private var isPlaying = false
    private var viewWidth = 0
    private var viewHeight = 0

    private val textColors = listOf(
        Color.parseColor("#E53935"),
        Color.parseColor("#43A047"),
        Color.parseColor("#1E88E5"),
        Color.parseColor("#FB8C00"),
        Color.parseColor("#8E24AA"),
        Color.parseColor("#D81B60"),
        Color.parseColor("#00ACC1"),
        Color.parseColor("#3949AB"),
        Color.parseColor("#7CB342"),
        Color.parseColor("#F4511E")
    )

    fun setDanmakuList(list: List<String>) {
        danmakuPool.clear()
        danmakuPool.addAll(list)
    }

    fun start() {
        if (isPlaying || danmakuPool.isEmpty()) return
        isPlaying = true
        startSpawning()
    }

    fun stop() {
        isPlaying = false
        removeCallbacks(spawnRunnable)
        // 先拷贝一份再 cancel，避免 cancel() 的回调里对 runningAnimators 做 remove 触发并发修改异常
        val snapshot = runningAnimators.toList()
        runningAnimators.clear()
        activeLanes.clear()
        snapshot.forEach { it.cancel() }
        removeAllViews()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
    }

    private fun startSpawning() {
        if (!isPlaying) return
        post(spawnRunnable)
    }

    private val spawnRunnable = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            val burst = random.nextInt(2) + 1 // 每次生成 1~2 条，提升密度
            repeat(burst) { spawnDanmaku() }
            val nextDelay = random.nextLong(150, 450)
            postDelayed(this, nextDelay)
        }
    }

    private fun spawnDanmaku() {
        if (viewWidth == 0 || viewHeight == 0 || danmakuPool.isEmpty()) return

        val text = danmakuPool.random(random)
        val textView = createDanmakuItem(text)
        addView(textView)

        textView.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val textWidth = textView.measuredWidth
        val textHeight = textView.measuredHeight

        val laneHeight = textHeight + dpToPx(14)
        val laneCount = maxOf(1, (viewHeight - dpToPx(80)) / laneHeight)
        val lane = findAvailableLane(laneCount)
        if (lane < 0) {
            removeView(textView)
            return
        }

        activeLanes.add(lane)

        val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        params.topMargin = dpToPx(40) + lane * laneHeight
        params.gravity = Gravity.TOP or Gravity.START
        textView.layoutParams = params

        val startX = viewWidth.toFloat()
        val endX = -textWidth.toFloat()
        textView.translationX = startX

        val duration = random.nextLong(3000, 6000)
        val animator = ObjectAnimator.ofFloat(textView, "translationX", startX, endX)
        animator.duration = duration
        animator.interpolator = LinearInterpolator()

        animator.addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {}

            override fun onAnimationEnd(animation: Animator) {
                cleanup(animation, lane, textView)
            }

            override fun onAnimationCancel(animation: Animator) {
                cleanup(animation, lane, textView)
            }

            override fun onAnimationRepeat(animation: Animator) {}

            private fun cleanup(animation: Animator, lane: Int, view: TextView) {
                activeLanes.remove(lane)
                removeView(view)
                runningAnimators.remove(animation)
            }
        })

        runningAnimators.add(animator)
        animator.start()
    }

    private fun findAvailableLane(laneCount: Int): Int {
        repeat(5) {
            val lane = random.nextInt(laneCount)
            if (!activeLanes.contains(lane)) return lane
        }
        for (i in 0 until laneCount) {
            if (!activeLanes.contains(i)) return i
        }
        return -1
    }

    private fun createDanmakuItem(text: String): TextView {
        val textView = TextView(context)
        textView.text = text
        textView.setTextColor(textColors.random(random))
        textView.textSize = random.nextFloat() * 8 + 13 // 13sp - 21sp
        textView.setShadowLayer(3f, 1f, 1f, Color.argb(160, 0, 0, 0))
        textView.includeFontPadding = false
        textView.isSingleLine = true
        return textView
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density + 0.5f).toInt()
    }
}
