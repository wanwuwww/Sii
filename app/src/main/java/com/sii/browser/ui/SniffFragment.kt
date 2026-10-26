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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sii.browser.R
import com.sii.browser.core.webview.SniffStore
import com.sii.browser.core.webview.SniffType
import com.sii.browser.core.webview.SniffedResource
import com.sii.browser.di.AppServices
import com.sii.browser.download.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 资源嗅探列表：按类型分类展示当前页面嗅探到的资源，
 * 支持复制链接、直接下载、HLS(m3u8) 内建下载、新标签打开。
 */
class SniffFragment : Fragment() {

    private lateinit var container: LinearLayout
    private lateinit var chips: LinearLayout
    private var sniffHint: View? = null
    private var filter: SniffType? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(requireContext().getColor(R.color.bg))
        addView(PageHeader.build(
            requireContext(),
            getString(R.string.menu_sniff),
            onBack = { parentFragmentManager.popBackStack() },
            onMenu = { _ -> confirmClear() }
        ))
        addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(requireContext().getColor(R.color.divider))
        })
        sniffHint = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(2))
            visibility = View.GONE
            addView(TextView(requireContext()).apply {
                text = getString(R.string.sniff_disabled_hint)
                setTextSize(12.5f)
                setTextColor(requireContext().getColor(R.color.text_primary))
            })
            addView(TextView(requireContext()).apply {
                text = getString(R.string.sniff_long_press_hint)
                setTextSize(11f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
                setPadding(0, dp(2), 0, 0)
            })
            addView(TextView(requireContext()).apply {
                text = getString(R.string.sniff_enable)
                setTextSize(12.5f)
                setTextColor(ThemeAccent.color(requireContext()))
                setPadding(0, dp(6), 0, 0)
                setOnClickListener { enableSniff() }
            })
        }
        addView(sniffHint)
        chips = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        addView(chips)
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        addView(CardList.scroll(requireContext(), content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        this@SniffFragment.container = content
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        SniffStore.onChange = { key ->
            if (key === AppServices.sniffTarget) {
                view.post { render() }
            }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroyView() {
        SniffStore.onChange = null
        sniffHint = null
        super.onDestroyView()
    }

    private fun key(): Any? = AppServices.sniffTarget

    private fun render() {
        sniffHint?.visibility =
            if (AppServices.settings.sniffEnabled()) View.GONE else View.VISIBLE
        renderChips()
        container.removeAllViews()
        val target = key()
        val all = target?.let { SniffStore.list(it) }.orEmpty()
        val items = if (filter == null) all else all.filter { it.type == filter }
        if (items.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = getString(if (all.isEmpty()) R.string.sniff_empty else R.string.search_no_match)
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

    private fun renderChips() {
        chips.removeAllViews()
        val options = listOf<SniffType?>(
            null, SniffType.VIDEO, SniffType.HLS, SniffType.AUDIO,
            SniffType.IMAGE, SniffType.DOC, SniffType.MSE, SniffType.OTHER
        )
        options.forEach { type ->
            val label = typeLabel(type)
            chips.addView(TextView(requireContext()).apply {
                text = label
                setTextSize(12.5f)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setTextColor(
                    if (filter == type) ThemeAccent.color(requireContext())
                    else requireContext().getColor(R.color.text_secondary)
                )
                setBackgroundResource(
                    if (filter == type) R.drawable.bg_accent_soft else R.drawable.bg_card
                )
                if (filter == type) {
                    val accent = ThemeAccent.color(requireContext())
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        (accent and 0x00FFFFFF) or 0x22000000
                    )
                }
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    filter = type
                    render()
                }
            })
        }
    }

    private fun typeLabel(type: SniffType?): String = when (type) {
        null -> getString(R.string.sniff_all)
        SniffType.VIDEO -> getString(R.string.sniff_video)
        SniffType.HLS -> getString(R.string.sniff_hls)
        SniffType.AUDIO -> getString(R.string.sniff_audio)
        SniffType.IMAGE -> getString(R.string.sniff_image)
        SniffType.DOC -> getString(R.string.sniff_doc)
        SniffType.MSE -> getString(R.string.sniff_mse)
        SniffType.OTHER -> getString(R.string.sniff_other)
    }

    private fun row(res: SniffedResource): View {
        val title = TextView(requireContext()).apply {
            text = fileName(res.url)
            maxLines = 1
            setTextSize(13.5f)
            setTextColor(requireContext().getColor(R.color.text_primary))
        }
        val subtitle = TextView(requireContext()).apply {
            text = buildString {
                append(host(res.url))
                if (res.size > 0) append(" · ").append(formatSize(res.size))
            }
            maxLines = 1
            setTextSize(11.5f)
            setTextColor(requireContext().getColor(R.color.text_secondary))
        }
        val playable = res.type == SniffType.VIDEO ||
            res.type == SniffType.HLS || res.type == SniffType.AUDIO
        val mse = res.type == SniffType.MSE
        val actions = TextView(requireContext()).apply {
            text = when {
                mse -> getString(R.string.sniff_mse)
                playable -> getString(R.string.sniff_play)
                res.type == SniffType.HLS -> getString(R.string.sniff_hls_download)
                else -> getString(R.string.sniff_download)
            }
            setTextSize(12.5f)
            setTextColor(ThemeAccent.color(requireContext()))
            setPadding(dp(10), dp(6), 0, dp(6))
            setOnClickListener {
                when {
                    mse -> Toast.makeText(
                        requireContext(), R.string.sniff_mse_hint, Toast.LENGTH_SHORT
                    ).show()
                    playable -> play(res)
                    else -> download(res)
                }
            }
        }
        return CardList.column(requireContext()).apply {
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title)
                    addView(subtitle)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(actions)
            })
            setOnClickListener {
                ClipData.newPlainText(null, res.url).let {
                    (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(it)
                }
                Toast.makeText(requireContext(), R.string.link_copied, Toast.LENGTH_SHORT).show()
            }
            setOnLongClickListener {
                showActions(res)
                true
            }
        }
    }

    /** 长按资源：播放 / 下载 / 复制 / 新标签打开。 */
    private fun showActions(res: SniffedResource) {
        val playable = res.type == SniffType.VIDEO ||
            res.type == SniffType.HLS || res.type == SniffType.AUDIO
        val mse = res.type == SniffType.MSE
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (!mse) {
            if (playable) actions.add(getString(R.string.sniff_play) to { play(res) })
            actions.add(getString(R.string.sniff_download) to { download(res) })
        }
        actions.add(getString(R.string.menu_copy_link) to {
            ClipData.newPlainText(null, res.url).let {
                (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(it)
            }
            Toast.makeText(requireContext(), R.string.link_copied, Toast.LENGTH_SHORT).show()
        })
        if (!mse) {
            actions.add(getString(R.string.menu_open_new_tab) to {
                AppServices.pendingOpenUrl = res.url
                parentFragmentManager.popBackStack()
            })
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(fileName(res.url))
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .show()
    }

    /** 用外部播放器打开嗅探到的视频/音频/流媒体。 */
    private fun play(res: SniffedResource) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(android.net.Uri.parse(res.url), mimeOf(res))
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val player = AppServices.settings.videoPlayer()
        if (!player.isNullOrBlank()) intent.setPackage(player)
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(requireContext(), R.string.sniff_no_player, Toast.LENGTH_SHORT).show()
        }
    }

    private fun mimeOf(res: SniffedResource): String {
        val ext = res.url.substringBefore('?').substringBefore('#')
            .substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "flv" -> "video/x-flv"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "m3u8" -> "application/x-mpegURL"
            "ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            else -> res.mime ?: "video/*"
        }
    }

    private fun download(res: SniffedResource) {
        if (res.type == SniffType.MSE || res.url.startsWith("blob:", true)) {
            Toast.makeText(requireContext(), R.string.sniff_mse_hint, Toast.LENGTH_SHORT).show()
            return
        }
        val target = key() as? com.sii.browser.core.webview.SiiWebView
        if (res.type == SniffType.HLS) {
            DownloadService.startHlsDownload(
                requireContext(),
                res.url,
                fileName(res.url).substringBeforeLast('.'),
                target?.getReferer(),
                target?.settings?.userAgentString
            )
        } else {
            com.sii.browser.download.DownloadDispatcher.start(
                requireContext(), res.url, fileName(res.url), null,
                target?.getReferer(),
                target?.settings?.userAgentString
            )
        }
        Toast.makeText(requireContext(), R.string.sniff_download_started, Toast.LENGTH_SHORT).show()
    }

    private fun enableSniff() {
        AppServices.settings.setSniffEnabled(true)
        AppServices.sniffTarget?.injectJavaScript(
            com.sii.browser.core.webview.SniffJsBridge.SNIFF_JS
        )
        render()
    }

    private fun confirmClear() {
        val target = key() ?: return
        android.app.AlertDialog.Builder(requireContext())
            .setMessage(R.string.sniff_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                SniffStore.clear(target)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fileName(url: String): String =
        url.substringBefore('?').substringBefore('#').substringAfterLast('/').ifEmpty { url }

    private fun host(url: String): String =
        runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
