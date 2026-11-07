// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
    private var showAllPages = false
    private var rowCard: LinearLayout? = null
    private var pendingRows: List<SniffedResource> = emptyList()
    private var pendingIndex = 0
    private val thumbRows = mutableListOf<ThumbRow>()

    private class ThumbRow(
        val res: SniffedResource,
        val box: View,
        val image: ImageView,
        val placeholder: TextView
    )
    private val storeListener: (Any) -> Unit = { key ->
        if (key === AppServices.sniffTarget) {
            view?.post { render() }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(requireContext().getColor(R.color.bg))
        }
        val header = PageHeader.build(
            requireContext(),
            getString(R.string.menu_sniff),
            onBack = { parentFragmentManager.popBackStack() },
            onMenu = { showHeaderMenu() }
        )
        root.addView(header)
        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(requireContext().getColor(R.color.divider))
        }
        root.addView(divider)
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
        root.addView(sniffHint)
        chips = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        // 分类按钮一屏放不下，横向可滚动，否则靠后的几项点不到。
        val chipsHost = android.widget.HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                chips,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(chipsHost)
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        val list = CardList.scroll(requireContext(), content)
        root.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        list.setOnScrollChangeListener { v, _, _, _, _ ->
            if (!v.canScrollVertically(1)) appendRows()
            loadVisibleThumbs()
        }
        this.container = content
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        SniffStore.addListener(storeListener)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroyView() {
        SniffStore.removeListener(storeListener)
        sniffHint = null
        rowCard = null
        pendingRows = emptyList()
        pendingIndex = 0
        thumbRows.clear()
        super.onDestroyView()
    }

    private fun key(): Any? = AppServices.sniffTarget

    private fun render() {
        sniffHint?.visibility =
            if (AppServices.settings.sniffEnabled()) View.GONE else View.VISIBLE
        renderChips()
        container.removeAllViews()
        thumbRows.clear()
        val all = key()?.let { SniffStore.list(it) }.orEmpty()
        val items = displayedItems()
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
        rowCard = card
        pendingRows = items
        pendingIndex = 0
        appendRows()
        container.addView(card)
        view?.post { loadVisibleThumbs() }
    }

    /** 分批建行：资源上百条时一次性构视图会让进入页面卡住。 */
    private fun appendRows() {
        val card = rowCard ?: return
        if (pendingIndex >= pendingRows.size) return
        val end = minOf(pendingIndex + ROW_CHUNK, pendingRows.size)
        for (i in pendingIndex until end) {
            if (i > 0) card.addView(CardList.styledDivider(requireContext()))
            card.addView(row(pendingRows[i]))
        }
        pendingIndex = end
        view?.post { loadVisibleThumbs() }
    }

    /** 当前列表实际展示的资源（当前页/全部页面 + 类型筛选），播放器左右切换也用这份顺序。 */
    private fun displayedItems(): List<SniffedResource> {
        val target = key()
        val all = target?.let { SniffStore.list(it) }.orEmpty()
        val current = (target as? com.sii.browser.core.webview.SiiWebView)?.url
        var visible = if (showAllPages || current.isNullOrEmpty()) {
            all
        } else {
            all.filter { SniffStore.samePage(it.pageUrl, current) }
        }
        // 两个采集源记录的页面 URL 可能不一致（SPA/重定向），匹配不到时只在**同站点**内兑底。
        if (visible.isEmpty() && all.isNotEmpty() && !showAllPages) {
            val host = hostOf(current)
            if (host != null) visible = all.filter { hostOf(it.pageUrl) == host }
        }
        return if (filter == null) visible else visible.filter { it.type == filter }
    }

    private fun hostOf(url: String?): String? =
        runCatching { android.net.Uri.parse(url.orEmpty()).host }.getOrNull()

    private fun renderChips() {
        chips.removeAllViews()
        chips.addView(TextView(requireContext()).apply {
            text = if (showAllPages) getString(R.string.sniff_all_pages)
            else getString(R.string.sniff_current_page)
            setTextSize(12.5f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setTextColor(ThemeAccent.color(requireContext()))
            setBackgroundResource(R.drawable.bg_accent_soft)
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener {
                showAllPages = !showAllPages
                render()
            }
        })
        val options = listOf<SniffType?>(
            null, SniffType.HLS, SniffType.VIDEO, SniffType.AUDIO,
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

    /** 行首缩略图：图片类加载真实缩略图，其余类型显示类型首字占位。 */
    private fun thumbBox(res: SniffedResource): View {
        val context = requireContext()
        val night = AppServices.settings.isNightMode()
        val image = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        val mark = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            gravity = android.view.Gravity.CENTER
            setTextSize(15f)
            setTextColor(context.getColor(R.color.text_secondary))
            text = typeLabel(res.type).take(1)
        }
        val box = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginEnd = dp(10)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (night) 0x1FFFFFFF else 0x14000000)
            }
            clipToOutline = true
            addView(image)
            addView(mark)
        }
        if (res.type == SniffType.IMAGE || res.type == SniffType.VIDEO) {
            thumbRows.add(ThumbRow(res, box, image, mark))
        }
        return box
    }

    /** 只给可见行拉缩略图，避免一打开就同时下载上百张图。 */
    private fun loadVisibleThumbs() {
        if (thumbRows.isEmpty()) return
        if (AppServices.privacyManager.isIncognito) return
        val target = AppServices.sniffTarget
        val userAgent = target?.settings?.userAgentString
        val rect = android.graphics.Rect()
        thumbRows.forEach { row ->
            if (row.image.tag != null) return@forEach
            // 用外层方框判断可见性：内部 ImageView 加载前是 GONE，永远不可见。
            if (!row.box.getGlobalVisibleRect(rect)) return@forEach
            val url = row.res.url
            row.image.tag = url
            val onReady = ready@ { bitmap: android.graphics.Bitmap? ->
                if (row.image.tag != url || bitmap == null) return@ready
                row.image.setImageBitmap(bitmap)
                row.image.visibility = View.VISIBLE
                row.placeholder.visibility = View.GONE
            }
            when (row.res.type) {
                SniffType.IMAGE -> SniffThumbs.load(
                    url, userAgent, row.res.pageUrl ?: target?.url, onReady
                )
                SniffType.VIDEO -> SniffThumbs.loadVideoFrame(
                    url, userAgent, row.res.pageUrl ?: target?.url, onReady
                )
                else -> Unit
            }
        }
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
        val playable = SniffStore.isPlayable(res.type)
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
                addView(thumbBox(res))
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
        val playable = SniffStore.isPlayable(res.type)
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

    /** 用内建播放器打开嗅探到的视频/音频/流媒体，带上当前页面的 Referer/UA。 */
    private fun play(res: SniffedResource) {
        val target = AppServices.sniffTarget
        val items = displayedItems()
            .filter { SniffStore.isPlayable(it.type) }
            .ifEmpty { listOf(res) }
        val index = items.indexOfFirst { it.url == res.url }.coerceAtLeast(0)
        parentFragmentManager.beginTransaction()
            .replace(
                R.id.shell_root,
                PlayerFragment.newInstance(
                    items,
                    index,
                    target?.url,
                    target?.settings?.userAgentString
                )
            )
            .addToBackStack(null)
            .commit()
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

    private fun showHeaderMenu() {
        val target = key() as? com.sii.browser.core.webview.SiiWebView
        val host = runCatching { android.net.Uri.parse(target?.url.orEmpty()).host }.getOrNull()
        val enabled = host != null && com.sii.browser.data.SiteConfig.sniffEnabledRaw(
            AppServices.siteConfigRepository.cachedConfig(host)
        )
        val labels = mutableListOf(getString(R.string.sniff_menu_clear))
        if (host != null) {
            labels.add(
                getString(
                    if (enabled) R.string.site_menu_prompt_pause
                    else R.string.site_menu_prompt_resume
                )
            )
        }
        android.app.AlertDialog.Builder(requireContext())
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 0 -> confirmClear()
                    host != null -> {
                        val text = com.sii.browser.data.SiteConfig.withSniffEnabled(
                            AppServices.siteConfigRepository.cachedConfig(host), !enabled
                        )
                        AppServices.siteConfigRepository.cachePut(host, text)
                        lifecycleScope.launch(Dispatchers.IO) {
                            AppServices.siteConfigRepository.put(host, text)
                        }
                        // 切换开关后都从零开始，避免看到旧站/旧条目。
                        target?.let { com.sii.browser.core.webview.SniffStore.clear(it) }
                        if (!enabled) {
                            target?.injectJavaScript(
                                com.sii.browser.core.webview.SniffJsBridge.SNIFF_JS
                            )
                            // 站点刚加入嗅探：重载一次，把错过的请求重新采集。
                            target?.reload()
                        }
                        Toast.makeText(
                            requireContext(),
                            if (enabled) R.string.site_prompt_paused
                            else R.string.site_prompt_enabled_reload,
                            Toast.LENGTH_SHORT
                        ).show()
                        render()
                    }
                }
            }
            .show()
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

    private companion object {
        /** 每批渲染的行数，滚到底部再追加。 */
        const val ROW_CHUNK = 30
    }
}
