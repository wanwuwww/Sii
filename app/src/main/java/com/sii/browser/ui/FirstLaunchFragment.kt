// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
        bindLegalLinks(view)
        view.findViewById<View>(R.id.first_launch_agree).setOnClickListener {
            AppServices.settings.markAgreementDone()
            // 首次启动完全套用「玻璃胶囊」预设。
            Presets.apply(AppServices.settings, Presets.GLASS)
            enterBrowser()
        }
    }

    private fun bindLegalLinks(view: View) {
        val links = view.findViewById<TextView>(R.id.first_launch_links)
        val terms = getString(R.string.legal_terms_title)
        val privacy = getString(R.string.legal_privacy_title)
        val text = getString(R.string.first_launch_links, terms, privacy)
        val spannable = SpannableString(text)
        fun clickable(target: String, type: String) {
            val start = text.indexOf(target)
            if (start < 0) return
            spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        parentFragmentManager.beginTransaction()
                            .replace(R.id.shell_root, LegalFragment.newInstance(type))
                            .addToBackStack(null)
                            .commit()
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = ThemeAccent.color(requireContext())
                        ds.isUnderlineText = false
                    }
                },
                start,
                start + target.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        clickable(terms, LegalFragment.TYPE_TERMS)
        clickable(privacy, LegalFragment.TYPE_PRIVACY)
        links.text = spannable
        links.movementMethod = LinkMovementMethod.getInstance()
        links.highlightColor = Color.TRANSPARENT
    }

    private fun enterBrowser() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.shell_root, BrowserFragment())
            .commit()
    }
}
