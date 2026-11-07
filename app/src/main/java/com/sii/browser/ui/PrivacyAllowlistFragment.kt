// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.sii.browser.R
import com.sii.browser.di.AppServices

/** 已放行域名管理：查看、删除、手动添加，修改后立即刷新拦截引擎。 */
class PrivacyAllowlistFragment : Fragment() {

    private lateinit var container: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(R.color.bg))
        }
        root.addView(
            PageHeader.build(
                context,
                getString(R.string.privacy_allowlist_title),
                onBack = { parentFragmentManager.popBackStack() }
            )
        )
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(context.getColor(R.color.divider))
        })
        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(24))
        }
        root.addView(
            CardList.scroll(context, container),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        render()
    }

    private fun render() {
        container.removeAllViews()
        val context = requireContext()
        val hosts = AppServices.settings.privacyAllowedHosts().sorted()
        val card = CardList.card(context)

        card.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(TextView(context).apply {
                    text = getString(R.string.privacy_allowlist_desc)
                    setTextSize(12.5f)
                    setTextColor(context.getColor(R.color.text_secondary))
                    setLineSpacing(dp(3).toFloat(), 1.15f)
                })
                addView(TextView(context).apply {
                    text = getString(R.string.privacy_allowlist_add)
                    setTextSize(13f)
                    setTextColor(ThemeAccent.color(context))
                    setPadding(0, dp(8), 0, 0)
                    setOnClickListener { showAddDialog() }
                })
            }
        )

        if (hosts.isEmpty()) {
            card.addView(TextView(context).apply {
                text = getString(R.string.privacy_allowlist_empty)
                setTextSize(13f)
                setTextColor(context.getColor(R.color.text_secondary))
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
        } else {
            hosts.forEach { host -> card.addView(hostRow(host)) }
        }
        container.addView(card)
    }

    private fun hostRow(host: String): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(8), 0)
            addView(TextView(context).apply {
                text = host
                setTextSize(13.5f)
                setTextColor(context.getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_close)
                background = null
                contentDescription = getString(R.string.delete)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { removeHost(host) }
            })
        }
    }

    private fun removeHost(host: String) {
        val remaining = AppServices.settings.privacyAllowedHosts() - host
        AppServices.settings.setPrivacyAllowedHosts(remaining)
        AppServices.syncPrivacyAllowlist()
        render()
        Toast.makeText(
            requireContext(),
            getString(R.string.privacy_allowlist_removed, host),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showAddDialog() {
        val context = requireContext()
        val input = EditText(context).apply {
            hint = getString(R.string.privacy_allowlist_hint)
            setSingleLine()
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        android.app.AlertDialog.Builder(context)
            .setTitle(R.string.privacy_allowlist_add_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val host = normalize(input.text.toString())
                if (host == null) {
                    Toast.makeText(context, R.string.privacy_allowlist_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                AppServices.settings.setPrivacyAllowedHosts(
                    AppServices.settings.privacyAllowedHosts() + host
                )
                AppServices.syncPrivacyAllowlist()
                render()
                Toast.makeText(
                    context,
                    getString(R.string.privacy_allowlist_added, host),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun normalize(raw: String): String? {
        var value = raw.trim().lowercase()
        value = value.substringAfter("://", value)
        value = value.substringBefore('/').substringBefore('?').substringBefore('#')
        if (value.isEmpty() || !value.contains('.') || value.contains(' ')) return null
        if (value.any { !it.isLetterOrDigit() && it != '.' && it != '-' }) return null
        return value
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
