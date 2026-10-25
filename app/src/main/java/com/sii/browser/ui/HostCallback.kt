package com.sii.browser.ui

import android.content.Intent

/**
 * Fragment 宿主回调契约。
 * ShellActivity 通过这些接口向 Fragment 下发事件，Fragment 实现需要的事件即可。
 */
interface ThemeAware {
    /** 系统/设置夜间模式变化，重新应用主题。 */
    fun onThemeChanged(night: Boolean)
}

interface SoftInputAware {
    /** 软键盘弹出状态变化。 */
    fun onSoftInputChanged(visible: Boolean)
}

interface IntentAware {
    /** 外部 Intent（深链/分享文本）分发。 */
    fun handleNewIntent(intent: Intent): Boolean
}

interface FocusAware {
    /** 窗口焦点变化。 */
    fun onWindowFocusGained(hasFocus: Boolean)
}

interface KeyEventSink {
    /** 拦截物理按键（音量键翻页等）。 */
    fun dispatchKeyEventInternal(event: android.view.KeyEvent): Boolean
}
