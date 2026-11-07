// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.sii.browser.R
import com.sii.browser.data.SiteIconCache
import com.sii.browser.data.db.PasswordItem
import com.sii.browser.di.AppServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 密码库列表：按站点分组展示，点击进入详情/编辑，长按快捷操作。
 */
class PasswordFragment : Fragment() {

    private lateinit var container: LinearLayout
    private var items: List<PasswordItem> = emptyList()
    private var query: String = ""
    private var unlocked = false
    private var loaded = false
    private var authInFlight = false
    private var lockHint = 0

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
    ): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(R.color.bg))
        }
        root.addView(
            PageHeader.build(
                context,
                getString(R.string.menu_passwords),
                onBack = { closePage() },
                onMenu = { showMenu() },
                actionLabel = "＋",
                onAction = { openDetail(0L) }
            )
        )
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(context.getColor(R.color.divider))
        })
        root.addView(EditText(context).apply {
            hint = getString(R.string.search_hint)
            background = ContextCompat.getDrawable(context, R.drawable.bg_search_box)
            setPadding(dp(14), 0, dp(14), 0)
            setTextSize(14f)
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                topMargin = dp(8)
                bottomMargin = dp(4)
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    query = s?.toString().orEmpty()
                    render()
                }
            })
        })
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(
            CardList.scroll(context, content),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        this@PasswordFragment.container = content
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!AppServices.settings.passwordBiometric()) {
            unlocked = true
            load()
            return
        }
        if (AppServices.passwordUnlockedAt > 0) {
            unlocked = true
            load()
            return
        }
        render()
        view.post { if (isAdded && view != null) requestUnlock() }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        viewLifecycleOwner.lifecycleScope.launch {
            AppServices.passwordRepository.all().collect { list ->
                items = list
                render()
            }
        }
    }

    /** 指纹/面容或锁屏密码验证后才展示列表；失败停在锁定态，可重试。 */
    private fun requestUnlock() {
        if (!isAdded || view == null || authInFlight) return
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuthenticate = runCatching {
            BiometricManager.from(requireContext()).canAuthenticate(authenticators)
        }.getOrDefault(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED)
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            lockHint = R.string.pwd_lock_unavailable
            render()
            return
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult
            ) {
                authInFlight = false
                if (!isAdded || view == null) return
                unlocked = true
                lockHint = 0
                AppServices.passwordUnlockedAt = System.currentTimeMillis()
                render()
                load()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                authInFlight = false
                if (!isAdded || view == null) return
                render()
            }
        }
        val prompt = runCatching {
            BiometricPrompt(
                requireActivity(),
                ContextCompat.getMainExecutor(requireContext()),
                callback
            )
        }.getOrNull()
        if (prompt == null) {
            lockHint = R.string.pwd_lock_unavailable
            render()
            return
        }
        val info = runCatching {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.pwd_lock_title))
                .setAllowedAuthenticators(authenticators)
                .build()
        }.getOrNull()
        if (info == null || runCatching { prompt.authenticate(info) }.isFailure) {
            authInFlight = false
            lockHint = R.string.pwd_lock_unavailable
            render()
            return
        }
        authInFlight = true
    }

    private fun render() {
        if (!::container.isInitialized) return
        container.removeAllViews()
        if (!unlocked) {
            renderLock()
            return
        }
        val context = requireContext()
        val filtered = if (query.isBlank()) {
            items
        } else {
            items.filter {
                it.host.contains(query, true) || it.username.contains(query, true)
            }
        }
        if (filtered.isEmpty()) {
            container.addView(TextView(context).apply {
                text = getString(if (items.isEmpty()) R.string.pwd_empty else R.string.search_no_match)
                setTextSize(13f)
                setTextColor(context.getColor(R.color.text_secondary))
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(60), dp(8), dp(8))
            })
            return
        }
        filtered.groupBy { it.host }.toSortedMap().forEach { (host, group) ->
            container.addView(
                CardList.sectionTitle(
                    context,
                    getString(R.string.pwd_group_title, host, group.size)
                )
            )
            val card = CardList.pageCard(context)
            CardList.addRows(card, group.sortedByDescending { it.updatedAt }.map { row(it) })
            container.addView(card)
        }
    }

    private fun renderLock() {
        val context = requireContext()
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(44), dp(24), dp(16))
        }
        wrap.addView(TextView(context).apply {
            text = getString(R.string.pwd_locked)
            setTextSize(13.5f)
            setTextColor(context.getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
        })
        if (lockHint != 0) {
            wrap.addView(TextView(context).apply {
                text = getString(lockHint)
                setTextSize(12f)
                setTextColor(context.getColor(R.color.text_secondary))
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
        }
        wrap.addView(TextView(context).apply {
            text = getString(R.string.pwd_unlock_action)
            setTextSize(14f)
            setTextColor(ThemeAccent.color(context))
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setOnClickListener { requestUnlock() }
        })
        container.addView(wrap)
    }

    private fun row(item: PasswordItem): View {
        val context = requireContext()
        return CardList.row(context).apply {
            addView(siteAvatar(item.host))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                addView(TextView(context).apply {
                    text = item.username.ifEmpty { "-" }
                    setTextSize(14.5f)
                    setTextColor(context.getColor(R.color.text_primary))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply {
                    text = getString(R.string.pwd_updated, timeLabel(item.updatedAt))
                    setTextSize(11.5f)
                    setTextColor(context.getColor(R.color.text_secondary))
                    setPadding(0, dp(2), 0, 0)
                })
            })
            addView(TextView(context).apply {
                text = "›"
                setTextSize(18f)
                setTextColor(context.getColor(R.color.text_secondary))
                setPadding(dp(8), 0, 0, 0)
            })
            setOnClickListener { openDetail(item.id) }
            setOnLongClickListener {
                showItemMenu(item)
                true
            }
        }
    }

    private fun siteAvatar(host: String): View {
        val context = requireContext()
        val size = dp(34)
        val holder = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(12) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(colorOf(host))
            }
            clipToOutline = true
            tag = host
        }
        holder.addView(TextView(context).apply {
            text = host.take(1).uppercase()
            setTextSize(15f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        })
        val cached = SiteIconCache.cached(context, host)
        if (cached != null) {
            showFavicon(holder, cached)
        } else {
            SiteIconCache.load(context, host) { bitmap ->
                view?.post {
                    if (bitmap != null && holder.parent != null && holder.tag == host) {
                        showFavicon(holder, bitmap)
                    }
                }
            }
        }
        return holder
    }

    private fun showFavicon(holder: FrameLayout, bitmap: Bitmap) {
        holder.removeAllViews()
        holder.addView(ImageView(requireContext()).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        })
    }

    private fun showItemMenu(item: PasswordItem) {
        val options = arrayOf(
            getString(R.string.pwd_copy_username),
            getString(R.string.pwd_copy),
            getString(R.string.pwd_edit),
            getString(R.string.delete)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(item.host)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        if (item.username.isNotBlank()) copyText(item.username)
                    }
                    1 -> copyPassword(item)
                    2 -> openDetail(item.id)
                    else -> confirmDelete(item)
                }
            }
            .show()
    }

    private fun copyPassword(item: PasswordItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val plain = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                AppServices.passwordRepository.decrypt(item)
            }
            if (plain == null) {
                Toast.makeText(requireContext(), R.string.pwd_failed, Toast.LENGTH_SHORT).show()
            } else if (isAdded) {
                copyText(plain)
            }
        }
    }

    private fun confirmDelete(item: PasswordItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pwd_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    AppServices.passwordRepository.delete(item.id)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMenu() {
        val lockOn = AppServices.settings.passwordBiometric()
        val options = arrayOf(
            (if (lockOn) "✓ " else "") + getString(R.string.pwd_lock_toggle),
            getString(R.string.pwd_import_csv),
            getString(R.string.pwd_export_csv),
            getString(R.string.pwd_clear_all)
        )
        AlertDialog.Builder(requireContext())
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleLock()
                    1 -> csvImportLauncher.launch(arrayOf("text/*", "text/csv"))
                    2 -> confirmExportCsv()
                    else -> confirmClearAll()
                }
            }
            .show()
    }

    /** 密码库加锁：开启时验证一次，本页停留期间有效；关闭需二次确认。 */
    private fun toggleLock() {
        if (AppServices.settings.passwordBiometric()) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.pwd_lock_toggle)
                .setMessage(R.string.pwd_lock_off_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    AppServices.settings.setPasswordBiometric(false)
                    AppServices.passwordUnlockedAt = 0L
                    unlocked = true
                    render()
                    load()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuthenticate = runCatching {
            BiometricManager.from(requireContext()).canAuthenticate(authenticators)
        }.getOrDefault(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED)
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(requireContext(), R.string.pwd_lock_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        AppServices.settings.setPasswordBiometric(true)
        AppServices.passwordUnlockedAt = 0L
        unlocked = false
        render()
        view?.post { if (isAdded && view != null) requestUnlock() }
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pwd_clear_all)
            .setMessage(R.string.pwd_clear_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    AppServices.passwordRepository.clearAll()
                    if (isAdded) {
                        Toast.makeText(requireContext(), R.string.pwd_clear_done, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
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
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                ch == '"' -> quoted = !quoted
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

    private fun openDetail(id: Long) {
        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(R.id.shell_root, PasswordDetailFragment().apply {
                arguments = bundleOf(PasswordDetailFragment.ARG_ID to id)
            })
            .addToBackStack(null)
            .commit()
    }

    private fun closePage() {
        AppServices.passwordUnlockedAt = 0L
        val fm = parentFragmentManager
        if (!fm.popBackStackImmediate(BACK_STACK, FragmentManager.POP_BACK_STACK_INCLUSIVE)) {
            fm.popBackStack()
        }
    }

    private fun copyText(text: String) {
        if (text.isBlank()) return
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
        Toast.makeText(requireContext(), R.string.pwd_copied, Toast.LENGTH_SHORT).show()
    }

    private fun timeLabel(time: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(time))

    private fun colorOf(value: String): Int {
        var h = 0
        value.forEach { h = h * 31 + it.code }
        return COLORS[Math.floorMod(h, COLORS.size)]
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val BACK_STACK = "passwords"

        private val COLORS = intArrayOf(
            0xFF2F7CF6.toInt(), 0xFF34C28F.toInt(), 0xFFF2984A.toInt(), 0xFF8A6FF0.toInt(),
            0xFFE5537A.toInt(), 0xFF3AB3C9.toInt(), 0xFFC9A23A.toInt(), 0xFF6D7F95.toInt()
        )
    }
}
