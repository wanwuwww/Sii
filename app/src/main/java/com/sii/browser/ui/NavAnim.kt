// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import androidx.fragment.app.FragmentTransaction
import com.sii.browser.R

/** 页面切换统一转场：进入右滑淡入，返回右滑淡出。 */
object NavAnim {
    fun apply(transaction: FragmentTransaction): FragmentTransaction =
        transaction.setCustomAnimations(
            R.anim.slide_in_right,
            R.anim.fade_out,
            R.anim.fade_in,
            R.anim.slide_out_right
        )
}
