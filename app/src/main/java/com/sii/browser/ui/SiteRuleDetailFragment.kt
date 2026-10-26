// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sii.browser.R
import com.sii.browser.data.SiteConfig
import com.sii.browser.data.WebPageBehavior
import com.sii.browser.di.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 单站设置：所有选项都是「跟随全局 / 显式覆盖」模型，
 * 未写入的键表示跟随全局，避免把默认值固化到站点。
 */
class SiteRuleDetailFragment : Fragment() {

    private lateinit var container: LinearLayout
    private val host: String get() = arguments?.getString(ARG_HOST).orEmpty()

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
        addView(
            PageHeader.build(
                requireContext(),
                host.ifEmpty { getString(R.string.site_rule_single_title) },
                onBack = { parentFragmentManager.popBackStack() }
            )
        )
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(
            CardList.scroll(requireContext(), content),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        this@SiteRuleDetailFragment.container = content
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        build()
    }

    private fun build() {
        container.removeAllViews()
        val config = readConfig()
        val master = config.optBoolean("on", true)

        container.addView(CardList.sectionTitle(requireContext(), host))

        card(
            switchRow(
                getString(R.string.site_rule_enable),
                config.optBoolean("on", true)
            ) { enabled ->
                setRaw("on", enabled)
                build()
            }
        )
        if (!config.optBoolean("on", true)) {
            container.addView(TextView(requireContext()).apply {
                text = getString(R.string.site_master_off_hint)
                setTextSize(11.5f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
                setPadding(dp(18), dp(2), dp(18), dp(8))
            })
        }

        container.addView(CardList.sectionTitle(requireContext(), getString(R.string.site_section_content)))
        card(
            fontRow(config, master),
            triRow(getString(R.string.site_rule_img), "img", config, master),
            triRow(getString(R.string.site_rule_js), "js", config, master),
            triRow(getString(R.string.settings_site_block_popup), "pop", config, master),
            triRow(getString(R.string.site_rule_dom), "dom", config, master),
            triRow(getString(R.string.site_rule_db), "db", config, master),
            triRow(getString(R.string.site_rule_file), "file", config, master),
            triRow(getString(R.string.site_rule_3p), "3p", config, master)
        )

        container.addView(CardList.sectionTitle(requireContext(), getString(R.string.site_section_basic)))
        card(
            triRow(getString(R.string.site_rule_ab), "ab", config, master),
            triRow(getString(R.string.site_rule_incognito), "incog", config, master)
        )

        container.addView(CardList.sectionTitle(requireContext(), getString(R.string.site_section_translate)))
        val btnLabels = arrayOf(
            getString(R.string.site_translate_follow),
            getString(R.string.site_translate_show),
            getString(R.string.site_translate_hide)
        )
        val modeLabels = arrayOf(
            getString(R.string.site_translate_follow),
            getString(R.string.site_translate_replace),
            getString(R.string.site_translate_dual)
        )
        val targetLabels = arrayOf(
            getString(R.string.site_translate_follow),
            getString(R.string.site_translate_zh),
            getString(R.string.site_translate_en)
        )
        val targetValue = SiteConfig.text(config, "trlang")
        card(
            choiceRow(
                getString(R.string.site_translate_button),
                btnLabels,
                when (SiteConfig.tri(config, "trbtn")) { 1 -> 1; 2 -> 2; else -> 0 },
                master
            ) { index ->
                when (index) {
                    0 -> removeKey("trbtn")
                    else -> setRaw("trbtn", index)
                }
                build()
            },
            choiceRow(
                getString(R.string.site_translate_mode),
                modeLabels,
                when (SiteConfig.tri(config, "trmode")) { 1 -> 1; 2 -> 2; else -> 0 },
                master
            ) { index ->
                when (index) {
                    0 -> removeKey("trmode")
                    else -> setRaw("trmode", index)
                }
                build()
            },
            choiceRow(
                getString(R.string.site_translate_target),
                targetLabels,
                when (targetValue) { "zh", "zh-CN" -> 1; "en" -> 2; else -> 0 },
                master
            ) { index ->
                when (index) {
                    0 -> removeKey("trlang")
                    1 -> setRaw("trlang", "zh")
                    else -> setRaw("trlang", "en")
                }
                build()
            }
        )

        container.addView(CardList.sectionTitle(requireContext(), getString(R.string.site_section_permissions)))
        card(
            permissionRow(getString(R.string.site_rule_mic), "mic", "perm", config, master),
            permissionRow(getString(R.string.site_rule_cam), "cam", "perm", config, master),
            permissionRow(getString(R.string.site_rule_clip), "clip", null, config, master),
            permissionRow(getString(R.string.site_rule_openapp), "openapp", null, config, master),
            permissionRow(getString(R.string.site_rule_redirect), "redirect", null, config, master),
            permissionRow(getString(R.string.site_rule_geo), "geo", null, config, master)
        )

        container.addView(CardList.sectionTitle(requireContext(), getString(R.string.site_section_advanced)))
        card(
            triRow(getString(R.string.site_rule_back_no_reload), "backnr", config, master),
            triRow(getString(R.string.settings_dnt), "dnt", config, master),
            triRow(getString(R.string.settings_save_data), "savedata", config, master),
            triRow(getString(R.string.settings_safe_browsing), "safebrowsing", config, master),
            triRow(getString(R.string.settings_webrtc_protect), "webrtc", config, master)
        )

        container.addView(TextView(requireContext()).apply {
            text = getString(R.string.site_reset)
            setTextSize(14f)
            setTextColor(requireContext().getColor(R.color.danger))
            setPadding(dp(18), dp(20), dp(18), dp(8))
            setOnClickListener { confirmReset() }
        })
    }

    // ---- 行构建 ----

    private fun fontRow(config: JSONObject, enabled: Boolean = true): View {
        val current = SiteConfig.intOpt(config, "fs")
        val value = TextView(requireContext()).apply {
            text = current?.let { "$it%" } ?: getString(
                R.string.site_follow_global_with_value,
                "${AppServices.settings.textSize()}%"
            )
            setTextSize(13f)
            setTextColor(requireContext().getColor(R.color.text_secondary))
        }
        return CardList.row(requireContext()).apply {
            alpha = if (enabled) 1f else 0.4f
            addView(TextView(requireContext()).apply {
                text = getString(R.string.site_rule_font)
                setTextSize(14f)
                setTextColor(requireContext().getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(value)
            setOnClickListener { if (enabled) showFontPicker(current) }
        }
    }

    private fun showFontPicker(current: Int?) {
        val initial = (current ?: AppServices.settings.textSize()).coerceIn(50, 200)
        val valueText = TextView(requireContext()).apply {
            text = "$initial%"
            setTextSize(14f)
            setTextColor(requireContext().getColor(R.color.text_primary))
            gravity = android.view.Gravity.CENTER
        }
        val seek = SeekBar(requireContext()).apply {
            max = (200 - 50) / 5
            progress = (initial - 50) / 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    valueText.text = "${50 + progress * 5}%"
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(valueText)
            addView(seek)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.site_rule_font)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                setRaw("fs", 50 + seek.progress * 5)
                build()
            }
            .setNeutralButton(R.string.site_follow_global) { _, _ ->
                removeKey("fs")
                build()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun triRow(title: String, key: String, config: JSONObject, enabled: Boolean = true): View {
        val labels = arrayOf(
            "跟随全局",
            getString(R.string.site_on),
            getString(R.string.site_off)
        )
        val selected = when (SiteConfig.tri(config, key)) {
            1 -> 1
            2 -> 2
            else -> 0
        }
        return choiceRow(
            title, labels, selected, enabled,
            desc = "全局当前：${globalOnOff(key)}"
        ) { index ->
            when (index) {
                0 -> removeKey(key)
                1 -> setRaw(key, 1)
                else -> setRaw(key, 2)
            }
        }
    }

    /** 单站开关：蓝色移动开关，下面小字显示全局开关状态，点小字恢复跟随。 */
    private fun triSwitchRow(
        title: String,
        key: String,
        config: JSONObject,
        enabled: Boolean = true
    ): View {
        val globalOn = globalSwitchOn(key)
        val site = SiteConfig.tri(config, key)
        val checked = when (site) {
            1 -> true
            2 -> false
            else -> globalOn
        }
        val context = requireContext()
        val switch = Switch(context).apply {
            isChecked = checked
            isEnabled = enabled
            trackDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.switch_track)
            thumbDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.switch_thumb)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(26))
            setOnCheckedChangeListener { _, value -> setRaw(key, if (value) 1 else 2) }
        }
        val hint = TextView(context).apply {
            text = if (globalOn) "全局开启" else "全局关闭"
            setTextSize(10.5f)
            setTextColor(context.getColor(R.color.text_secondary))
            alpha = 0.6f
            setPadding(0, dp(3), 0, 0)
            setOnClickListener {
                removeKey(key)
                build()
            }
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextSize(14f)
                setTextColor(context.getColor(R.color.text_primary))
            })
            addView(hint)
        }
        return CardList.row(context).apply {
            alpha = if (enabled) 1f else 0.4f
            addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(switch)
        }
    }

