// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * 底部工具栏容器：在按钮收到触摸前识别横向滑动。
 *
 * 工具栏按钮铺满整条，普通 OnTouchListener 收不到已被按钮消费的 MOVE/UP；
 * 这里在 onInterceptTouchEvent 层拦截横向手势，未滑动时照常把点击交给按钮。
 */
class SwipeBarLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val threshold = 48f * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                swiping = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swiping && isHorizontal(ev)) {
                    swiping = true
                    for (i in 0 until childCount) getChildAt(i).cancelLongPress()
                }
                if (swiping) return true
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                swiping = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swiping && isHorizontal(ev)) swiping = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (swiping) {
                    val dx = ev.rawX - downX
                    if (abs(dx) >= threshold) {
                        if (dx < 0) onSwipeLeft?.invoke() else onSwipeRight?.invoke()
                    }
                    swiping = false
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                swiping = false
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun isHorizontal(ev: MotionEvent): Boolean {
        val dx = ev.rawX - downX
        val dy = ev.rawY - downY
        return abs(dx) > slop && abs(dx) > abs(dy) * 1.4f
    }
}
