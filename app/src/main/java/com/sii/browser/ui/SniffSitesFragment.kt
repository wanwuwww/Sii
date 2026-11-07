// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sii.browser.R
import com.sii.browser.data.SiteConfig
import com.sii.browser.di.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 已启用资源嗅探的站点：默认所有站点关闭嗅探，只有这里列出的站点会记录资源。
 */
class SniffSitesFragment : Fragment() {

    private lateinit var container: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(requireContext().getColor(R.color.bg))
        }
        root.addView(
            PageHeader.build(
                requireContext(),
                getString(R.string.settings_sniff_sites),
                onBack = { parentFragmentManager.popBackStack() }
            )
        )
        root.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(requireContext().getColor(R.color.divider))
        })
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        root.addView(
            CardList.scroll(requireContext(), content),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        container = content
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()
        val hosts = AppServices.siteConfigRepository.cachedAll()
            .filterValues { SiteConfig.sniffEnabledRaw(it) }
            .keys
            .sorted()
        if (hosts.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = getString(R.string.sniff_sites_empty)
                setTextSize(13f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
                setPadding(dp(8), dp(16), dp(8), dp(8))
            })
            return
        }
        val card = CardList.pageCard(requireContext())
        CardList.addRows(card, hosts.map { host -> row(host) })
        container.addView(card)
    }

    private fun row(host: String): View {
        val name = TextView(requireContext()).apply {
            text = host
            setTextSize(14f)
            setTextColor(requireContext().getColor(R.color.text_primary))
            maxLines = 1
        }
        val remove = TextView(requireContext()).apply {
            text = getString(R.string.sniff_sites_remove)
            setTextSize(13f)
            setTextColor(ThemeAccent.color(requireContext()))
            setPadding(dp(10), dp(6), 0, dp(6))
            setOnClickListener { removeHost(host) }
        }
        return CardList.row(requireContext()).apply {
            addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(remove)
        }
    }

    private fun removeHost(host: String) {
        val text = SiteConfig.withSniffEnabled(
            AppServices.siteConfigRepository.cachedConfig(host), false
        )
        AppServices.siteConfigRepository.cachePut(host, text)
        lifecycleScope.launch(Dispatchers.IO) {
            AppServices.siteConfigRepository.put(host, text)
        }
        Toast.makeText(requireContext(), R.string.sniff_sites_removed, Toast.LENGTH_SHORT).show()
        render()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
