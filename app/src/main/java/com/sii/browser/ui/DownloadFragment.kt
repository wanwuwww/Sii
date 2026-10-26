// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sii.browser.R
import com.sii.browser.data.db.DownloadState
import com.sii.browser.di.AppServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 下载列表（下载任务列表）。
 * Room Flow 响应式驱动，每行：名称/进度条/状态/暂停恢复/删除。
 */
class DownloadFragment : Fragment() {

    private lateinit var container: LinearLayout
    private var sortBy: Int = SORT_TIME
    private var filter: Int = FILTER_ALL
    private var currentTasks: List<com.sii.browser.data.db.DownloadTask> = emptyList()
    private val apkLabels = mutableMapOf<Long, String>()
    private val lastToggleAt = mutableMapOf<Long, Long>()
    private val rowViews = mutableMapOf<Long, View>()
    private val boundTasks = mutableMapOf<Long, com.sii.browser.data.db.DownloadTask>()
    private var listSignature: String = ""
    private val fileExists = mutableMapOf<Long, Boolean>()
    private val lastProgress = mutableMapOf<Long, Pair<Long, Long>>()
    private val lastSpeed = mutableMapOf<Long, Long>()

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
        addView(PageHeader.build(requireContext(), getString(R.string.menu_downloads), onBack = {
            parentFragmentManager.popBackStack()
        }, onMenu = { anchor ->
            showMenu(anchor)
        }))
        addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(requireContext().getColor(R.color.divider))
        })
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        addView(buildFilterBar())
        addView(CardList.scroll(requireContext(), content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        this@DownloadFragment.container = content
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requestNotificationPermission()
        viewLifecycleOwner.lifecycleScope.launch {
            AppServices.downloadRepository.allTasks().collect { tasks ->
                currentTasks = tasks.map { it }
                render(currentTasks)
            }
        }
    }

    private fun showMenu(anchor: View) {
        MenuPopup.show(anchor, listOf(
            MenuPopup.Header(getString(R.string.download_sort)),
            MenuPopup.Item(getString(R.string.download_sort_time), sortBy == SORT_TIME) {
                sortBy = SORT_TIME
                render(currentTasks)
            },
            MenuPopup.Item(getString(R.string.download_sort_name), sortBy == SORT_NAME) {
                sortBy = SORT_NAME
                render(currentTasks)
            },
            MenuPopup.Item(getString(R.string.download_sort_size), sortBy == SORT_SIZE) {
                sortBy = SORT_SIZE
                render(currentTasks)
            },
            MenuPopup.Header(getString(R.string.download_settings)),
            MenuPopup.Item(getString(R.string.download_pause_all)) { pauseAll() },
            MenuPopup.Item(getString(R.string.download_resume_all)) { resumeAll() },
            MenuPopup.Item(getString(R.string.download_clear_completed)) { clearCompleted() },
            MenuPopup.Item(getString(R.string.download_clear_all)) { clearAll() },
            MenuPopup.Item(getString(R.string.download_settings)) { openDownloadSettings() }
        ))
    }

    private fun pauseAll() {
        com.sii.browser.download.DownloadService.pauseAllTasks(requireContext())
    }

    private fun resumeAll() {
        com.sii.browser.download.DownloadService.resumeAllTasks(requireContext())
    }

    private fun clearCompleted() = confirmClear(completedOnly = true)

    private fun clearAll() = confirmClear(completedOnly = false)

    private fun confirmClear(completedOnly: Boolean) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(
                getString(
                    if (completedOnly) R.string.download_clear_completed
                    else R.string.download_clear_all
                )
            )
            .setMessage(R.string.download_clear_confirm)
            .setPositiveButton(R.string.download_clear_keep) { _, _ ->
                doClear(completedOnly, deleteFiles = false)
            }
            .setNeutralButton(R.string.download_clear_delete) { _, _ ->
                doClear(completedOnly, deleteFiles = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doClear(completedOnly: Boolean, deleteFiles: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val tasks = AppServices.downloadRepository.allTasks().first()
            tasks.filter { !completedOnly || it.state == DownloadState.COMPLETED }
                .forEach { AppServices.downloadEngine.cancelAndWait(it.id) }
            if (deleteFiles) {
                val ctx = requireContext().applicationContext
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    tasks.filter {
                        !completedOnly || it.state == DownloadState.COMPLETED
                    }.forEach { task ->
                        com.sii.browser.download.IncognitoCleanup.deleteFile(
                            ctx, task.path, task.name
                        )
                    }
                }
            }
            if (completedOnly) {
                AppServices.downloadRepository.clearCompleted()
            } else {
                AppServices.downloadRepository.clearAll()
            }
            context?.let { com.sii.browser.download.DownloadService.onTasksChanged(it) }
            fileExists.clear()
            apkLabels.clear()
        }
    }

    private fun openDownloadSettings() {
        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(R.id.shell_root, SettingsDetailFragment().apply {
                arguments = android.os.Bundle().apply {
                    putString(SettingsDetailFragment.ARG_VIEW, SettingsDetailFragment.VIEW_DL_SET)
                }
            })
            .addToBackStack(null)
            .commit()
    }

    /** 列表结构签名：任务集合/顺序/状态变化时才重建行，进度只原地更新。 */
    private fun structureSignature(
        sorted: List<com.sii.browser.data.db.DownloadTask>
    ): String = AppServices.settings.downloader().toString() + "|" + sorted.joinToString("|") {
        "${it.id}:${it.state}:${it.name}:${it.path.orEmpty()}"
    }

    private fun render(tasks: List<com.sii.browser.data.db.DownloadTask>) {
        val filtered = tasks.filter { filterMatch(it) }
        val sorted = when (sortBy) {
            SORT_NAME -> filtered.sortedBy { it.name.lowercase() }
            SORT_SIZE -> filtered.sortedByDescending { it.totalSize }
            else -> filtered.sortedByDescending { it.createdAt }
        }
        val signature = structureSignature(sorted)
        if (signature != listSignature) {
            listSignature = signature
            rebuildList(sorted)
        } else {
            sorted.forEach { updateRow(it) }
        }
    }

    private fun rebuildList(sorted: List<com.sii.browser.data.db.DownloadTask>) {
        container.removeAllViews()
        rowViews.clear()
        boundTasks.clear()
        if (AppServices.settings.downloader() == 1) {
            container.addView(TextView(requireContext()).apply {
                text = getString(R.string.download_system_managed)
                setTextSize(11.5f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
                setPadding(dp(14), dp(8), dp(14), dp(8))
            })
        }
        if (sorted.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = getString(R.string.download_empty)
                setPadding(dp(8), dp(24), dp(8), dp(8))
            })
            return
        }
        val card = CardList.card(requireContext())
        sorted.forEachIndexed { index, task ->
            val row = buildRow(task)
            rowViews[task.id] = row
            boundTasks[task.id] = task
            if (index > 0) card.addView(CardList.divider(card.context))
            card.addView(row)
        }
        container.addView(card)
    }

    /** 结构不变时只刷新动态内容，按钮实例不动，点击/长按不被重建打断。 */
    private fun updateRow(task: com.sii.browser.data.db.DownloadTask) {
        val row = rowViews[task.id] ?: return
        val bar = row.findViewById<ProgressBar>(R.id.download_progress) ?: return
        bar.isIndeterminate = task.state == DownloadState.DOWNLOADING && task.totalSize <= 0L
        bar.progress = if (task.totalSize > 0) {
            (task.downloadedSize * 100 / task.totalSize).toInt()
        } else 0
        boundTasks[task.id] = task
        row.findViewById<TextView>(R.id.download_state)?.let { tv ->
            val exists = taskFileExists(task)
            tv.text = if (task.state == DownloadState.COMPLETED && !exists) {
                getString(R.string.download_file_deleted)
            } else stateLabel(task.state)
            tv.setTextColor(
                requireContext().getColor(
                    if (task.state == DownloadState.COMPLETED && !exists) R.color.danger
                    else R.color.text_secondary
                )
            )
        }
        row.findViewById<TextView>(R.id.download_size)?.let { tv ->
            tv.text = sizeText(task)
        }
    }

    private fun buildFilterBar(): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        val scroll = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        val filters = listOf(
            FILTER_ALL to R.string.download_filter_all,
            FILTER_APK to R.string.download_filter_apk,
            FILTER_VIDEO to R.string.download_filter_video,
            FILTER_AUDIO to R.string.download_filter_audio,
            FILTER_IMAGE to R.string.download_filter_image,
            FILTER_DOC to R.string.download_filter_doc,
            FILTER_ARCHIVE to R.string.download_filter_archive,
            FILTER_OTHER to R.string.download_filter_other
        )
        filters.forEach { (value, label) ->
            row.addView(TextView(context).apply {
                text = getString(label)
                setTextSize(12.5f)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setTextColor(
                    if (filter == value) ThemeAccent.color(context) else context.getColor(R.color.text_secondary)
                )
                setOnClickListener {
                    filter = value
                    render(currentTasks)
                    refreshFilterBar(this)
                }
            })
        }
        return scroll
    }

    private fun refreshFilterBar(selected: TextView) {
        val parent = selected.parent as? LinearLayout ?: return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) as? TextView ?: continue
            child.setTextColor(
                if (child === selected) ThemeAccent.color(requireContext())
                else requireContext().getColor(R.color.text_secondary)
            )
        }
    }

    private fun filterMatch(task: com.sii.browser.data.db.DownloadTask): Boolean {
        if (filter == FILTER_ALL) return true
        val ext = task.name.substringAfterLast('.', "").lowercase()
        val mime = task.mimetype.orEmpty().lowercase()
        return when (filter) {
            FILTER_APK -> ext == "apk" || mime.contains("android.package")
            FILTER_VIDEO -> mime.startsWith("video/") ||
                ext in setOf("mp4", "mkv", "avi", "webm", "m3u8", "flv", "mov", "ts")
            FILTER_AUDIO -> mime.startsWith("audio/") ||
                ext in setOf("mp3", "m4a", "aac", "flac", "ogg", "wav")
            FILTER_IMAGE -> mime.startsWith("image/") ||
                ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")
            FILTER_DOC -> ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub")
            FILTER_ARCHIVE -> ext in setOf("zip", "rar", "7z", "tar", "gz")
            else -> !filterMatchType(task, ext, mime)
        }
    }

    private fun filterMatchType(
        task: com.sii.browser.data.db.DownloadTask,
        ext: String,
        mime: String
    ): Boolean =
        ext == "apk" || mime.contains("android.package") ||
            mime.startsWith("video/") || mime.startsWith("audio/") ||
            mime.startsWith("image/") ||
            ext in setOf(
                "mp4", "mkv", "avi", "webm", "m3u8", "flv", "mov", "ts",
                "mp3", "m4a", "aac", "flac", "ogg", "wav",
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg",
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub",
                "zip", "rar", "7z", "tar", "gz"
            )

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        if (requireContext().checkSelfPermission(permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(permission), 2001)
    }

    private fun buildRow(task: com.sii.browser.data.db.DownloadTask): View {
        val context = requireContext()
        val progress = if (task.totalSize > 0) {
            (task.downloadedSize * 100 / task.totalSize).toInt()
        } else 0
        val exists = taskFileExists(task)
        val name = TextView(context).apply {
            text = displayName(task)
            maxLines = 1
            setTextSize(14f)
            setTextColor(context.getColor(R.color.text_primary))
        }
        val state = TextView(context).apply {
            id = R.id.download_state
            text = if (task.state == DownloadState.COMPLETED && !exists) {
                getString(R.string.download_file_deleted)
            } else stateLabel(task.state)
            setTextSize(11.5f)
            setTextColor(
                context.getColor(
                    if (task.state == DownloadState.COMPLETED && !exists) R.color.danger
                    else R.color.text_secondary
                )
            )
            setPadding(dp(6), 0, dp(6), 0)
        }
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            id = R.id.download_progress
            max = 100
            this.progress = progress
            isIndeterminate = task.state == DownloadState.DOWNLOADING && task.totalSize <= 0L
            visibility = if (task.state == DownloadState.COMPLETED) View.GONE else View.VISIBLE
        }
        val action = ImageButton(context).apply {
            background = null
            when {
                task.state == DownloadState.DOWNLOADING -> {
                    setImageResource(R.drawable.ic_pause)
                    contentDescription = getString(R.string.download_pause)
                    setOnClickListener { toggleRun(task) { com.sii.browser.download.DownloadService.pauseTask(requireContext(), task.id) } }
                }
                task.state == DownloadState.PAUSED -> {
                    setImageResource(R.drawable.ic_play)
                    contentDescription = getString(R.string.download_resume)
                    setOnClickListener { toggleRun(task) { com.sii.browser.download.DownloadService.resumeTask(requireContext(), task.id) } }
                }
                task.state == DownloadState.FAILED -> {
                    setImageResource(R.drawable.ic_download_failed)
                    contentDescription = getString(R.string.download_resume)
                    setColorFilter(context.getColor(R.color.danger))
                    setOnClickListener { toggleRun(task) { com.sii.browser.download.DownloadService.resumeTask(requireContext(), task.id) } }
                }
                task.state == DownloadState.COMPLETED && !exists -> {
                    setImageResource(R.drawable.ic_refresh)
                    contentDescription = getString(R.string.download_redownload)
                    setOnClickListener { redownload(task) }
                }
                task.state == DownloadState.COMPLETED -> {
                    setImageResource(R.drawable.ic_download_done)
                    setColorFilter(ThemeAccent.color(context))
                    contentDescription = if (isApk(task)) getString(R.string.download_install)
                    else getString(R.string.download_open)
                    setOnClickListener { openTask(task) }
                }
                else -> {
                    setImageResource(R.drawable.ic_play)
                    contentDescription = getString(R.string.download_resume)
                    setOnClickListener { toggleRun(task) { com.sii.browser.download.DownloadService.resumeTask(requireContext(), task.id) } }
                }
            }
        }
        val delete = ImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            background = null
            contentDescription = getString(R.string.delete)
            setOnClickListener { confirmDelete(task) }
        }
        val sizeText = TextView(context).apply {
            id = R.id.download_size
            text = sizeText(task)
            setTextSize(11f)
            setTextColor(context.getColor(R.color.text_secondary))
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(android.widget.ImageView(context).apply {
                setImageResource(fileIcon(task))
                setColorFilter(context.getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                    marginEnd = dp(8)
                }
            })
            addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(state)
            addView(action)
            addView(delete)
        }
        return CardList.column(context).apply {
            addView(header)
            addView(bar)
            addView(sizeText)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { openTask(task) }
            setOnLongClickListener {
                showTaskMenu(task, it)
                true
            }
        }
    }

    private fun sizeText(task: com.sii.browser.data.db.DownloadTask): String {
        val speed = speedFor(task)
        return when {
            task.state == DownloadState.COMPLETED ->
                getString(R.string.download_size_only, formatSize(task.totalSize))
            task.state == DownloadState.DOWNLOADING && speed > 0 && task.totalSize > 0 ->
                getString(
                    R.string.download_size_speed,
                    formatSize(task.downloadedSize),
                    formatSize(task.totalSize),
                    formatSize(speed)
                )
            task.state == DownloadState.DOWNLOADING && speed > 0 ->
                getString(
                    R.string.download_size_speed_unknown_total,
                    formatSize(task.downloadedSize),
                    formatSize(speed)
                )
            task.totalSize > 0 ->
                getString(
                    R.string.download_size_progress,
                    formatSize(task.downloadedSize),
                    formatSize(task.totalSize)
                )
            else -> getString(R.string.download_size_unknown)
        }
    }

    private fun speedFor(task: com.sii.browser.data.db.DownloadTask): Long {
        if (task.state != DownloadState.DOWNLOADING) {
            lastProgress.remove(task.id)
            lastSpeed.remove(task.id)
            return 0
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastProgress[task.id]
        var speed = lastSpeed[task.id] ?: 0L
        if (previous == null) {
            lastProgress[task.id] = task.downloadedSize to now
            return 0
        }
        val delta = task.downloadedSize - previous.first
        val elapsed = now - previous.second
        if (delta > 0 && elapsed >= 300) {
            val instant = delta * 1000L / elapsed
            speed = if (speed <= 0) instant else (speed * 2 + instant) / 3
            lastProgress[task.id] = task.downloadedSize to now
            lastSpeed[task.id] = speed
        }
        return speed
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (index == 0) "${value.toLong()} ${units[index]}"
        else String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[index])
    }

    /** 暂停/继续防抖：800ms 内忽略同任务的再次点击，避免狂点时误触状态切换。 */
    private fun toggleRun(task: com.sii.browser.data.db.DownloadTask, run: () -> Unit) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastToggleAt[task.id] ?: 0L
        if (now - last < 800) return
        lastToggleAt[task.id] = now
        run()
    }

    private fun isApk(task: com.sii.browser.data.db.DownloadTask): Boolean =
        task.name.endsWith(".apk", ignoreCase = true) ||
            task.mimetype.orEmpty().contains("android.package")

    private fun isVideo(task: com.sii.browser.data.db.DownloadTask): Boolean {
        val ext = task.name.substringAfterLast('.', "").lowercase()
        return task.mimetype.orEmpty().startsWith("video/") ||
            ext in setOf("mp4", "mkv", "avi", "webm", "m3u8", "flv", "mov", "ts")
    }

    private fun fileIcon(task: com.sii.browser.data.db.DownloadTask): Int {
        val ext = task.name.substringAfterLast('.', "").lowercase()
        val mime = task.mimetype.orEmpty().lowercase()
        return when {
            isApk(task) -> R.drawable.ic_file_apk
            isVideo(task) -> R.drawable.ic_file_video
            mime.startsWith("audio/") ||
                ext in setOf("mp3", "m4a", "aac", "flac", "ogg", "wav") ->
                R.drawable.ic_file_audio
            mime.startsWith("image/") ||
                ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg") ->
                R.drawable.ic_file_image
            ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub") ->
                R.drawable.ic_file_doc
            ext in setOf("zip", "rar", "7z", "tar", "gz") -> R.drawable.ic_file_archive
            else -> R.drawable.ic_file_other
        }
    }

    private fun taskFileExists(task: com.sii.browser.data.db.DownloadTask): Boolean {
        // 只缓存“存在”。下载刚完成/SAF 移动期间可能短暂不存在，缓存 false 会误报文件已删除。
        if (fileExists[task.id] == true) return true
        val path = task.path ?: return false
        val exists = if (path.startsWith("content://")) {
            runCatching {
                requireContext().contentResolver.openInputStream(android.net.Uri.parse(path))
                    ?.use { true } ?: false
            }.getOrDefault(false)
        } else {
            java.io.File(path).exists()
        }
        if (exists) fileExists[task.id] = true
        return exists
    }

    override fun onResume() {
        super.onResume()
        if (::container.isInitialized) {
            // 重新进入下载页时重新探测，文件被外部删除也能及时反映。
            fileExists.clear()
            render(currentTasks)
        }
    }

    private fun displayName(task: com.sii.browser.data.db.DownloadTask): String {
        if (!isApk(task)) return task.name
        apkLabels[task.id]?.let { return it }
        val path = task.path
        if (path.isNullOrEmpty() || path.startsWith("content://")) return task.name
        val label = runCatching {
            val info = requireContext().packageManager
                .getPackageArchiveInfo(path, 0) ?: return@runCatching task.name
            val appInfo = info.applicationInfo ?: return@runCatching task.name
            appInfo.sourceDir = path
            requireContext().packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(task.name)
        apkLabels[task.id] = label
        return label
    }

    private fun openTask(task: com.sii.browser.data.db.DownloadTask) {
        if (task.state != DownloadState.COMPLETED) return
        if (!taskFileExists(task)) {
            Toast.makeText(requireContext(), R.string.download_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        if (isApk(task)) {
            installApk(task)
            return
        }
        val path = task.path ?: return
        val opened = runCatching {
            val uri = if (path.startsWith("content://")) {
                android.net.Uri.parse(path)
            } else {
                androidx.core.content.FileProvider.getUriForFile(
                    requireContext(), "${requireContext().packageName}.fileprovider",
                    java.io.File(path)
                )
            }
            val ext = task.name.substringAfterLast('.', "").lowercase()
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext) ?: "*/*"
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
            true
        }.getOrDefault(false)
        if (!opened) {
            Toast.makeText(requireContext(), R.string.download_file_missing, Toast.LENGTH_SHORT).show()
        }
    }

    /** 长按菜单：强制弹出应用选择器，用第三方应用打开文件。 */
    private fun openWith(task: com.sii.browser.data.db.DownloadTask) {
        if (task.state != DownloadState.COMPLETED) return
        if (!taskFileExists(task)) {
            Toast.makeText(requireContext(), R.string.download_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val path = task.path ?: return
        runCatching {
            val uri = if (path.startsWith("content://")) {
                android.net.Uri.parse(path)
            } else {
                androidx.core.content.FileProvider.getUriForFile(
                    requireContext(), "${requireContext().packageName}.fileprovider",
                    java.io.File(path)
                )
            }
            val ext = task.name.substringAfterLast('.', "").lowercase()
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext) ?: "*/*"
            val view = android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(
                android.content.Intent.createChooser(
                    view, getString(R.string.download_open_with)
                )
            )
        }.onFailure {
            Toast.makeText(requireContext(), R.string.download_file_missing, Toast.LENGTH_SHORT).show()
        }
    }

    private fun installApk(task: com.sii.browser.data.db.DownloadTask) {
        if (!isApk(task)) return
        val path = task.path ?: return
        runCatching {
            val uri = if (path.startsWith("content://")) {
                android.net.Uri.parse(path)
            } else {
                val file = java.io.File(path)
                if (!file.isFile || !file.name.endsWith(".apk", ignoreCase = true)) {
                    throw IllegalStateException("not an apk")
                }
                androidx.core.content.FileProvider.getUriForFile(
                    requireContext(), "${requireContext().packageName}.fileprovider",
                    file
                )
            }
            // 与系统安装器一致：INSTALL_PACKAGE + APK MIME，未授权未知来源时由安装器引导。
            val intent = android.content.Intent("android.intent.action.INSTALL_PACKAGE")
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        }.onFailure {
            Toast.makeText(requireContext(), R.string.download_file_missing, Toast.LENGTH_SHORT).show()
        }
    }

    private fun redownload(task: com.sii.browser.data.db.DownloadTask) {
        val intent = android.content.Intent(
            requireContext(), com.sii.browser.download.DownloadService::class.java
        ).apply {
            action = com.sii.browser.download.DownloadService.ACTION_REDOWNLOAD
            putExtra(com.sii.browser.download.DownloadService.EXTRA_TASK_ID, task.id)
        }
        requireContext().startService(intent)
        fileExists.remove(task.id)
        apkLabels.remove(task.id)
    }

    private fun showTaskMenu(task: com.sii.browser.data.db.DownloadTask, anchor: View) {
        val items = mutableListOf<MenuPopup.Item>()
        if (task.state == DownloadState.COMPLETED && taskFileExists(task)) {
            items.add(MenuPopup.Item(
                getString(if (isApk(task)) R.string.download_install else R.string.download_open)
            ) { openTask(task) })
            items.add(MenuPopup.Item(getString(R.string.download_open_with)) { openWith(task) })
        } else {
            items.add(MenuPopup.Item(getString(R.string.download_redownload)) { redownload(task) })
        }
        items.add(MenuPopup.Item(getString(R.string.download_copy_url)) {
            val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("url", task.url))
            Toast.makeText(requireContext(), R.string.link_copied, Toast.LENGTH_SHORT).show()
        })
        items.add(MenuPopup.Item(getString(R.string.download_view_url)) {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.download_url_title)
                .setMessage(task.url)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        })
        items.add(MenuPopup.Item(getString(R.string.download_share)) {
            runCatching {
                startActivity(
                    android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, task.url)
                        },
                        getString(R.string.download_share)
                    )
                )
            }
        })
        items.add(MenuPopup.Item(getString(R.string.download_remove_record)) {
            viewLifecycleOwner.lifecycleScope.launch {
                AppServices.downloadRepository.deleteTask(task.id)
                context?.let { com.sii.browser.download.DownloadService.onTasksChanged(it) }
                fileExists.remove(task.id)
                apkLabels.remove(task.id)
            }
        })
        if (task.state == DownloadState.COMPLETED) {
            items.add(MenuPopup.Item(getString(R.string.download_delete_with_file)) {
                deleteTaskAndFile(task)
            })
        }
        MenuPopup.show(anchor, items)
    }

    private fun confirmDelete(task: com.sii.browser.data.db.DownloadTask) {
        val hasFile = task.state == DownloadState.COMPLETED && taskFileExists(task)
        if (!hasFile) {
            viewLifecycleOwner.lifecycleScope.launch {
                AppServices.downloadRepository.deleteTask(task.id)
                context?.let { com.sii.browser.download.DownloadService.onTasksChanged(it) }
            }
            return
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(R.string.download_clear_confirm)
            .setPositiveButton(R.string.download_remove_record) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    AppServices.downloadRepository.deleteTask(task.id)
                context?.let { com.sii.browser.download.DownloadService.onTasksChanged(it) }
                }
            }
            .setNeutralButton(R.string.download_delete_with_file) { _, _ ->
                deleteTaskAndFile(task)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteTaskAndFile(task: com.sii.browser.data.db.DownloadTask) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.sii.browser.download.IncognitoCleanup.deleteFile(ctx, task.path, task.name)
            }
            AppServices.downloadRepository.deleteTask(task.id)
            context?.let { com.sii.browser.download.DownloadService.onTasksChanged(it) }
            fileExists.remove(task.id)
            apkLabels.remove(task.id)
        }
    }

    private fun stateLabel(state: Int): String = when (state) {
        DownloadState.PENDING -> getString(R.string.download_state_pending)
        DownloadState.DOWNLOADING -> getString(R.string.download_state_downloading)
        DownloadState.PAUSED -> getString(R.string.download_state_paused)
        DownloadState.COMPLETED -> getString(R.string.download_state_completed)
        else -> getString(R.string.download_state_failed)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SORT_TIME = 0
        private const val SORT_NAME = 1
        private const val SORT_SIZE = 2
        private const val FILTER_ALL = 0
        private const val FILTER_APK = 1
        private const val FILTER_VIDEO = 2
        private const val FILTER_AUDIO = 3
        private const val FILTER_IMAGE = 4
        private const val FILTER_DOC = 5
        private const val FILTER_ARCHIVE = 6
        private const val FILTER_OTHER = 7
    }
}
