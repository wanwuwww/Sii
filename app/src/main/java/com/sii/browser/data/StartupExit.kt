// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data

import android.content.Context

/**
 * 启动/退出状态标记。
 *
 * 启动时写入「运行中」；正常退出（返回键退出、从最近任务划掉）写回「正常结束」。
 * 崩溃或被系统强杀时不会写回，下次启动即可判定上次为非正常退出。
 */
object StartupExit {

    private const val PREFS = "sii_startup_state"
    private const val KEY_CLEAN = "clean_exit"

    @Volatile
    private var abnormalPending = false

    /** 只在进程内首次启动时判定，Activity 重建不重复读取。 */
    private var initialized = false

    /** 冷启动时调用：读出上次是否正常结束，并把本次标记为运行中。 */
    fun readAndMarkRunning(context: Context) {
        if (initialized) {
            abnormalPending = false
            return
        }
        initialized = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        abnormalPending = prefs.contains(KEY_CLEAN) && !prefs.getBoolean(KEY_CLEAN, true)
        prefs.edit().putBoolean(KEY_CLEAN, false).apply()
    }

    /** 取一次「上次非正常退出」标记，取完即清。 */
    fun consumeAbnormal(): Boolean {
        val value = abnormalPending
        abnormalPending = false
        return value
    }

    /** 正常结束（用户主动退出或划掉任务）。 */
    fun markClean(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CLEAN, true).apply()
    }
}
