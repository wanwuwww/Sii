// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.util.TypedValue

/**
 * 主题强调色（主色）：从当前主题的 colorAccent 解析，
 * 使「设置 → 主色」的绿/橙/紫能作用于所有强调色元素。
 */
object ThemeAccent {

    fun color(context: Context): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorAccent, value, true)
        return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
    }
}
