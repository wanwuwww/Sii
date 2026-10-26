// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sii.browser.R
import com.sii.browser.di.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 网站设定：站点规则列表（按精确域名），支持添加、删除与进入单站设置。
 */
class SiteRulesFragment : Fragment() {

    private lateinit var container: LinearLayout

    override fun onDestroyView() {
        if (::container.isInitialized) container.removeAllViews()
        super.onDestroyView()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(R.color.bg_page))
        }
        root.addView(
            PageHeader.build(
                context,
                getString(R.string.settings_site_rules),
                onBack = { parentFragmentManager.popBackStack() },
                actionLabel = getString(R.string.site_rule_add_short),
                onAction = { showAddDialog() }
            )
        )
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(
            CardList.scroll(context, content),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        this@SiteRulesFragment.container = content
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        render()
    }

    private fun render() {
        container.removeAllViews()
        val hosts = AppServices.siteConfigRepository.cachedAll().keys.sorted()
        if (hosts.isEmpty()) {
            container.addView(CardList.styledCard(requireContext(), page = true).apply {
                addView(CardList.row(requireContext()).apply {
                    addView(TextView(requireContext()).apply {
                        text = getString(R.string.site_rules_empty)
                        setTextSize(13f)
                        setTextColor(requireContext().getColor(R.color.text_secondary))
                    })
                })
            })
        } else {
            val card = CardList.styledCard(requireContext(), page = true)
            CardList.addRows(card, hosts.map { hostRow(it) })
            container.addView(card)
        }
    }

    private fun hostRow(host: String): View {
        val enabled = runCatching {
            JSONObject(AppServices.siteConfigRepository.cachedConfig(host).orEmpty())
                .optBoolean("on", true)
        }.getOrDefault(true)
        return CardList.row(requireContext()).apply {
            addView(TextView(requireContext()).apply {
                text = host
                setTextSize(14f)
                setTextColor(requireContext().getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            addView(TextView(requireContext()).apply {
                text = getString(if (enabled) R.string.site_rule_on else R.string.site_rule_off)
                setTextSize(12f)
                setTextColor(
                    requireContext().getColor(
                        if (enabled) R.color.text_secondary else R.color.danger
                    )
                )
                setPadding(0, 0, dp(8), 0)
            })
            addView(TextView(requireContext()).apply {
                text = "›"
                setTextSize(18f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
            })
            setOnClickListener {
                NavAnim.apply(parentFragmentManager.beginTransaction())
                    .replace(R.id.shell_root, SiteRuleDetailFragment().apply {
                        arguments = bundleOf(SiteRuleDetailFragment.ARG_HOST to host)
                    })
                    .addToBackStack(null)
                    .commit()
            }
            setOnLongClickListener {
                confirmDelete(host)
                true
            }
        }
    }

    private fun showAddDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.site_rule_host_hint)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.site_rule_add)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val host = normalizeHost(input.text.toString())
                if (host.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            AppServices.siteConfigRepository.put(host, "{\"on\":true}")
                        }
                        AppServices.refreshAdBlockWhitelist()
                        render()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(host: String) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.site_rule_delete, host))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppServices.siteConfigRepository.remove(host)
                    }
                    AppServices.refreshAdBlockWhitelist()
                    render()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun normalizeHost(raw: String): String {
        var h = raw.trim().lowercase()
        h = h.removePrefix("https://").removePrefix("http://")
        h = h.substringBefore('/').substringBefore('?').substringBefore(':')
        return h
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