    /** 全局开关状态（不是“允许/阻止”的翻译结果）。 */
    private fun globalSwitchOn(key: String): Boolean = when (key) {
        "img" -> AppServices.settings.webPageBehavior()
            .isEnabled(WebPageBehavior.BIT_NO_IMAGE) || AppServices.settings.siteNoImage()
        "js" -> AppServices.settings.siteBlockJs()
        "pop" -> AppServices.settings.siteBlockPopup()
        "dom" -> AppServices.settings.siteBlockDom()
        "db" -> AppServices.settings.siteBlockDb()
        "file" -> AppServices.settings.siteBlockFile()
        "3p" -> AppServices.settings.thirdPartyCookiesBlocked()
        "ab" -> AppServices.adBlock.enabled
        "backnr" -> AppServices.settings.siteBackNoReload()
        "dnt" -> AppServices.settings.dntEnabled()
        "savedata" -> AppServices.settings.saveDataEnabled()
        "safebrowsing" -> AppServices.settings.safeBrowsing()
        "webrtc" -> AppServices.settings.webrtcProtect()
        else -> false
    }

    private fun permissionRow(
        title: String,
        key: String,
        legacyKey: String?,
        config: JSONObject,
        enabled: Boolean = true
    ): View {
        val labels = arrayOf(
            "跟随全局",
            "询问",
            getString(R.string.site_always_allow),
            getString(R.string.site_deny)
        )
        val selected = when (SiteConfig.permission(config, key, legacyKey)) {
            1, 3 -> 2
            2 -> 3
            4 -> 1
            else -> 0
        }
        return choiceRow(
            title, labels, selected, enabled,
            desc = "全局当前：${permissionGlobalLabel(key)}"
        ) { index ->
            when (index) {
                0 -> {
                    removeKey(key)
                    if (legacyKey != null) removeKey(legacyKey)
                }
                1 -> setRaw(key, 4)
                2 -> setRaw(key, 3)
                else -> setRaw(key, 2)
            }
        }
    }

