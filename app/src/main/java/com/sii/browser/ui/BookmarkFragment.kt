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
import android.text.TextUtils
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
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sii.browser.R
import com.sii.browser.data.SiteIconCache
import com.sii.browser.data.db.BookmarkFolder
import com.sii.browser.data.db.BookmarkItem
import com.sii.browser.data.db.FolderItemCount
import com.sii.browser.di.AppServices
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 书签列表：文件夹浏览、排序/大小/图标样式、置顶、编辑模式、长按菜单与拖动排序。
 * 页面结构与 index.html 的 bmView 对齐，⋮ 菜单使用可展开的二级选项。
 */
class BookmarkFragment : Fragment() {

    private lateinit var listContainer: LinearLayout
    private lateinit var editBar: LinearLayout

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) applyHtmlImport(uri) }

    private var currentItems: List<BookmarkItem> = emptyList()
    private var currentFolders: List<BookmarkFolder> = emptyList()
    private var itemCounts: List<FolderItemCount> = emptyList()
    private var folderCounts: List<FolderItemCount> = emptyList()

    private var sortMode = "az"
    private var sizeMode = "md"
    private var iconMode = "initial"
    private var editMode = false
    private val selectedIds = mutableSetOf<String>()

    private val folderId: String? get() = arguments?.getString(ARG_FOLDER)
    private val repository get() = AppServices.bookmarkRepository

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
        setBackgroundColor(requireContext().getColor(R.color.bg_page))

        addView(
            PageHeader.build(requireContext(), getString(R.string.menu_bookmarks), onBack = {
                parentFragmentManager.popBackStack()
            }, onMenu = { anchor -> showMenu(anchor) })
        )
        addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(requireContext().getColor(R.color.divider))
        })

        editBar = buildEditBar()
        addView(editBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

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
        sortMode = AppServices.settings.bookmarkSort()
        sizeMode = AppServices.settings.bookmarkSize()
        iconMode = AppServices.settings.bookmarkIcon()
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                repository.itemsInFolder(folderId),
                repository.childFolders(folderId),
                repository.itemCounts(),
                repository.folderCounts()
            ) { items, folders, counts, fcounts ->
                currentItems = items
                currentFolders = folders
                itemCounts = counts
                folderCounts = fcounts
                render()
            }.collect { }
        }
    }

    // ---- 列表 ----

    private fun render() {
        listContainer.removeAllViews()
        editBar.visibility = if (editMode) View.VISIBLE else View.GONE
        if (folderId != null) listContainer.addView(upRow())

        val folders = sortedFolders()
        val items = sortedItems()
        if (folders.isEmpty() && items.isEmpty()) {
            listContainer.addView(TextView(requireContext()).apply {
                text = getString(R.string.bookmarks_empty)
                setTextSize(13f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(60), dp(8), dp(8))
            })
            return
        }
        val itemRows = mutableListOf<View>()
        folders.forEach { listContainer.addView(folderRow(it)) }
        items.forEach { listContainer.addView(itemRow(it, items, itemRows)) }
    }

    private fun sortedFolders(): List<BookmarkFolder> = when (sortMode) {
        "az" -> currentFolders.sortedBy { it.title.lowercase() }
        "za" -> currentFolders.sortedByDescending { it.title.lowercase() }
        else -> currentFolders
    }

    private fun sortedItems(): List<BookmarkItem> {
        val pinnedFirst = Comparator<BookmarkItem> { a, b ->
            (a.pinned == 0).compareTo(b.pinned == 0)
        }
        val comparator = when (sortMode) {
            "za" -> pinnedFirst.thenByDescending { it.title.lowercase() }
            "recent" -> pinnedFirst
                .thenByDescending { it.lastOpenedAt }
                .thenByDescending { it.updatedAt }
            "manual" -> pinnedFirst
            else -> pinnedFirst.thenBy { it.title.lowercase() }
        }
        return currentItems.sortedWith(comparator)
    }

    private fun upRow(): View = cardRow().apply {
        addView(android.widget.ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = android.content.res.ColorStateList.valueOf(
                requireContext().getColor(R.color.text_secondary)
            )
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(32), iconSize()).apply {
                marginEnd = dp(8)
            }
        })
        addView(TextView(requireContext()).apply {
            text = getString(R.string.bookmark_up)
            setTextSize(17f)
            setTextColor(requireContext().getColor(R.color.text_primary))
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL
            )
            letterSpacing = 0.01f
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        setOnClickListener { parentFragmentManager.popBackStack() }
    }

    private fun folderRow(folder: BookmarkFolder): View = cardRow().apply {
        if (editMode) addView(selectionCircle(folder.id in selectedIds))
        addView(folderIcon())
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(requireContext()).apply {
                text = folder.title
                setTextSize(titleSize())
                setTextColor(requireContext().getColor(R.color.text_primary))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(requireContext()).apply {
                val count = (itemCounts.firstOrNull { it.folderId == folder.id }?.count ?: 0) +
                    (folderCounts.firstOrNull { it.folderId == folder.id }?.count ?: 0)
                text = if (count == 0) getString(R.string.bookmark_folder_empty)
                else getString(R.string.bookmark_items_unit, count)
                setTextSize(subSize())
                setTextColor(requireContext().getColor(R.color.text_secondary))
                setPadding(0, dp(3), 0, 0)
            })
        }
        addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener {
            if (editMode) {
                toggleSelection(folder.id)
            } else {
                NavAnim.apply(parentFragmentManager.beginTransaction())
                    .replace(R.id.shell_root, BookmarkFragment().apply {
                        arguments = bundleOf(ARG_FOLDER to folder.id)
                    })
                    .addToBackStack(null)
                    .commit()
            }
        }
        if (!editMode) {
            setOnLongClickListener {
                showFolderContext(folder, this)
                true
            }
        }
    }

    private fun itemRow(
        item: BookmarkItem,
        displayedItems: List<BookmarkItem>,
        itemRows: MutableList<View>
    ): View {
        val row = cardRow()
        if (editMode) row.addView(selectionCircle(item.id in selectedIds))
        itemIcon(item)?.let { row.addView(it) }
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(requireContext()).apply {
                text = item.title.ifEmpty { item.url }
                setTextSize(titleSize())
                setTextColor(requireContext().getColor(R.color.text_primary))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(requireContext()).apply {
                text = hostOf(item.url).ifEmpty { item.url }
                setTextSize(subSize())
                setTextColor(requireContext().getColor(R.color.text_secondary))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val handle = TextView(requireContext()).apply {
            text = "☰"
            setTextSize(17f)
            setTextColor(requireContext().getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(34), iconSize())
            if (!editMode) setOnTouchListener(bookmarkDragListener(item, row, displayedItems, itemRows))
        }
        row.addView(handle)
        itemRows.add(row)

        row.setOnClickListener {
            if (editMode) {
                toggleSelection(item.id)
            } else {
                openBookmark(item)
            }
        }
        if (!editMode) {
            row.setOnLongClickListener {
                showItemContext(item, row)
                true
            }
        }
        return row
    }

    private fun cardRow(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipToOutline = true
        setPadding(dp(14), rowPadding(), dp(10), rowPadding())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        CardList.stylePageCard(this)
    }

    private fun selectionCircle(selected: Boolean): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (selected) {
                setColor(ThemeAccent.color(requireContext()))
            } else {
                setStroke(dp(2), requireContext().getColor(R.color.line))
            }
        }
    }

    private fun folderIcon(): View = ImageView(requireContext()).apply {
        setImageResource(R.drawable.ic_folder)
        layoutParams = LinearLayout.LayoutParams(iconSize(), iconSize()).apply {
            marginEnd = dp(12)
        }
    }

    private fun itemIcon(item: BookmarkItem): View? {
        if (iconMode == "none") return null
        val size = iconSize()
        val holder = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(12) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(colorOf(item.url))
            }
            clipToOutline = true
        }
        val letter = TextView(requireContext()).apply {
            text = (item.title.ifEmpty { item.url }).take(1).uppercase()
            setTextSize(if (sizeMode == "lg") 20f else 16f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        holder.addView(letter)
        if (iconMode == "favicon") {
            val host = hostOf(item.url)
            if (host.isNotEmpty()) {
                val cached = SiteIconCache.cached(requireContext(), host)
                if (cached != null) {
                    showFavicon(holder, cached)
                } else {
                    SiteIconCache.load(requireContext(), host) { bitmap ->
                        view?.post {
                            if (bitmap != null && holder.parent != null) showFavicon(holder, bitmap)
                        }
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

    private fun rowPadding(): Int = when (sizeMode) {
        "lg" -> dp(16)
        "sm" -> dp(9)
        else -> dp(13)
    }

    private fun iconSize(): Int = when (sizeMode) {
        "lg" -> dp(46)
        "sm" -> dp(30)
        else -> dp(38)
    }

    private fun titleSize(): Float = when (sizeMode) {
        "lg" -> 15f
        "sm" -> 13f
        else -> 14f
    }

    private fun subSize(): Float = if (sizeMode == "sm") 11f else 12f

    // ---- 菜单 ----

    private fun showMenu(anchor: View) {
        MenuPopup.show(
            anchor,
            listOf(
                MenuPopup.Item(
                    getString(if (editMode) R.string.bookmark_done else R.string.bookmark_edit)
                ) {
                    editMode = !editMode
                    selectedIds.clear()
                    render()
                },
                MenuPopup.Group(
                    getString(R.string.bookmark_sort),
                    listOf(
                        sortOption(R.string.bookmark_sort_az, "az"),
                        sortOption(R.string.bookmark_sort_za, "za"),
                        sortOption(R.string.bookmark_sort_recent, "recent"),
                        sortOption(R.string.bookmark_sort_manual, "manual")
                    )
                ),
                MenuPopup.Group(
                    getString(R.string.bookmark_size),
                    listOf(
                        sizeOption(R.string.bookmark_size_lg, "lg"),
                        sizeOption(R.string.bookmark_size_md, "md"),
                        sizeOption(R.string.bookmark_size_sm, "sm")
                    )
                ),
                MenuPopup.Group(
                    getString(R.string.bookmark_icon),
                    listOf(
                        iconOption(R.string.bookmark_icon_initial, "initial"),
                        iconOption(R.string.bookmark_icon_favicon, "favicon"),
                        iconOption(R.string.bookmark_icon_none, "none")
                    )
                ),
                MenuPopup.Item(getString(R.string.new_folder)) { showNewFolderDialog() },
                MenuPopup.Item(getString(R.string.bookmark_import_html)) {
                    importLauncher.launch(arrayOf("text/html", "text/plain", "*/*"))
                },
                MenuPopup.Item(getString(R.string.bookmark_export_html)) { exportHtml() }
            )
        )
    }

    private fun sortOption(labelRes: Int, value: String) = MenuPopup.Item(
        getString(labelRes),
        sortMode == value
    ) {
        sortMode = value
        AppServices.settings.setBookmarkSort(value)
        render()
    }

    private fun sizeOption(labelRes: Int, value: String) = MenuPopup.Item(
        getString(labelRes),
        sizeMode == value
    ) {
        sizeMode = value
        AppServices.settings.setBookmarkSize(value)
        render()
    }

    private fun iconOption(labelRes: Int, value: String) = MenuPopup.Item(
        getString(labelRes),
        iconMode == value
    ) {
        iconMode = value
        AppServices.settings.setBookmarkIcon(value)
        render()
    }

    private fun showItemContext(item: BookmarkItem, anchor: View) {
        MenuPopup.show(
            anchor,
            listOf(
                MenuPopup.Item(
                    getString(if (item.pinned == 1) R.string.bookmark_unpin else R.string.bookmark_pin)
                ) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        repository.setPinned(item, item.pinned == 0)
                    }
                },
                MenuPopup.Item(getString(R.string.bookmark_add_home)) { addToHome(item) },
                MenuPopup.Item(getString(R.string.menu_copy_link)) { copyLink(item.url) },
                MenuPopup.Item(getString(R.string.bookmark_edit_one)) { showEditItemDialog(item) },
                MenuPopup.Item(getString(R.string.delete)) {
                    confirmDelete(getString(R.string.delete)) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            repository.deleteItem(item.id)
                        }
                    }
                }
            )
        )
    }

    private fun showFolderContext(folder: BookmarkFolder, anchor: View) {
        MenuPopup.show(
            anchor,
            listOf(
                MenuPopup.Item(getString(R.string.bookmark_edit_one)) { showRenameFolderDialog(folder) },
                MenuPopup.Item(getString(R.string.delete)) {
                    confirmDelete(getString(R.string.delete)) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            repository.deleteFolderCascade(folder.id)
                        }
                    }
                }
            )
        )
    }

    // ---- 编辑模式 ----

    private fun buildEditBar(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(6), dp(14), dp(6))
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addView(editButton(R.string.bookmark_move) { moveSelected() },
            LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(8) })
        addView(editButton(R.string.delete) { deleteSelected() },
            LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(8) })
        addView(editButton(R.string.bookmark_done) {
            editMode = false
            selectedIds.clear()
            render()
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
    }

    private fun editButton(labelRes: Int, onClick: () -> Unit): TextView = TextView(requireContext()).apply {
        text = getString(labelRes)
        setTextSize(13f)
        setTextColor(ThemeAccent.color(requireContext()))
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_card)
        setOnClickListener { onClick() }
    }

    private fun toggleSelection(id: String) {
        if (!selectedIds.add(id)) selectedIds.remove(id)
        render()
    }

    private fun selectedItems(): List<BookmarkItem> = currentItems.filter { it.id in selectedIds }
    private fun selectedFolders(): List<BookmarkFolder> = currentFolders.filter { it.id in selectedIds }

    private fun moveSelected() {
        val items = selectedItems()
        val folders = selectedFolders()
        if (items.isEmpty() && folders.isEmpty()) {
            toast(R.string.bookmark_select_hint)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val allFolders = repository.allFoldersOnce()
            val choices = folderChoices(allFolders)
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.bookmark_move_to)
                .setItems(choices.map { it.first }.toTypedArray()) { _, which ->
                    val target = choices[which].second
                    if (folders.any { folder ->
                            target != null &&
                                (target == folder.id || isDescendant(allFolders, folder.id, target))
                        }
                    ) {
                        toast(R.string.bookmark_move_invalid)
                        return@setItems
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        items.forEach { repository.moveItemToFolder(it, target) }
                        folders.forEach { repository.moveFolderToFolder(it, target) }
                        editMode = false
                        selectedIds.clear()
                        render()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun deleteSelected() {
        val items = selectedItems()
        val folders = selectedFolders()
        if (items.isEmpty() && folders.isEmpty()) {
            toast(R.string.bookmark_select_hint)
            return
        }
        confirmDelete(getString(R.string.delete)) {
            viewLifecycleOwner.lifecycleScope.launch {
                if (items.isNotEmpty()) repository.deleteItems(items.map { it.id })
                folders.forEach { repository.deleteFolderCascade(it.id) }
                editMode = false
                selectedIds.clear()
                render()
            }
        }
    }

    private fun folderChoices(folders: List<BookmarkFolder>): List<Pair<String, String?>> {
        val byParent = folders.groupBy { it.parentFolderId }
        val out = mutableListOf<Pair<String, String?>>(getString(R.string.bookmark_root) to null)
        fun walk(parentId: String?, depth: Int) {
            byParent[parentId].orEmpty().sortedBy { it.ordering }.forEach { folder ->
                out.add("${"　".repeat(depth)}${folder.title}" to folder.id)
                walk(folder.id, depth + 1)
            }
        }
        walk(null, 0)
        return out
    }

    private fun isDescendant(
        folders: List<BookmarkFolder>,
        ancestorId: String,
        targetId: String
    ): Boolean {
        var current = folders.firstOrNull { it.id == targetId }
        while (current != null) {
            if (current.id == ancestorId) return true
            current = folders.firstOrNull { it.id == current?.parentFolderId }
        }
        return false
    }

    // ---- 打开与编辑 ----

    private fun openBookmark(item: BookmarkItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.markOpened(item)
        }
        AppServices.pendingOpenUrl = item.url
        // 书签可能位于多级文件夹中，必须退回浏览器根页面，而不是只退一级文件夹。
        parentFragmentManager.popBackStack(
            null,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
        )
    }

    private fun addToHome(item: BookmarkItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            AppServices.favoriteRepository.add(item.url, item.title)
            toast(getString(R.string.bookmark_added))
        }
    }

    private fun copyLink(url: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, url))
        toast(R.string.link_copied)
    }

    private fun showEditItemDialog(item: BookmarkItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val folders = repository.allFoldersOnce()
            val choices = folderChoices(folders)
            var selectedFolderId = item.folderId
            var selectedLabel = choices.firstOrNull { it.second == selectedFolderId }?.first
                ?: getString(R.string.bookmark_root)

            val nameInput = EditText(requireContext()).apply { setText(item.title) }
            val urlInput = EditText(requireContext()).apply { setText(item.url) }
            val location = TextView(requireContext()).apply {
                text = "$selectedLabel  ›"
                setTextSize(14f)
                setTextColor(requireContext().getColor(R.color.text_primary))
                setPadding(dp(4), dp(12), dp(4), dp(12))
            }
            location.setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.bookmark_location)
                    .setSingleChoiceItems(
                        choices.map { it.first }.toTypedArray(),
                        choices.indexOfFirst { it.second == selectedFolderId }.coerceAtLeast(0)
                    ) { dialog, which ->
                        selectedFolderId = choices[which].second
                        selectedLabel = choices[which].first
                        location.text = "$selectedLabel  ›"
                        dialog.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            val box = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(nameInput)
                addView(urlInput)
                addView(TextView(requireContext()).apply {
                    text = getString(R.string.bookmark_location)
                    setTextSize(12f)
                    setTextColor(requireContext().getColor(R.color.text_secondary))
                    setPadding(dp(4), dp(10), 0, 0)
                })
                addView(location)
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.bookmark_edit_one)
                .setView(box)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val title = nameInput.text.toString().trim().ifEmpty { item.title }
                    val url = urlInput.text.toString().trim().ifEmpty { item.url }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repository.updateItem(item, title, url, selectedFolderId)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showRenameFolderDialog(folder: BookmarkFolder) {
        val input = EditText(requireContext()).apply { setText(folder.title) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.bookmark_edit_one)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title = input.text.toString().trim()
                if (title.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        repository.renameFolder(folder, title)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showNewFolderDialog() {
        val input = EditText(requireContext())
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.folder_name)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        repository.addFolder(name, folderId)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- 拖动排序 ----

    /** 按住右侧 ☰ 上下拖动：拖动期间禁止外层滚动，松手后按当前展示顺序重排。 */
    private fun bookmarkDragListener(
        item: BookmarkItem,
        row: View,
        displayedItems: List<BookmarkItem>,
        itemRows: MutableList<View>
    ): View.OnTouchListener {
        var startY = 0f
        var fromIndex = -1
        var dragIndex = -1
        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    fromIndex = displayedItems.indexOfFirst { it.id == item.id }
                    dragIndex = fromIndex
                    startY = event.rawY
                    row.elevation = dp(8).toFloat()
                    (row.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (fromIndex < 0) {
                        false
                    } else {
                        row.translationY = event.rawY - startY
                        val center = row.top + row.translationY + row.height / 2f
                        dragIndex = itemRows.indexOfFirst { center < it.top + it.height / 2f }
                            .let { if (it < 0) itemRows.lastIndex else it }
                        true
                    }
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    (row.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                    row.translationY = 0f
                    row.elevation = 0f
                    if (fromIndex >= 0 && dragIndex in displayedItems.indices &&
                        fromIndex != dragIndex
                    ) {
                        val reordered = displayedItems.toMutableList()
                        val moved = reordered.removeAt(fromIndex)
                        reordered.add(dragIndex.coerceIn(0, reordered.size), moved)
                        sortMode = "manual"
                        AppServices.settings.setBookmarkSort(sortMode)
                        viewLifecycleOwner.lifecycleScope.launch {
                            repository.reorderItems(reordered)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ---- 导入导出 ----

    private fun exportHtml() {
        viewLifecycleOwner.lifecycleScope.launch {
            val items = repository.allItemsOnce()
            val folders = repository.allFoldersOnce()
            val html = com.sii.browser.data.BookmarkHtml.export(items, folders)
            val name = "sii-bookmarks-${System.currentTimeMillis()}.html"
            val path = FileExchange.writeToDownloads(requireContext(), name, html)
            Toast.makeText(
                requireContext(),
                if (path != null) getString(R.string.data_exported_path, path)
                else getString(R.string.rules_import_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun applyHtmlImport(uri: android.net.Uri) {
        val text = FileExchange.readText(requireContext(), uri)
        if (text.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.rules_import_empty, Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = runCatching { com.sii.browser.data.BookmarkHtml.parse(text) }
                .getOrDefault(emptyList())
            if (entries.isEmpty()) {
                Toast.makeText(requireContext(), R.string.rules_import_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val folderIds = mutableMapOf<String, String>()
            entries.forEach { entry ->
                val targetFolder = entry.folder?.let { name ->
                    folderIds.getOrPut(name) {
                        repository.childFoldersOnce(null)
                            .firstOrNull { it.title == name }?.id
                            ?: repository.addFolder(name, null).id
                    }
                }
                repository.addItem(entry.url, entry.title, targetFolder)
            }
            Toast.makeText(requireContext(), R.string.rules_imported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun hostOf(url: String): String =
        runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()

    private fun colorOf(url: String): Int {
        var h = 0
        url.forEach { h = h * 31 + it.code }
        return COLORS[Math.floorMod(h, COLORS.size)]
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_FOLDER = "folder_id"
        private val COLORS = intArrayOf(
            0xFF2F7CF6.toInt(), 0xFF34C28F.toInt(), 0xFFF2984A.toInt(), 0xFF8A6FF0.toInt(),
            0xFFE5537A.toInt(), 0xFF3AB3C9.toInt(), 0xFFC9A23A.toInt(), 0xFF6D7F95.toInt()
        )
    }
}
