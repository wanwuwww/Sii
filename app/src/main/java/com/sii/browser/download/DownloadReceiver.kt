// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 下载广播接收器。
 * 接收暂停/恢复/重下/全部控制，转发给 [DownloadService]。
 */
class DownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in CONTROLS) return
        val service = Intent(context, DownloadService::class.java).apply {
            this.action = action
            if (intent.hasExtra(DownloadService.EXTRA_TASK_ID)) {
                putExtra(DownloadService.EXTRA_TASK_ID, intent.getLongExtra(DownloadService.EXTRA_TASK_ID, -1L))
            }
        }
        runCatching { context.startForegroundService(service) }
    }

    private companion object {
        val CONTROLS = setOf(
            DownloadService.ACTION_PAUSE,
            DownloadService.ACTION_RESUME,
            DownloadService.ACTION_REDOWNLOAD,
            DownloadService.ACTION_PAUSE_ALL,
            DownloadService.ACTION_RESUME_ALL
        )
    }
}
