// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 播放器手势层：只观察触摸、不消费事件，播放器自身的点击与进度条拖动照常生效。
 * 左 1/3 竖直滑 = 亮度、右 1/3 竖直滑 = 音量、横向滑 = 进度；底部 15%（控制条区域）不接管。
 */
class PlayerGestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    enum class Side { LEFT, RIGHT }

    interface Listener {
        fun onVerticalStart(side: Side)

        /** [delta] 为相对屏幕高度 0.6 倍的位移比例，向上为正。 */
        fun onVerticalMove(side: Side, delta: Float)

        fun onVerticalEnd()

        fun onSeekStart()

        /** [fraction] 为相对屏幕宽度的位移比例，向右为正。 */
        fun onSeekMove(fraction: Float)

        fun onSeekEnd()

        /** 单击画面：切换工具栏显隐（拖动手势不触发）。 */
        fun onTap()
    }

    var listener: Listener? = null

    /** 控制条等区域：落在这些视图范围内的触摸不参与手势与单击。 */
    var excluded: List<View> = emptyList()

    private val slop = 12f * resources.displayMetrics.density
    private val tapSlop = 6f * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var mode = MODE_NONE
    private var side = Side.LEFT
    private var intercepting = false

    private fun ignoredAt(x: Float, y: Float): Boolean = excluded.any {
        it.visibility == View.VISIBLE &&
            x >= it.left && x <= it.right && y >= it.top && y <= it.bottom
    }

    /** 视频区域的触摸由本层接管，不再下传给 PlayerView，避免它的控制器再切一次显隐。 */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            intercepting = !ignoredAt(ev.x, ev.y)
        }
        return intercepting
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        handleTouch(ev)
        return true
    }

    private fun handleTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = ev.downTime
                moved = false
                mode = MODE_NONE
                side = if (ev.x < width / 3f) Side.LEFT else Side.RIGHT
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > tapSlop || abs(dy) > tapSlop) moved = true
                if (mode == MODE_NONE) {
                    if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) {
                        mode = MODE_SEEK
                        listener?.onSeekStart()
                    } else if (abs(dy) > slop && abs(dy) > abs(dx) * 1.2f &&
                        downY < height * 0.85f
                    ) {
                        mode = MODE_VERTICAL
                        listener?.onVerticalStart(side)
                    }
                }
                when (mode) {
                    MODE_VERTICAL -> listener?.onVerticalMove(
                        side,
                        (downY - ev.y) / (height.coerceAtLeast(1) * 0.6f)
                    )
                    MODE_SEEK -> listener?.onSeekMove((ev.x - downX) / width.coerceAtLeast(1))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!moved && mode == MODE_NONE && ev.eventTime - downTime < 250L) {
                    listener?.onTap()
                }
                when (mode) {
                    MODE_VERTICAL -> listener?.onVerticalEnd()
                    MODE_SEEK -> listener?.onSeekEnd()
                }
                mode = MODE_NONE
                moved = false
                intercepting = false
            }
        }
    }

    private companion object {
        const val MODE_NONE = 0
        const val MODE_VERTICAL = 1
        const val MODE_SEEK = 2
    }
}
