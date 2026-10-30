// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.sii.browser.R
import com.sii.browser.data.Presets
import com.sii.browser.di.AppServices

/**
 * 首次启动引导页：展示使用条款说明，同意后进入浏览器主界面。
 * 拒绝则退出应用。条款详情链接在后续模块补齐。
 */
class FirstLaunchFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_first_launch, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.first_launch_agree).setOnClickListener {
            AppServices.settings.markAgreementDone()
            // 首次启动完全套用「玻璃胶囊」预设。
            Presets.apply(AppServices.settings, Presets.GLASS)
            enterBrowser()
        }
    }

    private fun enterBrowser() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.shell_root, BrowserFragment())
            .commit()
    }
}
