// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.content.pm.PackageInfo
import android.os.Build

/**
 * WebView 提供商信息。
 * 用于检测系统 WebView 的安装来源与 forceDark 可用性。
 */
class WebViewProvider(
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long,
    val majorVersion: Int,
    val forceDarkAvailable: Boolean
) {
    companion object {
        @Suppress("DEPRECATION")
        fun from(packageInfo: PackageInfo?): WebViewProvider {
            if (packageInfo == null) {
                return WebViewProvider(null, null, 0L, 0, forceDarkAvailable = Build.VERSION.SDK_INT >= 29)
            }
            val versionName = packageInfo.versionName ?: ""
            val dot = versionName.indexOf('.')
            val major = if (dot > 0) {
                versionName.substring(0, dot).toIntOrNull() ?: 0
            } else 0
            val code = if (Build.VERSION.SDK_INT >= 28) {
                packageInfo.longVersionCode
            } else {
                packageInfo.versionCode.toLong()
            }
            return WebViewProvider(
                packageName = packageInfo.packageName,
                versionName = versionName,
                versionCode = code,
                majorVersion = major,
                forceDarkAvailable = Build.VERSION.SDK_INT >= 29
            )
        }
    }

    /** SDK>=21 且包名为空：系统内置 WebView 提供商。 */
    fun isBuiltIn(): Boolean {
        if (Build.VERSION.SDK_INT < 21) return false
        return packageName.isNullOrEmpty()
    }
}
