// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
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
import com.sii.browser.core.webview.LanguageId
import com.sii.browser.core.webview.Translator
import com.sii.browser.di.AppServices
import kotlinx.coroutines.launch

/**
 * 文本翻译：粘贴或输入文本，选择语言与引擎（本地 / 云端）后翻译。
 */
class TextTranslateFragment : Fragment() {

    private data class Lang(val label: String, val tag: String)

    private val langs = com.sii.browser.core.webview.TranslateLanguages.TOOL
        .map { Lang(it.label, it.tag) }

    private lateinit var input: EditText
    private lateinit var sourceValue: TextView
    private lateinit var targetValue: TextView
    private lateinit var engineLocal: TextView
    private lateinit var engineCloud: TextView
    private lateinit var providerHint: TextView
    private lateinit var translateButton: TextView
    private lateinit var resultCard: LinearLayout
    private lateinit var resultText: TextView

    private var sourceTag = ""
    private var targetTag = ""
    private var engine = 0
    private var translating = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val settings = AppServices.settings
        sourceTag = settings.textTranslateSource()
        targetTag = settings.textTranslateTarget().ifBlank { defaultTarget() }
        engine = settings.textTranslateEngine()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(R.color.bg))
        }
        root.addView(
            PageHeader.build(
                context,
                getString(R.string.menu_text_translate),
                onBack = { parentFragmentManager.popBackStack() },
                onMenu = { showPageMenu() },
                actionLabel = getString(R.string.text_translate_clear),
                onAction = { clearAll() }
            )
        )
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(context.getColor(R.color.divider))
        })
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(20))
        }
        root.addView(
            CardList.scroll(context, content),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        input = EditText(context).apply {
            hint = getString(R.string.text_translate_hint)
            setTextSize(14f)
            gravity = Gravity.TOP or Gravity.START
            minLines = 6
            maxLines = 12
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = null
            setPadding(0, dp(4), 0, dp(8))
        }
        val inputCard = CardList.pageCard(context)
        val inputRow = CardList.row(context)
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pasteButton = actionText(getString(R.string.text_translate_paste)) { pasteFromClipboard() }
        inputRow.addView(pasteButton)
        CardList.addRows(inputCard, listOf(inputRow))
        content.addView(inputCard)

        val langCard = CardList.pageCard(context)
        sourceValue = valueText()
        targetValue = valueText()
        val sourceRow = choiceRow(
            getString(R.string.text_translate_source),
            sourceValue
        ) { pickSource() }
        val targetRow = choiceRow(
            getString(R.string.text_translate_target),
            targetValue
        ) { pickTarget() }
        CardList.addRows(langCard, listOf(sourceRow, targetRow))
        content.addView(langCard)

        val engineCard = CardList.pageCard(context)
        engineLocal = chip(getString(R.string.text_translate_local)) { setEngine(0) }
        engineCloud = chip(getString(R.string.text_translate_cloud)) { setEngine(1) }
        providerHint = TextView(context).apply {
            setTextSize(11.5f)
            setTextColor(context.getColor(R.color.text_secondary))
        }
        val engineRow = CardList.row(context).apply {
            addView(TextView(context).apply {
                text = getString(R.string.text_translate_engine)
                setTextSize(14f)
                setTextColor(context.getColor(R.color.text_primary))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(engineLocal)
            addView(engineCloud)
        }
        val hintRow = CardList.row(context).apply {
            addView(providerHint)
            setOnLongClickListener {
                openTranslateSettings()
                true
            }
        }
        CardList.addRows(engineCard, listOf(engineRow, hintRow))
        content.addView(engineCard)

        translateButton = TextView(context).apply {
            text = getString(R.string.text_translate_action)
            setTextSize(15f)
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(ThemeAccent.color(context))
            }
            setOnClickListener { translateNow() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                bottomMargin = dp(8)
            }
        }
        content.addView(translateButton)

        resultCard = CardList.pageCard(context).apply { visibility = View.GONE }
        resultText = TextView(context).apply {
            setTextSize(14.5f)
            setTextColor(context.getColor(R.color.text_primary))
            setTextIsSelectable(true)
        }
        val resultHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        resultHeader.addView(TextView(context).apply {
            text = getString(R.string.text_translate_result)
            setTextSize(13f)
            setTextColor(context.getColor(R.color.text_secondary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        resultHeader.addView(actionText(getString(R.string.text_translate_copy)) { copyResult() })
        val resultRow = CardList.row(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(resultHeader, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(resultText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
        CardList.addRows(resultCard, listOf(resultRow))
        content.addView(resultCard)

        refreshLanguageLabels()
        refreshEngine()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity()
        activity.onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    parentFragmentManager.popBackStack()
                    activity.moveTaskToBack(true)
                }
            }
        )
    }

    private fun defaultTarget(): String =
        if (resources.configuration.locales[0].language == "zh") "zh-CN" else "en"

    private fun choiceRow(label: String, value: TextView, onClick: () -> Unit): View {
        val context = requireContext()
        return CardList.row(context).apply {
            addView(TextView(context).apply {
                text = label
                setTextSize(14f)
                setTextColor(context.getColor(R.color.text_primary))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
            addView(TextView(context).apply {
                text = "›"
                setTextSize(18f)
                setTextColor(context.getColor(R.color.text_secondary))
                setPadding(dp(8), 0, 0, 0)
            })
            setOnClickListener { onClick() }
        }
    }

    private fun valueText(): TextView {
        val context = requireContext()
        return TextView(context).apply {
            setTextSize(14f)
            setTextColor(ThemeAccent.color(context))
        }
    }

    private fun actionText(label: String, onClick: () -> Unit): TextView {
        val context = requireContext()
        return TextView(context).apply {
            text = label
            setTextSize(12.5f)
            setTextColor(ThemeAccent.color(context))
            setPadding(dp(10), dp(8), dp(6), dp(8))
            setOnClickListener { onClick() }
        }
    }

    private fun chip(label: String, onClick: () -> Unit): TextView {
        val context = requireContext()
        return TextView(context).apply {
            text = label
            setTextSize(12.5f)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
            setOnClickListener { onClick() }
        }
    }

    private fun refreshLanguageLabels() {
        val auto = getString(R.string.text_translate_auto)
        sourceValue.text = if (sourceTag.isBlank()) auto else labelOf(sourceTag)
        targetValue.text = labelOf(targetTag)
    }

    private fun labelOf(tag: String): String =
        langs.firstOrNull { it.tag == tag }?.label ?: tag

    private fun refreshEngine() {
        val context = requireContext()
        val accent = ThemeAccent.color(context)
        val secondary = context.getColor(R.color.text_primary)
        listOf(engineLocal to (engine == 0), engineCloud to (engine == 1)).forEach { (chip, on) ->
            chip.setTextColor(if (on) accent else secondary)
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (on) (accent and 0x00FFFFFF) or 0x1A000000 else 0)
                setStroke(dp(1), if (on) accent else context.getColor(R.color.line))
            }
        }
        providerHint.text = if (engine == 1) {
            val index = AppServices.settings.translateProvider().coerceIn(0, 4)
            getString(
                R.string.text_translate_provider,
                SettingsDetailFragment.TRANSLATE_PROVIDER_NAMES[index]
            )
        } else {
            getString(R.string.text_translate_local_hint)
        }
    }

    private fun setEngine(value: Int) {
        engine = value
        AppServices.settings.setTextTranslateEngine(value)
        refreshEngine()
        if (value == 1) maybeShowCloudNotice()
    }

    private fun maybeShowCloudNotice(afterOk: (() -> Unit)? = null) {
        if (AppServices.settings.textTranslateCloudNotice()) {
            afterOk?.invoke()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.text_translate_cloud_notice_title)
            .setMessage(R.string.text_translate_cloud_notice)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                AppServices.settings.setTextTranslateCloudNotice(true)
                afterOk?.invoke()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                engine = 0
                AppServices.settings.setTextTranslateEngine(0)
                refreshEngine()
            }
            .show()
    }

    private fun pickSource() {
        val labels = arrayOf(getString(R.string.text_translate_auto)) + langs.map { it.label }
        val current = if (sourceTag.isBlank()) 0 else langs.indexOfFirst { it.tag == sourceTag } + 1
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.text_translate_source)
            .setSingleChoiceItems(labels, current.coerceAtLeast(0)) { dialog, which ->
                sourceTag = if (which == 0) "" else langs[which - 1].tag
                AppServices.settings.setTextTranslateSource(sourceTag)
                refreshLanguageLabels()
                dialog.dismiss()
            }
            .show()
    }

    private fun pickTarget() {
        val labels = langs.map { it.label }.toTypedArray()
        val current = langs.indexOfFirst { it.tag == targetTag }.coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.text_translate_target)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                targetTag = langs[which].tag
                AppServices.settings.setTextTranslateTarget(targetTag)
                refreshLanguageLabels()
                dialog.dismiss()
            }
            .show()
    }

    private fun pasteFromClipboard() {
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            ?.coerceToText(requireContext())?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(requireContext(), R.string.text_translate_empty_input, Toast.LENGTH_SHORT).show()
            return
        }
        input.setText(text)
        input.setSelection(input.text.length)
    }

    private fun clearAll() {
        input.setText("")
        resultText.text = ""
        resultCard.visibility = View.GONE
    }

    private fun showPageMenu() {
        AlertDialog.Builder(requireContext())
            .setItems(arrayOf(getString(R.string.menu_add_home))) { _, _ -> createShortcut() }
            .show()
    }

    /** 桌面快捷方式：带 sii_action，由 BrowserFragment.handleNewIntent 直接打开本页。 */
    private fun createShortcut() {
        val context = requireContext()
        val shortcut = androidx.core.content.pm.ShortcutInfoCompat.Builder(
            context, "sii_text_translate"
        )
            .setShortLabel(getString(R.string.menu_text_translate))
            .setLongLabel(getString(R.string.menu_text_translate))
            .setIcon(
                androidx.core.graphics.drawable.IconCompat.createWithResource(
                    context, R.drawable.ic_launcher_sii
                )
            )
            .setIntent(
                Intent(context, ShellActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra("sii_action", "text_translate")
                }
            )
            .build()
        val ok = runCatching {
            androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        }.getOrDefault(false)
        if (!ok) {
            Toast.makeText(context, R.string.shortcut_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun translateNow() {
        if (translating) return
        val raw = input.text.toString().trim()
        if (raw.isEmpty()) {
            Toast.makeText(requireContext(), R.string.text_translate_empty_input, Toast.LENGTH_SHORT).show()
            return
        }
        if (engine == 1 && !AppServices.settings.textTranslateCloudNotice()) {
            maybeShowCloudNotice { translateNow() }
            return
        }
        translating = true
        translateButton.isEnabled = false
        translateButton.text = getString(R.string.text_translate_working)
        resultCard.visibility = View.VISIBLE
        resultText.text = getString(R.string.text_translate_working)
        viewLifecycleOwner.lifecycleScope.launch {
            val detected = detectSource(raw)
            if (!isAdded) return@launch
            if (detected != null && sameLanguage(detected)) {
                finishTranslate()
                resultCard.visibility = View.GONE
                Toast.makeText(
                    requireContext(), R.string.text_translate_same_language, Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            if (engine == 0) {
                val needed = listOfNotNull(
                    com.sii.browser.core.webview.TranslateLanguages.mlkit(detected),
                    com.sii.browser.core.webview.TranslateLanguages.mlkit(targetTag)
                ).distinct()
                if (needed.size < 2) {
                    finishTranslate()
                    resultCard.visibility = View.GONE
                    Toast.makeText(
                        requireContext(), R.string.text_translate_local_only, Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                val missing = com.sii.browser.core.webview.OfflineTranslator
                    .missingModels(requireContext(), needed)
                if (missing.isNotEmpty()) {
                    finishTranslate()
                    resultCard.visibility = View.GONE
                    promptDownload(missing) { translateNow() }
                    return@launch
                }
            }
            val error = translateText(raw, detected)
            if (!isAdded) return@launch
            finishTranslate()
            if (error != null) {
                resultText.text = error
                showErrorAction(error)
            }
        }
    }

    private fun finishTranslate() {
        translating = false
        translateButton.isEnabled = true
        translateButton.text = getString(R.string.text_translate_action)
    }

    /** 自动检测源语言：优先 ML Kit 语言识别，识别不出时按 CJK 字符回退。 */
    private suspend fun detectSource(raw: String): String? {
        if (sourceTag.isNotBlank()) return sourceTag
        val identified = LanguageId.identify(requireContext(), raw)
        val tag = identified?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("zh")) "zh-CN" else it
        } ?: if (raw.any { it.code in 0x4E00..0x9FFF }) "zh" else "en"
        if (::sourceValue.isInitialized) {
            sourceValue.text = "${getString(R.string.text_translate_auto)} · ${labelOfTag(tag)}"
        }
        return tag
    }

    private fun labelOfTag(tag: String): String =
        com.sii.browser.core.webview.TranslateLanguages.label(tag)

    private fun sameLanguage(source: String): Boolean {
        val sourceCode = com.sii.browser.core.webview.TranslateLanguages.mlkit(source) ?: return false
        val targetCode =
            com.sii.browser.core.webview.TranslateLanguages.mlkit(targetTag) ?: return false
        return sourceCode == targetCode
    }

    private fun promptDownload(codes: List<String>, then: () -> Unit) {
        val names = codes
            .map { com.sii.browser.core.webview.TranslateLanguages.label(it) }
            .distinct()
            .joinToString("、")
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.text_translate_need_pack)
            .setMessage(getString(R.string.text_translate_download_pack_msg, names))
            .setPositiveButton(R.string.text_translate_go_download) { _, _ ->
                downloadModels(codes, then)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadModels(codes: List<String>, then: () -> Unit) {
        if (com.sii.browser.core.webview.OfflineTranslator.downloading) {
            Toast.makeText(
                requireContext(), R.string.text_translate_downloading, Toast.LENGTH_SHORT
            ).show()
            return
        }
        translating = true
        translateButton.isEnabled = false
        translateButton.text = getString(R.string.text_translate_downloading)
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = com.sii.browser.core.webview.OfflineTranslator.downloadModels(
                requireContext(), codes
            ) { percent, _, _, _, _ ->
                view?.post {
                    if (!isAdded) return@post
                    translateButton.text =
                        getString(R.string.text_translate_download_progress, percent)
                }
            }
            if (!isAdded) return@launch
            finishTranslate()
            if (ok) {
                then()
            } else {
                Toast.makeText(
                    requireContext(), R.string.text_translate_pack_failed, Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** 逐行翻译（长行分段），成功时把译文写入结果卡并返回 null，否则返回错误文案。 */
    private suspend fun translateText(raw: String, from: String?): String? {
        val lines = raw.split('\n')
        val translated = StringBuilder()
        lines.forEachIndexed { index, line ->
            if (line.isNotBlank()) {
                var rest = line
                val parts = mutableListOf<String>()
                while (rest.length > 900) {
                    parts.add(rest.substring(0, 900))
                    rest = rest.substring(900)
                }
                parts.add(rest)
                for (chunk in parts) {
                    val result = Translator.translate(
                        requireContext(),
                        chunk,
                        targetTag,
                        from = from,
                        modeOverride = if (engine == 0) 2 else 1
                    )
                    val text = result.text
                    if (text == null) return errorText(result.error)
                    translated.append(text)
                }
            }
            if (index < lines.lastIndex) translated.append('\n')
        }
        resultText.text = translated.toString()
        return null
    }

    private fun errorText(code: String?): String = when (code) {
        "offline_model_missing" -> getString(R.string.text_translate_need_pack)
        "offline_unsupported", "offline_source_unknown" -> getString(R.string.text_translate_local_only)
        "network" -> getString(R.string.text_translate_network)
        "no key", "no endpoint" -> getString(R.string.text_translate_not_configured)
        else -> getString(R.string.text_translate_failed)
    }

    private fun showErrorAction(message: String) {
        if (message != getString(R.string.text_translate_need_pack)) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.text_translate_need_pack)
            .setMessage(R.string.settings_translate_offline_desc)
            .setPositiveButton(R.string.text_translate_go_download) { _, _ -> openTranslateOffline() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openTranslateOffline() {
        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(R.id.shell_root, SettingsDetailFragment().apply {
                arguments = android.os.Bundle().apply {
                    putString(
                        SettingsDetailFragment.ARG_VIEW,
                        SettingsDetailFragment.VIEW_V2_TRANSLATE_OFFLINE
                    )
                }
            })
            .addToBackStack(null)
            .commit()
    }

    private fun openTranslateSettings() {
        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(R.id.shell_root, SettingsDetailFragment().apply {
                arguments = android.os.Bundle().apply {
                    putString(
                        SettingsDetailFragment.ARG_VIEW,
                        SettingsDetailFragment.VIEW_V2_TRANSLATE
                    )
                }
            })
            .addToBackStack(null)
            .commit()
    }

    private fun copyResult() {
        val text = resultText.text?.toString().orEmpty()
        if (text.isBlank()) return
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
        Toast.makeText(requireContext(), R.string.text_translate_copied, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
