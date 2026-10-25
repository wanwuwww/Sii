package com.sii.browser.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.sii.browser.R

/**
 * 页面页头：左侧返回、标题，可选右侧菜单按钮。
 */
object PageHeader {

    fun build(
        context: Context,
        title: CharSequence,
        onBack: () -> Unit,
        onMenu: ((View) -> Unit)? = null,
        actionLabel: CharSequence? = null,
        onAction: ((View) -> Unit)? = null
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(context.getColor(R.color.toolbar_bg))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(context, 52)
        )

        addView(backView(context, onBack))
        addView(TextView(context).apply {
            text = title
            setTextSize(17f)
            setTextColor(context.getColor(R.color.text_primary))
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL
            )
            letterSpacing = 0.01f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setPadding(dp(context, 2), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            )
        })
        if (actionLabel != null) {
            val action = TextView(context).apply {
                text = actionLabel
                setTextSize(13.5f)
                setTextColor(ThemeAccent.color(context))
                gravity = Gravity.CENTER
                setPadding(dp(context, 8), 0, dp(context, 8), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { onAction?.invoke(this) }
            }
            addView(action)
        }
        if (onMenu != null) {
            val menuButton = actionView(context, "⋮", 20f) { }
            menuButton.setOnClickListener { onMenu(menuButton) }
            addView(menuButton)
        }
    }

    private fun backView(context: Context, onClick: () -> Unit): View =
        android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = android.content.res.ColorStateList.valueOf(
                context.getColor(R.color.text_primary)
            )
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
            layoutParams = LinearLayout.LayoutParams(dp(context, 48), LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
        }

    private fun actionView(context: Context, label: String, size: Float, onClick: () -> Unit): View =
        TextView(context).apply {
            text = label
            setTextSize(size)
            setTextColor(context.getColor(R.color.text_primary))
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL
            )
            layoutParams = LinearLayout.LayoutParams(dp(context, 48), LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
        }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
