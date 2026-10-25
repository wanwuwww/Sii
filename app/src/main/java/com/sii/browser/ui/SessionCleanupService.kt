package com.sii.browser.ui

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.sii.browser.data.ExitCleanup

/**
 * 轻量会话服务：只用于接收“从最近任务移除”的 onTaskRemoved 回调。
 * 应用在前台时启动，不显示通知；任务被移除时执行退出清理并停止自己。
 */
class SessionCleanupService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        ExitCleanup.clearNow()
        com.sii.browser.data.StartupExit.markClean(applicationContext)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}
