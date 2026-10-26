// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sii.browser.R
import com.sii.browser.data.db.PasswordItem
import com.sii.browser.di.AppServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 密码库列表：按域名/用户名展示，点击填充到当前页面，长按查看/删除。
 */
class PasswordFragment : Fragment() {

    private lateinit var container: LinearLayout
    private var items: List<PasswordItem> = emptyList()
    private var query: String = ""
    private var unlocked = false

    private val csvImportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importCsv(uri) }

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
        setBackgroundColor(requireContext().getColor(R.color.bg))
        addView(PageHeader.build(
            requireContext(),
            getString(R.string.menu_passwords),
            onBack = { parentFragmentManager.popBackStack() },
            onMenu = { showCsvMenu() }
        ))
        addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(requireContext().getColor(R.color.divider))
        })
        addView(android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.search_hint)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    query = s?.toString().orEmpty()
                    render()
                }
            })
        })
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        addView(CardList.scroll(requireContext(), content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        this@PasswordFragment.container = content
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (AppServices.settings.passwordBiometric()) {
            requireUnlock()
        } else {
            unlocked = true
            load()
        }
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            AppServices.passwordRepository.all().collect { list ->
                items = list
                render()
            }
        }
    }

    /** 指纹/面容或锁屏密码验证后才展示列表。 */
    private fun requireUnlock() {
        val authenticators = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val can = androidx.biometric.BiometricManager.from(requireContext())
            .canAuthenticate(authenticators)
        if (can != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            unlocked = true
            load()
            return
        }
        val prompt = androidx.biometric.BiometricPrompt(
            requireActivity(),
            androidx.core.content.ContextCompat.getMainExecutor(requireContext()),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: androidx.biometric.BiometricPrompt.AuthenticationResult
                ) {
                    unlocked = true
                    load()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (isAdded) parentFragmentManager.popBackStack()
                }
            }
        )
        prompt.authenticate(
            androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.pwd_lock_title))
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }

    private fun showCsvMenu() {
        val items = arrayOf(
            getString(R.string.pwd_export_csv),
            getString(R.string.pwd_import_csv)
        )
        AlertDialog.Builder(requireContext())
            .setItems(items) { _, which ->
                if (which == 0) confirmExportCsv() else csvImportLauncher.launch(arrayOf("text/*", "text/csv"))
            }
            .show()
    }

    private fun confirmExportCsv() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pwd_export_csv)
            .setMessage(R.string.pwd_export_csv_warning)
            .setPositiveButton(android.R.string.ok) { _, _ -> exportCsv() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportCsv() {
        lifecycleScope.launch {
            val all = AppServices.passwordRepository.all().first()
            val builder = StringBuilder("host,username,password\n")
            all.forEach { item ->
                val password = AppServices.passwordRepository.decrypt(item).orEmpty()
                builder.append(csvCell(item.host)).append(',')
                    .append(csvCell(item.username)).append(',')
                    .append(csvCell(password)).append('\n')
            }
            val dir = requireContext().getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?: requireContext().filesDir
            val file = java.io.File(dir, "Sii_passwords_${System.currentTimeMillis()}.csv")
            runCatching { file.writeText(builder.toString()) }
                .onSuccess {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.pwd_export_csv_done, file.absolutePath),
                        Toast.LENGTH_LONG
                    ).show()
                }
                .onFailure {
                    Toast.makeText(requireContext(), R.string.pwd_export_csv_failed, Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun importCsv(uri: android.net.Uri) {
        val text = runCatching {
            requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(requireContext(), R.string.pwd_import_csv_failed, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            var count = 0
            text.lineSequence().forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                val cells = parseCsvLine(line)
                if (index == 0 && cells.firstOrNull()?.lowercase() == "host") return@forEachIndexed
                if (cells.size < 3) return@forEachIndexed
                val host = cells[0].trim()
                val username = cells[1]
                val password = cells[2]
                if (host.isNotBlank() && username.isNotBlank()) {
                    AppServices.passwordRepository.save(host, username, password)
                    count++
                }
            }
            Toast.makeText(
                requireContext(),
                getString(R.string.pwd_import_csv_done, count),
                Toast.LENGTH_SHORT
            ).show()
            render()
        }
    }

    private fun csvCell(value: String): String =
        "\"" + value.replace("\"", "\"\"") + "\""

    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '\"' && quoted && i + 1 < line.length && line[i + 1] == '\"' -> {
                    current.append('\"')
                    i++
                }
                ch == '\"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    cells.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        cells.add(current.toString())
        return cells
    }

    private fun render() {
        container.removeAllViews()
        if (!unlocked) {
            container.addView(TextView(requireContext()).apply {
                text = getString(R.string.pwd_locked)
                setTextSize(13f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
                setPadding(dp(8), dp(24), dp(8), dp(8))
            })
            return
        }
        val filtered = if (query.isBlank()) {
            items
        } else {
            items.filter {
                it.host.contains(query, true) || it.username.contains(query, true)
            }
        }
        if (filtered.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = getString(if (items.isEmpty()) R.string.pwd_empty else R.string.search_no_match)
                setTextSize(13f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
                setPadding(dp(8), dp(24), dp(8), dp(8))
            })
            return
        }
        val card = CardList.card(requireContext())
        CardList.addRows(card, filtered.map { row(it) })
        container.addView(card)
    }

    private fun row(item: PasswordItem): View = CardList.row(requireContext()).apply {
        addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(requireContext()).apply {
                text = item.host
                setTextSize(14f)
                setTextColor(requireContext().getColor(R.color.text_primary))
            })
            addView(TextView(requireContext()).apply {
                text = item.username.ifEmpty { "-" }
                setTextSize(11.5f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
            })
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        setOnClickListener {
            val password = AppServices.passwordRepository.decrypt(item)
            if (password == null) {
                Toast.makeText(requireContext(), R.string.pwd_failed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val fill = AppServices.fillPassword
            if (fill != null) {
                fill(item.username, password)
                Toast.makeText(requireContext(), R.string.pwd_filled, Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            } else {
                copyText(password)
            }
        }
        setOnLongClickListener {
            val password = AppServices.passwordRepository.decrypt(item)
            AlertDialog.Builder(requireContext())
                .setTitle(item.host)
                .setItems(
                    arrayOf(
                        getString(R.string.pwd_copy),
                        getString(R.string.pwd_view),
                        getString(R.string.delete)
                    )
                ) { _, which ->
                    when (which) {
                        0 -> password?.let { copyText(it) }
                        1 -> AlertDialog.Builder(requireContext())
                            .setTitle(item.username)
                            .setMessage(password ?: getString(R.string.pwd_failed))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                        else -> viewLifecycleOwner.lifecycleScope.launch {
                            AppServices.passwordRepository.delete(item.id)
                        }
                    }
                }
                .show()
            true
        }
    }

    private fun copyText(text: String) {
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
        Toast.makeText(requireContext(), R.string.pwd_copied, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