    private fun permissionGlobalLabel(key: String): String = when (key) {
        "mic" -> when (AppServices.settings.permissionMic()) {
            1 -> "允许"; 2 -> "拒绝"; else -> "询问"
        }
        "cam" -> when (AppServices.settings.permissionCam()) {
            1 -> "允许"; 2 -> "拒绝"; else -> "询问"
        }
        "geo" -> when (AppServices.settings.permissionGeo()) {
            1 -> "允许"; 2 -> "拒绝"; else -> "询问"
        }
        "openapp" -> when (AppServices.settings.openAppPolicy()) {
            1 -> "允许"; 2 -> "拒绝"; else -> "询问"
        }
        "redirect" -> when (AppServices.settings.siteRedirectPolicy()) {
            1 -> "允许"; 2 -> "拒绝"; else -> "询问"
        }
        "clip" -> if (AppServices.settings.clipboardGuard()) "拒绝" else "允许"
        else -> "询问"
    }

    private fun globalOnOff(key: String): String = when (key) {
        "img" -> if (AppServices.settings.webPageBehavior()
                .isEnabled(WebPageBehavior.BIT_NO_IMAGE) ||
            AppServices.settings.siteNoImage()
        ) getString(R.string.site_off) else getString(R.string.site_on)
        "js" -> if (AppServices.settings.siteBlockJs()) getString(R.string.site_off)
        else getString(R.string.site_on)
        "pop" -> if (AppServices.settings.siteBlockPopup()) getString(R.string.site_off)
        else getString(R.string.site_on)
        "dom" -> if (AppServices.settings.siteBlockDom()) getString(R.string.site_off)
        else getString(R.string.site_on)
        "db" -> if (AppServices.settings.siteBlockDb()) getString(R.string.site_off)
        else getString(R.string.site_on)
        "file" -> if (AppServices.settings.siteBlockFile()) getString(R.string.site_off)
        else getString(R.string.site_on)
        "3p" -> if (AppServices.settings.thirdPartyCookiesBlocked())
            getString(R.string.site_off) else getString(R.string.site_on)
        "ab" -> if (AppServices.adBlock.enabled) getString(R.string.site_on)
        else getString(R.string.site_off)
        "incog" -> if (AppServices.privacyManager.isIncognito) getString(R.string.site_on)
        else getString(R.string.site_off)
        else -> getString(R.string.site_off)
    }

