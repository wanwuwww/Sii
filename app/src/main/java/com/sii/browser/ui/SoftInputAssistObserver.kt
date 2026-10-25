package com.sii.browser.ui

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * 软键盘弹出检测。
 *
 * 原理：监听根视图全局布局变化，比较窗口完整高度与可见区域高度，
 * 差值超过阈值（120dp）判定键盘弹出，回调监听者。
 * 与 Activity 生命周期绑定：RESUME 注册、PAUSE 注销、DESTROY 清理。
 */
class SoftInputAssistObserver(
    activity: Activity,
    private val listener: (Boolean) -> Unit
) : DefaultLifecycleObserver {

    private var rootView: View? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private val thresholdPx: Int
    private val visibleFrame = Rect()
    private var lastReportedVisible = false

    init {
        thresholdPx = (120 * activity.resources.displayMetrics.density).toInt()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        rootView = content?.getChildAt(0)
    }

    override fun onResume(owner: LifecycleOwner) {
        val view = rootView ?: return
        val observer = view.viewTreeObserver
        val listener = layoutListener ?: createLayoutListener().also { layoutListener = it }
        observer.removeOnGlobalLayoutListener(listener)
        observer.addOnGlobalLayoutListener(listener)
    }

    override fun onPause(owner: LifecycleOwner) {
        layoutListener?.let {
            val observer = rootView?.viewTreeObserver ?: return
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(it)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        onPause(owner)
        rootView = null
        layoutListener = null
    }

    private fun createLayoutListener() = ViewTreeObserver.OnGlobalLayoutListener {
        val view = rootView ?: return@OnGlobalLayoutListener
        // 窗口完整高度不随键盘变化（edge-to-edge 下根布局只加内边距），
        // 可见区域会扣除键盘，用两者差值判断。
        val fullHeight = view.rootView.height
        if (fullHeight <= 0) return@OnGlobalLayoutListener
        view.getWindowVisibleDisplayFrame(visibleFrame)
        val keyboardVisible = fullHeight - visibleFrame.height() > thresholdPx
        if (keyboardVisible != lastReportedVisible) {
            lastReportedVisible = keyboardVisible
            listener(keyboardVisible)
        }
    }
}
