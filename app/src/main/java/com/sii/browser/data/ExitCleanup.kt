// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data

import com.sii.browser.di.AppServices
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** 退出清理：只在任务被移除/真正退出时执行，切到后台不触发。 */
object ExitCleanup {

    fun clearNow() {
        val settings = AppServices.settings
        if (settings.clearDataOnExit() != 1) return
        val items = settings.exitClearItems().split(',').toSet()
        val privacy = AppServices.privacyManager
        if ("cache" in items) privacy.clearCache()
        if ("cookies" in items) privacy.clearCookies()
        if ("form" in items) privacy.clearFormData()
        if ("webstorage" in items) privacy.clearWebStorage()
        // 进程即将结束，数据库清理限时同步完成，避免协程被直接杀掉。
        runBlocking {
            withTimeoutOrNull(2500L) {
                if ("history" in items) AppServices.historyRepository.clearAll()
                if ("downloads" in items) AppServices.downloadRepository.clearAll()
                if ("passwords" in items) AppServices.passwordRepository.clearAll()
                if ("favorites" in items) AppServices.favoriteRepository.clearAll()
                if ("scripts" in items) AppServices.scriptRepository.clearAll()
                if ("siteconfigs" in items) AppServices.siteConfigRepository.clearAll()
            }
        }
    }
}
