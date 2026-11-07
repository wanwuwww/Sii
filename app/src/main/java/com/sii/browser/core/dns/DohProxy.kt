// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.dns

import android.content.Context
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.sii.browser.di.AppServices
import java.util.concurrent.Executor

/**
 * 应用内 DoH：本地代理 + WebView ProxyController，仅影响 WebView 的网页请求。
 */
object DohProxy {

    enum class Status { OFF, RUNNING, UNSUPPORTED, FAILED }

    @Volatile
    var status: Status = Status.OFF
        private set

    @Volatile
    var lastError: String? = null
        private set

    private var proxy: LocalHttpProxy? = null
    private val executor = Executor { it.run() }

    fun isSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
    }.getOrDefault(false)

    /** 按设置启停；可重复调用。 */
    fun sync(context: Context) {
        if (!AppServices.settings.dohProxyEnabled()) {
            stop()
            return
        }
        if (!isSupported()) {
            status = Status.UNSUPPORTED
            return
        }
        val current = proxy
        if (current != null && current.running) {
            status = Status.RUNNING
            return
        }
        val started = LocalHttpProxy { AppServices.settings.dohProvider() }
        if (!started.start()) {
            status = Status.FAILED
            lastError = "proxy start failed"
            return
        }
        proxy = started
        applyOverride(started.port)
    }

    /** 切换服务商后重建代理。 */
    fun restart(context: Context) {
        stop()
        sync(context)
    }

    fun stop() {
        proxy?.stop()
        proxy = null
        if (isSupported()) {
            runCatching {
                ProxyController.getInstance().clearProxyOverride(executor, Runnable { })
            }
        }
        status = Status.OFF
        lastError = null
    }

    private fun applyOverride(port: Int) {
        runCatching {
            val config = ProxyConfig.Builder()
                .addProxyRule("http://127.0.0.1:$port")
                .addBypassRule("<local>")
                .build()
            ProxyController.getInstance().setProxyOverride(
                config,
                executor,
                Runnable {
                    status = Status.RUNNING
                    lastError = null
                }
            )
            // 回调可能晚于页面重建，先乐观标记，避免设置页一直停在「等待生效」。
            status = Status.RUNNING
            lastError = null
        }.onFailure {
            status = Status.FAILED
            lastError = it.message ?: it.javaClass.simpleName
            proxy?.stop()
            proxy = null
        }
    }
}
