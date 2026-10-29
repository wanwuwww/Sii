// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.sii.browser.R

/** 建议列表的行/列表构建：PopupWindow 与页面内覆盖层共用同一套视觉。 */
object SuggestionRows {

    fun column(
        context: Context,
        items: List<Suggestion>,
        onPick: (Suggestion) -> Unit
    ): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 4), 0, dp(context, 4))
        }
        items.forEachIndexed { index, item ->
            column.addView(row(context, item, onPick))
            if (index != items.lastIndex) {
                column.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)
                    )
                    setBackgroundColor(ContextCompat.getColor(context, R.color.line))
                })
            }
        }
        return column
    }

    private fun row(
        context: Context,
        item: Suggestion,
        onPick: (Suggestion) -> Unit
    ): View {
        val accent = ThemeAccent.color(context)
        val primary = ContextCompat.getColor(context, R.color.text_primary)
        val secondary = ContextCompat.getColor(context, R.color.text_secondary)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 9), dp(context, 10), dp(context, 9))
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onPick(item) }
        }
        val search = item.kind == SuggestionKind.SEARCH
        val iconColor = if (search) accent else secondary
        val iconBg = if (search) ((0x26 shl 24) or (accent and 0x00FFFFFF))
        else ((0x14 shl 24) or (primary and 0x00FFFFFF))
        val iconWrap = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(iconBg)
            }
        }
        iconWrap.addView(ImageView(context).apply {
            setImageResource(
                when (item.kind) {
                    SuggestionKind.SEARCH -> R.drawable.ic_m_find
                    SuggestionKind.HISTORY -> R.drawable.ic_m_history
                    SuggestionKind.BOOKMARK -> R.drawable.ic_m_bmlist
                    else -> R.drawable.ic_m_copy
                }
            )
            imageTintList = android.content.res.ColorStateList.valueOf(iconColor)
            setPadding(dp(context, 9), dp(context, 9), dp(context, 9), dp(context, 9))
        }, FrameLayout.LayoutParams(dp(context, 36), dp(context, 36), Gravity.CENTER))
        row.addView(iconWrap, LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)))

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 10), 0, dp(context, 6), 0)
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tagRes = when (item.kind) {
            SuggestionKind.HISTORY -> R.string.suggest_tag_history
            SuggestionKind.BOOKMARK -> R.string.suggest_tag_bookmark
            SuggestionKind.CLIPBOARD_TEXT,
            SuggestionKind.CLIPBOARD_LINK,
            SuggestionKind.CLIPBOARD_MIXED -> R.string.suggest_tag_clipboard
            else -> null
        }
        if (tagRes != null) {
            titleRow.addView(TextView(context).apply {
                text = context.getString(tagRes)
                setTextSize(9.5f)
                setTextColor(accent)
                setPadding(dp(context, 5), dp(context, 1), dp(context, 5), dp(context, 1))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 5).toFloat()
                    setColor((0x1F shl 24) or (accent and 0x00FFFFFF))
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(context, 6) })
        }
        titleRow.addView(TextView(context).apply {
            text = item.title
            setTextSize(if (search) 15f else 14f)
            setTextColor(primary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (search) {
                typeface = android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        textColumn.addView(titleRow)
        val subtitle = item.subtitle.orEmpty()
        if (subtitle.isNotBlank()) {
            textColumn.addView(TextView(context).apply {
                text = subtitle
                setTextSize(11.5f)
                setTextColor(secondary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(context, 2), 0, 0)
            })
        }
        row.addView(textColumn, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        row.addView(TextView(context).apply {
            text = "↵"
            setTextSize(15f)
            setTextColor(secondary)
            gravity = Gravity.CENTER
            setPadding(dp(context, 8), 0, dp(context, 4), 0)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return row
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