    private fun switchRow(title: String, checked: Boolean, onChanged: (Boolean) -> Unit): View {
        val switch = Switch(requireContext()).apply {
            isChecked = checked
            trackDrawable = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.switch_track)
            thumbDrawable = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.switch_thumb)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(26))
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        }
        return CardList.row(requireContext()).apply {
            addView(TextView(requireContext()).apply {
                text = title
                setTextSize(14f)
                setTextColor(requireContext().getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(switch)
        }
    }

    private fun choiceRow(
        title: String,
        labels: Array<String>,
        selected: Int,
        enabled: Boolean = true,
        desc: String? = null,
        onSelect: (Int) -> Unit
    ): View {
        val value = TextView(requireContext()).apply {
            text = labels.getOrElse(selected) { labels[0] }
            setTextSize(13f)
            setTextColor(requireContext().getColor(R.color.text_secondary))
        }
        val titleColumn = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(requireContext()).apply {
                text = title
                setTextSize(14f)
                setTextColor(requireContext().getColor(R.color.text_primary))
            })
            if (!desc.isNullOrBlank()) {
                addView(TextView(requireContext()).apply {
                    text = desc
                    setTextSize(10.5f)
                    setTextColor(requireContext().getColor(R.color.text_secondary))
                    alpha = 0.6f
                    setPadding(0, dp(3), 0, 0)
                })
            }
        }
        return CardList.row(requireContext()).apply {
            alpha = if (enabled) 1f else 0.4f
            addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
            setOnClickListener {
                if (!enabled) return@setOnClickListener
                val checked = selected.coerceIn(0, labels.lastIndex)
                AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setSingleChoiceItems(labels, checked) { dialog, which ->
                        value.text = labels[which]
                        onSelect(which)
                        dialog.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun card(vararg rows: View) {
        val card = CardList.pageCard(requireContext())
        CardList.addRows(card, rows.toList())
        container.addView(card)
    }

    // ---- 配置持久化 ----

    private fun readConfig(): JSONObject =
        SiteConfig.parse(AppServices.siteConfigRepository.cachedConfig(host)) ?: JSONObject()

    private fun setRaw(key: String, value: Any) {
        persist(readConfig().put(key, value))
    }

    private fun removeKey(key: String) {
        val config = readConfig()
        config.remove(key)
        persist(config)
    }

    private fun persist(config: JSONObject) {
        val text = config.toString()
        if (config.length() == 0) {
            AppServices.siteConfigRepository.cacheRemove(host)
        } else {
            AppServices.siteConfigRepository.cachePut(host, text)
        }
        AppServices.refreshAdBlockWhitelist()
        AppServices.reapplySiteSettings?.invoke()
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (config.length() == 0) {
                    AppServices.siteConfigRepository.remove(host)
                } else {
                    AppServices.siteConfigRepository.put(host, text)
                }
            }
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.site_reset)
            .setMessage(getString(R.string.site_reset_confirm, host))
            .setPositiveButton(R.string.site_reset) { _, _ ->
                AppServices.siteConfigRepository.cacheRemove(host)
                AppServices.refreshAdBlockWhitelist()
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppServices.siteConfigRepository.remove(host)
                    }
                }
                Toast.makeText(requireContext(), R.string.site_reset_done, Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ARG_HOST = "host"
    }
}
