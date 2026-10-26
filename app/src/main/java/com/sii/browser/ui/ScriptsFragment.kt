// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sii.browser.R
import com.sii.browser.data.db.ScriptEntity
import com.sii.browser.di.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 油猴脚本管理：按 index.html 的卡片排列，支持启用/停用、更新、删除、编辑与运行时机。
 */
class ScriptsFragment : Fragment() {

    private lateinit var listContainer: LinearLayout
    private var scripts: List<ScriptEntity> = emptyList()
    private val client = OkHttpClient()

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) applyScriptImport(uri) }

    override fun onDestroyView() {
        if (::listContainer.isInitialized) listContainer.removeAllViews()
        super.onDestroyView()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(requireContext().getColor(R.color.bg))

        addView(
            PageHeader.build(
                requireContext(),
                getString(R.string.menu_scripts),
                onBack = { parentFragmentManager.popBackStack() },
                onMenu = { anchor -> showImportExport(anchor) },
                actionLabel = "＋ ${getString(R.string.scripts_new)}",
                onAction = { showEditScriptDialog(null) }
            )
        )
        addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(requireContext().getColor(R.color.divider))
        })

        listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(20))
        }
        addView(
            CardList.scroll(requireContext(), listContainer),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            AppServices.scriptRepository.all().collect { list ->
                scripts = list
                render()
            }
        }
    }

    private fun render() {
        listContainer.removeAllViews()
        if (scripts.isEmpty()) {
            listContainer.addView(TextView(requireContext()).apply {
                text = getString(R.string.scripts_empty) + "\n" +
                    getString(R.string.scripts_empty_hint)
                setTextSize(13f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.5f)
                setPadding(dp(12), dp(60), dp(12), dp(20))
            })
        } else {
            scripts.forEach { listContainer.addView(scriptCard(it)) }
        }
        listContainer.addView(TextView(requireContext()).apply {
            text = getString(R.string.scripts_about)
            setTextSize(12f)
            setTextColor(requireContext().getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.7f)
            setPadding(dp(12), dp(18), dp(12), dp(12))
        })
    }

    private fun scriptCard(script: ScriptEntity): View {
        val context = requireContext()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            clipToOutline = true
            setPadding(dp(14), dp(12), dp(8), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            setOnClickListener { showEditScriptDialog(script) }
        }

        card.addView(TextView(context).apply {
            text = "猴"
            setTextSize(16f)
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(0xFF7A5AF5.toInt())
            }
        })

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = script.name + if (script.enabled) "" else "（${getString(R.string.scripts_disabled)}）"
                setTextSize(14f)
                setTextColor(context.getColor(if (script.enabled) R.color.text_primary else R.color.text_secondary))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                val match = script.match.ifEmpty { "*" }
                text = match + " · " + runAtLabel(script.runAt)
                setTextSize(11.5f)
                setTextColor(context.getColor(R.color.text_secondary))
                typeface = Typeface.MONOSPACE
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
        }
        card.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (!script.url.isNullOrBlank()) {
            card.addView(TextView(context).apply {
                text = getString(R.string.scripts_update)
                setTextSize(11.5f)
                setTextColor(ThemeAccent.color(context))
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setBackgroundResource(R.drawable.bg_list_item)
                setOnClickListener { updateScript(script) }
            })
        }

        card.addView(Switch(context).apply {
            isChecked = script.enabled
            trackDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.switch_track)
            thumbDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.switch_thumb)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(26)).apply { marginStart = dp(6) }
            setOnCheckedChangeListener { _, checked ->
                viewLifecycleOwner.lifecycleScope.launch {
                    AppServices.scriptRepository.setEnabled(script, checked)
                    toast(if (checked) R.string.scripts_enabled_toast else R.string.scripts_disabled_toast)
                }
            }
        })

        card.addView(TextView(context).apply {
            text = "✕"
            setTextSize(14f)
            setTextColor(context.getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(2) }
            setBackgroundResource(R.drawable.bg_list_item)
            setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch {
                    AppServices.scriptRepository.delete(script.id)
                    toast(R.string.scripts_deleted)
                }
            }
        })
        return card
    }

    // ---- 新建 / 编辑 ----

    private fun showEditScriptDialog(existing: ScriptEntity?) {
        val context = requireContext()
        var runAt = existing?.runAt.orEmpty()
        val nameInput = EditText(context).apply {
            hint = getString(R.string.scripts_name_hint)
            setSingleLine()
            setText(existing?.name.orEmpty())
        }
        val matchInput = EditText(context).apply {
            hint = getString(R.string.scripts_match_hint)
            setSingleLine()
            setText(existing?.match?.ifEmpty { "*://*/*" } ?: "*://*/*")
        }
        val runAtValue = TextView(context).apply {
            text = runAtLabel(runAt)
            setTextSize(13f)
            setTextColor(context.getColor(R.color.text_secondary))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        val runAtRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            addView(TextView(context).apply {
                text = getString(R.string.scripts_run_at)
                setTextSize(13f)
                setTextColor(context.getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(runAtValue)
            setOnClickListener {
                val labels = arrayOf(
                    getString(R.string.scripts_run_follow),
                    getString(R.string.scripts_run_start),
                    getString(R.string.scripts_run_end),
                    getString(R.string.scripts_run_idle)
                )
                val values = arrayOf("", "document-start", "document-end", "document-idle")
                AlertDialog.Builder(context)
                    .setTitle(R.string.scripts_run_at)
                    .setSingleChoiceItems(labels, values.indexOf(runAt).coerceAtLeast(0)) { dialog, which ->
                        runAt = values[which]
                        runAtValue.text = labels[which]
                        dialog.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        val codeInput = EditText(context).apply {
            hint = getString(R.string.scripts_code_hint)
            minLines = 8
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            setText(
                existing?.code
                    ?: "// 页面加载时执行的 JavaScript\n// 可用 document 操作当前页面"
            )
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(nameInput)
            addView(matchInput)
            addView(runAtRow)
            addView(codeInput)
        }
        val scroll = ScrollView(context).apply {
            addView(box)
            isFillViewport = true
        }
        AlertDialog.Builder(context)
            .setTitle(if (existing == null) R.string.scripts_new else R.string.scripts_edit)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val code = codeInput.text.toString()
                if (code.isBlank()) {
                    toast(R.string.scripts_code_required)
                    return@setPositiveButton
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    AppServices.scriptRepository.saveScript(
                        existing,
                        nameInput.text.toString().trim(),
                        matchInput.text.toString().trim(),
                        runAt,
                        code
                    )
                    toast(R.string.scripts_saved)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- 更新 ----

    private fun updateScript(script: ScriptEntity) {
        val url = script.url?.takeIf { it.isNotBlank() } ?: return
        toast(getString(R.string.scripts_updating, script.name))
        viewLifecycleOwner.lifecycleScope.launch {
            val code = withContext(Dispatchers.IO) { fetchText(url) }
            if (code.isNullOrBlank()) {
                toast(R.string.scripts_update_failed)
            } else {
                AppServices.scriptRepository.updateFromSource(script, code)
                toast(R.string.scripts_updated)
            }
        }
    }

    private fun fetchText(url: String): String? = runCatching {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()
        }
    }.getOrNull()

    // ---- 导入导出 ----

    private fun showImportExport(anchor: View) {
        MenuPopup.show(
            anchor,
            listOf(
                MenuPopup.Item(getString(R.string.scripts_import)) { importScripts() },
                MenuPopup.Item(getString(R.string.scripts_export)) { exportScripts() },
                MenuPopup.Item(getString(R.string.scripts_update_interval)) { showUpdateIntervalDialog() },
                MenuPopup.Item(getString(R.string.scripts_check_update)) { checkUpdatesNow() }
            )
        )
    }

    private fun showUpdateIntervalDialog() {
        val days = intArrayOf(0, 1, 3, 7, 15)
        val labels = arrayOf(
            getString(R.string.scripts_update_off),
            getString(R.string.scripts_update_1d),
            getString(R.string.scripts_update_3d),
            getString(R.string.scripts_update_7d),
            getString(R.string.scripts_update_15d)
        )
        val current = days.indexOf(AppServices.settings.scriptUpdateDays()).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.scripts_update_interval)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                AppServices.settings.setScriptUpdateDays(days[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun checkUpdatesNow() {
        Toast.makeText(requireContext(), R.string.scripts_checking, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val updated = com.sii.browser.script.ScriptUpdater.check()
            Toast.makeText(
                requireContext(),
                getString(R.string.scripts_updated_count, updated),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun exportScripts() {
        val array = org.json.JSONArray()
        scripts.forEach { array.put(it.code) }
        val name = "sii-scripts-${System.currentTimeMillis()}.json"
        val path = FileExchange.writeToDownloads(requireContext(), name, array.toString())
        Toast.makeText(
            requireContext(),
            if (path != null) getString(R.string.data_exported_path, path)
            else getString(R.string.rules_import_failed),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun importScripts() {
        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    private fun applyScriptImport(uri: android.net.Uri) {
        val text = FileExchange.readText(requireContext(), uri)
        if (text.isNullOrBlank()) {
            toast(R.string.rules_import_empty)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = runCatching {
                if (text.contains("==UserScript==")) {
                    AppServices.scriptRepository.add(text)
                } else {
                    val array = org.json.JSONArray(text)
                    for (i in 0 until array.length()) {
                        val code = array.optString(i)
                        if (code.isNotBlank()) AppServices.scriptRepository.add(code)
                    }
                }
                true
            }.getOrDefault(false)
            toast(if (ok) R.string.rules_imported else R.string.rules_import_failed)
        }
    }

    private fun runAtLabel(value: String): String = when (value) {
        "document-start" -> getString(R.string.scripts_run_start)
        "document-end" -> getString(R.string.scripts_run_end)
        "document-idle" -> getString(R.string.scripts_run_idle)
        else -> getString(R.string.scripts_run_follow)
    }

    private fun toast(resId: Int) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
