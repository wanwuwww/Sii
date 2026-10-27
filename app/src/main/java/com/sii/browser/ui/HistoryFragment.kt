// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sii.browser.R
import com.sii.browser.data.db.HistoryItem
import com.sii.browser.di.AppServices
import kotlinx.coroutines.launch

/**
 * 历史列表：按日期分组，支持搜索、打开、长按删除与清空。
 * 视觉沿用设置/书签页的页头、卡片、分组标题与行高。
 */
class HistoryFragment : Fragment() {

    private lateinit var listContainer: LinearLayout
    private var items: List<HistoryItem> = emptyList()
    private var query: String = ""
    private var visibleCount = 100
    private val searchDebounce = Runnable {
        visibleCount = 100
        renderItems()
    }

    override fun onDestroyView() {
        if (::listContainer.isInitialized) listContainer.removeAllViews()
        super.onDestroyView()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(R.color.bg_page))
        }

        root.addView(
            PageHeader.build(
                context,
                getString(R.string.menu_history),
                onBack = { parentFragmentManager.popBackStack() },
                onMenu = { showClearDialog() }
            )
        )

        val searchCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply {
                marginStart = dp(14)
                marginEnd = dp(14)
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
            CardList.stylePageCard(this)
        }
        searchCard.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_m_find)
            imageTintList = android.content.res.ColorStateList.valueOf(
                context.getColor(R.color.text_secondary)
            )
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                marginEnd = dp(10)
            }
        })
        val input = EditText(context).apply {
            hint = getString(R.string.search_hint)
            background = null
            setSingleLine(true)
            setTextSize(14f)
            setTextColor(context.getColor(R.color.text_primary))
            setHintTextColor(context.getColor(R.color.text_secondary))
            setPadding(0, 0, 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    if (::listContainer.isInitialized) {
                        listContainer.removeCallbacks(searchDebounce)
                        listContainer.postDelayed(searchDebounce, 200)
                    }
                }
            })
        }
        searchCard.addView(
            input,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        root.addView(searchCard)

        listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(
            CardList.scroll(context, listContainer),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            AppServices.historyRepository.recent(500).collect { list ->
                items = list
                renderItems()
            }
        }
    }

    private fun renderItems() {
        listContainer.removeAllViews()
        val matched = if (query.isBlank()) {
            items
        } else {
            items.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.url.contains(query, ignoreCase = true)
            }
        }
        if (matched.isEmpty()) {
            listContainer.addView(TextView(requireContext()).apply {
                text = getString(
                    if (query.isBlank()) R.string.history_empty else R.string.search_no_match
                )
                setTextSize(14f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(48), dp(24), dp(24))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
            return
        }
        val filtered = matched.take(visibleCount)
        filtered.groupBy { dayLabel(it.updatedAt) }.forEach { (label, groupItems) ->
            listContainer.addView(CardList.sectionTitle(requireContext(), label))
            val card = CardList.pageCard(requireContext())
            CardList.addRows(card, groupItems.map { historyRow(it) })
            listContainer.addView(card)
        }
        if (matched.size > visibleCount) {
            listContainer.addView(TextView(requireContext()).apply {
                text = getString(R.string.history_load_more)
                setTextSize(13f)
                setTextColor(ThemeAccent.color(requireContext()))
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(14), dp(8), dp(14))
                setOnClickListener {
                    visibleCount += 100
                    renderItems()
                }
            })
        }
    }

    private fun historyRow(item: HistoryItem): View {
        val context = requireContext()
        val row = CardList.row(context)
        val iconSize = dp(36)
        row.addView(FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = dp(12)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(colorOf(item.url))
            }
            clipToOutline = true
            addView(TextView(context).apply {
                text = item.title.ifEmpty { item.url }.take(1).uppercase()
                setTextSize(16f)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            })
        })

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = item.title.ifEmpty { item.url }
                setTextSize(14f)
                setTextColor(context.getColor(R.color.text_primary))
                typeface = android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL
                )
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                text = item.url
                setTextSize(11.5f)
                setTextColor(context.getColor(R.color.text_secondary))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.setOnClickListener {
            AppServices.pendingOpenUrl = item.url
            parentFragmentManager.popBackStack()
        }
        row.setOnLongClickListener {
            AlertDialog.Builder(context)
                .setMessage(R.string.delete)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        AppServices.historyRepository.delete(item.id)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        return row
    }

    private fun showClearDialog() {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.clear_history)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    AppServices.historyRepository.clearAll()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dayLabel(timestamp: Long): String {
        val now = java.util.Calendar.getInstance()
        val that = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        val sameDay = now.get(java.util.Calendar.YEAR) == that.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == that.get(java.util.Calendar.DAY_OF_YEAR)
        if (sameDay) return getString(R.string.history_today)
        val yesterday = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        val isYesterday = yesterday.get(java.util.Calendar.YEAR) == that.get(java.util.Calendar.YEAR) &&
            yesterday.get(java.util.Calendar.DAY_OF_YEAR) == that.get(java.util.Calendar.DAY_OF_YEAR)
        if (isYesterday) return getString(R.string.history_yesterday)
        return getString(
            R.string.history_month_day,
            that.get(java.util.Calendar.MONTH) + 1,
            that.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun colorOf(url: String): Int {
        var h = 0
        url.forEach { h = h * 31 + it.code }
        return COLORS[Math.floorMod(h, COLORS.size)]
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        val COLORS = intArrayOf(
            0xFF2F7CF6.toInt(), 0xFF34C28F.toInt(), 0xFFF2984A.toInt(), 0xFF8A6FF0.toInt(),
            0xFFE5537A.toInt(), 0xFF3AB3C9.toInt(), 0xFFC9A23A.toInt(), 0xFF6D7F95.toInt()
        )
    }
}
