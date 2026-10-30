// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.sii.browser.R
import com.sii.browser.di.AppServices

/** 定制页的只读浏览器外壳：地址栏、工具栏、标签栏、快捷引擎和菜单预览。 */
class HomePreviewShell(
    private val context: Context,
    private val panel: HomePanel,
    private val onMenuClick: () -> Unit,
    private val onAddressClick: () -> Unit,
    private val onToolbarClick: () -> Unit
) {
    val view: FrameLayout = FrameLayout(context)
    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val topChrome = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val bottomChrome = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val contentFrame = FrameLayout(context)
    private val webContent = ScrollView(context).apply {
        visibility = View.GONE
        setBackgroundColor(Color.WHITE)
        addView(TextView(context).apply {
            text = "示例网页\n\n这是用于预览地址栏、快捷引擎、标签栏和工具栏的模拟页面。"
            setTextSize(14f)
            setTextColor(Color.parseColor("#222222"))
            setPadding(dp(18), dp(24), dp(18), dp(24))
        })
    }
    private var showMenu = false
    private var previewWeb = false

    init {
        contentFrame.addView(
            panel.view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        contentFrame.addView(
            webContent,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(topChrome)
        root.addView(contentFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomChrome)
        view.addView(
            root,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        render()
    }

    fun setMenuVisible(visible: Boolean) {
        showMenu = visible
        render()
    }

    fun setPreviewWeb(web: Boolean) {
        previewWeb = web
        render()
    }

    fun render() {
        val settings = AppServices.settings
        root.setBackgroundColor(panel.backgroundColor())
        topChrome.removeAllViews()
        bottomChrome.removeAllViews()
        val modern = settings.uiStyle() == 1
        if (modern) {
            bottomChrome.addView(buildModernDock())
        } else {
            val position = settings.toolbarPosition()
            if (position == 1) topChrome.addView(buildToolbar(top = true))
            topChrome.addView(buildAddressBar())
            if (previewWeb && settings.searchToolbar()) topChrome.addView(buildQuickEngine())
            if (previewWeb && settings.tabBarPosition() != 2) topChrome.addView(buildTabStrip())
            if (position == 0) bottomChrome.addView(buildToolbar(top = false))
        }
        panel.view.visibility = if (previewWeb) View.GONE else View.VISIBLE
        webContent.visibility = if (previewWeb) View.VISIBLE else View.GONE
        while (view.childCount > 1) view.removeViewAt(1)
        if (showMenu) view.addView(buildMenuOverlay())
    }

    /** 新版预览：单行玻璃底栏，颜色跟随日夜。 */
    private fun buildModernDock(): View {
        val night = AppServices.settings.isNightMode()
        val dockTint = ThemePalette.fill(context, ThemePart.TOOLBAR, onWeb = previewWeb)
        val primary = if (night) Color.WHITE
        else ContextCompat.getColor(context, R.color.text_primary)
        val wrap = FrameLayout(context).apply {
            setPadding(dp(10), dp(6), dp(10), dp(10))
        }
        val dock = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(dockTint ?: if (night) 0xFA1B1E23.toInt() else 0xFAFFFFFF.toInt())
                setStroke(dp(1), if (night) 0x24FFFFFF else 0x1A000000)
            }
            setOnClickListener { onToolbarClick() }
        }
        val address = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { onAddressClick() }
            addView(TextView(context).apply {
                text = "S"
                gravity = Gravity.CENTER
                setTextSize(10f)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ThemeAccent.color(context))
                }
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(TextView(context).apply {
                text = "about:home"
                setTextSize(13f)
                setTextColor(primary)
                isSingleLine = true
                setPadding(dp(8), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = "↻"
                gravity = Gravity.CENTER
                setTextSize(18f)
                setTextColor(primary)
            }, LinearLayout.LayoutParams(dp(36), dp(40)))
        }
        dock.addView(address, LinearLayout.LayoutParams(0, dp(48), 1f))
        listOf(
            R.drawable.ic_tb_back,
            R.drawable.ic_tb_home,
            R.drawable.ic_tb_tabs,
            R.drawable.ic_tb_menu
        ).forEachIndexed { index, icon ->
            dock.addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(primary)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                if (index == 3) setOnClickListener { onMenuClick() }
            }, LinearLayout.LayoutParams(dp(40), dp(48)))
        }
        wrap.addView(
            dock,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56),
                Gravity.BOTTOM
            )
        )
        return wrap
    }

    private fun buildAddressBar(): View {
        val s = AppServices.settings
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(chromeColor())
            setOnClickListener { onAddressClick() }
        }
        val icon = TextView(context).apply {
            text = "S"
            gravity = Gravity.CENTER
            setTextSize(11f)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ThemeAccent.color(context))
            }
        }
        bar.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
        val addressText = TextView(context).apply {
            text = when (s.urlBoxBehavior()) {
                1 -> "Sii 主页"
                2 -> "sii.local"
                else -> "about:home"
            }
            setTextSize(13f)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            isSingleLine = true
        }
        val field = FrameLayout(context).apply {
            setPadding(dp(6), dp(3), dp(6), dp(3))
            when (s.addressStyle()) {
                1 -> {
                    val line = View(context).apply {
                        setBackgroundColor(ContextCompat.getColor(context, R.color.text_secondary))
                    }
                    addView(line, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1), Gravity.BOTTOM))
                }
                2 -> background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(ContextCompat.getColor(context, R.color.card))
                    setStroke(dp(1), ContextCompat.getColor(context, R.color.line))
                }
            }
            addView(addressText)
        }
        bar.addView(field, LinearLayout.LayoutParams(0, dp(40), 1f))
        bar.addView(TextView(context).apply {
            this.text = "↻"
            gravity = Gravity.CENTER
            setTextSize(22f)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        return bar
    }

    private fun buildQuickEngine(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(3), dp(8), dp(3))
            setBackgroundColor(chromeColor())
        }
        listOf("百度", "Google", "必应", "搜狗").forEachIndexed { index, name ->
            row.addView(TextView(context).apply {
                text = name
                gravity = Gravity.CENTER
                setTextSize(11f)
                setTextColor(
                    if (index == 0) ThemeAccent.color(context)
                    else ContextCompat.getColor(context, R.color.text_secondary)
                )
                setPadding(dp(8), dp(5), dp(8), dp(5))
            })
        }
        return row
    }

    private fun buildTabStrip(): View {
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
            setBackgroundColor(tabsColor())
        }
        listOf("主页", "示例页面").forEachIndexed { index, title ->
            strip.addView(TextView(context).apply {
                text = title
                setTextSize(11f)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = GradientDrawable().apply {
                    cornerRadius = dp(7).toFloat()
                    setColor(
                        if (index == 0) ContextCompat.getColor(context, R.color.card)
                        else Color.TRANSPARENT
                    )
                }
            }, LinearLayout.LayoutParams(0, dp(32), 1f).apply { marginEnd = dp(4) })
        }
        return strip
    }

    private fun buildToolbar(top: Boolean): View {
        val s = AppServices.settings
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(chromeColor())
            setOnClickListener { onToolbarClick() }
        }
        if (s.toolbarPosition() == 2) return bar
        val hidden = s.toolbarHidden().orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
        val order = (s.toolbarOrder().orEmpty().split(',')
            .filter { it.isNotEmpty() } + listOf("tabs", "back", "forward", "home", "menu"))
            .distinct()
        val buttons = mapOf(
            "tabs" to Triple("标签", R.drawable.ic_tb_tabs, "1"),
            "back" to Triple("后退", R.drawable.ic_tb_back, null),
            "forward" to Triple("前进", R.drawable.ic_tb_forward, null),
            "home" to Triple("主页", R.drawable.ic_tb_home, null),
            "menu" to Triple("菜单", R.drawable.ic_tb_menu, null)
        )
        order.filter { it !in hidden && buttons.containsKey(it) }.forEach { key ->
            val data = buttons.getValue(key)
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                if (key == "menu") setOnClickListener { onMenuClick() }
            }
            item.addView(ImageView(context).apply {
                setImageResource(data.second)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.text_primary)
                )
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            if (!s.toolbarNoLabel()) {
                item.addView(TextView(context).apply {
                    text = data.first
                    setTextSize(8.5f)
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    gravity = Gravity.CENTER
                })
            }
            bar.addView(item, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        return bar
    }

    private fun buildContent(): View {
        val frame = FrameLayout(context)
        if (previewWeb) {
            frame.addView(ScrollView(context).apply {
                setBackgroundColor(Color.WHITE)
                addView(TextView(context).apply {
                    text = "示例网页\n\n这是用于预览地址栏、快捷引擎、标签栏和工具栏的模拟页面。"
                    setTextSize(14f)
                    setTextColor(Color.parseColor("#222222"))
                    setPadding(dp(18), dp(24), dp(18), dp(24))
                })
            })
        } else {
            (panel.view.parent as? ViewGroup)?.removeView(panel.view)
            frame.addView(
                panel.view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        return frame
    }

    private fun buildMenuOverlay(): View {
        val overlay = FrameLayout(context)
        overlay.addView(View(context).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
            setOnClickListener { onMenuClick() }
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val top = AppServices.settings.toolbarPosition() == 1
        val menuPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(
                    ThemePalette.fill(context, ThemePart.MENU, onWeb = previewWeb)
                        ?: ContextCompat.getColor(context, R.color.card)
                )
            }
        }
        val upper = menuUpperItems()
        val fixed = menuFixedItems()
        val columns = AppServices.settings.menuColumns().coerceIn(4, 5)
        val dark = AppServices.settings.isNightMode()
        menuPanel.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(4), dp(14), dp(6))
                addView(TextView(context).apply {
                    text = context.getString(R.string.menu)
                    setTextSize(15f)
                    setTextColor(
                        if (dark) android.graphics.Color.WHITE
                        else ContextCompat.getColor(context, R.color.text_primary)
                    )
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                addView(TextView(context).apply {
                    text = context.getString(R.string.settings)
                    setTextSize(13f)
                    setTextColor(ThemeAccent.color(context))
                })
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val addUpper = {
            if (AppServices.settings.menuScrollStyle() == 1) {
                for (rowIndex in 0..1) {
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    upper.forEachIndexed { index, item ->
                        if (index % 2 == rowIndex) row.addView(menuPreviewItem(item, columns, fixed = false))
                    }
                    menuPanel.addView(
                        HorizontalScrollView(context).apply {
                            isHorizontalScrollBarEnabled = false
                            addView(row)
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58))
                    )
                }
            } else {
                val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                upper.chunked(columns).forEach { chunk ->
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    chunk.forEach { item ->
                        row.addView(menuPreviewItem(item, columns, fixed = false))
                    }
                    grid.addView(row)
                }
                menuPanel.addView(
                    grid,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }
        val addFixed = {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            fixed.forEach { row.addView(menuPreviewItem(it, fixed.size, fixed = true)) }
            menuPanel.addView(
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(row)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(AppServices.settings.fixedMenuHeight())
                )
            )
        }
        val addDivider = {
            menuPanel.addView(View(context).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.line))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }
        if (fixed.isNotEmpty() && AppServices.settings.fixedMenuPosition() == 0) {
            addFixed()
            if (upper.isNotEmpty()) addDivider()
            addUpper()
        } else {
            addUpper()
            if (fixed.isNotEmpty()) addDivider()
            if (fixed.isNotEmpty()) addFixed()
        }
        overlay.addView(menuPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (top) Gravity.TOP else Gravity.BOTTOM
        ).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
        return overlay
    }

    private fun menuPreviewItem(item: MenuCatalog.Item, columns: Int, fixed: Boolean): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(2), dp(3), dp(2))
            val iconSize = if (fixed) AppServices.settings.fixedMenuIconSize()
            else AppServices.settings.menuIconSize()
            addView(ImageView(context).apply {
                setImageResource(item.iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.text_primary)
                )
            }, LinearLayout.LayoutParams(dp(iconSize.coerceIn(16, 36)), dp(iconSize.coerceIn(16, 36))))
            addView(TextView(context).apply {
                text = context.getString(item.labelRes)
                setTextSize(
                    (if (fixed) AppServices.settings.fixedMenuTextSize()
                    else AppServices.settings.menuTextSize()).coerceIn(8, 18).toFloat()
                )
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                gravity = Gravity.CENTER
                visibility = if (
                    if (fixed) AppServices.settings.fixedMenuShowLabels()
                    else AppServices.settings.menuShowLabels()
                ) View.VISIBLE else View.GONE
            })
            layoutParams = LinearLayout.LayoutParams(
                ((360 - 16) / columns).coerceAtLeast(48).let { dp(it) },
                if (fixed) dp(AppServices.settings.fixedMenuHeight()) else dp(56)
            )
        }

    private fun menuUpperItems(): List<MenuCatalog.Item> {
        val hidden = AppServices.settings.hiddenMenus().orEmpty()
            .split(',').filter { it.isNotEmpty() }.toSet()
        val fixed = menuFixedIds()
        val displayed = AppServices.settings.displayedMenus().orEmpty()
            .split(',').filter { it.isNotEmpty() }
        val ordered = if (displayed.isEmpty()) MenuCatalog.ITEMS
        else displayed.mapNotNull { id -> MenuCatalog.ITEMS.firstOrNull { it.id == id } } +
            MenuCatalog.ITEMS.filter { item -> item.id !in displayed }
        return ordered.filter { it.id !in hidden && it.id !in fixed }
    }

    private fun menuFixedItems(): List<MenuCatalog.Item> =
        menuFixedIds().mapNotNull { id -> MenuCatalog.ITEMS.firstOrNull { it.id == id } }

    private fun menuFixedIds(): List<String> =
        AppServices.settings.fixedItems().orEmpty()
            .split(',').filter { it.isNotEmpty() }
            .ifEmpty { MenuCatalog.DEFAULT_FIXED }

    private fun chromeColor(): Int =
        ThemePalette.fill(context, ThemePart.TOOLBAR, onWeb = previewWeb)
            ?: ContextCompat.getColor(context, R.color.toolbar_bg)

    /** 标签栏/标签卡底色：开启「标签页」时用主色淡底。 */
    private fun tabsColor(): Int =
        ThemePalette.fill(context, ThemePart.TABS, onWeb = previewWeb)
            ?: ContextCompat.getColor(context, R.color.toolbar_bg)

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
