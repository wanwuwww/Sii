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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sii.browser.R
import com.sii.browser.core.privacy.PrivacyPacks
import com.sii.browser.data.PrivacyLevels
import com.sii.browser.data.Settings
import com.sii.browser.di.AppServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 设置页（二级/三级通用，按 [ARG_VIEW] 页面 id 渲染）。
 * 层级按 settings 树（setView → general/privacy/appearance/advanced/about → 各子页）。
 */
class SettingsDetailFragment : Fragment() {

    private lateinit var container: LinearLayout
    private var offlineStatus: TextView? = null
    private var offlineProgress: android.widget.ProgressBar? = null
    private val settings: Settings get() = AppServices.settings
    private val viewId: String get() = arguments?.getString(ARG_VIEW).orEmpty()

    /** 导入数据用的 SAF 文件选择器。 */
    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) applyImport(uri) }

    /** 自定义字体文件选择。 */
    private val fontLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val dir = java.io.File(requireContext().filesDir, "fonts").apply { mkdirs() }
            val target = java.io.File(dir, "custom_font")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            settings.setCustomFont(target.absolutePath)
            toast(R.string.font_imported)
        }.onFailure {
            toast(R.string.font_import_failed)
        }
        build()
    }

    /** 下载目录选择（SAF 目录树）。 */
    private val dirLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            settings.setDownloadDir(uri.toString())
            build()
        }
    }

    /** 主页背景图选择（SAF 图片）。 */
    private val bgLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            settings.setHomeBackground(uri.toString())
            build()
        }
    }

    override fun onPause() {
        super.onPause()
        AppServices.reapplyPrivacyIfChanged?.invoke()
    }

    override fun onDestroyView() {
        if (::container.isInitialized) container.removeAllViews()
        super.onDestroyView()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        this.container = view.findViewById(R.id.settings_container)
        view.findViewById<View>(R.id.settings_back)?.setOnClickListener {
            if (parentFragmentManager.isStateSaved) return@setOnClickListener
            parentFragmentManager.popBackStack()
        }
        view.findViewById<android.widget.TextView>(R.id.settings_title)?.text = titleFor(viewId)
        build()
    }

    /** 页面标题（页头显示）。 */
    private fun titleFor(pageId: String): String = when (pageId) {
        VIEW_GENERAL -> getString(R.string.settings_general)
        VIEW_ENGINE -> getString(R.string.settings_engine)
        VIEW_DL_SET -> getString(R.string.settings_download)
        VIEW_PRIVACY -> getString(R.string.settings_privacy)
        VIEW_APPEARANCE -> getString(R.string.settings_appearance)
        VIEW_HOME_SET -> getString(R.string.settings_home)
        VIEW_UI -> getString(R.string.settings_ui_layout)
        VIEW_MENU_CUSTOM -> getString(R.string.settings_menu_custom)
        VIEW_TOOLBAR_CUSTOM -> getString(R.string.settings_toolbar_custom)
        VIEW_LP_CUSTOM -> getString(R.string.settings_lp_custom)
        VIEW_THEME_COLOR -> getString(R.string.settings_theme_color_detect)
        VIEW_V2_COLOR_DEFINE -> getString(R.string.settings_color_define)
        VIEW_ADVANCED -> getString(R.string.settings_advanced)
        VIEW_DESKTOP_SITES -> getString(R.string.settings_desktop_sites)
        VIEW_V2_HOME -> getString(R.string.settings_home_group)
        VIEW_V2_BROWSE -> getString(R.string.settings_browse)
        VIEW_V2_TRANSLATE -> getString(R.string.settings_translate)
        VIEW_V2_TRANSLATE_BAIDU -> getString(R.string.settings_translate_baidu)
        VIEW_V2_TRANSLATE_CUSTOM -> getString(R.string.settings_translate_custom)
        VIEW_V2_TRANSLATE_PARAMS -> getString(R.string.settings_translate_params)
        VIEW_V2_TRANSLATE_LOG -> getString(R.string.settings_translate_log)
        VIEW_V2_TRANSLATE_OFFLINE -> getString(R.string.settings_translate_offline_pack)
        VIEW_V2_READING -> getString(R.string.settings_reading)
        VIEW_V2_READING_MODE -> getString(R.string.settings_reading_mode)
        VIEW_V2_NIGHT -> getString(R.string.settings_night_group)
        VIEW_V2_THEME_COLOR_GROUP -> getString(R.string.settings_theme_color)
        VIEW_V2_CARD -> getString(R.string.settings_card_group)
        VIEW_V2_TABS -> getString(R.string.settings_tabs_start)
        VIEW_V2_ADDRESS -> getString(R.string.settings_address_search)
        VIEW_V2_SUGGEST -> getString(R.string.settings_suggest_group)
        VIEW_V2_SUGGEST_ORDER -> getString(R.string.settings_suggest_order)
        VIEW_V2_DOWNLOAD -> getString(R.string.settings_download)
        VIEW_V2_OPERATE -> getString(R.string.settings_operate)
        VIEW_V2_OPERATE_HOLD -> getString(R.string.settings_toolbar_hold)
        VIEW_V2_FONT -> getString(R.string.settings_font)
        VIEW_V2_MENUS -> getString(R.string.settings_menus_toolbar)
        VIEW_V2_GESTURE -> getString(R.string.settings_gestures)
        VIEW_V2_HOME_LOGO -> getString(R.string.settings_home_logo)
        VIEW_V2_HOME_SITES -> getString(R.string.settings_home_sites)
        VIEW_V2_HOME_SEARCH -> getString(R.string.settings_home_search)
        VIEW_V2_HOME_BG -> getString(R.string.settings_home_background)
        VIEW_V2_UI_LAYOUT -> getString(R.string.settings_ui_layout)
        VIEW_V2_SITE -> getString(R.string.settings_site_settings)
        VIEW_V2_SITE_DEFAULT -> getString(R.string.settings_site_default)
        VIEW_V2_SITE_PERMISSIONS -> getString(R.string.settings_site_permissions)
        VIEW_V2_INCOG -> getString(R.string.settings_incognito)
        VIEW_V2_PRIVACY_PROTECTION -> getString(R.string.settings_privacy_protection)
        VIEW_V2_PASSWORD -> getString(R.string.settings_password)
        VIEW_V2_CLEAR -> getString(R.string.settings_clear_data)
        VIEW_V2_UA -> getString(R.string.settings_ua_group)
        VIEW_V2_NETWORK -> getString(R.string.settings_network)
        VIEW_V2_NETLOG -> getString(R.string.settings_netlog)
        VIEW_V2_IMPORT -> getString(R.string.settings_import_export)
        VIEW_V2_LAB -> getString(R.string.settings_lab)
        VIEW_V2_MCP -> getString(R.string.settings_mcp)
        VIEW_ABOUT -> getString(R.string.settings_about)
        VIEW_CRASH_LOG -> getString(R.string.settings_crash_log)
        else -> getString(R.string.settings)
    }

    private fun build() {
        container.removeAllViews()
        when (viewId) {            VIEW_GENERAL -> buildGeneral()
            VIEW_ENGINE -> buildEngine()
            VIEW_DL_SET -> buildDlSet()
            VIEW_PRIVACY -> buildPrivacy()
            VIEW_APPEARANCE -> buildAppearance()
            VIEW_HOME_SET -> buildHomeSet()
            VIEW_UI -> buildUi()
            VIEW_MENU_CUSTOM -> buildMenuCustom()
            VIEW_TOOLBAR_CUSTOM -> buildToolbarCustom()
            VIEW_LP_CUSTOM -> buildLongPressCustom()
            VIEW_THEME_COLOR -> buildThemeColor()
            VIEW_V2_COLOR_DEFINE -> buildV2ColorDefine()
            VIEW_ADVANCED -> buildAdvanced()
            VIEW_DESKTOP_SITES -> buildDesktopSites()
            VIEW_V2_HOME -> buildV2Home()
            VIEW_V2_BROWSE -> buildV2Browse()
            VIEW_V2_TRANSLATE -> buildV2Translate()
            VIEW_V2_TRANSLATE_BAIDU -> buildV2TranslateBaidu()
            VIEW_V2_TRANSLATE_CUSTOM -> buildV2TranslateCustom()
            VIEW_V2_TRANSLATE_PARAMS -> buildV2TranslateParams()
            VIEW_V2_TRANSLATE_LOG -> buildV2TranslateLog()
            VIEW_V2_TRANSLATE_OFFLINE -> buildV2TranslateOffline()
            VIEW_V2_READING -> buildV2Reading()
            VIEW_V2_READING_MODE -> buildV2ReadingMode()
            VIEW_V2_NIGHT -> buildV2Night()
            VIEW_V2_THEME_COLOR_GROUP -> buildV2ThemeColorGroup()
            VIEW_V2_CARD -> buildV2Card()
            VIEW_V2_TABS -> buildV2Tabs()
            VIEW_V2_ADDRESS -> buildV2Address()
            VIEW_V2_SUGGEST -> buildV2Suggest()
            VIEW_V2_SUGGEST_ORDER -> buildV2SuggestOrder()
            VIEW_V2_DOWNLOAD -> buildDlSet()
            VIEW_V2_OPERATE -> buildV2Operate()
            VIEW_V2_OPERATE_HOLD -> buildV2OperateHold()
            VIEW_V2_FONT -> buildV2Font()
            VIEW_V2_MENUS -> buildV2Menus()
            VIEW_V2_GESTURE -> buildV2Gesture()
            VIEW_V2_HOME_LOGO -> buildV2HomeLogo()
            VIEW_V2_HOME_SITES -> buildV2HomeSites()
            VIEW_V2_HOME_SEARCH -> buildV2HomeSearch()
            VIEW_V2_HOME_BG -> buildV2HomeBg()
            VIEW_V2_UI_LAYOUT -> buildUi()
            VIEW_V2_SITE -> buildV2Site()
            VIEW_V2_SITE_DEFAULT -> buildV2SiteDefault()
            VIEW_V2_SITE_PERMISSIONS -> buildV2SitePermissions()
            VIEW_V2_INCOG -> buildV2Incog()
            VIEW_V2_PRIVACY_PROTECTION -> buildV2PrivacyProtection()
            VIEW_V2_PASSWORD -> buildV2Password()
            VIEW_V2_CLEAR -> buildV2Clear()
            VIEW_V2_UA -> buildV2Ua()
            VIEW_V2_NETWORK -> buildV2Network()
            VIEW_V2_NETLOG -> buildV2Netlog()
            VIEW_V2_IMPORT -> buildV2Import()
            VIEW_V2_LAB -> buildV2Lab()
            VIEW_V2_MCP -> buildV2Mcp()
            VIEW_ABOUT -> buildAbout()
            VIEW_CRASH_LOG -> buildCrashLog()
        }
        focusRequestedRow()
    }

    // ---- 通用 ----

    /** 语言预设（存储仍是 language() 的字符串 tag；空 = 跟随系统）。 */
    private fun showLanguageDialog() {
        val current = settings.language().orEmpty()
        val entries = com.sii.browser.data.UiLanguages.ALL
        val labels = entries.map { entry ->
            val name = if (entry.tag.isEmpty()) {
                getString(R.string.settings_language_follow)
            } else {
                entry.label
            }
            if (entry.translated) name else name + getString(R.string.settings_language_in_progress)
        }.toMutableList()
        val selected = entries.indexOfFirst { it.tag == current }.takeIf { it >= 0 } ?: 0
        val customIndex = entries.size
        labels.add(getString(R.string.settings_language_custom))
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, which ->
                if (which == customIndex) {
                    dialog.dismiss()
                    showLanguageCustomInput()
                    return@setSingleChoiceItems
                }
                val entry = entries[which]
                if (!entry.translated) {
                    Toast.makeText(
                        requireContext(),
                        R.string.settings_language_in_progress,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setSingleChoiceItems
                }
                dialog.dismiss()
                applyLanguage(entry.tag)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLanguageCustomInput() {
        val input = EditText(requireContext()).apply {
            setText(settings.language().orEmpty())
            hint = "zh-CN"
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_language_custom)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val tag = input.text.toString().trim()
                dialog.dismiss()
                applyLanguage(tag)
            }
        }
        dialog.show()
    }

    private fun applyLanguage(tag: String) {
        settings.setLanguage(tag.ifEmpty { null })
        // 语言变更作用于 attachBaseContext，需重建 Activity 才能生效。
        recreateAfterSave()
    }

    /** 重建前把最近标签快照同步落库，避免 Settings 页 recreate 后按旧快照恢复。 */
    private fun recreateAfterSave() {
        val snapshot = AppServices.lastTabsSnapshot
        if (snapshot != null && !AppServices.privacyManager.isIncognito) {
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    AppServices.tabRepository.save(snapshot)
                }
                if (isAdded) activity?.recreate()
            }
            return
        }
        activity?.recreate()
    }

    private fun sectionTitle(resId: Int) {
        container.addView(CardList.sectionTitle(requireContext(), getString(resId)))
    }

    private fun languageLabel(tag: String): String {
        if (tag.isBlank()) return getString(R.string.settings_language_follow)
        val entry = com.sii.browser.data.UiLanguages.ALL.firstOrNull { it.tag == tag }
        return entry?.label ?: tag
    }

    private fun buildGeneral() {
        sectionTitle(R.string.settings_section_search)
        card(
            navRow(
                getString(R.string.settings_language),
                languageLabel(settings.language().orEmpty())
            ) { showLanguageDialog() },
            navRow(getString(R.string.settings_engine), getString(R.string.settings_engine_desc)) {
                open(VIEW_ENGINE)
            }
        )
        sectionTitle(R.string.settings_section_default_apps)
        card(
            navRow(
                getString(R.string.settings_default_browser),
                getString(R.string.settings_default_browser_desc)
            ) { openDefaultAppsSettings() }
        )
        sectionTitle(R.string.settings_section_content)
        card(
            navRow(getString(R.string.settings_translate)) { open(VIEW_V2_TRANSLATE) },
            navRow(getString(R.string.settings_reading)) { open(VIEW_V2_READING) },
            switchRow(
                getString(R.string.settings_clipboard_guard),
                settings.clipboardGuard(),
                getString(R.string.settings_clipboard_guard_desc)
            ) { settings.setClipboardGuard(it) }
        )
        sectionTitle(R.string.settings_section_site_script)
        card(
            navRow(getString(R.string.settings_site_default)) { open(VIEW_V2_SITE_DEFAULT) },
            navRow(getString(R.string.settings_site_rules)) { openFragment(SiteRulesFragment()) },
            navRow(getString(R.string.settings_desktop_sites)) { open(VIEW_DESKTOP_SITES) },
            navRow(getString(R.string.settings_scripts)) { openFragment(ScriptsFragment()) }
        )
        sectionTitle(R.string.settings_section_download)
        card(
            navRow(getString(R.string.settings_download)) { open(VIEW_V2_DOWNLOAD) },
            switchRow(
                getString(R.string.settings_wifi_img),
                settings.wifiImagesOnly(),
                getString(R.string.settings_wifi_img_desc)
            ) { settings.setWifiImagesOnly(it) }
        )
        sectionTitle(R.string.settings_habits)
        card(
            navRow(getString(R.string.settings_gestures)) { open(VIEW_V2_GESTURE) },
            navRow(getString(R.string.settings_lp_custom)) { open(VIEW_LP_CUSTOM) },
            navRow(getString(R.string.settings_operate)) { open(VIEW_V2_OPERATE) },
            choiceRow(
                getString(R.string.settings_screen_orientation),
                ORIENTATIONS,
                settings.screenOrientation()
            ) {
                settings.setScreenOrientation(it)
                (activity as? ShellActivity)?.applyOrientation()
            },
            switchRow(getString(R.string.settings_keep_on), settings.keepOn()) {
                settings.setKeepOn(it)
            },
            switchRow(
                getString(R.string.settings_sniff),
                settings.sniffEnabled(),
                getString(R.string.settings_sniff_desc)
            ) { settings.setSniffEnabled(it) },
            navRow(getString(R.string.settings_sniff_sites)) {
                openFragment(SniffSitesFragment())
            }
        )
        aboutText(R.string.about_general)
    }

    private fun buildV2Translate() {
        val mode = settings.translateMode().coerceIn(0, 2)
        card(
            switchRow(
                getString(R.string.settings_translate_cloud),
                mode == 1,
                getString(R.string.settings_translate_cloud_desc)
            ) {
                settings.setTranslateMode(if (it) 1 else 0)
                build()
            },
            switchRow(
                getString(R.string.settings_translate_offline),
                mode == 2,
                getString(R.string.settings_translate_offline_desc)
            ) {
                settings.setTranslateMode(if (it) 2 else 0)
                build()
            },
            navRow(getString(R.string.settings_translate_offline_pack)) {
                open(VIEW_V2_TRANSLATE_OFFLINE)
            },
            choiceRow(
                getString(R.string.settings_translate_service),
                TRANSLATE_PROVIDER_NAMES,
                settings.translateProvider().coerceIn(0, 4),
                enabled = mode == 1
            ) {
                settings.setTranslateProvider(it)
                build()
            },
            textRow(
                getString(R.string.settings_translate_endpoint),
                settings.translateEndpoint(),
                enabled = mode == 1 && (settings.translateProvider() == 1 || settings.translateProvider() == 3)
            ) { settings.setTranslateEndpoint(it) },
            navRow(
                getString(R.string.settings_translate_baidu),
                enabled = mode == 1 && settings.translateProvider() == 4
            ) { open(VIEW_V2_TRANSLATE_BAIDU) },
            navRow(
                getString(R.string.settings_translate_custom),
                enabled = mode == 1 && settings.translateProvider() == 3
            ) { open(VIEW_V2_TRANSLATE_CUSTOM) },
            navRow(
                getString(R.string.settings_translate_target),
                translateTargetLabel()
            ) { showTranslateTargetDialog() },
            switchRow(
                getString(R.string.settings_translate_detect),
                settings.translateDetect(),
                getString(R.string.settings_translate_detect_desc)
            ) { settings.setTranslateDetect(it) },
            switchRow(
                getString(R.string.settings_translate_dual),
                settings.translateDual(),
                getString(R.string.settings_translate_dual_desc)
            ) { settings.setTranslateDual(it) },
            navRow(getString(R.string.settings_translate_params)) {
                open(VIEW_V2_TRANSLATE_PARAMS)
            },
            navRow(getString(R.string.settings_translate_log)) {
                open(VIEW_V2_TRANSLATE_LOG)
            }
        )
    }

    private fun buildV2TranslateBaidu() {
        card(
            textRow(
                getString(R.string.settings_translate_baidu_appid),
                settings.translateBaiduAppId()
            ) { settings.setTranslateBaiduAppId(it) },
            textRow(
                getString(R.string.settings_translate_baidu_key),
                settings.translateBaiduKey()
            ) { settings.setTranslateBaiduKey(it) }
        )
    }

    private fun buildV2TranslateCustom() {
        card(
            choiceRow(
                getString(R.string.settings_translate_custom_method),
                TRANSLATE_CUSTOM_METHOD_NAMES,
                settings.translateCustomMethod().coerceIn(0, 1)
            ) { settings.setTranslateCustomMethod(it) },
            textRow(
                getString(R.string.settings_translate_custom_headers),
                settings.translateCustomHeaders()
            ) { settings.setTranslateCustomHeaders(it) },
            textRow(
                getString(R.string.settings_translate_custom_body),
                settings.translateCustomBody()
            ) { settings.setTranslateCustomBody(it) },
            textRow(
                getString(R.string.settings_translate_custom_path),
                settings.translateCustomPath()
            ) { settings.setTranslateCustomPath(it) }
        )
    }

    private fun buildV2TranslateOffline() {
        val context = requireContext()
        val translator = com.sii.browser.core.webview.OfflineTranslator
        val downloading = translator.downloading
        val status = TextView(context).apply {
            setTextSize(13f)
            setTextColor(context.getColor(R.color.text_secondary))
            text = if (downloading) {
                getString(
                    R.string.settings_translate_offline_progress,
                    translator.progressPercent,
                    translator.progressIndex,
                    translator.progressCount
                )
            } else {
                getString(R.string.settings_translate_offline_checking)
            }
        }
        val progress = android.widget.ProgressBar(
            context, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = translator.progressPercent
            progressTintList = android.content.res.ColorStateList.valueOf(
                ThemeAccent.color(context)
            )
            visibility = if (downloading) View.VISIBLE else View.GONE
        }
        offlineStatus = status
        offlineProgress = progress
        val statusBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
            addView(status)
            addView(
                progress,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(4)
                ).apply { topMargin = dpToPx(6) }
            )
        }
        card(statusBox)

        val valueViews = mutableMapOf<String, TextView>()
        val rows = com.sii.browser.core.webview.TranslateLanguages.ALL.map { lang ->
            val value = valueView("…")
            valueViews[lang.tag] = value
            CardList.row(context).apply {
                addView(titleView(lang.label), weightParams())
                addView(value)
                setOnClickListener { onOfflineLanguageClick(lang) }
                setOnLongClickListener {
                    confirmDeleteLanguage(lang)
                    true
                }
            }
        }
        card(*rows.toTypedArray())
        card(
            navRow(getString(R.string.settings_translate_offline_delete_all)) {
                confirmDeleteAllLanguages()
            }
        )
        aboutText(R.string.settings_translate_offline_note)
        if (!downloading) refreshOfflineStatuses(valueViews)
    }

    private fun refreshOfflineStatuses(valueViews: Map<String, TextView>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val translator = com.sii.browser.core.webview.OfflineTranslator
            val languages = com.sii.browser.core.webview.TranslateLanguages.ALL
            val states = languages.map { lang ->
                lang.tag to translator.isModelReady(requireContext(), lang.mlkit)
            }
            if (!isAdded) return@launch
            var ready = 0
            states.forEach { (tag, isReady) ->
                if (isReady) ready++
                valueViews[tag]?.apply {
                    text = getString(
                        if (isReady) R.string.settings_translate_offline_downloaded
                        else R.string.settings_translate_offline_not_downloaded
                    )
                    setTextColor(
                        if (isReady) ThemeAccent.color(requireContext())
                        else requireContext().getColor(R.color.text_secondary)
                    )
                }
            }
            offlineStatus?.text = getString(
                R.string.settings_translate_offline_status,
                ready,
                languages.size
            )
        }
    }

    private fun onOfflineLanguageClick(lang: com.sii.browser.core.webview.TranslateLanguages.Lang) {
        if (com.sii.browser.core.webview.OfflineTranslator.downloading) {
            Toast.makeText(
                requireContext(),
                R.string.settings_translate_offline_downloading,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ready = com.sii.browser.core.webview.OfflineTranslator
                .isModelReady(requireContext(), lang.mlkit)
            if (!isAdded) return@launch
            if (ready) confirmDeleteLanguage(lang) else downloadLanguage(lang)
        }
    }

    private fun downloadLanguage(lang: com.sii.browser.core.webview.TranslateLanguages.Lang) {
        offlineStatus?.text = getString(R.string.settings_translate_offline_progress, 0, 0, 1)
        offlineProgress?.apply {
            progress = 0
            visibility = View.VISIBLE
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = com.sii.browser.core.webview.OfflineTranslator.downloadModels(
                requireContext(),
                listOf(lang.mlkit)
            ) { percent, index, count, _, _ ->
                view?.post {
                    if (!isAdded) return@post
                    offlineProgress?.progress = percent
                    offlineStatus?.text = getString(
                        R.string.settings_translate_offline_progress,
                        percent, index, count
                    )
                }
            }
            if (!isAdded) return@launch
            if (!ok) {
                offlineProgress?.visibility = View.GONE
                offlineStatus?.text = getString(R.string.settings_translate_offline_download_failed)
            }
            Toast.makeText(
                requireContext(),
                if (ok) R.string.settings_translate_offline_downloaded
                else R.string.settings_translate_offline_download_failed,
                Toast.LENGTH_SHORT
            ).show()
            build()
        }
    }

    private fun confirmDeleteLanguage(lang: com.sii.browser.core.webview.TranslateLanguages.Lang) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.settings_translate_offline_delete_one, lang.label))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    com.sii.browser.core.webview.OfflineTranslator.deleteModels(
                        requireContext(), listOf(lang.mlkit)
                    )
                    if (!isAdded) return@launch
                    Toast.makeText(
                        requireContext(),
                        R.string.settings_translate_offline_deleted,
                        Toast.LENGTH_SHORT
                    ).show()
                    build()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteAllLanguages() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_translate_offline_delete_all)
            .setMessage(R.string.settings_translate_offline_delete_all_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    com.sii.browser.core.webview.OfflineTranslator.deleteModels(
                        requireContext(),
                        com.sii.browser.core.webview.TranslateLanguages.ALL.map { it.mlkit }
                    )
                    if (!isAdded) return@launch
                    Toast.makeText(
                        requireContext(),
                        R.string.settings_translate_offline_deleted,
                        Toast.LENGTH_SHORT
                    ).show()
                    build()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildV2TranslateParams() {
        card(
            numberRow(
                getString(R.string.settings_translate_collect),
                settings.translateCollect(), 1, 2000,
                getString(R.string.settings_translate_collect_desc)
            ) { settings.setTranslateCollect(it) },
            numberRow(
                getString(R.string.settings_translate_scan),
                settings.translateScan(), 1, 500,
                getString(R.string.settings_translate_scan_desc)
            ) { settings.setTranslateScan(it) },
            decimalRow(
                getString(R.string.settings_translate_interval),
                settings.translateIntervalMs(), 100, 10000,
                getString(R.string.settings_translate_interval_desc)
            ) { settings.setTranslateIntervalMs(it) },
            numberRow(
                getString(R.string.settings_translate_chunk),
                settings.translateChunk(), 500, 8000,
                getString(R.string.settings_translate_chunk_desc)
            ) { settings.setTranslateChunk(it) },
            numberRow(
                getString(R.string.settings_translate_limit),
                settings.translateLimit(), 0, 100000,
                getString(R.string.settings_translate_limit_desc)
            ) { settings.setTranslateLimit(it) }
        )
    }

    private fun buildV2TranslateLog() {
        card(
            navRow(getString(R.string.translate_log_export)) { exportTranslateLog() },
            navRow(getString(R.string.translate_log_clear)) {
                com.sii.browser.core.webview.TranslationTrace.clear(requireContext())
                build()
            }
        )
        val text = com.sii.browser.core.webview.TranslationTrace.read(requireContext())
        if (text.isBlank()) {
            aboutText(R.string.translate_log_empty)
            return
        }
        card(TextView(requireContext()).apply {
            this.text = text
            setTextSize(10.5f)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(requireContext().getColor(R.color.text_primary))
            setTextIsSelectable(true)
            setPadding(
                CardList.dp(requireContext(), 12),
                CardList.dp(requireContext(), 10),
                CardList.dp(requireContext(), 12),
                CardList.dp(requireContext(), 10)
            )
        })
    }

    private fun exportTranslateLog() {
        val text = com.sii.browser.core.webview.TranslationTrace.read(requireContext())
        if (text.isBlank()) {
            Toast.makeText(requireContext(), R.string.translate_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                FileExchange.writeToDownloads(
                    requireContext(),
                    "sii-translate-${System.currentTimeMillis()}.txt",
                    text
                )
            }
            Toast.makeText(
                requireContext(),
                if (path != null) R.string.crash_log_exported else R.string.crash_log_export_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun buildV2Reading() {
        card(
            switchRow(
                getString(R.string.settings_auto_expand),
                settings.autoExpand(),
                getString(R.string.settings_auto_expand_desc)
            ) { settings.setAutoExpand(it) },
            navRow(getString(R.string.settings_reading_mode)) {
                open(VIEW_V2_READING_MODE)
            }
        )
    }

    private fun buildV2ReadingMode() {
        card(
            choiceRow(
                getString(R.string.settings_reader_theme),
                READER_THEMES,
                settings.readerThemeColor().coerceIn(0, 3)
            ) { settings.setReaderThemeColor(it) },
            numberRow(
                getString(R.string.settings_reader_size),
                settings.readerTextSize(), 0, 200,
                getString(R.string.settings_reader_size_desc)
            ) { settings.setReaderTextSize(it) },
            textRow(
                getString(R.string.settings_reader_css),
                settings.readerCustomCss().orEmpty()
            ) { settings.setReaderCustomCss(it.ifBlank { null }) }
        )
    }

    private fun buildDlSet() {
        val dirValue = settings.downloadDir()?.let { getString(R.string.settings_download_dir_set) }
            ?: getString(R.string.settings_download_dir_default)
        val builtin = settings.downloader() == 0
        card(
            choiceRow(
                getString(R.string.settings_downloader),
                DOWNLOADER_NAMES,
                settings.downloader()
            ) {
                settings.setDownloader(it)
                build()
            },
            choiceRow(
                getString(R.string.settings_download_protocol),
                DOWNLOAD_PROTOCOL_NAMES,
                settings.downloadProtocol().coerceIn(0, 2),
                getString(R.string.settings_download_protocol_desc),
                enabled = builtin
            ) { settings.setDownloadProtocol(it) },
            choiceRow(
                getString(R.string.settings_download_parallel),
                DOWNLOAD_PARALLEL_NAMES,
                settings.downloadParallel().coerceIn(1, 4) - 1,
                getString(R.string.settings_download_parallel_desc),
                enabled = builtin
            ) { index -> settings.setDownloadParallel(index + 1) },
            choiceRow(
                getString(R.string.settings_download_progress_style),
                arrayOf(
                    getString(R.string.download_progress_wave),
                    getString(R.string.download_progress_line),
                    getString(R.string.download_progress_segment),
                    getString(R.string.download_progress_shimmer),
                    getString(R.string.download_progress_percent),
                    getString(R.string.download_progress_dots),
                    getString(R.string.download_progress_fill)
                ),
                settings.downloadProgressStyle().coerceIn(0, 6)
            ) { settings.setDownloadProgressStyle(it) },
            switchRow(
                getString(R.string.settings_apk_rename),
                settings.apkRenameToLabel(),
                getString(R.string.settings_apk_rename_desc),
                enabled = builtin
            ) { settings.setApkRenameToLabel(it) },
            switchRow(
                getString(R.string.settings_download_saf_direct),
                settings.downloadSafDirect(),
                getString(R.string.settings_download_saf_direct_desc),
                enabled = builtin
            ) { settings.setDownloadSafDirect(it) },
            switchRow(
                getString(R.string.settings_download_confirm),
                settings.downloadConfirm(),
                enabled = builtin
            ) { settings.setDownloadConfirm(it) },
            switchRow(
                getString(R.string.settings_download_auto_open),
                settings.downloadAutoOpen(),
                enabled = builtin
            ) { settings.setDownloadAutoOpen(it) },
            switchRow(
                getString(R.string.settings_download_hls_mp4),
                settings.hlsToMp4(),
                enabled = builtin
            ) { settings.setHlsToMp4(it) },
            switchRow(
                getString(R.string.settings_download_retry),
                settings.downloadRetry(),
                enabled = builtin
            ) { settings.setDownloadRetry(it) },
            switchRow(
                getString(R.string.settings_download_wifi_only),
                settings.downloadWifiOnly()
            ) { settings.setDownloadWifiOnly(it) },
            navRow(
                getString(R.string.settings_download_dir),
                dirValue,
                enabled = builtin
            ) { dirLauncher.launch(null) },
            navRow(
                getString(R.string.settings_video_player),
                settings.videoPlayer()?.let { videoPlayerLabel(it) }
                    ?: getString(R.string.settings_video_player_system)
            ) { showVideoPlayerPicker() }
        )
        aboutText(R.string.about_download)
    }

    private fun videoPlayerLabel(pkg: String): String = runCatching {
        val pm = requireContext().packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun showVideoPlayerPicker() {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            type = "video/*"
        }
        val pm = requireContext().packageManager
        val apps = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
        val labels = mutableListOf(getString(R.string.settings_video_player_system))
        val values = mutableListOf<String?>(null)
        apps.forEach { (pkg, label) ->
            labels.add(label)
            values.add(pkg)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_video_player)
            .setItems(labels.toTypedArray()) { _, which ->
                settings.setVideoPlayer(values[which])
                build()
            }
            .show()
    }

    private fun buildEngine() {
        val custom = readCustomEngines()
        val all = (ENGINES + custom).map { it.first to com.sii.browser.data.SearchEngines.normalize(it.second) }
        val current = com.sii.browser.data.SearchEngines.normalize(
            settings.searchEngineUrl().orEmpty()
        )
        val names = all.map { it.first }.toTypedArray()
        val selected = all.indexOfFirst { it.second == current }.coerceAtLeast(0)
        card(
            choiceRow(getString(R.string.settings_engine), names, selected) {
                settings.setSearchEngineUrl(all[it].second)
            },
            textRow(getString(R.string.settings_search_engine), current) {
                settings.setSearchEngineUrl(com.sii.browser.data.SearchEngines.normalize(it))
            },
            navRow(getString(R.string.settings_suggest_group)) { open(VIEW_V2_SUGGEST) },
            switchRow(getString(R.string.settings_search_toolbar), settings.searchToolbar()) {
                settings.setSearchToolbar(it)
            },
            choiceRow(
                getString(R.string.settings_quick_engine_position),
                arrayOf("地址栏下方", "工具栏上方"),
                settings.quickEnginePosition().coerceIn(0, 1),
                enabled = settings.uiStyle() != 1
            ) { settings.setQuickEnginePosition(it) },
        )

        // 自定义搜索引擎：直接在本页列出（可删除）+ 添加入口
        container.addView(CardList.sectionTitle(requireContext(), getString(R.string.settings_engine_custom)))
        val rows = custom.map { (name, url) ->
            navRow(name, url) {
                AlertDialog.Builder(requireContext())
                    .setMessage(getString(R.string.delete) + " " + name)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        writeCustomEngines(custom.filterNot { it.first == name && it.second == url })
                        build()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }.toMutableList<View>()
        rows.add(navRow(getString(R.string.settings_engine_add)) {
            val nameInput = EditText(requireContext()).apply { hint = getString(R.string.settings_engine_name) }
            val urlInput = EditText(requireContext()).apply {
                hint = getString(R.string.settings_engine_url_hint)
            }
            val column = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                addView(nameInput)
                addView(urlInput)
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_engine_add)
                .setView(column)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val name = nameInput.text.toString().trim()
                    val url = urlInput.text.toString().trim()
                    if (name.isNotEmpty() && url.startsWith("http")) {
                        writeCustomEngines(
                            custom + (name to com.sii.browser.data.SearchEngines.normalize(url))
                        )
                        build()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        })
        card(*rows.toTypedArray())
    }

    private fun readCustomEngines(): List<Pair<String, String>> {
        val raw = settings.searchEngines() ?: return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val name = item.optString("name")
                val url = item.optString("url")
                if (name.isEmpty() || url.isEmpty()) null else name to url
            }
        }.getOrDefault(emptyList())
    }

    private fun writeCustomEngines(engines: List<Pair<String, String>>) {
        val array = org.json.JSONArray()
        engines.forEach { (name, url) ->
            array.put(org.json.JSONObject().apply { put("name", name); put("url", url) })
        }
        settings.setSearchEngines(array.toString())
    }

    // ---- 主页（新结构） ----

    private fun buildV2Home() {
        val rows = mutableListOf<View>()
        rows.add(homeChoiceRow())
        if (settings.homeUrl() != "about:home") {
            rows.add(
                textRow(getString(R.string.settings_home_url), settings.homeUrl()) {
                    settings.setHomeUrl(it.ifBlank { "about:home" })
                    build()
                }
            )
        }
        rows.add(navRow(getString(R.string.settings_home_logo)) { open(VIEW_V2_HOME_LOGO) })
        rows.add(navRow(getString(R.string.settings_home_sites)) { open(VIEW_V2_HOME_SITES) })
        rows.add(navRow(getString(R.string.settings_home_search)) { open(VIEW_V2_HOME_SEARCH) })
        rows.add(navRow(getString(R.string.settings_home_background)) { open(VIEW_V2_HOME_BG) })
        card(*rows.toTypedArray())
    }

    private fun homeChoiceRow(): View = choiceRow(
        getString(R.string.settings_home),
        arrayOf(getString(R.string.settings_home_sii), getString(R.string.settings_home_custom)),
        if (settings.homeUrl() == "about:home") 0 else 1
    ) { index ->
        if (index == 0) {
            settings.setHomeUrl("about:home")
            build()
        } else {
            val input = EditText(requireContext()).apply {
                setText(settings.homeUrl().takeIf { it != "about:home" }.orEmpty())
                hint = "https://example.com"
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_home_url)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    settings.setHomeUrl(input.text.toString().trim().ifBlank { "about:home" })
                    build()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // ---- 浏览（新结构） ----

    private fun openDefaultAppsSettings() {
        val intent = android.content.Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")
        runCatching { startActivity(intent) }.onFailure {
            runCatching {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${requireContext().packageName}")
                    )
                )
            }
        }
    }

    private fun buildV2Browse() {
        card(
            navRow(getString(R.string.settings_tabs_start)) { open(VIEW_V2_TABS) },
            navRow(getString(R.string.settings_address_search)) { open(VIEW_V2_ADDRESS) },
            navRow(getString(R.string.settings_download), getString(R.string.settings_download_desc)) {
                open(VIEW_V2_DOWNLOAD)
            },
            navRow(getString(R.string.settings_scripts)) { openFragment(ScriptsFragment()) },
            navRow(getString(R.string.settings_operate)) { open(VIEW_V2_OPERATE) },
            switchRow(
                getString(R.string.settings_wifi_img),
                settings.wifiImagesOnly(),
                getString(R.string.settings_wifi_img_desc)
            ) { settings.setWifiImagesOnly(it) },
            switchRow(
                getString(R.string.settings_sniff),
                settings.sniffEnabled(),
                getString(R.string.settings_sniff_desc)
            ) { settings.setSniffEnabled(it) },
            navRow(getString(R.string.settings_sniff_sites)) {
                openFragment(SniffSitesFragment())
            }
        )
    }

    private fun buildV2Tabs() {
        card(
            choiceRow(
                getString(R.string.settings_startup_behavior),
                STARTUP_BEHAVIOR_NAMES,
                settings.startupBehavior().coerceIn(0, 2)
            ) { settings.setStartupBehavior(it) }
        )
    }

    private fun buildV2Address() {
        card(
            navRow(getString(R.string.settings_suggest_group)) { open(VIEW_V2_SUGGEST) }
        )
    }

    private fun buildV2Suggest() {
        card(
            switchRow(
                getString(R.string.settings_search_suggest),
                settings.searchSuggestion() != 0,
                getString(R.string.settings_search_suggest_desc)
            ) {
                settings.setSearchSuggestion(if (it) 1 else 0)
            },
            switchRow(
                getString(R.string.settings_auto_fill_clipboard),
                settings.autoFillClipboard(),
                getString(R.string.settings_auto_fill_clipboard_desc)
            ) { settings.setAutoFillClipboard(it) },
            switchRow(
                getString(R.string.settings_search_suggest_history),
                settings.searchSuggestHistory()
            ) {
                settings.setSearchSuggestHistory(it)
            },
            switchRow(
                getString(R.string.settings_search_suggest_bookmarks),
                settings.searchSuggestBookmarks()
            ) {
                settings.setSearchSuggestBookmarks(it)
            },
            switchRow(
                getString(R.string.settings_search_suggest_online),
                settings.searchSuggestOnline(),
                getString(R.string.settings_search_suggest_online_desc)
            ) {
                settings.setSearchSuggestOnline(it)
            },
            choiceRow(
                getString(R.string.settings_suggestion_count),
                SUGGESTION_COUNTS.map { it.toString() }.toTypedArray(),
                SUGGESTION_COUNTS.indexOf(settings.suggestionCount()).coerceAtLeast(0)
            ) { index -> settings.setSuggestionCount(SUGGESTION_COUNTS[index]) },
            navRow(
                getString(R.string.settings_suggest_order),
                getString(R.string.settings_suggest_order_desc)
            ) { open(VIEW_V2_SUGGEST_ORDER) }
        )
    }

    private fun buildV2SuggestOrder() {
        val keys = listOf("clipboard", "history", "bookmark", "search")
        val labels = mapOf(
            "clipboard" to getString(R.string.settings_suggest_order_clipboard),
            "history" to getString(R.string.settings_suggest_order_history),
            "bookmark" to getString(R.string.settings_suggest_order_bookmark),
            "search" to getString(R.string.settings_suggest_order_search)
        )
        val order = settings.suggestOrder().split(",")
            .map { it.trim() }
            .filter { it in keys }
            .distinct()
            .toMutableList()
        keys.forEach { if (it !in order) order.add(it) }

        container.addView(CardList.sectionTitle(requireContext(), getString(R.string.settings_suggest_order_near)))
        val list = mutableListOf<View>()
        order.forEachIndexed { index, key ->
            val row = CardList.row(requireContext())
            row.addView(titleView(labels[key].orEmpty()), weightParams())
            val up = TextView(requireContext()).apply {
                text = "↑"
                setTextSize(20f)
                setTextColor(requireContext().getColor(if (index == 0) R.color.text_secondary else R.color.text_primary))
                gravity = android.view.Gravity.CENTER
                setPadding(dpToPx(10), 0, dpToPx(10), 0)
                alpha = if (index == 0) 0.35f else 1f
                setOnClickListener {
                    if (index == 0) return@setOnClickListener
                    order.add(index - 1, order.removeAt(index))
                    settings.setSuggestOrder(order.joinToString(","))
                    build()
                }
            }
            val down = TextView(requireContext()).apply {
                text = "↓"
                setTextSize(20f)
                setTextColor(requireContext().getColor(if (index == order.lastIndex) R.color.text_secondary else R.color.text_primary))
                gravity = android.view.Gravity.CENTER
                setPadding(dpToPx(10), 0, dpToPx(10), 0)
                alpha = if (index == order.lastIndex) 0.35f else 1f
                setOnClickListener {
                    if (index == order.lastIndex) return@setOnClickListener
                    order.add(index + 1, order.removeAt(index))
                    settings.setSuggestOrder(order.joinToString(","))
                    build()
                }
            }
            row.addView(up)
            row.addView(down)
            list.add(row)
        }
        card(*list.toTypedArray())
        container.addView(CardList.sectionTitle(requireContext(), getString(R.string.settings_suggest_order_far)))
    }

    private fun buildV2Operate() {
        card(
            choiceRow(
                getString(R.string.settings_startup_behavior),
                STARTUP_BEHAVIOR_NAMES,
                settings.startupBehavior().coerceIn(0, 2)
            ) { settings.setStartupBehavior(it) },
            choiceRow(
                getString(R.string.settings_back_action),
                BACK_ACTION_NAMES,
                settings.keyAction("back").coerceIn(0, 1)
            ) { settings.setKeyAction("back", it) },
            switchRow(
                getString(R.string.settings_exit_confirm),
                settings.exitConfirm(),
                getString(R.string.settings_exit_confirm_desc)
            ) { settings.setExitConfirm(it) },
            switchRow(
                getString(R.string.settings_gesture_volume),
                settings.gestureVolumeKey()
            ) { settings.setGestureVolumeKey(it) },
            switchRow(
                getString(R.string.settings_video_gesture),
                settings.videoGesture()
            ) { settings.setVideoGesture(it) },
            navRow(getString(R.string.settings_toolbar_hold)) {
                open(VIEW_V2_OPERATE_HOLD)
            }
        )
    }

    private fun buildV2OperateHold() {
        val rows = listOf(
            "refresh" to R.string.settings_hold_refresh,
            "home" to R.string.settings_hold_home,
            "tab" to R.string.settings_hold_tab,
            "menu" to R.string.settings_hold_menu,
            "back" to R.string.settings_hold_back
        )
        card(*rows.map { (key, label) ->
            choiceRow(
                getString(label),
                TOOLBAR_ACTION_NAMES,
                settings.keyAction("toolbar_hold_$key").coerceIn(0, 6)
            ) { settings.setKeyAction("toolbar_hold_$key", it) }
        }.toTypedArray())
        card(
            choiceRow(
                getString(R.string.settings_swipe_left),
                TOOLBAR_ACTION_NAMES,
                settings.keyAction("toolbar_swipe_left").coerceIn(0, 6)
            ) { settings.setKeyAction("toolbar_swipe_left", it) },
            choiceRow(
                getString(R.string.settings_swipe_right),
                TOOLBAR_ACTION_NAMES,
                settings.keyAction("toolbar_swipe_right").coerceIn(0, 6)
            ) { settings.setKeyAction("toolbar_swipe_right", it) }
        )
    }

    // ---- 外观 ----

    private fun buildAppearance() {
        card(
            navRow(getString(R.string.interface_diy)) { openFragment(HomeCustomizeFragment()) }
        )
    }

    /** 夜间：跟随系统夜间 / 夜间 CSS / 网页暗色 / 夜间滤镜。 */
    private fun buildV2Night() {
        card(
            switchRow(
                getString(R.string.settings_night_mode),
                settings.nightFollowSystem(),
                getString(R.string.settings_night_mode_auto)
            ) {
                settings.setNightFollowSystem(it)
                (activity as? ShellActivity)?.let { ShellActivity.smoothRecreateForNightSwitch(it) }
            },
            switchRow(
                getString(R.string.settings_night_css),
                settings.nightCssEnabled(),
                getString(R.string.settings_night_css_desc)
            ) {
                settings.setNightCssEnabled(it)
                if (it) settings.setWebViewDark(false)
                AppServices.applyWebViewDark?.invoke()
                build()
            },
            switchRow(
                getString(R.string.settings_webview_dark),
                settings.webViewDark(),
                getString(R.string.settings_webview_dark_desc)
            ) {
                settings.setWebViewDark(it)
                if (it) settings.setNightCssEnabled(false)
                AppServices.applyWebViewDark?.invoke()
                build()
            },
            sliderRow(
                getString(R.string.settings_night_filter),
                settings.nightFilter(), 0, 255, 5
            ) { settings.setNightFilter(it) }
        )
    }

    /** 主题色：主色 / 网页文字颜色 / 主题色应用范围 / 卡片。 */
    private fun buildV2ThemeColorGroup() {
        card(
            choiceRow(getString(R.string.settings_theme_color), THEME_COLOR_NAMES, themeColorIndex()) {
                AppServices.settings.setSkin(THEME_COLOR_IDS[it])
                recreateAfterSave()
            },
            choiceRow(
                getString(R.string.settings_web_text_color),
                arrayOf(
                    getString(R.string.settings_web_text_color_original),
                    getString(R.string.settings_web_text_color_accent),
                    getString(R.string.settings_web_text_color_day),
                    getString(R.string.settings_web_text_color_night)
                ),
                settings.webTextColor()
            ) {
                settings.setWebTextColor(it)
                AppServices.applyWebTextAccent?.invoke()
            },
            navRow(
                getString(R.string.settings_color_define),
                getString(R.string.settings_color_define_desc)
            ) { open(VIEW_V2_COLOR_DEFINE) },
            navRow(getString(R.string.settings_card_group)) { open(VIEW_V2_CARD) }
        )
    }

    /** 卡片：背景透明度 / 背景色 / 外框，作用于所有 CardList 卡片。 */
    private fun buildV2Card() {
        card(
            sliderRow(
                getString(R.string.settings_card_alpha),
                settings.settingsCardAlpha(), 0, 100, 1
            ) {
                settings.setSettingsCardAlpha(it)
                build()
            },
            choiceRow(
                getString(R.string.settings_card_bg),
                arrayOf("跟随主题色", "默认", "浅灰", "深灰"),
                settings.cardBg().coerceIn(0, 3)
            ) {
                settings.setCardBg(it)
                build()
            },
            switchRow(getString(R.string.settings_card_border), settings.cardBorder()) {
                settings.setCardBorder(it)
                build()
            }
        )
    }

    private fun buildV2Font() {
        val font = settings.customFont()
        card(
            navRow(
                getString(R.string.settings_custom_font),
                if (font.isNullOrBlank()) getString(R.string.settings_custom_font_none)
                else java.io.File(font).name
            ) { fontLauncher.launch(arrayOf("font/*", "application/octet-stream")) },
            navRow(
                getString(R.string.settings_custom_font_clear),
                enabled = !font.isNullOrBlank()
            ) {
                settings.setCustomFont(null)
                java.io.File(requireContext().filesDir, "fonts/custom_font").delete()
                build()
            }
        )
    }

    private fun buildV2Menus() {
        card(
            navRow(getString(R.string.settings_lp_custom)) { open(VIEW_LP_CUSTOM) }
        )
    }

    private fun buildV2Gesture() {
        card(
            switchRow(getString(R.string.settings_gesture_swipe), settings.gestureSwipeNav()) {
                settings.setGestureSwipeNav(it)
            },
            switchRow(getString(R.string.settings_gesture_volume), settings.gestureVolumeKey()) {
                settings.setGestureVolumeKey(it)
            },
            choiceRow(
                getString(R.string.settings_gesture_double_tap),
                DOUBLE_TAP_NAMES,
                settings.gestureDoubleTap().coerceIn(0, 3)
            ) { settings.setGestureDoubleTap(it) },
            switchRow(getString(R.string.settings_pull_refresh), settings.pullRefresh()) {
                settings.setPullRefresh(it)
            },
            switchRow(getString(R.string.settings_video_gesture), settings.videoGesture()) {
                settings.setVideoGesture(it)
            }
        )
    }

    // ---- 主页样式（三级） ----

    private fun buildV2HomeLogo() {
        card(
            switchRow(
                getString(R.string.settings_home_logo_show),
                settings.homeLogoShow()
            ) { settings.setHomeLogoShow(it) },
            textRow(getString(R.string.settings_home_logo), settings.homeLogoText()) {
                settings.setHomeLogoText(it)
            },
            choiceRow(
                getString(R.string.settings_home_logo_size),
                LOGO_SIZE_NAMES,
                presetIndex(settings.homeLogoSize(), LOGO_SIZE_VALUES)
            ) { index ->
                if (index < LOGO_SIZE_VALUES.size) {
                    settings.setHomeLogoSize(LOGO_SIZE_VALUES[index])
                } else {
                    promptNumber(
                        getString(R.string.settings_home_logo_size),
                        settings.homeLogoSize().takeIf { it > 0 } ?: 34,
                        16, 64
                    ) { settings.setHomeLogoSize(it) }
                }
            },
            choiceRow(
                getString(R.string.settings_home_logo_weight),
                LOGO_WEIGHT_NAMES,
                settings.homeLogoWeight().coerceIn(0, 1)
            ) { settings.setHomeLogoWeight(it) },
            choiceRow(
                getString(R.string.settings_home_logo_color),
                LOGO_COLOR_NAMES,
                logoColorIndex(settings.homeLogoColor())
            ) { index ->
                when (index) {
                    0 -> settings.setHomeLogoColor(null)
                    1 -> settings.setHomeLogoColor("accent")
                    else -> promptColor(getString(R.string.settings_home_logo_color)) {
                        settings.setHomeLogoColor(it)
                    }
                }
            }
        )
    }

    private fun buildV2HomeSites() {
        card(
            switchRow(
                getString(R.string.settings_show_sites),
                settings.showSites()
            ) { settings.setShowSites(it) },
            choiceRow(
                getString(R.string.settings_home_grid_columns),
                HOME_GRID_COLUMNS_NAMES,
                settings.homeGridColumns().coerceIn(4, 6) - 4
            ) { settings.setHomeGridColumns(it + 4) },
            choiceRow(
                getString(R.string.settings_home_icon_size),
                HOME_ICON_SIZE_NAMES,
                settings.homeIconSize().coerceIn(0, 2)
            ) { settings.setHomeIconSize(it) },
            choiceRow(
                getString(R.string.settings_home_icon_radius),
                HOME_ICON_RADIUS_NAMES,
                settings.homeIconRadius().coerceIn(0, 3)
            ) { settings.setHomeIconRadius(it) },
            choiceRow(
                getString(R.string.settings_home_icon_shape),
                HOME_ICON_SHAPE_NAMES,
                settings.homeIconShape().coerceIn(0, 1)
            ) { settings.setHomeIconShape(it) },
            switchRow(getString(R.string.settings_home_icon_accent), settings.homeIconAccent()) {
                settings.setHomeIconAccent(it)
            },
            switchRow(getString(R.string.settings_home_show_labels), settings.homeShowLabels()) {
                settings.setHomeShowLabels(it)
            },
            choiceRow(
                getString(R.string.settings_home_label_size),
                LABEL_SIZE_NAMES,
                presetIndex(settings.homeLabelSize(), LABEL_SIZE_VALUES)
            ) { index ->
                if (index < LABEL_SIZE_VALUES.size) {
                    settings.setHomeLabelSize(LABEL_SIZE_VALUES[index])
                } else {
                    promptNumber(
                        getString(R.string.settings_home_label_size),
                        settings.homeLabelSize().takeIf { it > 0 } ?: 12,
                        8, 20
                    ) { settings.setHomeLabelSize(it) }
                }
            },
            choiceRow(
                getString(R.string.settings_home_cell_gap),
                CELL_GAP_NAMES,
                presetIndex(settings.homeCellGap(), CELL_GAP_VALUES)
            ) { index ->
                if (index < CELL_GAP_VALUES.size) {
                    settings.setHomeCellGap(CELL_GAP_VALUES[index])
                } else {
                    promptNumber(
                        getString(R.string.settings_home_cell_gap),
                        settings.homeCellGap().takeIf { it >= 0 } ?: 8,
                        0, 24
                    ) { settings.setHomeCellGap(it) }
                }
            }
        )
    }

    private fun buildV2HomeSearch() {
        card(
            choiceRow(
                getString(R.string.settings_home_layout),
                HOME_LAYOUT_NAMES,
                settings.homeLayoutPos().coerceIn(0, 2)
            ) { settings.setHomeLayoutPos(it) },
            choiceRow(
                getString(R.string.settings_home_search_radius),
                HOME_SEARCH_RADIUS_NAMES,
                settings.homeSearchRadius().coerceIn(0, 3)
            ) { index ->
                if (index == 3) {
                    promptNumber(
                        getString(R.string.settings_home_search_radius),
                        settings.homeSearchRadiusCustom(),
                        0, 48
                    ) {
                        settings.setHomeSearchRadiusCustom(it)
                        settings.setHomeSearchRadius(3)
                        build()
                    }
                } else {
                    settings.setHomeSearchRadius(index)
                }
            },
            choiceRow(
                getString(R.string.settings_home_search_height),
                HOME_SEARCH_HEIGHT_NAMES,
                settings.homeSearchHeight().coerceIn(0, 2)
            ) { settings.setHomeSearchHeight(it) }
        )
    }

    private fun buildV2HomeBg() {
        card(
            navRow(
                getString(R.string.settings_home_background),
                if (settings.homeBackground() != null) getString(R.string.settings_home_background_set)
                else getString(R.string.settings_home_background_default)
            ) { chooseHomeBackground() },
            choiceRow(
                getString(R.string.settings_home_bg_blur),
                HOME_BG_BLUR_NAMES,
                settings.homeBgBlur().coerceIn(0, 3),
                enabled = settings.homeBackground() != null
            ) { settings.setHomeBgBlur(it) },
            sliderRow(
                getString(R.string.settings_home_bg_overlay),
                settings.homeBgOverlay(), 0, 100, 5
            ) { settings.setHomeBgOverlay(it) }
        )
    }

    // ---- 隐私与安全 ----    // ---- 隐私与安全 ----

    private fun buildPrivacy() {
        sectionTitle(R.string.settings_section_ads)
        card(
            navRow(getString(R.string.settings_rules)) { openFragment(RulesFragment()) }
        )
        sectionTitle(R.string.settings_section_privacy)
        card(
            navRow(getString(R.string.settings_incognito)) { open(VIEW_V2_INCOG) },
            navRow(getString(R.string.settings_privacy_protection)) { open(VIEW_V2_PRIVACY_PROTECTION) },
            navRow(getString(R.string.settings_password)) { open(VIEW_V2_PASSWORD) }
        )
        sectionTitle(R.string.settings_section_data)
        card(
            navRow(getString(R.string.settings_clear_data)) { open(VIEW_V2_CLEAR) }
        )
        aboutText(R.string.about_privacy)
    }

    private fun buildV2Site() {
        card(
            navRow(getString(R.string.settings_site_default)) { open(VIEW_V2_SITE_DEFAULT) },
            navRow(getString(R.string.settings_site_rules)) { openFragment(SiteRulesFragment()) },
            navRow(getString(R.string.settings_desktop_sites)) { open(VIEW_DESKTOP_SITES) }
        )
    }

    private fun buildV2SiteDefault() {
        card(
            switchRow(
                getString(R.string.settings_site_no_image),
                settings.siteNoImage()
            ) { settings.setSiteNoImage(it) },
            switchRow(
                getString(R.string.settings_site_block_js),
                settings.siteBlockJs(),
                getString(R.string.settings_site_block_js_desc)
            ) { settings.setSiteBlockJs(it) },
            switchRow(
                getString(R.string.settings_site_block_popup),
                settings.siteBlockPopup(),
                getString(R.string.settings_site_block_popup_desc)
            ) { settings.setSiteBlockPopup(it) },
            switchRow(
                getString(R.string.settings_site_block_db),
                settings.siteBlockDb(),
                getString(R.string.settings_site_block_db_desc)
            ) { settings.setSiteBlockDb(it) },
            switchRow(
                getString(R.string.settings_site_block_dom),
                settings.siteBlockDom(),
                getString(R.string.settings_site_block_dom_desc)
            ) { settings.setSiteBlockDom(it) },
            switchRow(
                getString(R.string.settings_site_block_file),
                settings.siteBlockFile(),
                getString(R.string.settings_site_block_file_desc)
            ) { settings.setSiteBlockFile(it) },
            switchRow(
                getString(R.string.settings_block_third_party),
                settings.thirdPartyCookiesBlocked(),
                getString(R.string.settings_block_third_party_desc)
            ) { settings.setThirdPartyCookiesBlocked(it) },
            switchRow(
                getString(R.string.settings_site_back_noreload),
                settings.siteBackNoReload()
            ) { settings.setSiteBackNoReload(it) },
            choiceRow(
                getString(R.string.settings_site_allow_redirect),
                REDIRECT_POLICY_NAMES,
                settings.siteRedirectPolicy().coerceIn(0, 2)
            ) { settings.setSiteRedirectPolicy(it) },
            choiceRow(
                getString(R.string.settings_open_app_policy),
                OPEN_APP_POLICY_NAMES,
                settings.openAppPolicy().coerceIn(0, 2)
            ) { settings.setOpenAppPolicy(it) },
            switchRow(
                getString(R.string.settings_dnt),
                settings.dntEnabled(),
                getString(R.string.settings_dnt_desc)
            ) { settings.setDntEnabled(it) },
            switchRow(
                getString(R.string.settings_save_data),
                settings.saveDataEnabled(),
                getString(R.string.settings_save_data_desc)
            ) { settings.setSaveDataEnabled(it) },
            switchRow(
                getString(R.string.settings_safe_browsing),
                settings.safeBrowsing(),
                getString(R.string.settings_safe_browsing_desc)
            ) { settings.setSafeBrowsing(it) },
            switchRow(
                getString(R.string.settings_webrtc_protect),
                settings.webrtcProtect(),
                getString(R.string.settings_webrtc_protect_desc)
            ) { settings.setWebrtcProtect(it) },
            navRow(getString(R.string.settings_site_permissions)) {
                open(VIEW_V2_SITE_PERMISSIONS)
            }
        )
    }

    private fun buildV2SitePermissions() {
        card(
            choiceRow(
                getString(R.string.settings_permission_geo),
                PERMISSION_DEFAULT_NAMES,
                settings.permissionGeo().coerceIn(0, 2)
            ) { settings.setPermissionGeo(it) },
            choiceRow(
                getString(R.string.settings_permission_cam),
                PERMISSION_DEFAULT_NAMES,
                settings.permissionCam().coerceIn(0, 2)
            ) { settings.setPermissionCam(it) },
            choiceRow(
                getString(R.string.settings_permission_mic),
                PERMISSION_DEFAULT_NAMES,
                settings.permissionMic().coerceIn(0, 2)
            ) { settings.setPermissionMic(it) }
        )
    }

    private fun buildV2Incog() {
        sectionTitle(R.string.settings_incognito_section_behavior)
        card(
            switchRow(
                getString(R.string.settings_incognito_no_history),
                settings.incognitoNoHistory()
            ) { settings.setIncognitoNoHistory(it) },
            switchRow(
                getString(R.string.settings_incognito_no_restore),
                settings.incognitoNoRestore()
            ) { settings.setIncognitoNoRestore(it) },
            switchRow(
                getString(R.string.settings_incognito_no_form),
                settings.incognitoNoForm()
            ) { settings.setIncognitoNoForm(it) },
            switchRow(
                getString(R.string.settings_incognito_secure),
                settings.incognitoSecure()
            ) { settings.setIncognitoSecure(it) },
            switchRow(
                getString(R.string.settings_incognito_clear),
                settings.incognitoClearOnExit(),
                getString(R.string.settings_incognito_clear_desc)
            ) { settings.setIncognitoClearOnExit(it) }
        )

        sectionTitle(R.string.settings_incognito_section_permissions)
        card(
            switchRow(
                getString(R.string.settings_incognito_no_geo),
                settings.incognitoNoGeo()
            ) { settings.setIncognitoNoGeo(it) },
            switchRow(
                getString(R.string.settings_incognito_deny_cam),
                settings.incognitoDenyCam()
            ) { settings.setIncognitoDenyCam(it) },
            switchRow(
                getString(R.string.settings_incognito_deny_mic),
                settings.incognitoDenyMic()
            ) { settings.setIncognitoDenyMic(it) }
        )

        sectionTitle(R.string.settings_incognito_section_cleanup)
        card(
            switchRow(
                getString(R.string.settings_incognito_clean_downloads),
                settings.incognitoCleanDownloads()
            ) { settings.setIncognitoCleanDownloads(it) },
            switchRow(
                getString(R.string.settings_incognito_clean_translation),
                settings.incognitoCleanTranslation()
            ) { settings.setIncognitoCleanTranslation(it) }
        )
    }




    private fun confirmAllowPrivacyHost(blockedHost: String) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.privacy_allow_host_title)
            .setMessage(getString(R.string.privacy_allow_host_message, blockedHost))
            .setPositiveButton(R.string.privacy_allow_host_confirm) { _, _ ->
                settings.setPrivacyAllowedHosts(
                    settings.privacyAllowedHosts() + blockedHost
                )
                AppServices.syncPrivacyAllowlist()
                build()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.privacy_allow_host_toast, blockedHost),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildV2PrivacyProtection() {
        val level = settings.privacyLevel().coerceIn(0, 3)
        val levelNames = arrayOf("关闭", "标准", "严格", "自定义")
        card(
            choiceRow(
                getString(R.string.privacy_level),
                levelNames,
                level,
                getString(R.string.privacy_level_desc)
            ) { applyPrivacyLevel(it) }
        )

        card(
            switchRow(
                getString(R.string.privacy_compat),
                settings.privacyCompat(),
                getString(R.string.privacy_compat_desc),
                enabled = level == 1
            ) {
                settings.setPrivacyCompat(it)
                AppServices.syncPrivacyAllowlist()
            }
        )

        card(android.widget.TextView(requireContext()).apply {
            text = getString(R.string.privacy_breakage_hint)
            setTextSize(12.5f)
            setTextColor(requireContext().getColor(R.color.text_secondary))
            setLineSpacing(dpToPx(3).toFloat(), 1.15f)
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
        })

        card(
            navRow(
                getString(R.string.privacy_allowlist_title),
                settings.privacyAllowedHosts().size.toString()
            ) { openFragment(PrivacyAllowlistFragment()) }
        )

        sectionTitle(R.string.privacy_stats)
        val rows = mutableListOf<View>()
        AppServices.adBlock.blockedCategoryStats().forEach { (category, count) ->
            rows.add(privacyStatRow(privacyCategoryLabel(category), count.toString()))
        }
        if (settings.privacyCookies()) {
            rows.add(privacyStatRow(getString(R.string.privacy_cookies), getString(R.string.privacy_cookies_blocked)))
        }
        if (rows.isEmpty()) {
            rows.add(privacyStatRow(getString(R.string.privacy_stats_empty), ""))
        }
        card(*rows.toTypedArray())

        val blockedHosts = AppServices.adBlock.privacyBlockedHostStats()
        if (blockedHosts.isNotEmpty()) {
            sectionTitle(R.string.privacy_blocked_hosts)
            val hostRows = mutableListOf<View>()
            blockedHosts.forEach { (blockedHost, count) ->
                hostRows.add(
                    navRow(blockedHost, getString(R.string.privacy_blocked_times, count)) {
                        confirmAllowPrivacyHost(blockedHost)
                    }
                )
            }
            card(*hostRows.toTypedArray())
        }

        sectionTitle(R.string.privacy_custom)
        card(
            switchRow(
                getString(R.string.privacy_tracking),
                settings.privacyTracking(),
                enabled = level == 3
            ) { setPrivacyCategory { settings.setPrivacyTracking(it) } },
            switchRow(
                getString(R.string.privacy_social),
                settings.privacySocial(),
                enabled = level == 3
            ) { setPrivacyCategory { settings.setPrivacySocial(it) } },
            switchRow(
                getString(R.string.privacy_miners),
                settings.privacyMiners(),
                enabled = level == 3
            ) { setPrivacyCategory { settings.setPrivacyMiners(it) } },
            switchRow(
                getString(R.string.privacy_fingerprint),
                settings.privacyFingerprint(),
                enabled = level == 3
            ) { setPrivacyCategory { settings.setPrivacyFingerprint(it) } },
            switchRow(
                getString(R.string.privacy_content),
                settings.privacyContent(),
                enabled = level == 3
            ) { setPrivacyCategory { settings.setPrivacyContent(it) } },
            switchRow(
                getString(R.string.privacy_cookies),
                settings.privacyCookies(),
                enabled = level == 3
            ) {
                settings.setPrivacyCookies(it)
                settings.setPrivacyLevel(3)
                build()
            },
            switchRow(
                getString(R.string.privacy_clean_urls),
                settings.privacyCleanUrls(),
                enabled = level == 3
            ) {
                settings.setPrivacyCleanUrls(it)
                settings.setPrivacyLevel(3)
                build()
            },
            switchRow(
                getString(R.string.privacy_gpc),
                settings.privacyGpc(),
                enabled = level == 3
            ) {
                settings.setPrivacyGpc(it)
                settings.setPrivacyLevel(3)
                build()
            },
            switchRow(
                getString(R.string.privacy_https_only),
                settings.privacyHttpsOnly(),
                enabled = level == 3
            ) {
                settings.setPrivacyHttpsOnly(it)
                settings.setPrivacyLevel(3)
                build()
            }
        )
        aboutText(R.string.privacy_custom_hint)
    }

    private fun setPrivacyCategory(action: () -> Unit) {
        action()
        settings.setPrivacyLevel(3)
        build()
        refreshPrivacyRules()
    }

    private fun applyPrivacyLevel(level: Int) {
        val normalized = level.coerceIn(0, 3)
        PrivacyLevels.apply(settings, normalized)
        AppServices.syncPrivacyAllowlist()
        AppServices.adBlock.resetPrivacyStats()
        build()
        if (normalized == 0) {
            AppServices.adBlock.privacyEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.withContext(
                    kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.NonCancellable
                ) {
                    AppServices.ruleRepository.loadIntoEngine(AppServices.adBlock)
                }
                if (isAdded) build()
            }
        } else {
            refreshPrivacyRules()
        }
    }

    private fun refreshPrivacyRules() {
        val categories = buildSet {
            if (settings.privacyTracking()) add(PrivacyPacks.TRACKING)
            if (settings.privacySocial()) add(PrivacyPacks.SOCIAL)
            if (settings.privacyMiners()) add(PrivacyPacks.MINERS)
            if (settings.privacyFingerprint()) add(PrivacyPacks.FINGERPRINT)
            if (settings.privacyContent()) add(PrivacyPacks.CONTENT)
        }
        Toast.makeText(requireContext(), R.string.privacy_rules_updating, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(
                kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.NonCancellable
            ) {
                val downloaded = AppServices.ruleRepository.ensurePrivacyPacks(categories)
                AppServices.ruleRepository.loadIntoEngine(AppServices.adBlock)
                downloaded
            }
            if (!isAdded) return@launch
            Toast.makeText(
                requireContext(),
                if (ok) R.string.privacy_rules_ready else R.string.privacy_rules_failed,
                Toast.LENGTH_SHORT
            ).show()
            build()
        }
    }

    private fun privacyStatRow(label: String, value: String): View {
        val row = CardList.row(requireContext())
        row.addView(titleView(label), weightParams())
        row.addView(TextView(requireContext()).apply {
            text = value
            setTextSize(13f)
            setTextColor(requireContext().getColor(R.color.text_secondary))
        })
        return row
    }

    private fun privacyCategoryLabel(category: String): String = when (category) {
        PrivacyPacks.TRACKING -> getString(R.string.privacy_tracking)
        PrivacyPacks.SOCIAL -> getString(R.string.privacy_social)
        PrivacyPacks.MINERS -> getString(R.string.privacy_miners)
        PrivacyPacks.FINGERPRINT -> getString(R.string.privacy_fingerprint)
        PrivacyPacks.CONTENT -> getString(R.string.privacy_content)
        else -> category
    }

    private fun buildV2Password() {
        val enabled = settings.passwordEnabled()
        card(
            navRow(getString(R.string.settings_password_manage)) { openFragment(PasswordFragment()) },
            switchRow(getString(R.string.settings_password_enabled), enabled) {
                settings.setPasswordEnabled(it)
                build()
            },
            navRow(
                getString(R.string.settings_password_fill_mode),
                getString(
                    when (settings.passwordFillMode()) {
                        1 -> R.string.settings_password_autofill
                        2 -> R.string.pwd_fill_mode_off
                        else -> R.string.pwd_fill_mode_prompt
                    }
                ),
                enabled = enabled
            ) { showPasswordFillModeDialog() }
        )
    }

    private fun showPasswordFillModeDialog() {
        val modes = arrayOf(
            getString(R.string.pwd_fill_mode_prompt),
            getString(R.string.settings_password_autofill),
            getString(R.string.pwd_fill_mode_off)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_password_fill_mode)
            .setSingleChoiceItems(modes, settings.passwordFillMode()) { dialog, which ->
                settings.setPasswordFillMode(which)
                settings.setPasswordAutofill(which == 1)
                dialog.dismiss()
                build()
            }
            .show()
    }

    private fun buildV2Clear() {
        val clearOnExit = settings.clearDataOnExit() == 1
        card(
            switchRow(
                getString(R.string.settings_clear_on_exit),
                clearOnExit,
                getString(R.string.settings_clear_on_exit_desc)
            ) {
                settings.setClearDataOnExit(if (it) 1 else 0)
                val cleanup = android.content.Intent(
                    requireContext(), SessionCleanupService::class.java
                )
                if (it) {
                    runCatching { requireContext().startService(cleanup) }
                } else {
                    requireContext().stopService(cleanup)
                }
                build()
            },
            navRow(
                getString(R.string.settings_clear_on_exit_items),
                getString(
                    R.string.settings_clear_on_exit_items_count,
                    settings.exitClearItems().split(',').count { it.isNotBlank() }
                ),
                enabled = clearOnExit
            ) { showExitClearItemsDialog() },
            navRow(getString(R.string.settings_clear_data)) { confirmClearData() }
        )
    }

    // ---- 高级 ----

    private fun buildAdvanced() {
        card(
            navRow(getString(R.string.settings_ua_group)) { open(VIEW_V2_UA) },
            navRow(getString(R.string.settings_network)) { open(VIEW_V2_NETWORK) },
            navRow(getString(R.string.settings_import_export)) { open(VIEW_V2_IMPORT) },
            navRow(getString(R.string.settings_mcp)) { open(VIEW_V2_MCP) },
            navRow(getString(R.string.settings_lab)) { open(VIEW_V2_LAB) }
        )
        aboutText(R.string.about_advanced)
    }

    private fun buildV2Ua() {
        val currentMobile = settings.userAgent().orEmpty()
        val mobileIndex = MOBILE_UA_PRESETS.indexOfFirst { it.second == currentMobile }
            .takeIf { it >= 0 } ?: 0
        val currentDesktop = settings.desktopUserAgent().orEmpty()
        val desktopIndex = DESKTOP_UA_PRESETS.indexOfFirst { it.second == currentDesktop }
            .takeIf { it >= 0 } ?: 0
        card(
            choiceRow(
                getString(R.string.settings_ua_preset),
                MOBILE_UA_PRESETS.map { it.first }.toTypedArray(),
                mobileIndex
            ) { index ->
                settings.setUserAgent(MOBILE_UA_PRESETS[index].second.ifBlank { null })
                build()
            },
            textRow(getString(R.string.settings_ua), currentMobile) {
                settings.setUserAgent(it.ifBlank { null })
            },
            choiceRow(
                getString(R.string.settings_desktop_ua_preset),
                DESKTOP_UA_PRESETS.map { it.first }.toTypedArray(),
                desktopIndex
            ) { index ->
                settings.setDesktopUserAgent(DESKTOP_UA_PRESETS[index].second)
                build()
            },
            textRow(getString(R.string.settings_desktop_ua), currentDesktop) {
                settings.setDesktopUserAgent(it.ifBlank { null })
            },
            switchRow(
                getString(R.string.settings_ua_simplify),
                settings.uaSimplified(),
                getString(R.string.settings_ua_simplify_desc)
            ) { settings.setUaSimplified(it) },
            navRow(
                getString(R.string.settings_desktop_sites),
                getString(R.string.settings_desktop_sites_count, desktopSitesList().size)
            ) { open(VIEW_DESKTOP_SITES) }
        )
    }

    private fun buildV2Network() {
        card(
            switchRow(
                getString(R.string.settings_https_only),
                settings.httpsOnly(),
                getString(R.string.settings_https_only_desc)
            ) { settings.setHttpsOnly(it) },
            switchRow(
                getString(R.string.settings_ignore_ssl),
                settings.ignoreSslError(),
                getString(R.string.settings_ignore_ssl_desc)
            ) { settings.setIgnoreSslError(it) },
            switchRow(
                getString(R.string.settings_web_debug),
                settings.webDebug(),
                getString(R.string.settings_web_debug_desc)
            ) { settings.setWebDebug(it) },
            switchRow(
                getString(R.string.settings_js_console_log),
                settings.jsConsoleLog(),
                getString(R.string.settings_js_console_log_desc)
            ) { settings.setJsConsoleLog(it) },
            navRow(getString(R.string.settings_netlog)) { open(VIEW_V2_NETLOG) }
        )
        sectionTitle(R.string.settings_private_dns)
        card(
            navRow(
                getString(R.string.settings_private_dns_open),
                getString(R.string.settings_private_dns_desc)
            ) { openPrivateDnsSettings() },
            navRow(getString(R.string.settings_private_dns_manual_row)) {
                showPrivateDnsHelp()
            }
        )
        card(*buildPrivateDnsRows())
        aboutText(R.string.settings_private_dns_note)
        sectionTitle(R.string.settings_doh)
        card(
            switchRow(
                getString(R.string.settings_doh_enable),
                settings.dohProxyEnabled(),
                getString(R.string.settings_doh_desc)
            ) { enabled ->
                settings.setDohProxyEnabled(enabled)
                if (enabled) {
                    com.sii.browser.core.dns.DohProxy.sync(requireContext())
                } else {
                    com.sii.browser.core.dns.DohProxy.stop()
                }
                build()
                view?.postDelayed({ if (isAdded) build() }, 600)
            },
            choiceRow(
                getString(R.string.settings_doh_provider),
                DOH_PROVIDER_NAMES,
                dohProviderIndex(),
                enabled = settings.dohProxyEnabled()
            ) { index ->
                settings.setDohProvider(DOH_PROVIDER_TAGS[index])
                if (settings.dohProxyEnabled()) {
                    com.sii.browser.core.dns.DohProxy.restart(requireContext())
                }
                build()
            },
            infoRow(getString(R.string.settings_doh_status), dohStatusText())
        )
        aboutText(R.string.settings_doh_note)
    }

    private fun dohProviderIndex(): Int =
        DOH_PROVIDER_TAGS.indexOf(settings.dohProvider()).takeIf { it >= 0 } ?: 0

    private fun dohStatusText(): String {
        val proxy = com.sii.browser.core.dns.DohProxy
        return when {
            !settings.dohProxyEnabled() -> getString(R.string.settings_doh_status_off)
            !proxy.isSupported() -> getString(R.string.settings_doh_status_unsupported)
            proxy.status == com.sii.browser.core.dns.DohProxy.Status.RUNNING ->
                getString(R.string.settings_doh_status_running)
            proxy.status == com.sii.browser.core.dns.DohProxy.Status.FAILED ->
                getString(R.string.settings_doh_status_failed) +
                    (proxy.lastError?.let { "：$it" } ?: "")
            else -> getString(R.string.settings_doh_status_pending)
        }
    }

    private fun buildPrivateDnsRows(): Array<View> {
        val context = requireContext()
        val hosts = listOf(
            "dns.alidns.com" to R.string.settings_private_dns_provider_ali,
            "doh.pub" to R.string.settings_private_dns_provider_tencent,
            "1dot1dot1dot1.cloudflare-dns.com" to R.string.settings_private_dns_provider_cloudflare,
            "dns.google" to R.string.settings_private_dns_provider_google
        )
        return hosts.map { (host, providerRes) ->
            CardList.row(context).apply {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = getString(providerRes)
                        setTextSize(14f)
                        setTextColor(context.getColor(R.color.text_primary))
                    })
                    addView(TextView(context).apply {
                        text = host
                        setTextSize(12f)
                        setTextColor(context.getColor(R.color.text_secondary))
                        setPadding(0, dpToPx(2), 0, 0)
                    })
                }, weightParams())
                addView(TextView(context).apply {
                    text = getString(R.string.settings_private_dns_copy)
                    setTextSize(13f)
                    setTextColor(ThemeAccent.color(context))
                })
                setOnClickListener { copyDnsHost(host) }
            }
        }.toTypedArray()
    }

    private fun openPrivateDnsSettings() {
        if (android.os.Build.VERSION.SDK_INT < 28) {
            showPrivateDnsManual()
            return
        }
        val direct = listOf(
            android.content.Intent("android.settings.PRIVATE_DNS_SETTINGS"),
            android.content.Intent().setComponent(
                android.content.ComponentName(
                    "com.android.settings",
                    "com.android.settings.Settings" + '$' + "PrivateDnsSettingsActivity"
                )
            ),
            android.content.Intent().setComponent(
                android.content.ComponentName(
                    "com.android.settings",
                    "com.android.settings.wifi.PrivateDnsSettingsActivity"
                )
            )
        )
        for (intent in direct) {
            if (runCatching { startActivity(intent); true }.getOrDefault(false)) return
        }
        if (openDiscoveredPrivateDns()) return
        showPrivateDnsPathDialog()
    }

    /** 在设置 App 里找名字含 PrivateDns 的页面并直接打开（各家设置包名不同）。 */
    private fun openDiscoveredPrivateDns(): Boolean {
        val pm = requireContext().packageManager
        val packages = listOf(
            "com.android.settings",
            "com.oplus.settings",
            "com.coloros.settings",
            "com.oneplus.settings"
        )
        packages.forEach { pkg ->
            val info = runCatching {
                pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES)
            }.getOrNull() ?: return@forEach
            val activities = info.activities ?: return@forEach
            for (activity in activities) {
                val name = activity.name
                val matched = name.contains("privatedns", true) ||
                    name.contains("private_dns", true)
                if (!matched) continue
                val intent = android.content.Intent().setComponent(
                    android.content.ComponentName(pkg, name)
                )
                if (runCatching { startActivity(intent); true }.getOrDefault(false)) {
                    return true
                }
            }
        }
        return false
    }

    /** 找不到独立页面时，按品牌给出准确路径，可复制或直接打开系统设置。 */
    private fun showPrivateDnsPathDialog() {
        val path = privateDnsPathText()
        val message = getString(R.string.settings_private_dns_no_page) + "\n\n" + path +
            "\n\n" + getString(R.string.settings_private_dns_note)
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_private_dns_open)
            .setMessage(message)
            .setPositiveButton(R.string.settings_private_dns_open_settings) { _, _ ->
                runCatching {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
                }
            }
            .setNeutralButton(R.string.settings_private_dns_copy_path) { _, _ ->
                val clipboard = requireContext()
                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("dns_path", path))
                Toast.makeText(
                    requireContext(),
                    R.string.settings_private_dns_path_copied,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun privateDnsPathText(): String {
        val brand = (android.os.Build.BRAND + " " + android.os.Build.MANUFACTURER).lowercase()
        return getString(
            when {
                brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme") ->
                    R.string.settings_private_dns_path_coloros
                brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
                    R.string.settings_private_dns_path_hyperos
                brand.contains("samsung") -> R.string.settings_private_dns_path_oneui
                brand.contains("huawei") || brand.contains("honor") ->
                    R.string.settings_private_dns_path_harmony
                brand.contains("google") -> R.string.settings_private_dns_path_aosp
                else -> R.string.settings_private_dns_path_generic
            }
        )
    }

    private fun showPrivateDnsHelp() {
        if (android.os.Build.VERSION.SDK_INT < 28) {
            showPrivateDnsManual()
        } else {
            showPrivateDnsPathDialog()
        }
    }

    private fun showPrivateDnsManual() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_private_dns_open)
            .setMessage(R.string.settings_private_dns_manual)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun copyDnsHost(host: String) {
        val clipboard = requireContext()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("dns", host))
        Toast.makeText(
            requireContext(),
            getString(R.string.settings_private_dns_copied, host),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun buildV2Netlog() {
        val entries = com.sii.browser.core.webview.NetLog.snapshot()
        card(
            navRow(getString(R.string.netlog_clear)) {
                com.sii.browser.core.webview.NetLog.clear()
                build()
            }
        )
        if (entries.isEmpty()) {
            aboutText(R.string.netlog_empty)
            return
        }
        val list = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        entries.forEach { entry ->
            val host = runCatching {
                android.net.Uri.parse(entry.url).host
            }.getOrNull() ?: entry.url
            val state = if (entry.blocked) getString(R.string.netlog_blocked)
            else getString(R.string.netlog_allowed)
            list.addView(TextView(requireContext()).apply {
                text = "${timeFormat.format(java.util.Date(entry.time))}  $host  $state"
                setTextSize(12f)
                setTextColor(requireContext().getColor(R.color.text_primary))
                setPadding(0, dpToPx(8), 0, dpToPx(8))
                setTextIsSelectable(true)
            })
        }
        container.addView(list)
    }

    private fun buildV2Import() {
        card(
            navRow(
                getString(R.string.settings_export_data),
                getString(R.string.settings_export_data_desc)
            ) { exportData() },
            navRow(
                getString(R.string.settings_import_data),
                getString(R.string.settings_import_data_desc)
            ) { importData() }
        )
    }

    private fun buildV2Lab() {
        card(
            switchRow(
                getString(R.string.settings_cct_disabled),
                settings.cctDisabled(),
                getString(R.string.settings_cct_disabled_desc)
            ) { settings.setCctDisabled(it) }
        )
    }

    private fun buildV2Mcp() {
        val server = AppServices.mcpServer
        val enabled = settings.mcpEnabled()
        val running = server.isRunning
        val status = when {
            running -> getString(R.string.mcp_status_running)
            enabled -> getString(R.string.mcp_status_stopped)
            else -> getString(R.string.mcp_status_off)
        }
        val host = if (settings.mcpAllowLan()) {
            server.localIpv4() ?: "127.0.0.1"
        } else {
            "127.0.0.1"
        }
        val endpoint = if (enabled) "http://$host:${settings.mcpPort()}/mcp" else "-"
        val token = settings.mcpToken().orEmpty()

        sectionTitle(R.string.mcp_section_status)
        card(
            infoRow(getString(R.string.mcp_status), status),
            infoRow(getString(R.string.mcp_endpoint), endpoint),
            infoRow(
                getString(R.string.mcp_tools_enabled),
                getString(
                    R.string.mcp_tools_enabled_value,
                    server.enabledToolCount(),
                    server.totalToolCount()
                )
            ),
            infoRow(
                getString(R.string.mcp_recent_calls),
                server.auditSnapshot().size.toString()
            )
        )
        server.lastError?.let { error ->
            card(infoRow(getString(R.string.mcp_last_error), error))
        }

        sectionTitle(R.string.mcp_section_control)
        card(
            switchRow(
                getString(R.string.mcp_enable),
                enabled,
                getString(R.string.mcp_enable_desc)
            ) { value ->
                settings.setMcpEnabled(value)
                if (value) McpRuntime.ensureToken()
                McpRuntime.sync(requireContext())
                build()
            },
            switchRow(
                getString(R.string.mcp_lan),
                settings.mcpAllowLan(),
                getString(R.string.mcp_lan_desc)
            ) { value ->
                settings.setMcpAllowLan(value)
                McpRuntime.sync(requireContext())
                build()
            },
            numberRow(
                getString(R.string.mcp_port),
                settings.mcpPort(),
                1024,
                65535
            ) { value ->
                settings.setMcpPort(value)
                McpRuntime.sync(requireContext())
                build()
            },
            navRow(getString(R.string.mcp_token), tokenMask(token)) {
                showMcpTokenDialog()
            },
            navRow(
                getString(R.string.mcp_copy_url),
                getString(R.string.mcp_copy_url_desc)
            ) { copyMcpUrl() },
            navRow(
                getString(R.string.mcp_copy_config),
                getString(R.string.mcp_copy_config_desc)
            ) { copyMcpConfig() }
        )

        sectionTitle(R.string.mcp_section_permissions)
        card(
            switchRow(
                getString(R.string.mcp_perm_diag),
                settings.mcpAllowDiag(),
                getString(R.string.mcp_perm_diag_desc)
            ) { value ->
                settings.setMcpAllowDiag(value)
                if (!value) com.sii.browser.core.mcp.DiagLog.clear()
            },
            switchRow(
                getString(R.string.mcp_perm_read),
                settings.mcpAllowPageRead(),
                getString(R.string.mcp_perm_read_desc)
            ) { settings.setMcpAllowPageRead(it) },
            switchRow(
                getString(R.string.mcp_perm_tabs),
                settings.mcpAllowTabs(),
                getString(R.string.mcp_perm_tabs_desc)
            ) { settings.setMcpAllowTabs(it) },
            switchRow(
                getString(R.string.mcp_perm_action),
                settings.mcpAllowPageAction(),
                getString(R.string.mcp_perm_action_desc)
            ) { settings.setMcpAllowPageAction(it) },
            switchRow(
                getString(R.string.mcp_perm_js),
                settings.mcpAllowJs(),
                getString(R.string.mcp_perm_js_desc)
            ) { settings.setMcpAllowJs(it) },
            switchRow(
                getString(R.string.mcp_perm_bookmark),
                settings.mcpAllowBookmarkWrite(),
                getString(R.string.mcp_perm_bookmark_desc)
            ) { settings.setMcpAllowBookmarkWrite(it) },
            switchRow(
                getString(R.string.mcp_perm_history),
                settings.mcpAllowHistoryWrite(),
                getString(R.string.mcp_perm_history_desc)
            ) { settings.setMcpAllowHistoryWrite(it) },
            switchRow(
                getString(R.string.mcp_perm_incognito),
                settings.mcpAllowIncognito(),
                getString(R.string.mcp_perm_incognito_desc)
            ) { settings.setMcpAllowIncognito(it) }
        )

        sectionTitle(R.string.mcp_section_capture)
        card(
            switchRow(
                getString(R.string.mcp_capture),
                settings.mcpAllowCapture(),
                getString(R.string.mcp_capture_desc)
            ) { value ->
                settings.setMcpAllowCapture(value)
                if (!value) com.sii.browser.core.webview.NetworkCapture.clearAll()
                build()
            },
            switchRow(
                getString(R.string.mcp_capture_body),
                settings.mcpCaptureBody(),
                getString(R.string.mcp_capture_body_desc)
            ) { settings.setMcpCaptureBody(it) },
            navRow(
                getString(R.string.mcp_clear_capture),
                getString(R.string.mcp_clear_capture_desc)
            ) {
                com.sii.browser.core.webview.NetworkCapture.clearAll()
                toast(R.string.mcp_clear_capture_done)
            }
        )

        sectionTitle(R.string.mcp_section_audit)
        card(
            navRow(getString(R.string.mcp_audit), getString(R.string.mcp_audit_desc)) {
                showMcpAudit()
            }
        )
        aboutText(R.string.mcp_about)
    }

    /** 诊断接口在正式包与测试包一致开放。 */
    private fun tokenMask(token: String): String =
        if (token.length <= 12) token.ifEmpty { "-" }
        else "${token.take(6)}…${token.takeLast(4)}"

    private fun copyText(label: String, text: String) {
        val manager = requireContext()
            .getSystemService(android.content.ClipboardManager::class.java)
        manager?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        toast(R.string.mcp_copied)
    }

    private fun copyMcpConfig() {
        copyText("MCP config", McpRuntime.clientConfig())
    }

    /** 含令牌的连接地址：只需填写 URL 的客户端可直接使用。 */
    private fun copyMcpUrl() {
        val token = settings.mcpToken().orEmpty().ifBlank { McpRuntime.ensureToken() }
        val host = if (settings.mcpAllowLan()) {
            AppServices.mcpServer.localIpv4() ?: "127.0.0.1"
        } else {
            "127.0.0.1"
        }
        copyText("MCP URL", "http://$host:${settings.mcpPort()}/mcp?token=$token")
    }

    private fun showMcpTokenDialog() {
        val token = McpRuntime.ensureToken()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.mcp_token)
            .setMessage(token)
            .setPositiveButton(R.string.mcp_token_copy) { _, _ ->
                copyText("MCP token", token)
            }
            .setNeutralButton(R.string.mcp_token_reset) { _, _ ->
                AlertDialog.Builder(requireContext())
                    .setMessage(R.string.mcp_token_reset_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        McpRuntime.resetToken()
                        McpRuntime.sync(requireContext())
                        toast(R.string.mcp_token_reset_done)
                        build()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMcpAudit() {
        val entries = AppServices.mcpServer.auditSnapshot()
        if (entries.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.mcp_audit)
                .setMessage(R.string.mcp_audit_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val format = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        val text = entries.take(40).joinToString("\n") { entry ->
            val result = if (entry.ok) "OK" else "ERR"
            "${format.format(java.util.Date(entry.time))}  ${entry.tool}  " +
                "$result  ${entry.durationMs}ms  ${entry.client}"
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.mcp_audit)
            .setMessage(text)
            .setPositiveButton(R.string.mcp_audit_clear) { _, _ ->
                AppServices.mcpServer.clearAudit()
                build()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- 外观与主页 ----

    private fun buildHomeSet() {
        val rows = mutableListOf<View>()
        rows.add(homeChoiceRow())
        if (settings.homeUrl() != "about:home") {
            rows.add(
                textRow(getString(R.string.settings_home_url), settings.homeUrl()) {
                    settings.setHomeUrl(it.ifBlank { "about:home" })
                    build()
                }
            )
        }
        rows.add(
            switchRow(getString(R.string.settings_show_sites), settings.showSites()) {
                settings.setShowSites(it)
            }
        )
        card(*rows.toTypedArray())
    }

    private fun presetIndex(value: Int, presets: IntArray): Int {
        val idx = presets.indexOf(value)
        return if (idx >= 0) idx else presets.size
    }

    private fun logoColorIndex(value: String?): Int = when {
        value.isNullOrBlank() -> 0
        value == "accent" -> 1
        else -> 2
    }

    private fun promptNumber(
        title: String,
        initial: Int,
        min: Int,
        max: Int,
        onSave: (Int) -> Unit
    ) {
        val input = EditText(requireContext()).apply {
            setText(initial.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().toIntOrNull()?.let { onSave(it.coerceIn(min, max)) }
                build()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> build() }
            .show()
    }

    private fun promptColor(title: String, onSave: (String) -> Unit) {
        val input = EditText(requireContext()).apply {
            hint = "#4C8DFF"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) onSave(value)
                build()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> build() }
            .show()
    }

    private fun buildUi() {
        card()
        aboutText(R.string.about_ui)
    }

    /** 长按菜单定制（顺序 + 显隐）+ 文本选择菜单定制（附加项开关）。 */
    private fun buildLongPressCustom() {
        val context = requireContext()
        val hidden = settings.longPressHidden().orEmpty()
            .split(",").filter { it.isNotEmpty() }.toMutableSet()
        val order = settings.longPressOrder()?.split(",")?.filter { it.isNotEmpty() }
        val ordered = LONG_PRESS_ITEMS.sortedBy { (id, _) ->
            order?.indexOf(id)?.takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
        container.addView(CardList.sectionTitle(context, getString(R.string.settings_lp_items)))
        container.addView(
            dragSortCard(
                items = ordered,
                checkedOf = { id -> id !in hidden },
                onToggle = { id, visible ->
                    if (visible) hidden.remove(id) else hidden.add(id)
                    settings.setLongPressHidden(hidden.joinToString(","))
                },
                onOrderChanged = { settings.setLongPressOrder(it.joinToString(",")) }
            )
        )

        // 文本选择菜单附加项
        container.addView(CardList.sectionTitle(context, getString(R.string.settings_selection_menu)))
        val enabled = settings.selectionMenuItems().orEmpty()
            .split(",").filter { it.isNotEmpty() }.toMutableSet()
        val rows = SELECTION_ITEMS.map { (id, label) ->
            switchRow(label, id in enabled) { visible ->
                if (visible) enabled.add(id) else enabled.remove(id)
                settings.setSelectionMenuItems(enabled.joinToString(","))
            }
        }
        card(*rows.toTypedArray())
    }

    /** 工具栏定制：底部工具栏按钮显隐 + ☰ 拖动排序。 */
    private fun buildToolbarCustom() {
        val context = requireContext()
        val hidden = settings.toolbarHidden().orEmpty()
            .split(",").filter { it.isNotEmpty() }.toMutableSet()
        val order = settings.toolbarOrder()?.split(",")?.filter { it.isNotEmpty() }
        val ordered = TOOLBAR_ITEMS.sortedBy { (id, _) ->
            order?.indexOf(id)?.takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
        container.addView(CardList.sectionTitle(context, getString(R.string.settings_toolbar_order)))
        container.addView(
            dragSortCard(
                items = ordered,
                checkedOf = { id -> id !in hidden },
                onToggle = { id, visible ->
                    if (visible) hidden.remove(id) else hidden.add(id)
                    settings.setToolbarHidden(hidden.joinToString(","))
                },
                onOrderChanged = { settings.setToolbarOrder(it.joinToString(",")) }
            )
        )
    }

    /** 菜单定制：「菜单项」（上方两行）与「固定菜单栏」（下方一行）两个同源分组，均支持开关 + ☰ 拖动排序。 */
    private fun buildMenuCustom() {
        val context = requireContext()
        val fixedIds = settings.fixedItems()?.split(",")?.filter { it.isNotEmpty() } ?: DEFAULT_FIXED_IDS
        val hidden = settings.hiddenMenus().orEmpty()
            .split(",").filter { it.isNotEmpty() }.toMutableSet()

        // 上方菜单项
        container.addView(CardList.sectionTitle(context, getString(R.string.menu_items)))
        val upperOrder = settings.displayedMenus()?.split(",")?.filter { it.isNotEmpty() }
        val upperItems = CATALOG.sortedBy { (id, _) ->
            upperOrder?.indexOf(id)?.takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
        container.addView(
            dragSortCard(
                items = upperItems,
                checkedOf = { id -> id !in hidden && id !in fixedIds },
                onToggle = { id, visible ->
                    if (visible) {
                        hidden.remove(id)
                        // 与固定栏重复时，把固定栏那份移除（后操作者生效）
                        if (id in fixedIds) {
                            settings.setFixedItems((fixedIds - id).joinToString(","))
                        }
                    } else {
                        hidden.add(id)
                    }
                    settings.setHiddenMenus(hidden.joinToString(","))
                    view?.post { build() }
                },
                onOrderChanged = { settings.setDisplayedMenus(it.joinToString(",")) }
            )
        )

        // 下方固定菜单栏
        container.addView(CardList.sectionTitle(context, getString(R.string.fixed_toolbar)))
        val fixedItems = CATALOG.sortedBy { (id, _) ->
            fixedIds.indexOf(id).takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
        container.addView(
            dragSortCard(
                items = fixedItems,
                checkedOf = { id -> id in fixedIds },
                onToggle = { id, visible ->
                    if (visible) {
                        settings.setFixedItems((fixedIds + id).joinToString(","))
                        // 固定栏开启后，关掉上方可移动区里的同一项
                        hidden.add(id)
                        settings.setHiddenMenus(hidden.joinToString(","))
                    } else {
                        settings.setFixedItems((fixedIds - id).joinToString(","))
                    }
                    view?.post { build() }
                },
                onOrderChanged = { settings.setFixedItems(it.joinToString(",")) }
            )
        )
    }

    /** 可拖动排序的开关列表：按住左侧 ☰ 上下拖动，松手落位（对齐 index.html bindDragSort）。 */
    private fun dragSortCard(
        items: List<Pair<String, String>>,
        checkedOf: (String) -> Boolean,
        onToggle: (String, Boolean) -> Unit,
        onOrderChanged: (List<String>) -> Unit
    ): View {
        val context = requireContext()
        val card = CardList.settingsCard(context, settings.settingsCardAlpha())
        val rows = mutableListOf<View>()

        fun divider() = CardList.styledDivider(context)
        fun rebuild() {
            card.removeAllViews()
            rows.forEachIndexed { i, r ->
                if (i > 0) card.addView(divider())
                card.addView(r)
            }
        }

        items.forEach { (id, label) ->
            val switch = Switch(context).apply {
                isChecked = checkedOf(id)
                setOnCheckedChangeListener { _, value -> onToggle(id, value) }
            }
            val handle = TextView(context).apply {
                text = "☰"
                setTextSize(16f)
                setTextColor(context.getColor(R.color.text_secondary))
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(dpToPx(28), LinearLayout.LayoutParams.MATCH_PARENT)
            }
            val row = CardList.row(context).apply {
                tag = id
                addView(handle)
                addView(TextView(context).apply {
                    text = label
                    setTextSize(14f)
                    setTextColor(context.getColor(R.color.text_primary))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(switch)
            }
            var dragRow: View? = null
            var startY = 0f
            var rowH = 0
            var fromIndex = -1
            var dragIndex = -1
            handle.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        fromIndex = rows.indexOf(row)
                        dragIndex = fromIndex
                        startY = event.rawY
                        rowH = row.height.takeIf { it > 0 } ?: dpToPx(48)
                        dragRow = row
                        row.elevation = 8f
                        // 拖动期间禁止外层 ScrollView 抢手势，否则整页会跟着滑动
                        (row.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val cur = dragRow ?: return@setOnTouchListener true
                        val dy = event.rawY - startY
                        cur.translationY = dy
                        val target = (fromIndex + (dy / rowH).toInt())
                            .coerceIn(0, rows.lastIndex)
                        if (target != dragIndex) {
                            // 行让位动画（仅视觉位移，松手后再真正重排）
                            val step = if (target > dragIndex) -rowH else rowH
                            val range = if (target > dragIndex) {
                                (dragIndex + 1)..target
                            } else {
                                target until dragIndex
                            }
                            for (index in range) {
                                rows[index].animate()
                                    .translationYBy(step.toFloat())
                                    .setDuration(130)
                                    .start()
                            }
                            dragIndex = target
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        (row.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                        val cur = dragRow ?: return@setOnTouchListener true
                        cur.animate().cancel()
                        cur.translationY = 0f
                        cur.elevation = 0f
                        dragRow = null
                        val moved = fromIndex != dragIndex
                        if (moved) {
                            val item = rows.removeAt(fromIndex)
                            rows.add(dragIndex.coerceIn(0, rows.size), item)
                        }
                        rows.forEach {
                            it.animate().cancel()
                            it.translationY = 0f
                        }
                        rebuild()
                        if (moved) onOrderChanged(rows.map { it.tag as String })
                        true
                    }
                    else -> false
                }
            }
            rows.add(row)
        }
        rebuild()
        return card
    }

    // ---- 高级 ----

    private fun buildAbout() {
        val rows = mutableListOf<View>()
        val context = requireContext()
        rows.add(android.widget.TextView(context).apply {
            text = context.getString(R.string.about_project_desc)
            setTextSize(13f)
            setTextColor(context.getColor(R.color.text_secondary))
            setLineSpacing(CardList.dp(context, 3).toFloat(), 1.2f)
            setPadding(
                CardList.dp(context, 16),
                CardList.dp(context, 14),
                CardList.dp(context, 16),
                CardList.dp(context, 14)
            )
        })
        val latest = settings.updateLatest()?.split("|")?.takeIf { it.size >= 2 }
        if (latest != null) {
            rows.add(
                navRow(
                    getString(R.string.update_found_row, latest[0]),
                    getString(R.string.update_open_release)
                ) { openUpdateUrl(latest[1]) }
            )
        }
        rows.add(infoRow(getString(R.string.settings_about_version), appVersion()))
        rows.add(
            navRow(
                getString(R.string.settings_about_repo),
                getString(R.string.about_repo_url).removePrefix("https://")
            ) { openRepository() }.apply {
                setOnLongClickListener {
                    copyRepositoryUrl()
                    true
                }
            }
        )
        rows.add(navRow(getString(R.string.settings_check_update)) { checkForUpdate() })
        rows.add(
            choiceRow(
                getString(R.string.settings_update_prompt),
                arrayOf(
                    getString(R.string.update_prompt_off),
                    getString(R.string.update_prompt_startup)
                ),
                settings.updatePrompt().coerceIn(0, 1),
                getString(R.string.settings_update_prompt_desc)
            ) { value ->
                settings.setUpdatePrompt(value)
                build()
            }
        )
        rows.add(navRow(getString(R.string.settings_crash_log)) { open(VIEW_CRASH_LOG) })
        rows.add(navRow(getString(R.string.settings_about_license)) { showLicense() })
        rows.add(navRow(getString(R.string.legal_terms_title)) { openFragment(LegalFragment.newInstance(LegalFragment.TYPE_TERMS)) })
        rows.add(navRow(getString(R.string.legal_privacy_title)) { openFragment(LegalFragment.newInstance(LegalFragment.TYPE_PRIVACY)) })
        rows.add(
            navRow(
                getString(R.string.about_qq_group),
                getString(R.string.about_qq_group_desc)
            ) { openQqGroup() }.apply {
                setOnLongClickListener {
                    copyQqGroup()
                    true
                }
            }
        )
        card(*rows.toTypedArray())
    }

    /** 优先用 QQ 打开群链接，未安装则回到浏览器内打开。 */
    private fun openQqGroup() {
        val context = requireContext()
        val uri = android.net.Uri.parse(QQ_GROUP_URL)
        val qqIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            .setPackage("com.tencent.mobileqq")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val qqAvailable = runCatching {
            context.packageManager.resolveActivity(qqIntent, 0) != null
        }.getOrDefault(false)
        if (qqAvailable && runCatching { context.startActivity(qqIntent); true }.getOrDefault(false)) {
            return
        }
        val opened = runCatching {
            val open = AppServices.openUrl
            if (open == null) {
                false
            } else {
                open(QQ_GROUP_URL)
                true
            }
        }.getOrDefault(false)
        if (opened) {
            parentFragmentManager.popBackStack(
                null,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
            )
            return
        }
        runCatching {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
        }.onFailure {
            Toast.makeText(context, R.string.about_qq_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyQqGroup() {
        val clipboard = requireContext()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("qq_group", QQ_GROUP_NUMBER))
        Toast.makeText(requireContext(), R.string.about_qq_copied, Toast.LENGTH_SHORT).show()
    }

    private fun buildCrashLog() {
        val entries = com.sii.browser.core.privacy.CrashLog.snapshot()
        card(
            navRow(getString(R.string.crash_log_test)) {
                com.sii.browser.core.privacy.CrashLog.record(
                    "Test", "手动测试日志", "这是一条用于验证崩溃日志页面的测试记录"
                )
                build()
            },
            navRow(getString(R.string.crash_log_export)) { exportCrashLog() },
            navRow(getString(R.string.crash_log_clear)) {
                com.sii.browser.core.privacy.CrashLog.clear()
                build()
            }
        )
        if (entries.isEmpty()) {
            aboutText(R.string.crash_log_empty)
            return
        }
        aboutText(R.string.crash_log_hint)
        val format = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        entries.take(30).forEach { entry ->
            card(TextView(requireContext()).apply {
                text = buildString {
                    append(format.format(java.util.Date(entry.time)))
                    append("  ")
                    append(entry.type)
                    append("\n")
                    append(entry.message)
                    if (entry.stack.isNotBlank()) {
                        append("\n")
                        append(entry.stack.take(4000))
                    }
                }
                setTextSize(11f)
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(requireContext().getColor(R.color.text_primary))
                setPadding(
                    CardList.dp(requireContext(), 14),
                    CardList.dp(requireContext(), 10),
                    CardList.dp(requireContext(), 14),
                    CardList.dp(requireContext(), 10)
                )
            })
        }
    }

    private fun exportCrashLog() {
        val entries = com.sii.browser.core.privacy.CrashLog.snapshot()
        if (entries.isEmpty()) {
            Toast.makeText(requireContext(), R.string.crash_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val text = entries.joinToString("\n\n") { entry ->
            buildString {
                append(format.format(java.util.Date(entry.time)))
                append("  ")
                append(entry.type)
                append("\n")
                append(entry.message)
                if (entry.stack.isNotBlank()) {
                    append("\n")
                    append(entry.stack)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                FileExchange.writeToDownloads(
                    requireContext(),
                    "sii-crash-${System.currentTimeMillis()}.txt",
                    text
                )
            }
            Toast.makeText(
                requireContext(),
                if (path != null) R.string.crash_log_exported else R.string.crash_log_export_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun appVersion(): String = try {
        requireContext().packageManager
            .getPackageInfo(requireContext().packageName, 0).versionName ?: "-"
    } catch (e: Exception) {
        "-"
    }

    companion object {
        const val ARG_VIEW = "view"
        const val ARG_FOCUS = "focus"

        private const val QQ_GROUP_NUMBER = "1128056947"
        private const val QQ_GROUP_URL =
            "https://qun.qq.com/universal-share/share?ac=1&authKey=3fqbIa8tMhc9LMb3dGvf5yqHfjye71%2Bqt%2F5S47vaPpPfoCdZgzrEp1zQoydN3tQa" +
                "&busi_data=eyJncm91cENvZGUiOiIxMTI4MDU2OTQ3IiwidG9rZW4iOiJTRCtPZnJiYjJYajI2NnM2Q0ZtUTFDNDY3Zy9LdFFYMW9raytnaWxkREFwbUxmSlJOTUFKaElWdmd4NVRkRlY4IiwidWluIjoiMjg3Njc1NjY2MyJ9" +
                "&data=Y3NGxGKyKlgCPVs1Xi-SlaZP4rSZ6HDG75WMzmgzLO-kP4NYEdIzuFFQIAhJ13Y5Sa9iYJVERBoEqE41xBtJZQ&svctype=4&tempid=h5_group_info"

        const val VIEW_GENERAL = "general"
        const val VIEW_ENGINE = "engine"
        const val VIEW_DL_SET = "dlSet"
        const val VIEW_PRIVACY = "privacy"
        const val VIEW_APPEARANCE = "appearance"
        const val VIEW_HOME_SET = "homeSet"
        const val VIEW_UI = "ui"
        const val VIEW_MENU_CUSTOM = "menuCustom"
        const val VIEW_TOOLBAR_CUSTOM = "toolbarCustom"
        const val VIEW_LP_CUSTOM = "lpCustom"
        const val VIEW_THEME_COLOR = "themeColor"
        const val VIEW_V2_COLOR_DEFINE = "v2ColorDefine"
        const val VIEW_ADVANCED = "advanced"
        const val VIEW_DESKTOP_SITES = "desktopSites"
        const val VIEW_V2_HOME = "v2Home"
        const val VIEW_V2_BROWSE = "v2Browse"
        const val VIEW_V2_TRANSLATE = "v2Translate"
        const val VIEW_V2_TRANSLATE_BAIDU = "v2TranslateBaidu"
        const val VIEW_V2_TRANSLATE_CUSTOM = "v2TranslateCustom"
        const val VIEW_V2_TRANSLATE_PARAMS = "v2TranslateParams"
        const val VIEW_V2_TRANSLATE_LOG = "v2TranslateLog"
        const val VIEW_V2_TRANSLATE_OFFLINE = "v2TranslateOffline"
        const val VIEW_V2_READING = "v2Reading"
        const val VIEW_V2_READING_MODE = "v2ReadingMode"
        const val VIEW_V2_TABS = "v2Tabs"
        const val VIEW_V2_ADDRESS = "v2Address"
        const val VIEW_V2_SUGGEST = "v2Suggest"
        const val VIEW_V2_SUGGEST_ORDER = "v2SuggestOrder"
        const val VIEW_V2_DOWNLOAD = "v2Download"
        const val VIEW_V2_OPERATE = "v2Operate"
        const val VIEW_V2_OPERATE_HOLD = "v2OperateHold"
        const val VIEW_V2_THEME_COLOR_GROUP = "v2ThemeColorGroup"
        const val VIEW_V2_NIGHT = "v2Night"
        const val VIEW_V2_CARD = "v2Card"
        const val VIEW_V2_FONT = "v2Font"
        const val VIEW_V2_MENUS = "v2Menus"
        const val VIEW_V2_GESTURE = "v2Gesture"
        const val VIEW_V2_HOME_LOGO = "v2HomeLogo"
        const val VIEW_V2_HOME_SITES = "v2HomeSites"
        const val VIEW_V2_HOME_SEARCH = "v2HomeSearch"
        const val VIEW_V2_HOME_BG = "v2HomeBg"
        const val VIEW_V2_UI_LAYOUT = "v2UiLayout"
        const val VIEW_V2_SITE = "v2Site"
        const val VIEW_V2_SITE_DEFAULT = "v2SiteDefault"
        const val VIEW_V2_SITE_PERMISSIONS = "v2SitePermissions"
        const val VIEW_V2_INCOG = "v2Incog"
        const val VIEW_V2_PRIVACY_PROTECTION = "v2PrivacyProtection"
        const val VIEW_V2_PASSWORD = "v2Password"
        const val VIEW_V2_CLEAR = "v2Clear"
        const val VIEW_V2_UA = "v2Ua"
        const val VIEW_V2_NETWORK = "v2Network"
        const val VIEW_V2_NETLOG = "v2Netlog"
        const val VIEW_V2_IMPORT = "v2Import"
        const val VIEW_V2_LAB = "v2Lab"
        const val VIEW_V2_MCP = "v2Mcp"
        const val VIEW_ABOUT = "about"
        const val VIEW_CRASH_LOG = "crashLog"

        private val ORIENTATIONS = arrayOf("自动", "横屏", "竖屏")
        private val TOOLBAR_POS_NAMES = arrayOf("底部", "顶部", "隐藏")
        private val URL_DISPLAY_NAMES = arrayOf("地址", "标题", "域名")
        private val ADDRESS_STYLE_NAMES = arrayOf("无外框", "精简", "普通")
        private val SUGGESTION_DIRECTION_NAMES = arrayOf("搜索框上方", "搜索框下方")
        private val SUGGESTION_COUNTS = intArrayOf(3, 4, 5, 6, 8, 10)
        private val HOME_LAYOUT_NAMES = arrayOf("顶部", "居中", "底部")
        private val DOUBLE_TAP_NAMES = arrayOf("无", "回到顶部", "刷新", "打开主页")
        internal val TRANSLATE_PROVIDER_NAMES =
            arrayOf("MyMemory（免费）", "LibreTranslate（免费）", "Google（非官方）", "自定义", "百度翻译")
        private val DOH_PROVIDER_NAMES = arrayOf("阿里 DNS", "腾讯 DNS", "Cloudflare", "Google")
        private val DOH_PROVIDER_TAGS = arrayOf("ali", "tencent", "cloudflare", "google")
        private val TRANSLATE_CUSTOM_METHOD_NAMES = arrayOf("GET", "POST")
        private val PERMISSION_DEFAULT_NAMES = arrayOf("询问", "允许", "拒绝")
        private val REDIRECT_POLICY_NAMES = arrayOf("询问", "允许", "拒绝")
        private val BACK_ACTION_NAMES = arrayOf("默认", "最小化")
        private val TAB_BUTTON_ORDER_NAMES = arrayOf("新建在左", "撤销在左")
        private val STARTUP_BEHAVIOR_NAMES = arrayOf("打开主页", "恢复上次页面", "空白页")
        private val TOOLBAR_ACTION_NAMES =
            arrayOf("无", "刷新", "主页", "新建标签", "菜单", "后退", "前进")
        private val OPEN_APP_POLICY_NAMES = arrayOf("每次询问", "始终允许", "禁止")
        private val HOME_GRID_COLUMNS_NAMES = arrayOf("4 列", "5 列", "6 列")
        private val HOME_ICON_SIZE_NAMES = arrayOf("小", "中", "大")
        private val HOME_ICON_SHAPE_NAMES = arrayOf("圆形", "圆角方形")
        private val HOME_ICON_RADIUS_NAMES = arrayOf("无", "小", "中", "大")
        private val HOME_SEARCH_RADIUS_NAMES = arrayOf("小", "中", "大", "自定义")
        private val HOME_SEARCH_HEIGHT_NAMES = arrayOf("紧凑", "标准", "宽松")
        private val HOME_BG_BLUR_NAMES = arrayOf("关", "小", "中", "大")
        private val LOGO_SIZE_NAMES = arrayOf("小 24", "中 34", "大 44", "自定义")
        private val LOGO_SIZE_VALUES = intArrayOf(24, 34, 44)
        private val LOGO_WEIGHT_NAMES = arrayOf("常规", "加粗")
        private val LOGO_COLOR_NAMES = arrayOf("跟随主题", "主题色", "自定义")
        private val LABEL_SIZE_NAMES = arrayOf("小 10", "中 12", "大 14", "自定义")
        private val LABEL_SIZE_VALUES = intArrayOf(10, 12, 14)
        private val CELL_GAP_NAMES = arrayOf("紧凑 4", "标准 8", "宽松 12", "自定义")
        private val CELL_GAP_VALUES = intArrayOf(4, 8, 12)
        private val BG_OVERLAY_NAMES = arrayOf("关", "浅 20%", "中 40%", "深 60%", "自定义")
        private val BG_OVERLAY_VALUES = intArrayOf(0, 20, 40, 60)
        private val QUICK_ENGINE_POS_NAMES = arrayOf("地址栏下方", "工具栏上方")
        private val READER_THEMES = arrayOf("跟随夜间", "白底", "米黄", "深色")

        /** 标签栏位置：地址栏上方 / 工具栏上方。 */
        private val TAB_POS_NAMES = arrayOf("地址栏上方", "工具栏上方", "关闭")

        /** 移动端 UA 预设（名称 → UA；空字符串 = 系统默认）。 */
        private val MOBILE_UA_PRESETS = listOf(
            "系统默认" to "",
            "Android Chrome" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
            "iPhone Safari" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
        )

        /** 桌面端 UA 预设（电脑模式使用）。 */
        private val DESKTOP_UA_PRESETS = listOf(
            "Windows Chrome" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "macOS Safari" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15",
            "iPad Safari" to "Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
        )
        private val ENGINES = com.sii.browser.data.SearchEngines.DEFAULT

        private val THEME_COLOR_NAMES = arrayOf("蓝", "绿", "橙", "紫")
        private val DOWNLOADER_NAMES = arrayOf("Sii 内建下载器", "系统下载管理器")
        private val DOWNLOAD_PROTOCOL_NAMES = arrayOf("自动", "优先 HTTP/2", "HTTP/1.1")
        private val DOWNLOAD_PARALLEL_NAMES = arrayOf("1", "2", "3", "4")
        private val THEME_COLOR_IDS = arrayOf("blue", "green", "orange", "purple")

        /** 长按菜单项。 */
        private val LONG_PRESS_ITEMS = listOf(
            "background" to "后台打开",
            "newtab" to "新标签打开",
            "play" to "通过应用播放",
            "copy" to "复制链接",
            "copylinktext" to "复制链接文本",
            "share" to "分享链接",
            "download" to "下载链接",
            "viewimage" to "查看图片",
            "saveimage" to "保存图片",
            "downloadimage" to "下载图片",
            "shareimage" to "分享图片",
            "reverseimage" to "以图搜图",
            "imagemode" to "看图模式",
            "scanimage" to "识别图片二维码",
            "qrcode" to "生成二维码",
            "scanqr" to "扫描二维码",
            "pageinfo" to "页面信息",
            "markad" to "拦截目标域名"
        )

        /** 文本选择菜单附加项。 */
        private val SELECTION_ITEMS = listOf(
            "search" to "搜索",
            "share" to "分享",
            "copy" to "复制"
        )

        /** 底部工具栏按钮（id 与布局一致）。 */
        private val TOOLBAR_ITEMS = listOf(
            "tabs" to "标签",
            "back" to "后退",
            "forward" to "前进",
            "home" to "主页",
            "menu" to "菜单"
        )

        /** 菜单项目录（上方可移动区与下方固定栏共用同一套候选项）。 */
        private val CATALOG = listOf(
            "refresh" to "刷新/停止",
            "home" to "主页",
            "addbookmark" to "添加书签",
            "bookmarks" to "书签",
            "downloads" to "下载列表",
            "history" to "历史",
            "adblockstats" to "广告拦截统计",
            "adblock" to "广告拦截",
            "find" to "页内查找",
            "font" to "字体大小",
            "uiscale" to "界面缩放",
            "share" to "分享链接",
            "copylink" to "复制链接",
            "settings" to "设置",
            "reader" to "阅读模式",
            "sniff" to "资源嗅探",
            "scan" to "扫码",
            "readaloud" to "朗读网页",
            "savepage" to "保存网页",
            "screenshot" to "截图",
            "print" to "打印/PDF",
            "passwords" to "密码库",
            "scriptmenu" to "脚本菜单",
            "addhome" to "添加到桌面",
            "cleardata" to "清除数据",
            "adpick" to "选取广告元素",
            "closetabs" to "关闭全部标签",
            "dark" to "夜间模式",
            "nightauto" to "夜间自动跟随系统",
            "incog" to "无痕",
            "noimg" to "无图模式",
            "desktop" to "电脑模式",
            "game" to "游戏模式",
            "readlaterlist" to "离线库",
            "translatepage" to "翻译网页",
            "source" to "查看源码",
            "newtab" to "新建标签",
            "closeothers" to "关闭其它标签",
            "recentclosed" to "最近关闭",
            "fullscreen" to "全屏"
        )

        /** 固定栏默认项。 */
        private val DEFAULT_FIXED_IDS = listOf("dark", "incog", "noimg", "desktop")
    }
    private fun applyImport(uri: android.net.Uri) {
        val bytes = FileExchange.readBytes(requireContext(), uri)
        if (bytes == null || bytes.isEmpty()) {
            toast(R.string.rules_import_empty)
            return
        }
        if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            openZipBackup(bytes)
            return
        }
        val text = bytes.toString(Charsets.UTF_8)
        if (com.sii.browser.data.ExportCrypto.isEncrypted(text)) {
            promptImportPassword { password ->
                val plain = com.sii.browser.data.ExportCrypto.decrypt(text, password)
                if (plain == null) toast(R.string.import_password_wrong)
                else previewAndImport(plain)
            }
            return
        }
        previewAndImport(text)
    }

    /** 读取 zip 备份：data.json 明文，或 data.enc 需密码。 */
    private fun openZipBackup(bytes: ByteArray) {
        val entries = mutableMapOf<String, String>()
        val ok = runCatching {
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "data.json" || entry.name == "data.enc") {
                        entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }.isSuccess
        if (!ok) {
            toast(R.string.rules_import_failed)
            return
        }
        when {
            entries["data.enc"] != null -> promptImportPassword { password ->
                val plain = com.sii.browser.data.ExportCrypto.decrypt(
                    entries.getValue("data.enc"), password
                )
                if (plain == null) toast(R.string.import_password_wrong)
                else previewAndImport(plain)
            }
            entries["data.json"] != null -> previewAndImport(entries.getValue("data.json"))
            else -> toast(R.string.rules_import_failed)
        }
    }

    /** 导入前先显示类别与条目数，可勾选要恢复的项。 */
    private fun previewAndImport(text: String) {
        val root = runCatching { org.json.JSONObject(text) }.getOrNull()
        if (root == null) {
            toast(R.string.rules_import_failed)
            return
        }
        val cats = listOf(
            Triple("bookmarks", R.string.backup_cat_bookmarks, root.optJSONArray("bookmarks")?.length() ?: 0),
            Triple("history", R.string.backup_cat_history, root.optJSONArray("history")?.length() ?: 0),
            Triple("favorites", R.string.backup_cat_favorites, root.optJSONArray("favorites")?.length() ?: 0),
            Triple("downloads", R.string.backup_cat_downloads, root.optJSONArray("downloads")?.length() ?: 0),
            Triple("passwords", R.string.backup_cat_passwords, root.optJSONArray("passwords")?.length() ?: 0),
            Triple("scripts", R.string.backup_cat_scripts, root.optJSONArray("scripts")?.length() ?: 0),
            Triple("siteConfigs", R.string.backup_cat_site_configs, root.optJSONArray("siteConfigs")?.length() ?: 0),
            Triple("adblock", R.string.backup_cat_adblock, root.optJSONArray("adblock")?.length() ?: 0),
            Triple("settings", R.string.backup_cat_settings, root.optJSONObject("settings")?.length() ?: 0)
        ).filter { it.third > 0 }
        if (cats.isEmpty()) {
            toast(R.string.rules_import_empty)
            return
        }
        val labels = cats.map { getString(it.second) + "（" + it.third + "）" }.toTypedArray()
        val checked = BooleanArray(cats.size) { true }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_import_title)
            .setMultiChoiceItems(labels, checked) { _, which, value -> checked[which] = value }
            .setPositiveButton(R.string.backup_import_confirm_button) { _, _ ->
                val selected = cats.filterIndexed { index, _ -> checked[index] }
                    .map { it.first }.toSet()
                if (selected.isEmpty()) return@setPositiveButton
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.backup_import_mode_title)
                    .setMessage(R.string.backup_import_mode_message)
                    .setPositiveButton(R.string.backup_import_merge) { _, _ ->
                        performImport(root, selected, overwrite = false)
                    }
                    .setNeutralButton(R.string.backup_import_overwrite) { _, _ ->
                        android.app.AlertDialog.Builder(requireContext())
                            .setTitle(R.string.backup_import_overwrite)
                            .setMessage(R.string.backup_import_overwrite_confirm)
                            .setPositiveButton(R.string.backup_import_overwrite) { _, _ ->
                                performImport(root, selected, overwrite = true)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performImport(
        root: org.json.JSONObject,
        selected: Set<String>,
        overwrite: Boolean
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = runCatching {
                if (overwrite) {
                    if ("bookmarks" in selected) {
                        AppServices.bookmarkRepository.allItemsOnce().forEach {
                            AppServices.bookmarkRepository.deleteItem(it.id)
                        }
                    }
                    if ("history" in selected) AppServices.historyRepository.clearAll()
                    if ("favorites" in selected) AppServices.favoriteRepository.clearAll()
                    if ("passwords" in selected) AppServices.passwordRepository.clearAll()
                    if ("scripts" in selected) AppServices.scriptRepository.clearAll()
                    if ("siteConfigs" in selected) AppServices.siteConfigRepository.clearAll()
                    if ("adblock" in selected) AppServices.adBlock.clearCustomRules()
                }
                if ("bookmarks" in selected) root.optJSONArray("bookmarks")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val url = item.optString("url")
                        if (url.isBlank()) continue
                        if (AppServices.bookmarkRepository.findItemByUrl(url) == null) {
                            AppServices.bookmarkRepository.addItem(url, item.optString("title"), null)
                        }
                    }
                }
                if ("history" in selected) root.optJSONArray("history")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val url = item.optString("url")
                        if (url.isBlank()) continue
                        if (AppServices.historyRepository.findByUrl(url) == null) {
                            AppServices.historyRepository.recordVisit(url, item.optString("title"))
                        }
                    }
                }
                if ("favorites" in selected) root.optJSONArray("favorites")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val url = item.optString("url")
                        if (url.isBlank()) continue
                        if (AppServices.favoriteRepository.findByUrl(url) == null) {
                            AppServices.favoriteRepository.add(url, item.optString("title"))
                        }
                    }
                }
                if ("scripts" in selected) root.optJSONArray("scripts")?.let { arr ->
                    val existing = AppServices.scriptRepository.all().first()
                    val names = existing.map { it.name }.toMutableSet()
                    val urls = existing.mapNotNull { it.url?.takeIf { u -> u.isNotBlank() } }.toMutableSet()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val name = item.optString("name")
                        val url = item.optString("url")
                        if (name in names || (url.isNotBlank() && url in urls)) continue
                        AppServices.scriptRepository.saveScript(
                            existing = null,
                            name = name,
                            match = item.optString("match"),
                            runAt = item.optString("runAt"),
                            code = item.optString("code"),
                            sourceUrl = url.ifBlank { null }
                        )
                        if (name.isNotBlank()) names.add(name)
                        if (url.isNotBlank()) urls.add(url)
                    }
                }
                if ("siteConfigs" in selected) root.optJSONArray("siteConfigs")?.let { arr ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            AppServices.siteConfigRepository.put(
                                item.optString("host"), item.optString("config")
                            )
                        }
                    }
                }
                if ("adblock" in selected) root.optJSONArray("adblock")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val line = arr.optString(i)
                        if (line.isNotBlank() &&
                            !AppServices.adBlock.hasCustomRule(line)
                        ) {
                            AppServices.adBlock.addCustomRule(line)
                        }
                    }
                }
                if ("settings" in selected) root.optJSONObject("settings")?.let { obj ->
                    val values = mutableMapOf<String, Any>()
                    obj.keys().forEach { key ->
                        when (val value = obj.get(key)) {
                            is Boolean -> values[key] = value
                            is Number -> values[key] = value.toInt()
                            is String -> values[key] = value
                        }
                    }
                    AppServices.settings.restore(values)
                }
                if ("passwords" in selected) root.optJSONArray("passwords")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val host = item.optString("host")
                        val username = item.optString("username")
                        val password = item.optString("password")
                        if (host.isNotBlank() && password.isNotEmpty()) {
                            AppServices.passwordRepository.save(host, username, password)
                        }
                    }
                }
                true
            }.getOrDefault(false)
            toast(if (ok) R.string.data_imported else R.string.rules_import_failed)
        }
    }
    /** 主题色应用范围：逐项选择哪些位置在主页使用主题色；最后一个开关控制网页。 */
    private fun buildV2ColorDefine() {
        card(
            switchRow(
                getString(R.string.settings_color_part_toolbar),
                settings.themePartToolbar()
            ) { settings.setThemePartToolbar(it) },
            switchRow(
                getString(R.string.settings_color_part_tabs),
                settings.themePartTabs()
            ) { settings.setThemePartTabs(it) },
            switchRow(
                getString(R.string.settings_color_part_sites),
                settings.themePartSites()
            ) { settings.setThemePartSites(it) },
            switchRow(
                getString(R.string.settings_color_part_home_bg),
                settings.themePartHomeBg()
            ) { settings.setThemePartHomeBg(it) },
            switchRow(
                getString(R.string.settings_color_part_menu),
                settings.themePartMenu()
            ) { settings.setThemePartMenu(it) },
            switchRow(
                getString(R.string.settings_color_part_status),
                settings.themePartStatus()
            ) { settings.setThemePartStatus(it) },
            switchRow(
                getString(R.string.settings_color_night),
                settings.themePartsNight(),
                getString(R.string.settings_color_night_desc)
            ) { settings.setThemePartsNight(it) }
        )
        card(
            switchRow(
                getString(R.string.settings_color_over_detect),
                settings.themeOverDetect(),
                getString(R.string.settings_color_over_detect_desc)
            ) {
                settings.setThemeOverDetect(it)
                build()
            }
        )
        aboutText(R.string.about_color_define)
    }

    private fun buildThemeColor() {
        card(
            switchRow(
                getString(R.string.settings_theme_color_detect),
                settings.themeColorEnabled(),
                getString(R.string.settings_theme_color_detect_desc)
            ) {
                settings.setThemeColorEnabled(it)
                build()
            }
        )
        val master = settings.themeColorEnabled()
        card(
            switchRow(
                getString(R.string.settings_theme_color_toolbar),
                settings.themeColorToolbar(),
                enabled = master
            ) { settings.setThemeColorToolbar(it) },
            switchRow(
                getString(R.string.settings_theme_color_tabs),
                settings.themeColorTabs(),
                enabled = master
            ) { settings.setThemeColorTabs(it) },
            switchRow(
                getString(R.string.settings_theme_color_address),
                settings.themeColorAddress(),
                enabled = master
            ) { settings.setThemeColorAddress(it) },
            switchRow(
                getString(R.string.settings_theme_color_status),
                settings.themeColorStatus(),
                enabled = master
            ) { settings.setThemeColorStatus(it) },
            switchRow(
                getString(R.string.settings_theme_color_menu),
                settings.themeColorMenu(),
                enabled = master
            ) { settings.setThemeColorMenu(it) },
            switchRow(
                getString(R.string.settings_theme_color_long_press),
                settings.themeColorLongPress(),
                enabled = master
            ) { settings.setThemeColorLongPress(it) },
            switchRow(
                getString(R.string.settings_theme_color_force_bg),
                settings.themeColorForceBackground(),
                getString(R.string.settings_theme_color_force_bg_desc),
                enabled = master
            ) { settings.setThemeColorForceBackground(it) }
        )
        aboutText(R.string.about_theme_color)
    }

    /** 选择主页背景图（SAF），带持久化授权。 */
    private fun themeColorIndex(): Int {
        val idx = THEME_COLOR_IDS.indexOf(AppServices.settings.skin())
        return if (idx >= 0) idx else 0
    }
    private fun chooseHomeBackground() {
        if (settings.homeBackground() != null) {
            android.app.AlertDialog.Builder(requireContext())
                .setItems(
                    arrayOf(getString(R.string.settings_home_background_set), getString(R.string.settings_home_background_clear))
                ) { _, which ->
                    if (which == 0) {
                        bgLauncher.launch(arrayOf("image/*"))
                    } else {
                        settings.setHomeBackground(null)
                        build()
                    }
                }
                .show()
        } else {
            bgLauncher.launch(arrayOf("image/*"))
        }
    }

    /** 编辑自定义 CSS（注入到所有网页）。 */
    private val EXPORT_CATEGORIES = listOf(
        "bookmarks" to "书签",
        "history" to "历史",
        "favorites" to "主页收藏",
        "downloads" to "下载记录",
        "passwords" to "密码库",
        "scripts" to "用户脚本",
        "siteConfigs" to "站点设置",
        "adblock" to "广告拦截规则",
        "settings" to "设置习惯"
    )

    private fun exportData() {
        val checked = BooleanArray(EXPORT_CATEGORIES.size) { it < 3 }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_export_data)
            .setMultiChoiceItems(
                EXPORT_CATEGORIES.map { it.second }.toTypedArray(),
                checked
            ) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = EXPORT_CATEGORIES.filterIndexed { index, _ -> checked[index] }
                    .map { it.first }.toSet()
                if (selected.isNotEmpty()) promptExportPassword { password ->
                    runExport(selected, password)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    private fun importData() {
        importLauncher.launch(
            arrayOf(
                "application/zip",
                "application/json",
                "application/octet-stream",
                "text/plain",
                "*/*"
            )
        )
    }
    private fun toast(resId: Int) {
        android.widget.Toast.makeText(requireContext(), resId, android.widget.Toast.LENGTH_SHORT).show()
    }
    private fun toast(text: String) {
        android.widget.Toast.makeText(requireContext(), text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun promptExportPassword(onConfirm: (String?) -> Unit) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.export_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.export_password_title)
            .setMessage(R.string.export_password_message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onConfirm(input.text.toString().ifBlank { null })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptImportPassword(onConfirm: (String) -> Unit) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.export_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.import_password_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onConfirm(input.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runExport(selected: Set<String>, password: String? = null) {
        viewLifecycleOwner.lifecycleScope.launch {
            val root = org.json.JSONObject().apply {
                put("version", 1)
                put("exportedAt", System.currentTimeMillis())
            }
            if ("bookmarks" in selected) {
                val bookmarks = AppServices.bookmarkRepository.allItemsOnce()
                root.put("bookmarks", org.json.JSONArray().apply {
                    bookmarks.forEach { b ->
                        put(org.json.JSONObject().apply { put("url", b.url); put("title", b.title) })
                    }
                })
            }
            if ("history" in selected) {
                val history = AppServices.historyRepository.recent(500).first()
                root.put("history", org.json.JSONArray().apply {
                    history.forEach { h ->
                        put(org.json.JSONObject().apply { put("url", h.url); put("title", h.title) })
                    }
                })
            }
            if ("favorites" in selected) {
                val favorites = AppServices.favoriteRepository.all().first()
                root.put("favorites", org.json.JSONArray().apply {
                    favorites.forEach { f ->
                        put(org.json.JSONObject().apply { put("url", f.url); put("title", f.title) })
                    }
                })
            }
            if ("downloads" in selected) {
                val tasks = AppServices.downloadRepository.allTasks().first()
                root.put("downloads", org.json.JSONArray().apply {
                    tasks.forEach { t ->
                        put(org.json.JSONObject().apply {
                            put("url", t.url)
                            put("name", t.name)
                            put("state", t.state)
                            put("downloadedSize", t.downloadedSize)
                            put("totalSize", t.totalSize)
                        })
                    }
                })
            }
            var passwordsSkipped = 0
            if ("passwords" in selected) {
                val passwords = AppServices.passwordRepository.all().first()
                if (password.isNullOrBlank()) {
                    passwordsSkipped = passwords.size
                } else {
                    root.put("passwords", org.json.JSONArray().apply {
                        passwords.forEach { p ->
                            val plain = AppServices.passwordRepository.decrypt(p)
                            if (plain == null) {
                                passwordsSkipped++
                            } else {
                                put(org.json.JSONObject().apply {
                                    put("host", p.host)
                                    put("username", p.username)
                                    put("password", plain)
                                })
                            }
                        }
                    })
                }
            }
            if ("scripts" in selected) {
                val scripts = AppServices.scriptRepository.all().first()
                root.put("scripts", org.json.JSONArray().apply {
                    scripts.forEach { item ->
                        put(org.json.JSONObject().apply {
                            put("name", item.name)
                            put("author", item.author)
                            put("url", item.url)
                            put("match", item.match)
                            put("exclude", item.exclude)
                            put("runAt", item.runAt)
                            put("code", item.code)
                            put("enabled", item.enabled)
                        })
                    }
                })
            }
            if ("siteConfigs" in selected) {
                val configs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    AppServices.siteConfigRepository.all()
                }
                root.put("siteConfigs", org.json.JSONArray().apply {
                    configs.forEach { c ->
                        put(org.json.JSONObject().apply {
                            put("host", c.host)
                            put("config", c.config)
                        })
                    }
                })
            }
            if ("adblock" in selected) {
                val rules = AppServices.adBlock.customRuleTexts()
                root.put("adblock", org.json.JSONArray().apply { rules.forEach { put(it) } })
            }
            if ("settings" in selected) {
                val values = org.json.JSONObject()
                AppServices.settings.snapshot().forEach { (key, value) ->
                    if (key != com.sii.browser.data.SettingsKeys.HOME_BACKGROUND) {
                        values.put(key, value)
                    }
                }
                root.put("settings", values)
            }
            val name = "sii-backup-${System.currentTimeMillis()}.zip"
            val payload = runCatching {
                if (password.isNullOrBlank()) root.toString()
                else com.sii.browser.data.ExportCrypto.encrypt(root.toString(), password)
            }.getOrNull()
            if (payload == null) {
                toast(R.string.export_failed)
                return@launch
            }
            val bytes = runCatching {
                val out = java.io.ByteArrayOutputStream()
                java.util.zip.ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("info.json"))
                    zip.write(
                        org.json.JSONObject()
                            .put("format", 2)
                            .put("exportedAt", System.currentTimeMillis())
                            .put("counts", org.json.JSONObject().apply {
                                root.optJSONArray("bookmarks")?.let { put("bookmarks", it.length()) }
                                root.optJSONArray("history")?.let { put("history", it.length()) }
                                root.optJSONArray("passwords")?.let { put("passwords", it.length()) }
                                root.optJSONArray("scripts")?.let { put("scripts", it.length()) }
                            })
                            .toString().toByteArray(Charsets.UTF_8)
                    )
                    zip.closeEntry()
                    zip.putNextEntry(
                        java.util.zip.ZipEntry(if (password.isNullOrBlank()) "data.json" else "data.enc")
                    )
                    zip.write(payload.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                out.toByteArray()
            }.getOrNull()
            if (bytes == null) {
                toast(R.string.export_failed)
                return@launch
            }
            val path = FileExchange.writeBytesToDownloads(
                requireContext(), name, "application/zip", bytes
            )
            if (path != null) {
                toast(getString(R.string.data_exported_path, path))
                if (passwordsSkipped > 0) toast(R.string.backup_passwords_skipped)
            } else {
                toast(R.string.rules_import_failed)
            }
        }
    }

    /** 从 SAF 选中的文件导入书签/历史/主页收藏（与导出格式对应）。 */

    private fun buildDesktopSites() {
        val sites = desktopSitesList()
        container.addView(
            CardList.sectionTitle(requireContext(), getString(R.string.settings_desktop_sites))
        )
        if (sites.isEmpty()) {
            card(hintSiteRow())
        } else {
            card(*sites.map { desktopSiteRow(it) }.toTypedArray())
        }
        card(addSiteRow())
        aboutText(R.string.settings_desktop_sites_desc)
    }

    private fun editCustomCss() {
        val input = EditText(requireContext()).apply {
            setText(settings.customCss().orEmpty())
            minLines = 6
            gravity = android.view.Gravity.TOP
            hint = getString(R.string.settings_custom_css_hint)
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_custom_css)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings.setCustomCss(input.text.toString())
                build()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 等级预设：一键套用一组独立开关（套用后各开关仍可单独改）。 */


    private fun appVersionCode(): Long = runCatching {
        val info = requireContext().packageManager
            .getPackageInfo(requireContext().packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    /** 检查更新（地址未配置时提示先填地址）。 */

    private fun checkForUpdate() {
        viewLifecycleOwner.lifecycleScope.launch {
            val info = com.sii.browser.data.UpdateChecker.check()
            if (info == null) {
                toast(R.string.update_check_failed)
                return@launch
            }
            if (!com.sii.browser.data.UpdateChecker.isUpdate(info, appVersion(), appVersionCode())) {
                settings.setUpdateLatest(null)
                toast(R.string.update_latest)
                return@launch
            }
            val name = info.versionName.ifBlank { info.versionCode.toString() }
            settings.setUpdateLatest(
                listOf(name, info.url, info.notes.orEmpty()).joinToString("|")
            )
            val message = buildString {
                append(getString(R.string.update_available, name))
                info.notes?.let { append("\n\n").append(it.take(600)) }
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_check_update)
                .setMessage(message)
                .setPositiveButton(R.string.update_download) { _, _ -> openUpdateUrl(info.url) }
                .setNeutralButton(R.string.update_ignore_version) { _, _ ->
                    settings.setUpdateIgnoredVersion(name)
                    settings.setUpdateLatest(null)
                    toast(R.string.update_ignored)
                    build()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** 打开 Release 页面：优先用浏览器内打开，设置页不在栈顶时先回根页。 */
    private fun openUpdateUrl(url: String) {
        if (url.isBlank()) return
        val manager = parentFragmentManager
        repeat(manager.backStackEntryCount) { manager.popBackStackImmediate() }
        val opener = AppServices.openUrl
        if (opener != null) opener(url) else openRepositoryExternal(url)
    }

    private fun card(vararg rows: View) {
        val cardView = CardList.settingsCard(requireContext(), settings.settingsCardAlpha())
        CardList.addRows(cardView, rows.toList())
        container.addView(cardView)
    }

    /** 滑竿行：标题 + 当前值 + SeekBar（实时回调）。 */

    private fun sliderRow(
        title: String,
        value: Int,
        min: Int,
        max: Int,
        step: Int,
        onFinished: (() -> Unit)? = null,
        onChange: (Int) -> Unit
    ): View {
        val context = requireContext()
        val valueText = TextView(context).apply {
            text = getString(R.string.slider_value, value)
            setTextSize(13f)
            setTextColor(context.getColor(R.color.text_secondary))
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(titleView(title), weightParams())
            addView(valueText)
        }
        val seek = android.widget.SeekBar(context).apply {
            this.max = ((max - min) / step).coerceAtLeast(1)
            progress = ((value - min) / step).coerceIn(0, this.max)
            setPadding(0, dpToPx(6), 0, dpToPx(2))
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val newValue = min + progress * step
                    valueText.text = getString(R.string.slider_value, newValue)
                    if (fromUser) onChange(newValue)
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                    onFinished?.invoke()
                }
            })
        }
        return CardList.column(context).apply {
            addView(header)
            addView(seek)
        }
    }

    /** 页尾说明文案（对齐 index.html .about）。 */

    private fun aboutText(resId: Int) {
        container.addView(TextView(requireContext()).apply {
            text = getString(resId)
            setTextSize(12f)
            setTextColor(requireContext().getColor(R.color.text_secondary))
            gravity = android.view.Gravity.CENTER
            setPadding(dpToPx(18), dpToPx(26), dpToPx(18), dpToPx(20))
            setLineSpacing(0f, 1.8f)
        })
    }

    /** 默认电脑模式网站二级页：每个网站一行，可添加、编辑、删除。 */

    private fun desktopSitesList(): List<String> =
        settings.desktopSites().split(',', '，', ';', '；', '\n')
            .map { it.trim() }.filter { it.isNotEmpty() }

    private fun saveDesktopSites(sites: List<String>) {
        settings.setDesktopSites(sites.joinToString(","))
    }

    /** 归一化域名：去协议、尾斜杠与 www./m./mobile. 前缀。 */

    private fun normalizeDesktopHost(host: String): String =
        host.trim().lowercase()
            .removePrefix("http://").removePrefix("https://").removeSuffix("/")
            .removePrefix("www.").removePrefix("m.").removePrefix("mobile.")

    /** 被关闭的站点（归一化 host）。 */

    private fun disabledDesktopSites(): List<String> =
        settings.desktopSitesDisabled().split(',', '，', ';', '；', '\n')
            .map { normalizeDesktopHost(it) }.filter { it.isNotEmpty() }

    private fun saveDisabledDesktopSites(hosts: List<String>) {
        settings.setDesktopSitesDisabled(hosts.distinct().joinToString(","))
    }

    private fun hintSiteRow(): View =
        CardList.row(requireContext()).apply {
            addView(titleView(getString(R.string.settings_desktop_sites_empty)), weightParams())
            setOnClickListener { addDesktopSite() }
        }

    private fun desktopSiteRow(site: String): View {
        val normalized = normalizeDesktopHost(site)
        val toggle = android.widget.Switch(requireContext()).apply {
            isChecked = normalized !in disabledDesktopSites()
            setOnCheckedChangeListener { _, checked ->
                val disabled = disabledDesktopSites().toMutableList()
                if (checked) disabled.remove(normalized) else disabled.add(normalized)
                saveDisabledDesktopSites(disabled)
            }
        }
        return CardList.row(requireContext()).apply {
            addView(titleView(site), weightParams())
            addView(toggle)
            setOnClickListener { editDesktopSite(site) }
            setOnLongClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(site)
                    .setItems(
                        arrayOf(
                            getString(R.string.desktop_sites_edit),
                            getString(R.string.delete)
                        )
                    ) { _, which ->
                        if (which == 0) editDesktopSite(site) else confirmDeleteDesktopSite(site)
                    }
                    .show()
                true
            }
        }
    }

    private fun confirmDeleteDesktopSite(site: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_desktop_sites)
            .setMessage(getString(R.string.desktop_sites_delete_confirm, site))
            .setPositiveButton(R.string.delete) { _, _ ->
                saveDesktopSites(desktopSitesList().filter { it != site })
                saveDisabledDesktopSites(disabledDesktopSites() - normalizeDesktopHost(site))
                build()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addSiteRow(): View =
        CardList.row(requireContext()).apply {
            addView(
                titleView(
                    getString(R.string.settings_desktop_sites_add),
                    getString(R.string.settings_desktop_sites_add_desc)
                ),
                weightParams()
            )
            addView(valueView("＋"))
            setOnClickListener { addDesktopSite() }
        }

    private fun addDesktopSite() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.settings_desktop_sites_input_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_desktop_sites_add)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val site = input.text.toString().trim()
                if (site.isNotEmpty()) {
                    saveDesktopSites(desktopSitesList() + site)
                    build()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editDesktopSite(site: String) {
        val input = EditText(requireContext()).apply {
            setText(site)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_desktop_sites)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newSite = input.text.toString().trim()
                val updated = desktopSitesList()
                    .map { if (it == site) newSite else it }
                    .filter { it.isNotEmpty() }
                saveDesktopSites(updated)
                // 改名后清除旧名的禁用标记，新名保持启用。
                saveDisabledDesktopSites(disabledDesktopSites() - normalizeDesktopHost(site))
                build()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun decimalRow(
        title: String,
        initialMs: Int,
        minMs: Int,
        maxMs: Int,
        desc: String? = null,
        onSave: (Int) -> Unit
    ): View {
        val initialSeconds = initialMs / 1000.0
        val value = valueView(String.format(java.util.Locale.ROOT, "%.1f 秒", initialSeconds))
        return CardList.row(requireContext()).apply {
            addView(titleView(title, desc), weightParams())
            addView(value)
            setOnClickListener {
                val input = EditText(requireContext()).apply {
                    setText(String.format(java.util.Locale.ROOT, "%.1f", initialSeconds))
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("$title (0.1-10 秒)")
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val seconds = input.text.toString().toDoubleOrNull() ?: initialSeconds
                        val ms = (seconds * 1000).toInt().coerceIn(minMs, maxMs)
                        value.text = String.format(
                            java.util.Locale.ROOT, "%.1f 秒", ms / 1000.0
                        )
                        onSave(ms)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun numberRow(
        title: String,
        initial: Int,
        min: Int,
        max: Int,
        desc: String? = null,
        onSave: (Int) -> Unit
    ): View {
        val value = valueView(initial.toString())
        return CardList.row(requireContext()).apply {
            addView(titleView(title, desc), weightParams())
            addView(value)
            setOnClickListener {
                val input = EditText(requireContext()).apply {
                    setText(initial.toString())
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("$title ($min-$max)")
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val parsed = input.text.toString().toIntOrNull() ?: initial
                        val clamped = parsed.coerceIn(min, max)
                        value.text = clamped.toString()
                        onSave(clamped)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun textRow(
        title: String,
        initial: String,
        enabled: Boolean = true,
        onSave: (String) -> Unit
    ): View {
        val value = valueView(initial)
        return CardList.row(requireContext()).apply {
            alpha = if (enabled) 1f else 0.4f
            addView(titleView(title), weightParams())
            addView(value)
            setOnClickListener {
                if (!enabled) return@setOnClickListener
                val input = EditText(requireContext()).apply { setText(initial) }
                AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val text = input.text.toString().trim()
                        onSave(text)
                        value.text = text.ifEmpty { "-" }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun choiceRow(
        title: String,
        options: Array<String>,
        selected: Int,
        desc: String? = null,
        enabled: Boolean = true,
        onSelect: (Int) -> Unit
    ): View {
        val value = valueView(options.getOrElse(selected) { "" })
        return CardList.row(requireContext()).apply {
            alpha = if (enabled) 1f else 0.4f
            addView(titleView(title, desc), weightParams())
            addView(value)
            setOnClickListener {
                if (!enabled) return@setOnClickListener
                AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setSingleChoiceItems(options, selected) { dialog, which ->
                        dialog.dismiss()
                        value.text = options[which]
                        onSelect(which)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun stepRow(title: String, initial: Int, step: Int, min: Int, max: Int, onSave: (Int) -> Unit): View {
        val value = valueView("$initial%")
        return CardList.row(requireContext()).apply {
            addView(titleView(title), weightParams())
            addView(value)
            setOnClickListener {
                val next = (initial + step).coerceIn(min, max)
                onSave(next)
                value.text = "$next%"
            }
            setOnLongClickListener {
                val prev = (initial - step).coerceIn(min, max)
                onSave(prev)
                value.text = "$prev%"
                true
            }
        }
    }

    private fun switchRow(
        title: String,
        checked: Boolean,
        desc: String? = null,
        enabled: Boolean = true,
        onChanged: (Boolean) -> Unit
    ): View {
        val switch = Switch(requireContext()).apply {
            isChecked = checked
            isEnabled = enabled
            trackDrawable = androidx.core.content.ContextCompat
                .getDrawable(requireContext(), R.drawable.switch_track)
            thumbDrawable = androidx.core.content.ContextCompat
                .getDrawable(requireContext(), R.drawable.switch_thumb)
            layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(26))
            setOnCheckedChangeListener { _, value -> if (enabled) onChanged(value) }
        }
        return CardList.row(requireContext()).apply {
            alpha = if (enabled) 1f else 0.4f
            addView(titleView(title, desc), weightParams())
            addView(switch)
        }
    }

    private fun navRow(
        title: String,
        desc: String? = null,
        enabled: Boolean = true,
        onClick: () -> Unit
    ): View {
        return CardList.row(requireContext()).apply {
            alpha = if (enabled) 1f else 0.4f
            addView(titleView(title, desc), weightParams())
            addView(valueView("›"))
            setOnClickListener { if (enabled) onClick() }
        }
    }

    private fun infoRow(title: String, value: String): View {
        return CardList.row(requireContext()).apply {
            addView(titleView(title), weightParams())
            addView(valueView(value))
        }
    }

    private fun titleView(title: String): TextView = TextView(requireContext()).apply {
        text = title
        setTextSize(14f)
        setTextColor(requireContext().getColor(R.color.text_primary))
    }

    /** 标题 + 可选灰色副说明。 */

    private fun dpToPx(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun valueView(text: String): TextView = TextView(requireContext()).apply {
        this.text = text.ifEmpty { "-" }
        setTextSize(13f)
        setTextColor(requireContext().getColor(R.color.text_secondary))
    }

    private fun weightParams() =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    /** 快捷引擎与标签栏不能同时占用「工具栏上方」位置。 */

    private fun showLayoutConflict(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_ui_layout)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun titleView(title: String, desc: String? = null): LinearLayout {
        val column = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(requireContext()).apply {
            text = title
            setTextSize(14f)
            setTextColor(requireContext().getColor(R.color.text_primary))
        })
        if (!desc.isNullOrEmpty()) {
            column.addView(TextView(requireContext()).apply {
                text = desc
                setTextSize(12.5f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
                alpha = 0.55f
                setPadding(0, dpToPx(3), 0, 0)
                setLineSpacing(0f, 1.35f)
            })
        }
        return column
    }

    private fun open(targetView: String) {
        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(R.id.shell_root, SettingsDetailFragment().apply {
                arguments = bundleOf(ARG_VIEW to targetView)
            })
            .addToBackStack(null)
            .commit()
    }

    private fun openFragment(fragment: Fragment) {
        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(R.id.shell_root, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun confirmClearData() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_clear_data)
            .setMessage(R.string.clear_data_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                AppServices.privacyManager.clearAll {
                    viewLifecycleOwner.lifecycleScope.launch {
                        AppServices.historyRepository.clearAll()
                    }
                }
                Toast.makeText(requireContext(), R.string.data_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun translateTargetLabel(): String {
        val tag = settings.translateTarget()
        return if (tag.isBlank()) getString(R.string.settings_translate_target_follow)
        else com.sii.browser.core.webview.TranslateLanguages.label(tag)
    }

    private fun showTranslateTargetDialog() {
        val current = settings.translateTarget()
        val labels = mutableListOf(getString(R.string.settings_translate_target_follow))
        labels.addAll(
            com.sii.browser.core.webview.TranslateLanguages.ALL.map { it.label }
        )
        val selected = if (current.isBlank()) 0 else
            com.sii.browser.core.webview.TranslateLanguages.ALL.indexOfFirst {
                it.tag.equals(current, true)
            }.let { if (it >= 0) it + 1 else 0 }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_translate_target)
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, which ->
                settings.setTranslateTarget(
                    if (which == 0) ""
                    else com.sii.browser.core.webview.TranslateLanguages.ALL[which - 1].tag
                )
                dialog.dismiss()
                build()
            }
            .show()
    }

    /** 退出时清理的类目多选。 */

    private fun showExitClearItemsDialog() {
        val items = listOf(
            "cache" to "缓存",
            "cookies" to "Cookies",
            "history" to "历史记录",
            "form" to "表单数据",
            "webstorage" to "网页存储",
            "downloads" to "下载记录",
            "passwords" to "密码库",
            "favorites" to "主页收藏",
            "scripts" to "用户脚本",
            "siteconfigs" to "站点设置"
        )
        val selected = settings.exitClearItems().split(',').toSet()
        val checked = items.map { it.first in selected }.toBooleanArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_clear_on_exit_items)
            .setMultiChoiceItems(items.map { it.second }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings.setExitClearItems(
                    items.filterIndexed { index, _ -> checked[index] }.joinToString(",") { it.first }
                )
                build()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLicense() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_about_license)
            .setMessage(R.string.settings_license_text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** 打开项目仓库：先退回浏览器，再用内置标签加载；内置浏览不可用时交给系统。 */
    /** 从设置搜索跳转：滚动到匹配行并短暂高亮。 */
    private fun focusRequestedRow() {
        val keyword = arguments?.getString(ARG_FOCUS)?.takeIf { it.isNotBlank() } ?: return
        container.post {
            val target = findRowByText(container, keyword) ?: return@post
            val scroll = view?.findViewById<android.widget.ScrollView>(R.id.settings_scroll)
            scroll?.smoothScrollTo(0, target.top)
            val accent = ThemeAccent.color(requireContext())
            val highlight = (accent and 0x00FFFFFF) or (0x33 shl 24)
            target.setBackgroundColor(highlight)
            target.postDelayed({ target.background = null }, 1600)
        }
    }

    private fun findRowByText(group: ViewGroup, keyword: String): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is ViewGroup) {
                val found = findRowByText(child, keyword)
                if (found != null) return found
            } else if (child is android.widget.TextView &&
                child.text.contains(keyword, ignoreCase = true)
            ) {
                var row: View = child
                var parent = child.parent
                while (parent is View && parent !== container) {
                    row = parent
                    parent = parent.parent
                }
                return row
            }
        }
        return null
    }

    private fun openRepository() {
        val url = getString(R.string.about_repo_url)
        if (AppServices.openUrl != null) {
            // 设置页盖在浏览器之上，直接加载会被设置页遮住，先退回根页面。
            val manager = parentFragmentManager
            repeat(manager.backStackEntryCount) { manager.popBackStackImmediate() }
        }
        val opener = AppServices.openUrl
        if (opener != null) opener(url) else openRepositoryExternal(url)
    }

    private fun openRepositoryExternal(url: String) {
        runCatching {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)
                )
            )
        }
    }

    private fun copyRepositoryUrl() {
        val manager = requireContext()
            .getSystemService(android.content.ClipboardManager::class.java)
        manager?.setPrimaryClip(
            android.content.ClipData.newPlainText("repo", getString(R.string.about_repo_url))
        )
        Toast.makeText(requireContext(), R.string.settings_about_repo_copied, Toast.LENGTH_SHORT)
            .show()
    }

}




































