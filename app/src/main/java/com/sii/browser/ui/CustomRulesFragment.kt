// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sii.browser.R
import com.sii.browser.di.AppServices
import kotlinx.coroutines.launch

/**
 * 自定义规则编辑：多行 ABP 规则文本，保存后重载引擎。
 */
class CustomRulesFragment : Fragment() {

    private lateinit var container: LinearLayout

    override fun onDestroyView() {
        if (::container.isInitialized) container.removeAllViews()
        super.onDestroyView()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(requireContext().getColor(R.color.bg_page))
        addView(PageHeader.build(requireContext(), getString(R.string.adblock_custom), onBack = {
            parentFragmentManager.popBackStack()
        }))
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(CardList.scroll(requireContext(), content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        this@CustomRulesFragment.container = content
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            build(AppServices.ruleRepository.customRulesText())
        }
    }

    private fun build(initialText: String) {
        container.removeAllViews()
        container.addView(CardList.sectionTitle(requireContext(), getString(R.string.adblock_custom)))
        val card = CardList.styledCard(requireContext(), page = true)
        card.addView(TextView(requireContext()).apply {
            text = getString(R.string.adblock_custom_hint)
            setTextSize(12f)
            setTextColor(requireContext().getColor(R.color.text_secondary))
            setPadding(dp(16), dp(12), dp(16), dp(0))
        })
        val editor = EditText(requireContext()).apply {
            setText(initialText)
            minLines = 8
            gravity = android.view.Gravity.TOP
            hint = getString(R.string.rules_custom_hint)
            setTextSize(13f)
            setBackground(null)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        card.addView(editor)
        card.addView(TextView(requireContext()).apply {
            text = getString(R.string.rules_save)
            setTextSize(14f)
            setTextColor(ThemeAccent.color(requireContext()))
            setPadding(dp(16), dp(12), dp(16), dp(14))
            setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch {
                    AppServices.ruleRepository.saveCustomRules(editor.text.toString())
                    AppServices.ruleRepository.loadIntoEngine(AppServices.adBlock)
                    Toast.makeText(requireContext(), R.string.rules_saved, Toast.LENGTH_SHORT).show()
                }
            }
        })
        container.addView(card)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
