// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sii.browser.R
import com.sii.browser.core.mcp.McpServer
import com.sii.browser.di.AppServices
import kotlin.random.Random

/** MCP 服务启停与配置。 */
object McpRuntime {

    fun config(): McpServer.Config? {
        val settings = AppServices.settings
        if (!settings.mcpEnabled()) return null
        val token = settings.mcpToken().orEmpty()
        if (token.isBlank()) return null
        return McpServer.Config(
            port = settings.mcpPort().coerceIn(1024, 65535),
            lan = settings.mcpAllowLan(),
            token = token
        )
    }

    /** 按当前设置启动或停止；配置未变且已运行时不会重启，避免打断已连接客户端。 */
    fun sync(context: Context) {
        if (!AppServices.settings.mcpEnabled()) {
            context.stopService(Intent(context, McpService::class.java))
            AppServices.mcpServer.stop()
            return
        }
        val config = config() ?: return
        if (AppServices.settings.mcpForeground()) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, McpService::class.java)
                )
            }.onFailure {
                AppServices.mcpServer.start(config)
            }
        } else {
            context.stopService(Intent(context, McpService::class.java))
            if (!AppServices.mcpServer.isRunning ||
                AppServices.mcpServer.currentConfig() != config
            ) {
                AppServices.mcpServer.start(config)
            }
        }
    }

    /** 首次启用时生成访问令牌。 */
    fun ensureToken(): String {
        val existing = AppServices.settings.mcpToken()
        if (!existing.isNullOrBlank()) return existing
        val alphabet = "0123456789abcdef"
        val token = buildString(32) {
            repeat(32) { append(alphabet[Random.nextInt(alphabet.length)]) }
        }
        AppServices.settings.setMcpToken(token)
        return token
    }

    fun resetToken(): String {
        AppServices.settings.setMcpToken(null)
        return ensureToken()
    }

    /** 给客户端粘贴的配置片段。 */
    fun clientConfig(): String {
        val settings = AppServices.settings
        val token = ensureToken()
        val port = settings.mcpPort().coerceIn(1024, 65535)
        val host = if (settings.mcpAllowLan()) {
            AppServices.mcpServer.localIpv4() ?: "127.0.0.1"
        } else {
            "127.0.0.1"
        }
        return """
            {
              "mcpServers": {
                "sii-browser": {
                  "type": "http",
                  "url": "http://$host:$port/mcp",
                  "headers": { "Authorization": "Bearer $token" }
                }
              }
            }
        """.trimIndent()
    }
}

/** MCP 前台服务：保证浏览器切后台/锁屏时端口仍然可用。 */
class McpService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppServices.settings.setMcpEnabled(false)
            AppServices.mcpServer.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val config = McpRuntime.config()
        if (config == null) {
            AppServices.mcpServer.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(config, running = true))
        val alreadyRunning = AppServices.mcpServer.isRunning &&
            AppServices.mcpServer.currentConfig() == config
        val started = alreadyRunning || AppServices.mcpServer.start(config)
        if (!alreadyRunning) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, notification(config, running = started))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AppServices.mcpServer.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mcp_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun notification(config: McpServer.Config, running: Boolean): Notification {
        val content = if (running) {
            if (config.lan) {
                getString(R.string.mcp_notification_text_lan, config.port)
            } else {
                getString(R.string.mcp_notification_text_local, config.port)
            }
        } else {
            getString(R.string.mcp_notification_failed)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ShellActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, McpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.mcp_notification_title))
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.mcp_notification_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.sii.browser.mcp.STOP"
        private const val CHANNEL_ID = "mcp"
        private const val NOTIFICATION_ID = 1002
    }
}
