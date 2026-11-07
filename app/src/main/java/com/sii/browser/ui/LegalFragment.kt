// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.sii.browser.R

/**
 * 使用协议 / 隐私政策：读取 assets/legal 下的中文正文，离线展示。
 */
class LegalFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val type = arguments?.getString(ARG_TYPE) ?: TYPE_TERMS
        val privacy = type == TYPE_PRIVACY
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(R.color.bg))
        }
        root.addView(
            PageHeader.build(
                context,
                getString(if (privacy) R.string.legal_privacy_title else R.string.legal_terms_title),
                onBack = { parentFragmentManager.popBackStack() }
            )
        )
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(context.getColor(R.color.divider))
        })
        val text = runCatching {
            context.assets.open(if (privacy) "legal/privacy.txt" else "legal/terms.txt")
                .bufferedReader()
                .use { it.readText() }
        }.getOrDefault("")
        val content = TextView(context).apply {
            this.text = text
            setTextSize(14f)
            setTextColor(context.getColor(R.color.text_primary))
            setLineSpacing(CardList.dp(context, 5).toFloat(), 1.2f)
            setTextIsSelectable(true)
            setPadding(
                CardList.dp(context, 16),
                CardList.dp(context, 14),
                CardList.dp(context, 16),
                CardList.dp(context, 24)
            )
        }
        val card = CardList.pageCard(context).apply { addView(content) }
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(card)
        }
        root.addView(
            CardList.scroll(context, wrap),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return root
    }

    companion object {
        const val ARG_TYPE = "legal_type"
        const val TYPE_TERMS = "terms"
        const val TYPE_PRIVACY = "privacy"

        fun newInstance(type: String): LegalFragment = LegalFragment().apply {
            arguments = Bundle().apply { putString(ARG_TYPE, type) }
        }
    }
}
