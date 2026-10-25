package com.sii.browser.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.sii.browser.R
import com.sii.browser.di.AppServices

/**
 * 统一的页头 ⋮ 弹出菜单（对齐 index.html 的圆角卡片浮层）。
 * 支持普通项、分组标题和可展开的二级选项；用 showAsDropDown 定位，
 * 避免 edge-to-edge 下屏幕坐标与窗口坐标不一致导致弹窗错位。
 */
object MenuPopup {

    private var activePopup: PopupWindow? = null

    fun dismiss() {
        activePopup?.dismiss()
        activePopup = null
    }

    /** 菜单项。 */
    class Item(val label: CharSequence, val checked: Boolean = false, val action: () -> Unit)

    /** 分组标题。 */
    class Header(val label: CharSequence)

    /** 可展开的二级选项组。 */
    class Group(val label: CharSequence, val options: List<Item>)

    fun show(anchor: View, entries: List<Any>, labelTransform: (String) -> String = { it }) {
        val context = anchor.context
        val modern = AppServices.settings.uiStyle() == 1
        val dark = modern && AppServices.settings.isNightMode()
        val primary = if (dark) Color.WHITE else context.getColor(R.color.text_primary)
        val secondary = if (dark) 0xFF9AA0A8.toInt()
        else context.getColor(R.color.text_secondary)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(if (dark) R.drawable.bg_dialog_dark else R.drawable.bg_card)
            clipToOutline = true
            setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
        }
        var popup: PopupWindow? = null
        val subContainers = mutableListOf<LinearLayout>()

        fun itemRow(item: Item, indented: Boolean): TextView = TextView(context).apply {
            text = if (item.checked && indented) "✓  ${labelTransform(item.label.toString())}" else labelTransform(item.label.toString())
            setTextSize(if (indented) 13f else 14f)
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, if (indented) 40 else 44)
            setTextColor(
                if (item.checked) ThemeAccent.color(context) else primary
            )
            setPadding(
                dp(context, if (indented) 28 else 12),
                dp(context, if (indented) 9 else 12),
                dp(context, 12),
                dp(context, if (indented) 9 else 12)
            )
            setBackgroundResource(R.drawable.bg_list_item)
            isClickable = true
            setOnClickListener {
                popup?.dismiss()
                item.action()
            }
        }

        entries.forEach { entry ->
            when (entry) {
                is Header -> container.addView(TextView(context).apply {
                    text = labelTransform(entry.label.toString())
                    setTextSize(12f)
                    setTextColor(secondary)
                    setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 4))
                })
                is Item -> container.addView(itemRow(entry, indented = false))
                is Group -> {
                    val sub = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        visibility = View.GONE
                    }
                    val row = TextView(context).apply {
                        text = "${labelTransform(entry.label.toString())}  ›"
                        setTextSize(14f)
                        setTextColor(primary)
                        setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
                        setBackgroundResource(R.drawable.bg_list_item)
                        isClickable = true
                        setOnClickListener {
                            val expand = sub.visibility != View.VISIBLE
                            subContainers.forEach { it.visibility = View.GONE }
                            sub.visibility = if (expand) View.VISIBLE else View.GONE
                            text = if (expand) "${labelTransform(entry.label.toString())}  ⌄" else "${labelTransform(entry.label.toString())}  ›"
                            sub.post { popup?.update() }
                        }
                    }
                    entry.options.forEach { sub.addView(itemRow(it, indented = true)) }
                    subContainers.add(sub)
                    container.addView(row)
                    container.addView(sub)
                }
            }
        }

        container.measure(
            View.MeasureSpec.UNSPECIFIED,
            View.MeasureSpec.UNSPECIFIED
        )
        val minWidth = dp(context, 176)
        val width = container.measuredWidth.coerceAtLeast(minWidth)
        container.layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        val window = PopupWindow(
            container,
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = dp(context, 8).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        activePopup?.dismiss()
        activePopup = window
        window.setOnDismissListener {
            if (activePopup === window) activePopup = null
        }
        popup = window
        window.showAsDropDown(anchor, anchor.width - width, dp(context, 4))
    }

    private fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
