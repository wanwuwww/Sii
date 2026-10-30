// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.sii.browser.R
import com.sii.browser.di.AppServices

/** 全屏「主页」定制：上方真实主页预览，下方五个分类控制面板。 */
class HomeCustomizeFragment : Fragment() {

    companion object {
        const val ARG_TAB = "tab"
        private const val PREVIEW_REFRESH_INTERVAL_MS = 80L
    }

    private lateinit var previewHost: FrameLayout
    private lateinit var tabs: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var controlsScroll: ScrollView
    private lateinit var root: LinearLayout
    private var preview: HomePanel? = null
    private var previewShell: HomePreviewShell? = null
    private var activeTab = 0
    private var previewWeb = false
    private var previewRefreshPosted = false
    private var previewRefreshAt = 0L
    private var menuExpanded = false
    private var fullscreen = false
    private var savedPreviewHeight = 0

    private val settings get() = AppServices.settings

    private val fontLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val dir = java.io.File(requireContext().filesDir, "fonts").apply { mkdirs() }
            val target = java.io.File(dir, "custom_font")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            settings.setCustomFont(target.absolutePath)
        }
        buildControls()
    }

    private val bgLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        settings.setHomeBackground(uri.toString())
        refreshPreview()
        buildControls()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home_customize, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activeTab = (arguments?.getInt(ARG_TAB, 0) ?: 0).coerceIn(0, 8)
        view.findViewById<View>(R.id.home_custom_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        previewHost = view.findViewById(R.id.home_custom_preview)
        tabs = view.findViewById(R.id.home_custom_tabs)
        controls = view.findViewById(R.id.home_custom_controls)
        controlsScroll = view.findViewById(R.id.home_custom_controls_scroll)
        root = view.findViewById(R.id.home_custom_root)
        previewHost.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(requireContext().getColor(R.color.bg_page))
        }
        previewHost.clipToOutline = true
        view.findViewById<View>(R.id.home_custom_fullscreen).setOnClickListener {
            toggleFullscreen()
        }
        view.findViewById<View>(R.id.home_custom_refresh).setOnClickListener {
            view.post {
                preview?.applyHomeStyle()
                previewShell?.render()
            }
        }
        setupResizeHandle(view.findViewById(R.id.home_custom_resize_handle))

        val panel = HomePanel(
            requireContext(),
            onSearch = {},
            onOpen = {},
            onScan = {},
            onSuggest = { _, callback -> callback(emptyList()) },
            previewMode = true
        )
        preview = panel
        viewLifecycleOwner.lifecycleScope.launch {
            AppServices.favoriteRepository.all().collect { items ->
                if (isAdded && view?.isAttachedToWindow == true) preview?.bind(items)
            }
        }
        val shell = HomePreviewShell(
            requireContext(),
            panel,
            onMenuClick = {
                activeTab = if (activeTab == 6) 5 else 6
                renderTabs()
                buildControls()
                previewShell?.setMenuVisible(activeTab == 6)
            },
            onAddressClick = {
                activeTab = 2
                renderTabs()
                buildControls()
            },
            onToolbarClick = {
                activeTab = 5
                renderTabs()
                buildControls()
            }
        )
        previewShell = shell
        previewHost.addView(
            shell.view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val labels = listOf(
            getString(R.string.home_tab_logo),
            getString(R.string.home_tab_search),
            getString(R.string.home_tab_address),
            getString(R.string.home_tab_background),
            getString(R.string.home_tab_sites),
            getString(R.string.home_tab_toolbar),
            getString(R.string.home_tab_menu),
            getString(R.string.home_tab_tabs),
            getString(R.string.home_tab_theme),
            getString(R.string.home_tab_advanced)
        )
        labels.forEachIndexed { index, label ->
            tabs.addView(TextView(requireContext()).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(12f)
                setPadding(dp(12), dp(4), dp(12), 0)
                setOnClickListener {
                    activeTab = index
                    renderTabs()
                    buildControls()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            })
        }
        renderTabs()
        buildControls()
    }

    override fun onResume() {
        super.onResume()
        if (::controls.isInitialized) {
            preview?.applyHomeStyle()
            previewShell?.render()
        }
    }

    private fun setupResizeHandle(handle: View) {
        var startY = 0f
        var startHeight = 0
        handle.setOnTouchListener { _, event ->
            if (fullscreen) return@setOnTouchListener true
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startHeight = previewHost.height
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val chromeHeight = dp(48 + 18 + 52)
                    val maxHeight = (root.height - chromeHeight - dp(110)).coerceAtLeast(dp(160))
                    val height = (startHeight + (event.rawY - startY).toInt())
                        .coerceIn(dp(160), maxHeight)
                    val params = previewHost.layoutParams as LinearLayout.LayoutParams
                    params.weight = 0f
                    params.height = height
                    previewHost.layoutParams = params
                    controlsScroll.layoutParams = controlsScroll.layoutParams.apply {
                        this.height = (root.height - chromeHeight - height).coerceAtLeast(dp(110))
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        val handle = view?.findViewById<View>(R.id.home_custom_resize_handle)
        if (fullscreen) {
            savedPreviewHeight = previewHost.height
            tabs.visibility = View.GONE
            controlsScroll.visibility = View.GONE
            handle?.visibility = View.GONE
            val params = previewHost.layoutParams as LinearLayout.LayoutParams
            params.weight = 1f
            params.height = 0
            previewHost.layoutParams = params
        } else {
            tabs.visibility = View.VISIBLE
            controlsScroll.visibility = View.VISIBLE
            handle?.visibility = View.VISIBLE
            val params = previewHost.layoutParams as LinearLayout.LayoutParams
            params.weight = 0f
            params.height = savedPreviewHeight.coerceAtLeast(dp(180))
            previewHost.layoutParams = params
        }
    }

    override fun onDestroyView() {        previewShell?.view?.removeAllViews()
        (preview?.view?.parent as? ViewGroup)?.removeView(preview?.view)
        preview?.release()
        preview = null
        previewShell = null
        super.onDestroyView()
    }

    private fun renderTabs() {
        val accent = ThemeAccent.color(requireContext())
        for (i in 0 until tabs.childCount) {
            (tabs.getChildAt(i) as? TextView)?.setTextColor(
                if (i == activeTab) accent else requireContext().getColor(R.color.text_secondary)
            )
        }
    }

    private fun buildControls() {
        controls.removeAllViews()
        previewShell?.setMenuVisible(activeTab == 6)
        // 预设入口固定在顶部，任何分类下都能直接看到。
        addPresetSection()
        if (activeTab == 6) {
            buildMenuControls()
            return
        }
        val rows = when (activeTab) {
            0 -> logoRows()
            1 -> searchRows()
            2 -> addressRows()
            3 -> backgroundRows()
            4 -> favoriteRows()
            5 -> toolbarRows()
            7 -> tabsRows()
            8 -> themeRows()
            else -> advancedRows()
        }
        val card = CardList.styledCard(requireContext(), page = true)
        CardList.addRows(card, rows)
        controls.addView(card)
    }

    /** 顶部固定的界面风格预设入口。 */
    private fun addPresetSection() {
        val context = requireContext()
        controls.addView(CardList.sectionTitle(context, "界面风格"))
        val card = CardList.styledCard(context, page = true)
        CardList.addRows(
            card,
            listOf(
                navRow("界面风格预设", "经典 / 玻璃胶囊 / 极简白 / 专注") { showPresetDialog() }
            )
        )
        controls.addView(card)
    }

    private fun buildMenuControls() {
        val context = requireContext()
        val fixedIds = settings.fixedItems()?.split(",")?.filter { it.isNotEmpty() }
            ?: MenuCatalog.DEFAULT_FIXED
        val hidden = settings.hiddenMenus().orEmpty()
            .split(",").filter { it.isNotEmpty() }.toMutableSet()
        val displayed = settings.displayedMenus().orEmpty()
            .split(",").filter { it.isNotEmpty() }
        val ordered = if (displayed.isEmpty()) MenuCatalog.ITEMS
        else displayed.mapNotNull { id -> MenuCatalog.ITEMS.firstOrNull { it.id == id } } +
            MenuCatalog.ITEMS.filter { item -> item.id !in displayed }
        val upper = ordered.filter { it.id !in fixedIds }

        val rows = mutableListOf<View>().apply {
            add(
                choiceRow(
                    "菜单页样式",
                    arrayOf("上下滑动", "左右滑动"),
                    settings.menuScrollStyle().coerceIn(0, 1)
                ) { index ->
                    settings.setMenuScrollStyle(index)
                    refreshPreview()
                    view?.post { buildControls() }
                }
            )
            add(
                choiceRow(
                    "移动菜单列数",
                    arrayOf("4 列", "5 列"),
                    settings.menuColumns().coerceIn(4, 5) - 4
                ) { index ->
                    settings.setMenuColumns(index + 4)
                    refreshPreview()
                    view?.post { buildControls() }
                }
            )
            add(sliderRow("菜单图标大小", settings.menuIconSize(), 16, 36) {
                settings.setMenuIconSize(it)
                refreshPreview()
            })
            add(sliderRow("菜单文字大小", settings.menuTextSize(), 8, 18) {
                settings.setMenuTextSize(it)
                refreshPreview()
            })
            add(switchRow("上方菜单显示文字", settings.menuShowLabels()) {
                settings.setMenuShowLabels(it)
                refreshPreview()
            })
            add(sliderRow("固定菜单图标大小", settings.fixedMenuIconSize(), 16, 36) {
                settings.setFixedMenuIconSize(it)
                refreshPreview()
            })
            add(sliderRow("固定菜单文字大小", settings.fixedMenuTextSize(), 8, 18) {
                settings.setFixedMenuTextSize(it)
                refreshPreview()
            })
            add(switchRow("固定菜单显示文字", settings.fixedMenuShowLabels()) {
                settings.setFixedMenuShowLabels(it)
                refreshPreview()
            })
            add(choiceRow(
                "固定菜单位置",
                arrayOf("移动菜单项上方", "移动菜单项下方"),
                settings.fixedMenuPosition().coerceIn(0, 1)
            ) { index ->
                settings.setFixedMenuPosition(index)
                refreshPreview()
                view?.post { buildControls() }
            })
            add(sliderRow("固定菜单栏高度", settings.fixedMenuHeight(), 44, 80) {
                settings.setFixedMenuHeight(it)
                refreshPreview()
            })
        }
        val optionsCard = CardList.styledCard(context, page = true)
        CardList.addRows(optionsCard, rows)
        controls.addView(optionsCard)

        controls.addView(
            switchRow("展开全部菜单项", menuExpanded) {
                menuExpanded = it
                view?.post { buildControls() }
                refreshPreview()
            }
        )
        controls.addView(CardList.sectionTitle(context, if (menuExpanded) "菜单项（拖动 ☰ 排序）" else "菜单项（左右拖动 ☰ 排序）"))
        controls.addView(
            if (menuExpanded) {
                expandedMenuEditor(
                    upper,
                    checkedOf = { id -> id !in hidden && id !in fixedIds },
                    onToggle = { id, visible ->
                        if (visible) hidden.remove(id) else hidden.add(id)
                        settings.setHiddenMenus(hidden.joinToString(","))
                        view?.post { buildControls() }
                        refreshPreview()
                    },
                    onOrderChanged = { ids ->
                        settings.setDisplayedMenus(ids.joinToString(","))
                        refreshPreview()
                    }
                )
            } else {
                horizontalMenuEditor(upper, twoRows = true) { ids ->
                    settings.setDisplayedMenus(ids.joinToString(","))
                    refreshPreview()
                }
            }
        )
        controls.addView(CardList.sectionTitle(context, "隐藏菜单项"))
        controls.addView(
            CardList.styledCard(context, page = true).apply {
                addView(
                    multiSelectRow(
                        "隐藏菜单项",
                        MenuCatalog.ITEMS.map { it.id },
                        MenuCatalog.ITEMS.associate { it.id to context.getString(it.labelRes) },
                        hidden.joinToString(",")
                    ) { value ->
                        settings.setHiddenMenus(value)
                        buildControls()
                        refreshPreview()
                    }
                )
            }
        )

        controls.addView(CardList.sectionTitle(context, "固定菜单栏（左右拖动 ☰ 排序）"))
        val fixed = MenuCatalog.ITEMS.filter { it.id in fixedIds }
            .sortedBy { fixedIds.indexOf(it.id) }
        controls.addView(
            horizontalMenuEditor(fixed, twoRows = false) { ids ->
                settings.setFixedItems(ids.joinToString(","))
                refreshPreview()
            }
        )
        controls.addView(CardList.sectionTitle(context, "固定菜单项"))
        controls.addView(
            CardList.styledCard(context, page = true).apply {
                addView(
                    multiSelectRow(
                        "固定菜单项",
                        MenuCatalog.ITEMS.map { it.id },
                        MenuCatalog.ITEMS.associate { it.id to context.getString(it.labelRes) },
                        fixedIds.joinToString(",")
                    ) { value ->
                        settings.setFixedItems(value)
                        buildControls()
                        refreshPreview()
                    }
                )
            }
        )
    }

    private fun logoRows(): List<View> {
        val mode = settings.homeLogoMode().coerceIn(0, 2)
        val modeRow = choiceRow(
            "显示内容",
            arrayOf("文字", "Logo 图标", "时间"),
            mode
        ) { index ->
            settings.setHomeLogoMode(index)
            view?.post { buildControls() }
            refreshPreview()
        }
        val textRows = listOf(
            textRow(getString(R.string.settings_home_logo), settings.homeLogoText()) {
                settings.setHomeLogoText(it)
                refreshPreview()
            },
            choiceRow(
                getString(R.string.settings_home_logo_size),
                arrayOf("小 24", "中 34", "大 44"),
                ((settings.homeLogoSize().takeIf { it > 0 } ?: 34) - 24) / 10
            ) { index ->
                settings.setHomeLogoSize(intArrayOf(24, 34, 44)[index.coerceIn(0, 2)])
                refreshPreview()
            },
            choiceRow(
                getString(R.string.settings_home_logo_weight),
                arrayOf("常规", "加粗"),
                settings.homeLogoWeight().coerceIn(0, 1)
            ) { index ->
                settings.setHomeLogoWeight(index)
                refreshPreview()
            }
        ).map { if (mode == 0) it else disabledRow(it) }
        val colorRow = colorChoiceRow(
            getString(R.string.settings_home_logo_color),
            listOf(
                "跟随主题" to null,
                "主题色" to "accent",
                "蓝" to "#2F7CF6",
                "绿" to "#34C28F",
                "橙" to "#F2984A",
                "紫" to "#8A6FF0"
            ),
            settings.homeLogoColor()
        ) { value ->
            settings.setHomeLogoColor(value)
            refreshPreview()
        }.let { if (mode == 1) disabledRow(it) else it }
        val iconRow = sliderRow("Logo 图标大小", settings.homeLogoIconSize(), 24, 120) {
            settings.setHomeLogoIconSize(it)
            refreshPreview()
        }
        val iconSize = if (mode == 1) iconRow else disabledRow(iconRow)
        return listOf(modeRow) + textRows + listOf(colorRow, iconSize)
    }

    private fun disabledRow(view: View): View {
        view.alpha = 0.4f
        view.setOnClickListener {}
        // 行内的 Switch / SeekBar 也要一并禁用，否则点开关仍能改值。
        (view as? ViewGroup)?.let { row ->
            for (i in 0 until row.childCount) row.getChildAt(i).isEnabled = false
        }
        return view
    }

    private fun searchRows(): List<View> {
        val show = settings.homeSearchShow()
        val rows = mutableListOf<View>(
            switchRow("显示主页搜索框", show) { on ->
                settings.setHomeSearchShow(on)
                refreshPreview()
                view?.post { buildControls() }
            }
        )
        val details = listOf(
            switchRow(
                "主页顶部显示搜索框",
                settings.homeTopAddress() && settings.homeLayoutPos() != 0,
                enabled = settings.homeLayoutPos() != 0
            ) { on ->
                settings.setHomeTopAddress(on)
                refreshPreview()
                view?.post { buildControls() }
            },
            choiceRow(
                getString(R.string.settings_home_layout),
                if (settings.uiStyle() == 1) arrayOf("顶部", "居中")
                else arrayOf("顶部", "居中", "底部"),
                settings.homeLayoutPos().coerceIn(0, if (settings.uiStyle() == 1) 1 else 2)
            ) { index ->
                settings.setHomeLayoutPos(index)
                refreshPreview()
                view?.post { buildControls() }
            },
            choiceRow(
                getString(R.string.settings_home_search_radius),
                arrayOf("小", "中", "大", "自定义"),
                settings.homeSearchRadius().coerceIn(0, 3)
            ) { index ->
                settings.setHomeSearchRadius(index)
                if (index == 3) {
                    numberDialog(
                        getString(R.string.settings_home_search_radius),
                        settings.homeSearchRadiusCustom(), 0, 48
                    ) { value ->
                        settings.setHomeSearchRadiusCustom(value)
                        refreshPreview()
                    }
                } else refreshPreview()
            },
            choiceRow(
                getString(R.string.settings_home_search_height),
                arrayOf("小", "中", "大"),
                settings.homeSearchHeight().coerceIn(0, 2)
            ) { index ->
                settings.setHomeSearchHeight(index)
                refreshPreview()
            },
            sliderRow(
                getString(R.string.settings_home_search_alpha),
                settings.homeSearchAlpha().coerceIn(0, 100), 0, 100
            ) { value ->
                settings.setHomeSearchAlpha(value)
                refreshPreview()
            }
        )
        rows.addAll(if (show) details else details.map { disabledRow(it) })
        return rows
    }

    private fun backgroundRows(): List<View> {
        val rows = mutableListOf<View>()
        rows.add(
            navRow(
                getString(R.string.settings_home_background),
                if (settings.homeBackground() != null) "已设置" else "默认"
            ) { bgLauncher.launch(arrayOf("image/*")) }
        )
        if (settings.homeBackground() != null) {
            rows.add(
                navRow("清除背景图片", null) {
                    settings.setHomeBackground(null)
                    refreshPreview()
                    buildControls()
                }
            )
        }
        rows.add(
            sliderRow(
                getString(R.string.settings_home_bg_blur),
                settings.homeBgBlurPercent().takeIf { it >= 0 }
                    ?: when (settings.homeBgBlur()) {
                        1 -> 33; 2 -> 66; 3 -> 100; else -> 0
                    },
                0, 100
            ) { value ->
                settings.setHomeBgBlurPercent(value)
                refreshPreview()
            }
        )
        rows.add(
            sliderRow(
                getString(R.string.settings_home_bg_overlay),
                settings.homeBgOverlay(), 0, 100
            ) { value ->
                settings.setHomeBgOverlay(value)
                refreshPreview()
            }
        )
        return rows
    }

    private fun favoriteRows(): List<View> = listOf(
        choiceRow(
            getString(R.string.settings_home_grid_columns),
            arrayOf("4 列", "5 列", "6 列"),
            settings.homeGridColumns().coerceIn(4, 6) - 4
        ) { index ->
            settings.setHomeGridColumns(index + 4)
            refreshPreview()
        },
        choiceRow(
            getString(R.string.settings_home_icon_size),
            arrayOf("小", "中", "大"),
            settings.homeIconSize().coerceIn(0, 2)
        ) { index ->
            settings.setHomeIconSize(index)
            refreshPreview()
        },
        choiceRow(
            getString(R.string.settings_home_icon_shape),
            arrayOf("圆形", "圆角方形"),
            settings.homeIconShape().coerceIn(0, 1)
        ) { index ->
            settings.setHomeIconShape(index)
            refreshPreview()
        },
        choiceRow(
            getString(R.string.settings_home_icon_radius),
            arrayOf("无", "小", "中", "大"),
            settings.homeIconRadius().coerceIn(0, 3)
        ) { index ->
            settings.setHomeIconRadius(index)
            refreshPreview()
        },
        switchRow(getString(R.string.settings_home_icon_accent), settings.homeIconAccent()) {
            settings.setHomeIconAccent(it)
            refreshPreview()
        },
        switchRow(getString(R.string.settings_home_show_labels), settings.homeShowLabels()) {
            settings.setHomeShowLabels(it)
            refreshPreview()
        },
        numberRow(getString(R.string.settings_home_label_size), settings.homeLabelSize(), 8, 24) {
            settings.setHomeLabelSize(it)
            refreshPreview()
        },
        numberRow(getString(R.string.settings_home_cell_gap), settings.homeCellGap(), 0, 32) {
            settings.setHomeCellGap(it)
            refreshPreview()
        },
        switchRow(getString(R.string.settings_home_sites_title), settings.homeShowSitesTitle()) {
            settings.setHomeShowSitesTitle(it)
            refreshPreview()
        },
        switchRow(getString(R.string.settings_show_sites), settings.showSites()) {
            settings.setShowSites(it)
            refreshPreview()
        },
        switchRow(
            getString(R.string.settings_home_sites_card_bg),
            settings.homeSitesCardBg()
        ) {
            settings.setHomeSitesCardBg(it)
            refreshPreview()
        }
    )

    private fun addressRows(): List<View> = listOf(
        choiceRow(
            getString(R.string.settings_url_display),
            arrayOf("地址", "标题", "域名"),
            settings.urlBoxBehavior().coerceIn(0, 2)
        ) { index ->
            settings.setUrlBoxBehavior(index)
            refreshPreview()
        },
        choiceRow(
            getString(R.string.settings_address_style),
            arrayOf("无外框", "下划线", "圆角外框"),
            settings.addressStyle().coerceIn(0, 2)
        ) { index ->
            settings.setAddressStyle(index)
            refreshPreview()
        }.also { if (settings.uiStyle() == 1) disableRow(it, true) }
    )

    private fun toolbarRows(): List<View> {
        val rows = mutableListOf<View>()
        val modern = settings.uiStyle() == 1
        rows.add(
            choiceRow(
                getString(R.string.settings_ui_style),
                arrayOf(
                    getString(R.string.settings_ui_style_classic),
                    getString(R.string.settings_ui_style_modern)
                ),
                settings.uiStyle().coerceIn(0, 1)
            ) { index ->
                settings.setUiStyle(index)
                (activity as? ShellActivity)?.refreshDialogTheme()
                refreshPreview()
                view?.post { buildControls() }
            }
        )
        val positionRow = choiceRow(
            "工具栏位置",
            arrayOf("顶部", "底部", "隐藏"),
            settings.toolbarPosition().coerceIn(0, 2)
        ) { index ->
            settings.setToolbarPosition(index)
            refreshPreview()
        }
        rows.add(positionRow)
        if (modern) disableRow(positionRow, true)
        val noLabelRow = switchRow("隐藏工具栏文字", settings.toolbarNoLabel()) {
            settings.setToolbarNoLabel(it)
            refreshPreview()
        }
        rows.add(noLabelRow)
        if (modern) disableRow(noLabelRow, true)
        rows.add(switchRow("滚动自动隐藏", settings.toolbarAutoHide()) {
            settings.setToolbarAutoHide(it)
        })
        val tabBarRow = choiceRow(
            "标签栏位置",
            arrayOf("地址栏上方", "工具栏上方", "隐藏"),
            settings.tabBarPosition().coerceIn(0, 2)
        ) { index ->
            settings.setTabBarPosition(index)
            refreshPreview()
        }
        rows.add(tabBarRow)
        if (modern) disableRow(tabBarRow, true)
        val orderRows = reorderRows(
            "工具栏按钮顺序",
            listOf("tabs", "back", "forward", "home", "menu"),
            mapOf(
                "tabs" to "标签", "back" to "后退", "forward" to "前进",
                "home" to "主页", "menu" to "菜单"
            ),
            settings.toolbarOrder().orEmpty(),
            settings::setToolbarOrder
        )
        rows.addAll(orderRows)
        if (modern) orderRows.forEach { disableRow(it, true) }
        rows.add(
            multiSelectRow(
                "隐藏工具栏按钮",
                listOf("tabs", "back", "forward", "home", "menu"),
                mapOf(
                    "tabs" to "标签", "back" to "后退", "forward" to "前进",
                    "home" to "主页", "menu" to "菜单"
                ),
                settings.toolbarHidden().orEmpty(),
                settings::setToolbarHidden
            )
        )
        return rows
    }


    /** 展开模式：完整纵向列表，按住 ☰ 上下拖动。 */
    private fun verticalMenuEditor(
        items: List<MenuCatalog.Item>,
        checkedOf: (String) -> Boolean,
        onToggle: (String, Boolean) -> Unit,
        onOrderChanged: (List<String>) -> Unit
    ): View {
        val context = requireContext()
        val card = CardList.styledCard(context, page = true)
        val rows = mutableListOf<View>()
        fun rebuild() {
            card.removeAllViews()
            rows.forEachIndexed { index, row ->
                if (index > 0) card.addView(CardList.styledDivider(context))
                card.addView(row)
            }
        }
        items.forEach { item ->
            val switch = Switch(context).apply {
                isChecked = checkedOf(item.id)
                setOnCheckedChangeListener { _, value -> onToggle(item.id, value) }
            }
            val handle = TextView(context).apply {
                text = "☰"
                setTextSize(16f)
                setTextColor(context.getColor(R.color.text_secondary))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(8), 0)
            }
            val row = CardList.row(context).apply {
                tag = item.id
                addView(handle)
                addView(ImageView(context).apply {
                    setImageResource(item.iconRes)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        context.getColor(R.color.text_primary)
                    )
                }, LinearLayout.LayoutParams(dp(22), dp(22)))
                addView(TextView(context).apply {
                    text = context.getString(item.labelRes)
                    setTextSize(13f)
                    setTextColor(context.getColor(R.color.text_primary))
                    setPadding(dp(8), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(switch)
            }
            var startY = 0f
            var fromIndex = -1
            var dragIndex = -1
            var rowHeight = dp(48)
            handle.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        fromIndex = rows.indexOf(row)
                        dragIndex = fromIndex
                        startY = event.rawY
                        rowHeight = row.height.takeIf { it > 0 } ?: dp(48)
                        row.elevation = 8f
                        (row.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY - startY
                        row.translationY = dy
                        dragIndex = (fromIndex + (dy / rowHeight).toInt())
                            .coerceIn(0, rows.lastIndex)
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        (row.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                        row.animate().translationY(0f).setDuration(120).start()
                        row.elevation = 0f
                        if (fromIndex >= 0 && dragIndex >= 0 && fromIndex != dragIndex) {
                            rows.add(dragIndex, rows.removeAt(fromIndex))
                            onOrderChanged(rows.map { it.tag as String })
                            view?.post { buildControls() }
                        }
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

    /** 展开模式：5/4 列网格，按住 ☰ 可上下左右拖动。 */
    private fun expandedMenuEditor(
        items: List<MenuCatalog.Item>,
        checkedOf: (String) -> Boolean,
        onToggle: (String, Boolean) -> Unit,
        onOrderChanged: (List<String>) -> Unit
    ): View {
        val context = requireContext()
        val card = CardList.styledCard(context, page = true)
        val columns = settings.menuColumns().coerceIn(4, 5)
        val order = items.toMutableList()
        val grid = GridLayout(context).apply {
            columnCount = columns
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        fun rebuild() {
            grid.removeAllViews()
            order.forEachIndexed { index, item ->
                val cell = menuGridCell(item, checkedOf, onToggle) { shiftColumns, shiftRows ->
                    val target = (index + shiftRows * columns + shiftColumns)
                        .coerceIn(0, order.lastIndex)
                    if (target != index) {
                        order.add(target, order.removeAt(index))
                        onOrderChanged(order.map { it.id })
                        view?.post { buildControls() }
                    }
                }
                grid.addView(cell, GridLayout.LayoutParams(
                    GridLayout.spec(index / columns),
                    GridLayout.spec(index % columns, 1f)
                ).apply {
                    width = 0
                    height = dp(112)
                })
            }
        }
        rebuild()
        card.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return card
    }

    private fun menuGridCell(
        item: MenuCatalog.Item,
        checkedOf: (String) -> Boolean,
        onToggle: (String, Boolean) -> Unit,
        onShift: (Int, Int) -> Unit
    ): View {
        val context = requireContext()
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        cell.addView(ImageView(context).apply {
            setImageResource(item.iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(
                context.getColor(R.color.text_primary)
            )
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        cell.addView(TextView(context).apply {
            text = context.getString(item.labelRes)
            setTextSize(9.5f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.text_primary))
        })
        val switch = Switch(context).apply {
            isChecked = checkedOf(item.id)
            setOnCheckedChangeListener { _, value -> onToggle(item.id, value) }
        }
        cell.addView(switch, LinearLayout.LayoutParams(dp(34), dp(28)))
        val handle = TextView(context).apply {
            text = "☰"
            setTextSize(12f)
            setTextColor(context.getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
        }
        cell.addView(handle, LinearLayout.LayoutParams(dp(28), dp(22)))
        var startX = 0f
        var startY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    cell.elevation = 8f
                    (cell.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    cell.translationX = event.rawX - startX
                    cell.translationY = event.rawY - startY
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val width = cell.width.coerceAtLeast(1)
                    val height = cell.height.coerceAtLeast(1)
                    val shiftColumns = ((event.rawX - startX) / width).toInt()
                    val shiftRows = ((event.rawY - startY) / height).toInt()
                    cell.animate().translationX(0f).translationY(0f).setDuration(120).start()
                    cell.elevation = 0f
                    if (shiftColumns != 0 || shiftRows != 0) {
                        onShift(shiftColumns, shiftRows)
                    }
                    true
                }
                else -> false
            }
        }
        return cell
    }

    /** 横向滚动菜单编辑器：上方两行或固定栏一行，按住 ☰ 左右拖动。 */
    private fun horizontalMenuEditor(
        items: List<MenuCatalog.Item>,
        twoRows: Boolean,
        onOrderChanged: (List<String>) -> Unit
    ): View {
        val context = requireContext()
        val card = CardList.styledCard(context, page = true)
        val order = items.toMutableList()
        val rows = if (twoRows) 2 else 1

        fun rowItems(rowIndex: Int): List<MenuCatalog.Item> =
            order.filterIndexed { index, _ -> !twoRows || index % 2 == rowIndex }

        fun rebuild() {
            card.removeAllViews()
            for (rowIndex in 0 until rows) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                }
                rowItems(rowIndex).forEach { item ->
                    val fromGlobal = order.indexOf(item)
                    val fromRow = if (twoRows) fromGlobal / 2 else fromGlobal
                    row.addView(
                        menuEditorItem(item) { shift ->
                            val targetRow = (fromRow + shift)
                                .coerceIn(0, rowItems(rowIndex).lastIndex)
                            val targetGlobal = if (twoRows) targetRow * 2 + rowIndex else targetRow
                            if (targetGlobal != fromGlobal && fromGlobal >= 0) {
                                order.add(
                                    targetGlobal.coerceIn(0, order.size),
                                    order.removeAt(fromGlobal)
                                )
                                onOrderChanged(order.map { it.id })
                                view?.post { buildControls() }
                            }
                        }
                    )
                }
                card.addView(
                    HorizontalScrollView(context).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(row)
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64))
                )
            }
        }
        rebuild()
        return card
    }

    private fun menuEditorItem(item: MenuCatalog.Item, onShift: (Int) -> Unit): View {
        val context = requireContext()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        content.addView(ImageView(context).apply {
            setImageResource(item.iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(
                context.getColor(R.color.text_primary)
            )
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        content.addView(TextView(context).apply {
            text = context.getString(item.labelRes)
            setTextSize(9.5f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(context.getColor(R.color.text_primary))
            gravity = Gravity.CENTER
        })
        val handle = TextView(context).apply {
            text = "☰"
            setTextSize(13f)
            setTextColor(context.getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
        }
        content.addView(handle)
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(content, LinearLayout.LayoutParams(dp(58), dp(58)))
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(62))
            tag = item.id
        }
        var startX = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    (view.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    view.elevation = 8f
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    view.translationX = event.rawX - startX
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    (view.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                    val shift = ((event.rawX - startX) / view.width.coerceAtLeast(1)).toInt()
                    view.animate().translationX(0f).setDuration(120).start()
                    view.elevation = 0f
                    if (shift != 0) onShift(shift)
                    true
                }
                else -> false
            }
        }
        return view
    }

    private fun reorderRows(
        title: String,
        ids: List<String>,
        labels: Map<String, String>,
        current: String,
        save: (String?) -> Unit
    ): List<View> {
        val order = (current.split(',').filter { it.isNotEmpty() } + ids)
            .distinct()
            .filter { it in ids }
            .toMutableList()
        val rows = mutableListOf<View>()
        rows.add(CardList.row(requireContext()).apply {
            addView(titleView(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        order.forEachIndexed { index, id ->
            rows.add(CardList.row(requireContext()).apply {
                addView(
                    TextView(requireContext()).apply {
                        text = labels[id] ?: id
                        setTextSize(13f)
                        setTextColor(requireContext().getColor(R.color.text_primary))
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(arrow(index > 0, "↑") {
                    order.add(index - 1, order.removeAt(index))
                    save(order.joinToString(","))
                    buildControls()
                    refreshPreview()
                })
                addView(arrow(index < order.lastIndex, "↓") {
                    order.add(index + 1, order.removeAt(index))
                    save(order.joinToString(","))
                    buildControls()
                    refreshPreview()
                })
            })
        }
        return rows
    }

    private fun multiSelectRow(
        title: String,
        ids: List<String>,
        labels: Map<String, String>,
        current: String,
        save: (String?) -> Unit
    ): View {
        val selected = current.split(',').filter { it.isNotEmpty() }.toSet()
        return CardList.row(requireContext()).apply {
            addView(titleView(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueView("${selected.size} 项"))
            setOnClickListener {
                val checked = ids.map { it in selected }.toBooleanArray()
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setMultiChoiceItems(ids.map { labels[it] ?: it }.toTypedArray(), checked) { _, which, value ->
                        checked[which] = value
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val result = ids.filterIndexed { index, _ -> checked[index] }
                        save(result.joinToString(",").ifEmpty { null })
                        view?.post { buildControls() }
                        refreshPreview()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun arrow(enabled: Boolean, textValue: String, onClick: () -> Unit): TextView = TextView(requireContext()).apply {
        text = textValue
        setTextSize(17f)
        setTextColor(requireContext().getColor(R.color.text_secondary))
        alpha = if (enabled) 1f else 0.3f
        gravity = Gravity.CENTER
        setPadding(dp(8), 0, dp(8), 0)
        if (enabled) setOnClickListener { onClick() }
    }

    /** 标签页：样式、网格、透明度与操作按钮。 */
    private fun tabsRows(): List<View> {
        val grid = settings.tabCardGrid()
        val styleRow = choiceRow(
            "标签卡样式",
            arrayOf("列表", "网格"),
            if (grid) 1 else 0
        ) { index ->
            settings.setTabCardGrid(index == 1)
            refreshPreview()
            view?.post { buildControls() }
        }
        val columnsRow = choiceRow(
            "网格列数",
            arrayOf("2 列", "3 列"),
            settings.tabGridColumns().coerceIn(2, 3) - 2
        ) { index ->
            settings.setTabGridColumns(index + 2)
            refreshPreview()
        }
        val thumbRow = switchRow("显示网页缩略图", settings.tabShowThumb()) {
            settings.setTabShowThumb(it)
            refreshPreview()
        }
        if (!grid) {
            disableRow(columnsRow, true)
            disableRow(thumbRow, true)
        }
        return listOf(
            styleRow,
            columnsRow,
            thumbRow,
            sliderRow("面板背景透明度", settings.tabsPanelTransparency(), 0, 100) {
                settings.setTabsPanelTransparency(it)
                refreshPreview()
            },
            choiceRow(
                "新建/撤销顺序",
                arrayOf("新建在左", "撤销在左"),
                if (settings.tabActionSwap()) 1 else 0
            ) { index ->
                settings.setTabActionSwap(index == 1)
                refreshPreview()
            },
            switchRow("显示撤销按钮", settings.tabUndoVisible()) {
                settings.setTabUndoVisible(it)
                refreshPreview()
            },
            switchRow("关闭标签后提示", settings.closeTabToast()) {
                settings.setCloseTabToast(it)
            },
            switchRow("异常退出提醒", settings.exitRestorePrompt()) {
                settings.setExitRestorePrompt(it)
            }
        )
    }

    /** 主题：夜间、网页暗色、滤镜、主题色与网页文字颜色。 */
    private fun themeRows(): List<View> = listOf(
        switchRow("跟随系统夜间", settings.nightFollowSystem()) {
            settings.setNightFollowSystem(it)
            (activity as? ShellActivity)?.let { act ->
                ShellActivity.smoothRecreateForNightSwitch(act)
            }
        },
        switchRow("夜间 CSS", settings.nightCssEnabled()) {
            settings.setNightCssEnabled(it)
            if (it) settings.setWebViewDark(false)
            AppServices.applyWebViewDark?.invoke()
            view?.post { buildControls() }
            refreshPreview()
        },
        switchRow("网页暗色", settings.webViewDark()) {
            settings.setWebViewDark(it)
            if (it) settings.setNightCssEnabled(false)
            AppServices.applyWebViewDark?.invoke()
            view?.post { buildControls() }
            refreshPreview()
        },
        sliderRow("夜间滤镜", settings.nightFilter(), 0, 255) {
            settings.setNightFilter(it)
            refreshPreview()
        },
        sliderRow("玻璃强度", settings.glassStrength(), 0, 100) {
            settings.setGlassStrength(it)
            view?.post { refreshPreview() }
        },
        switchRow("实时模糊背景", settings.glassBlur()) {
            settings.setGlassBlur(it)
            refreshPreview()
        },
        choiceRow(
            "主题色",
            arrayOf("默认", "蓝", "绿", "橙", "紫"),
            themeColorIndex()
        ) { index ->
            settings.setSkin(themeSkins[index])
            activity?.recreate()
        },
        choiceRow(
            "网页文字颜色",
            arrayOf("原色", "跟随主色", "仅白天", "仅夜晚"),
            settings.webTextColor().coerceIn(0, 3)
        ) {
            settings.setWebTextColor(it)
            AppServices.applyWebTextAccent?.invoke()
            refreshPreview()
        },
        navRow("主题色识别", null) { openThemeColorPage() }
    )

    /** 界面风格预设：只套用界面相关项。 */
    private fun showPresetDialog() {
        val presets = arrayOf("经典", "玻璃胶囊", "极简白", "专注")
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("界面风格预设")
            .setItems(presets) { _, which -> applyPreset(which) }
            .show()
    }

    private fun applyPreset(index: Int) {
        when (index) {
            0 -> {
                settings.setUiStyle(0)
                settings.setHomeStyle(0)
                settings.setGlassStrength(30)
                settings.setGlassBlur(false)
                settings.setTabCardGrid(true)
                settings.setTabGridColumns(2)
                settings.setMenuScrollStyle(0)
                settings.setSettingsCardAlpha(100)
                settings.setCardBorder(true)
                settings.setAddressStyle(2)
                settings.setTabsPanelTransparency(0)
                settings.setThemeColorEnabled(true)
                settings.setHomeShowLabels(true)
                settings.setShowSites(true)
                settings.setHomeLogoMode(1)
                settings.setHomeSearchRadius(3)
                settings.setHomeSearchRadiusCustom(28)
                settings.setHomeTopAddress(false)
                settings.setNightMode(2)
            }
            1 -> {
                settings.setUiStyle(1)
                settings.setHomeStyle(0)
                settings.setGlassStrength(40)
                settings.setGlassBlur(true)
                settings.setTabCardGrid(true)
                settings.setTabGridColumns(3)
                settings.setMenuScrollStyle(0)
                settings.setSettingsCardAlpha(0)
                settings.setCardBorder(true)
                settings.setTabsPanelTransparency(0)
                settings.setThemeColorEnabled(false)
                settings.setHomeShowLabels(true)
                settings.setShowSites(true)
                settings.setHomeLogoMode(1)
                settings.setHomeSearchRadius(3)
                settings.setHomeSearchRadiusCustom(28)
                settings.setHomeTopAddress(false)
                settings.setNightMode(2)
            }
            2 -> {
                settings.setUiStyle(0)
                settings.setHomeStyle(1)
                settings.setGlassStrength(0)
                settings.setGlassBlur(false)
                settings.setTabCardGrid(true)
                settings.setTabGridColumns(3)
                settings.setMenuScrollStyle(0)
                settings.setSettingsCardAlpha(0)
                settings.setCardBorder(false)
                settings.setAddressStyle(0)
                settings.setTabsPanelTransparency(0)
                settings.setThemeColorEnabled(false)
                settings.setHomeShowLabels(true)
                settings.setShowSites(true)
                settings.setHomeLogoMode(1)
                settings.setHomeSearchRadius(0)
                settings.setHomeSearchRadiusCustom(28)
                settings.setHomeTopAddress(false)
                settings.setNightMode(2)
            }
            else -> {
                settings.setUiStyle(1)
                settings.setHomeStyle(0)
                settings.setGlassStrength(0)
                settings.setGlassBlur(true)
                settings.setTabCardGrid(false)
                settings.setMenuScrollStyle(1)
                settings.setSettingsCardAlpha(0)
                settings.setCardBorder(false)
                settings.setAddressStyle(1)
                settings.setTabsPanelTransparency(0)
                settings.setThemeColorEnabled(false)
                settings.setHomeShowLabels(false)
                settings.setShowSites(false)
                settings.setHomeLogoMode(2)
                settings.setHomeSearchRadius(3)
                settings.setHomeSearchRadiusCustom(28)
                settings.setHomeTopAddress(false)
                settings.setNightMode(2)
            }
        }
        (activity as? ShellActivity)?.let {
            ShellActivity.smoothRecreateForNightSwitch(it)
        } ?: activity?.recreate()
    }

    private val themeSkins = arrayOf("default", "blue", "green", "orange", "purple")

    private fun themeColorIndex(): Int = when (settings.skin()) {
        "blue" -> 1
        "green" -> 2
        "orange" -> 3
        "purple" -> 4
        else -> 0
    }

    private fun openThemeColorPage() {        val fragment = SettingsDetailFragment().apply {
            arguments = android.os.Bundle().apply {
                putString(SettingsDetailFragment.ARG_VIEW, SettingsDetailFragment.VIEW_THEME_COLOR)
            }
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.shell_root, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun advancedRows(): List<View> = listOf(
        sliderRow(
            getString(R.string.settings_card_alpha),
            settings.settingsCardAlpha(),
            0, 100
        ) {
            settings.setSettingsCardAlpha(it)
            CardList.applyCardStyle(controls)
        },
        choiceRow(
            "卡片背景色",
            arrayOf("跟随主题色", "默认", "浅灰", "深灰"),
            settings.cardBg().coerceIn(0, 3)
        ) { index ->
            settings.setCardBg(index)
            CardList.applyCardStyle(controls)
        },
        switchRow(
            getString(R.string.settings_card_border),
            settings.cardBorder()
        ) {
            settings.setCardBorder(it)
            CardList.applyCardStyle(controls)
        },
        choiceRow(
            "预览界面",
            arrayOf("主页", "模拟网页"),
            if (previewWeb) 1 else 0
        ) { index ->
            previewWeb = index == 1
            previewShell?.setPreviewWeb(previewWeb)
            refreshPreview()
        },
        choiceRow(
            getString(R.string.settings_home),
            arrayOf(getString(R.string.settings_home_sii), getString(R.string.settings_home_custom)),
            if (settings.homeUrl() == "about:home") 0 else 1
        ) { index ->
            if (index == 0) {
                settings.setHomeUrl("about:home")
                buildControls()
            } else {
                urlDialog()
            }
        },
        navRow(
            getString(R.string.settings_custom_font),
            if (settings.customFont().isNullOrBlank()) getString(R.string.settings_custom_font_none)
            else java.io.File(settings.customFont().orEmpty()).name
        ) { fontLauncher.launch(arrayOf("font/*", "application/octet-stream")) },
        navRow(
            getString(R.string.settings_custom_font_clear),
            null,
            enabled = !settings.customFont().isNullOrBlank()
        ) {
            settings.setCustomFont(null)
            java.io.File(requireContext().filesDir, "fonts/custom_font").delete()
            buildControls()
        }
    )

    private fun urlDialog() {
        val input = EditText(requireContext()).apply {
            setText(settings.homeUrl().takeIf { it != "about:home" }.orEmpty())
            hint = "https://example.com"
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_home_url)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings.setHomeUrl(input.text.toString().trim().ifBlank { "about:home" })
                buildControls()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshPreview() {
        if (previewRefreshPosted) return
        previewRefreshPosted = true
        // 拖动滑块时每帧重建预览外壳开销很大，合并到 ~80ms 一次。
        val wait = (PREVIEW_REFRESH_INTERVAL_MS -
            (android.os.SystemClock.uptimeMillis() - previewRefreshAt)).coerceAtLeast(0L)
        view?.postDelayed({
            previewRefreshPosted = false
            if (!isAdded) return@postDelayed
            previewRefreshAt = android.os.SystemClock.uptimeMillis()
            preview?.applyHomeStyle()
            previewShell?.render()
        }, wait)
    }

    // ---- 通用控制行 ----

    private fun titleView(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        setTextSize(14f)
        setTextColor(requireContext().getColor(R.color.text_primary))
    }

    private fun valueView(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        setTextSize(13f)
        setTextColor(requireContext().getColor(R.color.text_secondary))
        setPadding(dp(8), 0, 0, 0)
    }

    private fun row(title: String, value: String? = null): LinearLayout {
        return CardList.row(requireContext()).apply {
            addView(titleView(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (!value.isNullOrEmpty()) addView(valueView(value))
        }
    }

    private fun switchRow(
        title: String,
        checked: Boolean,
        enabled: Boolean = true,
        onChange: (Boolean) -> Unit
    ): View {
        val sw = Switch(requireContext()).apply {
            isChecked = checked
            isEnabled = enabled
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        return CardList.row(requireContext()).apply {
            alpha = if (enabled) 1f else 0.5f
            addView(titleView(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
        }
    }

    private fun choiceRow(
        title: String,
        labels: Array<String>,
        selected: Int,
        onChange: (Int) -> Unit
    ): View {
        val value = valueView(labels.getOrElse(selected) { "" })
        return CardList.row(requireContext()).apply {
            addView(titleView(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
            setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setItems(labels) { _, which ->
                        value.text = labels[which]
                        onChange(which)
                    }
                    .show()
            }
        }
    }

    private fun colorChoiceRow(
        title: String,
        colors: List<Pair<String, String?>>,
        selected: String?,
        onChange: (String?) -> Unit
    ): View {
        val value = valueView(colors.firstOrNull { it.second == selected }?.first ?: colors.first().first)
        return CardList.row(requireContext()).apply {
            addView(titleView(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
            setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setItems(colors.map { it.first }.toTypedArray()) { _, which ->
                        value.text = colors[which].first
                        onChange(colors[which].second)
                    }
                    .show()
            }
        }
    }

    private fun textRow(title: String, initial: String, onSave: (String) -> Unit): View {
        val value = valueView(initial)
        return CardList.row(requireContext()).apply {
            addView(titleView(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
            setOnClickListener {
                val input = EditText(requireContext()).apply { setText(initial) }
                AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val text = input.text.toString().trim()
                        value.text = text
                        onSave(text)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun numberRow(title: String, initial: Int, min: Int, max: Int, onSave: (Int) -> Unit): View {
        val value = valueView(initial.toString())
        return CardList.row(requireContext()).apply {
            addView(titleView(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
            setOnClickListener {
                numberDialog(title, initial, min, max) { saved ->
                    value.text = saved.toString()
                    onSave(saved)
                }
            }
        }
    }

    private fun numberDialog(title: String, initial: Int, min: Int, max: Int, onSave: (Int) -> Unit) {
        val input = EditText(requireContext()).apply {
            setText(initial.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(requireContext())
            .setTitle("$title ($min-$max)")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onSave((input.text.toString().toIntOrNull() ?: initial).coerceIn(min, max))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sliderRow(title: String, initial: Int, min: Int, max: Int, onSave: (Int) -> Unit): View {
        val value = valueView(initial.toString())
        val seek = SeekBar(requireContext()).apply {
            this.max = max - min
            progress = (initial - min).coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val current = progress + min
                    value.text = current.toString()
                    if (fromUser) onSave(current)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        return CardList.row(requireContext()).apply {
            addView(titleView(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                seek,
                LinearLayout.LayoutParams(dp(120), dp(32))
            )
            addView(value)
        }
    }

    private fun navRow(
        title: String,
        value: String?,
        enabled: Boolean = true,
        onClick: () -> Unit
    ): View {
        return CardList.row(requireContext()).apply {
            alpha = if (enabled) 1f else 0.4f
            addView(titleView(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (!value.isNullOrEmpty()) addView(valueView(value))
            setOnClickListener { if (enabled) onClick() }
        }
    }

    /** 新版下不生效的经典设置：整行变灰并禁止操作。 */
    private fun disableRow(row: View, disabled: Boolean) {
        row.alpha = if (disabled) 0.4f else 1f
        fun walk(target: View) {
            target.isEnabled = !disabled
            target.isClickable = !disabled
            if (target is ViewGroup) {
                for (i in 0 until target.childCount) walk(target.getChildAt(i))
            }
        }
        walk(row)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
