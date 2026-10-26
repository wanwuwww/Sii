// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 外部链接中转页：
 * 透明无界面，收到 VIEW 后转交 [ShellActivity] 并立即关闭，
 * 避免外部 App 打开链接时闪出中转任务栈。
 */
class TrampolineActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scheme = intent.data?.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            finish()
            return
        }
        val forward = Intent(this, ShellActivity::class.java).apply {
            action = intent.action
            data = intent.data
            type = intent.type
            component = null
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            intent.extras?.let { putExtras(it) }
        }
        runCatching { startActivity(forward) }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
