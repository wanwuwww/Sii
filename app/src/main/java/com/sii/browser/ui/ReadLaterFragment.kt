// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sii.browser.R
import com.sii.browser.data.db.ReadLaterItem
import com.sii.browser.di.AppServices
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 稍后读：离线保存的网页存档列表。 */
class ReadLaterFragment : Fragment() {

    private lateinit var container: LinearLayout

    override fun onDestroyView() {
        if (::container.isInitialized) container.removeAllViews()
        super.onDestroyView()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(requireContext().getColor(R.color.bg))
            addView(
                PageHeader.build(
                    requireContext(),
                    getString(R.string.menu_read_later),
                    onBack = { parentFragmentManager.popBackStack() }
                )
            )
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
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
        viewLifecycleOwner.lifecycleScope.launch {
            AppServices.readLaterRepository.all().collect { items -> render(items) }
        }
    }

    private fun render(items: List<ReadLaterItem>) {
        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = getString(R.string.readlater_empty)
                setTextSize(13f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
                setPadding(dp(8), dp(24), dp(8), dp(8))
            })
            return
        }
        val card = CardList.card(requireContext())
        CardList.addRows(card, items.map { row(it) })
        container.addView(card)
    }

    private fun row(item: ReadLaterItem): View {
        val title = TextView(requireContext()).apply {
            text = item.title.ifBlank { item.url }
            maxLines = 1
            setTextSize(14f)
            setTextColor(requireContext().getColor(R.color.text_primary))
        }
        val subtitle = TextView(requireContext()).apply {
            text = buildString {
                append(runCatching { android.net.Uri.parse(item.url).host }.getOrNull().orEmpty())
                if (item.read) append(" · ").append(getString(R.string.readlater_read))
            }
            maxLines = 1
            setTextSize(11.5f)
            setTextColor(requireContext().getColor(R.color.text_secondary))
        }
        return CardList.column(requireContext()).apply {
            addView(title)
            addView(subtitle)
            setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch {
                    AppServices.readLaterRepository.setRead(item.id, true)
                }
                val file = java.io.File(item.filePath)
                if (!file.exists()) {
                    android.widget.Toast.makeText(
                        requireContext(), R.string.readlater_file_missing, android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                if (file.name.endsWith(".html", ignoreCase = true)) {
                    AppServices.pendingOpenUrl =
                        android.net.Uri.fromFile(file).toString()
                    parentFragmentManager.popBackStack()
                } else if (file.name.endsWith(".mht", ignoreCase = true)) {
                    // MHT 解包成资源内嵌的 HTML，交给 WebView 渲染。
                    viewLifecycleOwner.lifecycleScope.launch {
                        val cached = kotlinx.coroutines.withContext(
                            kotlinx.coroutines.Dispatchers.IO
                        ) {
                            com.sii.browser.core.webview.MhtParser.toCachedHtml(
                                java.io.File(requireContext().cacheDir, "mht_view"), file
                            )
                        }
                        if (cached == null) {
                            android.widget.Toast.makeText(
                                requireContext(), R.string.readlater_file_missing,
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            AppServices.pendingOpenUrl =
                                android.net.Uri.fromFile(cached).toString()
                            parentFragmentManager.popBackStack()
                        }
                    }
                } else {
                    // PDF 用系统 PdfRenderer 在应用内逐页渲染。
                    NavAnim.apply(parentFragmentManager.beginTransaction())
                        .replace(R.id.shell_root, PdfViewerFragment.newInstance(file.absolutePath))
                        .addToBackStack(null)
                        .commit()
                }
            }
            setOnLongClickListener {
                android.app.AlertDialog.Builder(requireContext())
                    .setItems(
                        arrayOf(
                            getString(R.string.readlater_export),
                            getString(R.string.delete)
                        )
                    ) { _, which ->
                        if (which == 0) exportItem(item) else confirmDelete(item)
                    }
                    .show()
                true
            }
        }
    }

    private fun exportItem(item: ReadLaterItem) {
        val file = java.io.File(item.filePath)
        if (!file.exists()) {
            android.widget.Toast.makeText(
                requireContext(), R.string.readlater_file_missing, android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val mime = when {
            file.name.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            file.name.endsWith(".mht", ignoreCase = true) -> "application/x-mimearchive"
            else -> "text/html"
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.name)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                        put(
                            android.provider.MediaStore.Downloads.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS
                        )
                    }
                    val uri = requireContext().contentResolver.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    ) ?: return@runCatching false
                    requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } ?: return@runCatching false
                    true
                }.getOrDefault(false)
            }
            android.widget.Toast.makeText(
                requireContext(),
                if (ok) getString(R.string.readlater_exported, file.name)
                else getString(R.string.readlater_export_failed),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun confirmDelete(item: ReadLaterItem) {
        android.app.AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.readlater_delete_confirm, item.title.ifBlank { item.url }))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    AppServices.readLaterRepository.delete(item.id)
                    runCatching { java.io.File(item.filePath).delete() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
