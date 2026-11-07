// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.sii.browser.R
import com.sii.browser.core.webview.JsConsoleStore
import com.sii.browser.core.webview.PageRequests
import com.sii.browser.core.webview.SiiWebView
import com.sii.browser.di.AppServices
import org.json.JSONArray
import org.json.JSONObject

/**
 * 半屏开发者工具浮层：网页保持可见可操作，面板贴底、可拖动调高。
 * 数据来自 onConsoleMessage、[JsConsoleStore]、[PageRequests] 与页面拾取脚本。
 */
class DevToolsPanel(
    private val context: Context,
    private val host: FrameLayout,
    private val webViewProvider: () -> SiiWebView?,
    private val onHidden: () -> Unit
) {

    private val density = context.resources.displayMetrics.density
    private val tabs = arrayOf("控制台", "元素", "网络", "信息")
    private var currentTab = 0
    private var heightPercent = 55

    private lateinit var panel: LinearLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var content: LinearLayout
    private var consoleFilter = "ALL"
    private var networkFilter = "ALL"
    private var networkSelected = -1

    private data class Node(
        val depth: Int,
        val tag: String,
        val id: String,
        val cls: String,
        val text: String,
        val parent: Int,
        val hasChildren: Boolean,
        val selector: String
    )

    private var nodes: List<Node> = emptyList()
    private val expanded = mutableSetOf<Int>()
    private var treeList: LinearLayout? = null
    private var treeLoading = false
    private var picked: JSONObject? = null
    private var pickerOn = false
    private var elementsQuery = ""
    private var searchRunnable: Runnable? = null
    private data class OverrideRule(val selector: String, val props: List<Pair<String, String>>)
    private val overrideRules = mutableListOf<OverrideRule>()

    val isShowing: Boolean get() = host.visibility == View.VISIBLE

    fun show() {
        if (isShowing) return
        if (!built) build()
        currentTab = lastTab
        heightPercent = lastHeight
        host.visibility = View.VISIBLE
        applyHeight()
        if (!AppServices.settings.jsConsoleLog()) AppServices.settings.setJsConsoleLog(true)
        JsConsoleStore.onChange = { host.post { if (currentTab == 0) renderConsoleList() } }
        renderTabs()
        render()
    }

    fun hide() {
        if (!isShowing) return
        JsConsoleStore.onChange = null
        if (pickerOn) setPicker(false)
        host.removeCallbacks(pickPoller)
        host.removeAllViews()
        host.visibility = View.GONE
        built = false
        onHidden()
    }

    fun toggle() {
        if (isShowing) hide() else show()
    }

    /** 切标签后重新把拾取桥接到当前 WebView。 */
    fun onWebViewChanged() {
        if (!isShowing) return
        if (pickerOn) {
            val wv = webViewProvider() ?: return
            wv.evaluateJavascript(PICK_INIT, null)
            wv.evaluateJavascript("window.__siiPickOn=true", null)
            host.removeCallbacks(pickPoller)
            host.post(pickPoller)
        }
    }

    /** 返回键优先收面板。 */
    fun onBack(): Boolean {
        if (!isShowing) return false
        hide()
        return true
    }

    private var built = false

    @SuppressLint("ClickableViewAccessibility")
    private fun build() {
        built = true
        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
                setColor(context.getColor(R.color.bg))
                setStroke(dp(1), if (AppServices.settings.isNightMode()) 0x1FFFFFFF else 0x14000000)
            }
            elevation = 12f * density
        }

        // 拖动条
        val handle = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2) * density
                setColor(0x33000000)
            }
        }
        val handleWrap = FrameLayout(context).apply {
            setPadding(dp(8), dp(10), dp(8), dp(6))
            addView(
                handle,
                FrameLayout.LayoutParams(dp(40), dp(4), Gravity.CENTER_HORIZONTAL)
            )
        }
        panel.addView(handleWrap)

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(10), dp(4))
        }
        header.addView(
            TextView(context).apply {
                text = "开发者工具"
                setTextSize(14f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(context.getColor(R.color.text_primary))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(TextView(context).apply {
            text = "全屏"
            setTextSize(12f)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setTextColor(context.getColor(R.color.text_secondary))
            setOnClickListener {
                heightPercent = if (heightPercent >= 90) 55 else 100
                applyHeight()
                text = if (heightPercent >= 90) "退出全屏" else "全屏"
            }
        })
        header.addView(TextView(context).apply {
            text = "收起"
            setTextSize(12f)
            setPadding(dp(8), dp(6), dp(4), dp(6))
            setTextColor(context.getColor(R.color.text_secondary))
            setOnClickListener { hide() }
        })
        panel.addView(header)

        tabsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(2), dp(10), dp(2))
        }
        tabs.forEachIndexed { index, label ->
            tabsRow.addView(TextView(context).apply {
                text = label
                setTextSize(13f)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener {
                    currentTab = index
                    lastTab = index
                    renderTabs()
                    render()
                }
            })
        }
        panel.addView(tabsRow)

        content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(
            content,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // 拖动调高
        handleWrap.setOnTouchListener(object : View.OnTouchListener {
            private var startY = 0f
            private var startH = 0
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                return when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY
                        startH = panel.layoutParams.height
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val delta = (startY - event.rawY).toInt()
                        val target = (startH + delta).coerceIn(
                            (host.height * 0.25f).toInt(),
                            (host.height * 0.95f).toInt()
                        )
                        panel.layoutParams = panel.layoutParams.apply { height = target }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        heightPercent = ((panel.layoutParams.height * 100f / host.height)
                            .toInt()).coerceIn(25, 95)
                        applyHeight()
                        true
                    }
                    else -> false
                }
            }
        })

        host.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320), Gravity.BOTTOM
            )
        )
    }

    private var heightRetry = 0

    private fun applyHeight() {
        if (host.height <= 0) {
            if (heightRetry >= 3) return
            heightRetry++
            host.post { applyHeight() }
            return
        }
        heightRetry = 0
        lastHeight = heightPercent
        val h = (host.height * heightPercent / 100f).toInt().coerceAtLeast(dp(180))
        panel.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM
        )
    }

    private fun renderTabs() {
        val accent = ThemeAccent.color(context)
        for (i in 0 until tabsRow.childCount) {
            (tabsRow.getChildAt(i) as? TextView)?.setTextColor(
                if (i == currentTab) accent else context.getColor(R.color.text_secondary)
            )
        }
    }

    private fun render() {
        treeList = null
        consoleOutput = null
        consoleScroll = null
        consoleFilterRow = null
        content.removeAllViews()
        when (currentTab) {
            0 -> renderConsole()
            1 -> renderElements()
            2 -> renderNetwork()
            else -> renderInfo()
        }
    }

    // --- 控制台 ---

    private var consoleOutput: TextView? = null
    private var consoleScroll: ScrollView? = null
    private var consoleFilterRow: LinearLayout? = null

    private fun renderConsole() {
        if (consoleOutput == null) {
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(2), dp(10), dp(2))
            }
            consoleFilterRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            bar.addView(
                consoleFilterRow,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            bar.addView(Button(context).apply {
                text = "复制全部"
                setTextSize(11f)
                setOnClickListener {
                    val text = JsConsoleStore.snapshot().joinToString("\n") { e ->
                        val t = android.text.format.DateFormat.format("HH:mm:ss", e.time)
                        "$t [${e.level}] ${e.message}"
                    }
                    if (text.isBlank()) {
                        android.widget.Toast.makeText(context, "暂无日志", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        copy(text)
                    }
                }
            })
            bar.addView(Button(context).apply {
                text = "清空"
                setTextSize(11f)
                setOnClickListener { JsConsoleStore.clear(); renderConsoleList() }
            })
            content.addView(bar)

            val output = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                setTextSize(9.5f)
                setTextColor(context.getColor(R.color.text_primary))
                setPadding(dp(8), dp(4), dp(8), dp(6))
                setTextIsSelectable(true)
            }
            consoleOutput = output
            consoleScroll = ScrollView(context).apply { addView(output) }
            content.addView(
                consoleScroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )

            val input = EditText(context).apply {
                hint = "输入 JavaScript 后执行"
                setSingleLine(true)
                setTextSize(12f)
            }
            consoleInput = input
            val run = Button(context).apply {
                text = "执行"
                setTextSize(11f)
                setOnClickListener {
                    val code = input.text.toString().trim()
                    if (code.isEmpty()) return@setOnClickListener
                    consoleHistory.remove(code)
                    consoleHistory.add(code)
                    while (consoleHistory.size > 20) consoleHistory.removeAt(0)
                    webViewProvider()?.let { wv ->
                        JsConsoleStore.add("INPUT", "", 0, code)
                        wv.evaluateJavascript(code) { r ->
                            JsConsoleStore.add("RESULT", "", 0, r.orEmpty())
                        }
                    }
                    input.setText("")
                }
                setOnLongClickListener {
                    showConsoleHistory()
                    true
                }
            }
            content.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(2), dp(8), dp(6))
                addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(run)
            })
        }
        renderConsoleFilters()
        renderConsoleList()
    }

    private fun renderConsoleFilters() {
        val row = consoleFilterRow ?: return
        row.removeAllViews()
        listOf("ALL" to "全部", "LOG" to "LOG", "WARNING" to "警告", "ERROR" to "错误")
            .forEach { (key, label) ->
                row.addView(chip(label, consoleFilter == key) {
                    consoleFilter = key
                    renderConsoleFilters()
                    renderConsoleList()
                })
            }
    }

    private val consoleHistory = mutableListOf<String>()

    private fun showConsoleHistory() {
        if (consoleHistory.isEmpty()) {
            android.widget.Toast.makeText(context, "暂无历史命令", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val items = consoleHistory.reversed().take(20).toTypedArray()
        android.app.AlertDialog.Builder(context)
            .setTitle("历史命令")
            .setItems(items) { _, which ->
                consoleInput?.setText(items[which])
                consoleInput?.setSelection(consoleInput?.text?.length ?: 0)
            }
            .show()
    }

    private var consoleInput: EditText? = null

    private fun renderConsoleList() {
        val output = consoleOutput ?: return
        val entries = JsConsoleStore.snapshot().filter {
            consoleFilter == "ALL" || it.level.equals(consoleFilter, true)
        }
        if (entries.isEmpty()) {
            output.text = "（暂无输出，只记录打开面板之后的信息）"
            return
        }
        val sb = android.text.SpannableStringBuilder()
        entries.forEach { e ->
            val t = android.text.format.DateFormat.format("HH:mm:ss", e.time)
            val start = sb.length
            sb.append(t).append(" [").append(e.level).append("] ").append(e.message).append('\n')
            val color = when (e.level.uppercase()) {
                "ERROR" -> 0xFFD32F2F.toInt()
                "WARNING" -> 0xFFF57C00.toInt()
                "DEBUG" -> 0xFF757575.toInt()
                else -> context.getColor(R.color.text_primary)
            }
            sb.setSpan(
                android.text.style.ForegroundColorSpan(color),
                start, sb.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        output.text = sb
        consoleScroll?.post { consoleScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    // --- 元素 ---

    private fun renderElements() {
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(8), dp(2))
        }
        top.addView(Button(context).apply {
            text = "刷新"
            setTextSize(11f)
            setOnClickListener {
                nodes = emptyList()
                expanded.clear()
                treeLoading = false
                loadTree()
            }
        })
        top.addView(Button(context).apply {
            text = if (pickerOn) "退出点选" else "点选元素"
            setTextSize(11f)
            setOnClickListener { setPicker(!pickerOn) }
        })
        top.addView(EditText(context).apply {
            hint = "搜索标签 / id / 文本"
            setTextSize(11f)
            setSingleLine(true)
            setText(elementsQuery)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    elementsQuery = s?.toString().orEmpty()
                    searchRunnable?.let { host.removeCallbacks(it) }
                    val job = Runnable { treeList?.let { renderTreeRows(it) } }
                    searchRunnable = job
                    host.postDelayed(job, 300)
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(top)
        selectionBar()?.let { content.addView(it) }
        content.addView(hint("点选：在页面上点一下选中元素；搜索：过滤元素树；详情里点值可复制/改样式").apply {
            setPadding(dp(10), 0, dp(10), dp(4))
        })

        if (treeLoading) {
            content.addView(hint("读取中…"))
            return
        }
        if (nodes.isEmpty()) {
            loadTree()
            return
        }
        val treeList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        this.treeList = treeList
        val treeScroll = ScrollView(context).apply { addView(treeList) }
        val treeParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, lastElementsRatio
        )
        content.addView(treeScroll, treeParams)
        renderTreeRows(treeList)

        val divider = View(context).apply {
            setBackgroundColor(0x1A000000)
        }
        content.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))

        val detailWrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val detailList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val detailScroll = ScrollView(context).apply { addView(detailList) }
        fillDetailRows(detailList)
        detailWrap.addView(
            detailScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(6))
        }
        actions.addView(Button(context).apply {
            text = "属性"
            setTextSize(11f)
            isEnabled = picked != null
            setOnClickListener { editAttributes() }
        })
        actions.addView(Button(context).apply {
            text = "样式"
            setTextSize(11f)
            isEnabled = picked != null
            setOnClickListener { editStyles() }
        })
        actions.addView(Button(context).apply {
            text = "复制HTML"
            setTextSize(11f)
            isEnabled = picked != null
            setOnClickListener { copyHtml() }
        })
        actions.addView(Button(context).apply {
            text = "样式覆盖"
            setTextSize(11f)
            isEnabled = picked != null
            setOnClickListener { editOverride() }
        })
        actions.addView(Button(context).apply {
            text = "清除覆盖"
            setTextSize(11f)
            setOnClickListener { clearOverride() }
        })
        actions.addView(Button(context).apply {
            text = "清除高亮"
            setTextSize(11f)
            setOnClickListener { clearHighlight() }
        })
        detailWrap.addView(
            android.widget.HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(actions)
            }
        )
        val detailParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f - lastElementsRatio
        )
        content.addView(detailWrap, detailParams)
        content.addView(overrideList())

        divider.setOnTouchListener(object : View.OnTouchListener {
            private var startY = 0f
            private var startRatio = lastElementsRatio
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = e.rawY
                        startRatio = lastElementsRatio
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val total = (treeScroll.height + detailWrap.height).coerceAtLeast(1)
                        val ratio = startRatio + (e.rawY - startY) / total
                        lastElementsRatio = ratio.coerceIn(0.2f, 0.8f)
                        treeParams.weight = lastElementsRatio
                        detailParams.weight = 1f - lastElementsRatio
                        treeScroll.requestLayout()
                        detailWrap.requestLayout()
                        return true
                    }
                    MotionEvent.ACTION_UP -> return true
                }
                return false
            }
        })
    }

    private fun selectionBar(): View? {
        val p = picked ?: return null
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(6), dp(2))
            setBackgroundColor(0x0F2F7CF6)
        }
        val label = TextView(context).apply {
            text = "当前：<${p.optString("tag")}>" +
                (if (p.optString("id").isNotBlank()) "#${p.optString("id")}" else "") +
                (if (p.optString("cls").isNotBlank()) ".${p.optString("cls").substringBefore(' ')}" else "")
            setTextSize(11f)
            setTextColor(context.getColor(R.color.text_primary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = "×"
            setTextSize(16f)
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setTextColor(context.getColor(R.color.text_secondary))
            setOnClickListener {
                picked = null
                render()
            }
        })
        return row
    }

    private fun fillDetailRows(list: LinearLayout) {
        list.removeAllViews()
        val p = picked
        if (p == null) {
            list.addView(hint("点选页面元素或点树节点查看详情"))
            return
        }
        fun section(title: String) {
            list.addView(TextView(context).apply {
                text = title
                setTextSize(10f)
                setTextColor(ThemeAccent.color(context))
                setPadding(dp(12), dp(6), dp(12), dp(2))
            })
        }
        section("属性")
        val rect = p.optJSONObject("rect")
        val rectText = if (rect != null) {
            "${rect.optInt("x")}, ${rect.optInt("y")}  ${rect.optInt("w")} × ${rect.optInt("h")}"
        } else ""
        list.addView(kvRow("tag", p.optString("tag"), false, null))
        if (p.optString("id").isNotBlank()) list.addView(kvRow("id", p.optString("id"), false, null))
        if (p.optString("cls").isNotBlank()) list.addView(kvRow("class", p.optString("cls"), false, null))
        if (rectText.isNotBlank()) list.addView(kvRow("rect", rectText, false, null))
        if (p.optString("text").isNotBlank()) list.addView(kvRow("text", p.optString("text"), false, null))
        list.addView(kvRow("selector", p.optString("selector"), false, null))

        val style = p.optJSONObject("style") ?: return
        section("样式（点值可修改）")
        val keys = listOf(
            "display" to "display", "position" to "position", "color" to "color",
            "fontSize" to "font-size", "opacity" to "opacity",
            "margin" to "margin", "padding" to "padding", "border" to "border",
            "width" to "width", "height" to "height", "boxSizing" to "box-sizing"
        )
        keys.forEach { (jsonKey, cssKey) ->
            val value = style.optString(jsonKey)
            if (value.isBlank()) return@forEach
            list.addView(kvRow(cssKey, value, true) {
                editStyleValue(cssKey, jsonKey, value)
            })
        }
    }

    private fun kvRow(label: String, value: String, editable: Boolean, onEdit: (() -> Unit)?): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(3), dp(8), dp(3))
        }
        row.addView(TextView(context).apply {
            text = label
            setTextSize(10f)
            setTextColor(context.getColor(R.color.text_secondary))
        }, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
        val valueView = TextView(context).apply {
            text = value
            setTextSize(10.5f)
            typeface = Typeface.MONOSPACE
            setTextColor(context.getColor(R.color.text_primary))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener {
                if (editable && onEdit != null) onEdit() else copy(value)
            }
            setOnLongClickListener {
                copy(value)
                true
            }
        }
        row.addView(valueView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun editStyleValue(cssKey: String, jsonKey: String, current: String) {
        val input = EditText(context).apply {
            setText(current)
            setTextSize(12f)
            setSingleLine(true)
        }
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(input)
            addView(TextView(context).apply {
                text = "例如 #ff0000、red、rgb(255, 0, 0)"
                setTextSize(11f)
                setTextColor(context.getColor(R.color.text_secondary))
                setPadding(0, dp(4), 0, 0)
            })
        }
        android.app.AlertDialog.Builder(context)
            .setTitle(cssKey)
            .setView(wrap)
            .setPositiveButton("应用") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isEmpty()) return@setPositiveButton
                val p = picked ?: return@setPositiveButton
                val q = JSONObject.quote(p.optString("selector"))
                webViewProvider()?.evaluateJavascript(
                    "(function(){try{var e=document.querySelector($q);if(!e)return 'NOT_FOUND';" +
                        "e.style.setProperty(" + JSONObject.quote(cssKey) + "," + JSONObject.quote(value) + ");" +
                        "var got=e.style.getPropertyValue(" + JSONObject.quote(cssKey) + ");" +
                        "return got?got:'INVALID';}catch(x){return 'ERR'}})()"
                ) { raw ->
                    val got = unquote(raw)
                    when {
                        got == "INVALID" || got == "ERR" || got == "NOT_FOUND" || got.isBlank() -> {
                            android.widget.Toast.makeText(
                                context, "值无效，试试 #ff0000 或 rgb(255, 0, 0)",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        else -> {
                            picked?.optJSONObject("style")?.put(jsonKey, got)
                            android.widget.Toast.makeText(
                                context, "已应用", android.widget.Toast.LENGTH_SHORT
                            ).show()
                            render()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun overrideList(): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (overrideRules.isEmpty()) return box
        box.addView(TextView(context).apply {
            text = "已添加的覆盖"
            setTextSize(10f)
            setTextColor(ThemeAccent.color(context))
            setPadding(dp(12), dp(4), dp(12), dp(2))
        })
        overrideRules.forEachIndexed { index, rule ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(3), dp(6), dp(3))
            }
            row.addView(TextView(context).apply {
                text = rule.selector + "  " + rule.props.joinToString("; ") { "${it.first}:${it.second}" }
                setTextSize(10f)
                typeface = Typeface.MONOSPACE
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(context.getColor(R.color.text_primary))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(context).apply {
                text = "×"
                setTextSize(15f)
                setPadding(dp(8), 0, dp(8), 0)
                setTextColor(context.getColor(R.color.text_secondary))
                setOnClickListener {
                    overrideRules.removeAt(index)
                    rebuildOverride()
                    render()
                }
            })
            box.addView(row)
        }
        return box
    }

    private fun loadTree() {
        if (treeLoading) return
        treeLoading = true
        val script = "(function(){try{" +
            "var selOf=function(el){if(el.id)return '#'+CSS.escape(el.id);" +
            "var parts=[];while(el&&el.nodeType===1&&parts.length<5){" +
            "var p=el.nodeName.toLowerCase();" +
            "if(el.id){parts.unshift('#'+CSS.escape(el.id));break;}" +
            "var c=(el.className&&el.className.baseVal!==undefined?el.className.baseVal:el.className||'').toString().trim().split(/\\s+/).filter(Boolean)[0];" +
            "if(c)p+='.'+CSS.escape(c);" +
            "var par=el.parentElement;if(par){var i=1,s=el;while((s=s.previousElementSibling)){if(s.nodeName===el.nodeName)i++;}" +
            "p+=':nth-of-type('+i+')';}parts.unshift(p);el=el.parentElement;}" +
            "return parts.join(' > ');};" +
            "var out=[];var walk=function(el,d,parent){if(!el||out.length>=800||d>18)return;var idx=out.length;" +
            "var c=el.className;if(c&&c.baseVal!==undefined)c=c.baseVal;" +
            "out.push({d:d,t:el.tagName.toLowerCase(),i:el.id||'',c:(c||'').toString().slice(0,60)," +
            "x:(el.innerText||'').replace(/\\s+/g,' ').trim().slice(0,40),p:parent,k:el.children.length>0,s:selOf(el)});" +
            "for(var j=0;j<el.children.length;j++)walk(el.children[j],d+1,idx);};" +
            "if(document.body)walk(document.body,0,-1);return JSON.stringify(out);}catch(e){return 'ERR:'+e}})()"
        webViewProvider()?.injectJavaScript(script) { raw ->
            treeLoading = false
            val arr = runCatching { JSONArray(raw.orEmpty()) }.getOrNull()
            nodes = if (arr == null) emptyList() else buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        Node(
                            depth = o.optInt("d"),
                            tag = o.optString("t"),
                            id = o.optString("i"),
                            cls = o.optString("c"),
                            text = o.optString("x"),
                            parent = o.optInt("p", -1),
                            hasChildren = o.optBoolean("k"),
                            selector = o.optString("s")
                        )
                    )
                }
            }
            expanded.clear()
            nodes.forEachIndexed { index, node ->
                if (node.hasChildren && node.depth < 2) expanded.add(index)
            }
            if (currentTab == 1) render()
        }
    }

    private fun renderTreeRows(list: LinearLayout) {
        list.removeAllViews()
        val query = elementsQuery.trim()
        // 每次渲染只算一次匹配集合，避免每个节点重复 O(n) 扫描。
        val keep = if (query.isEmpty()) null else keepForQuery()
        val accent = ThemeAccent.color(context)
        var shown = 0
        nodes.forEachIndexed { index, node ->
            val visibleNow = if (keep != null) keep.contains(index) else visibleInTree(index)
            if (!visibleNow) return@forEachIndexed
            if (shown >= 300) return@forEachIndexed
            shown++
            val arrow = when {
                !node.hasChildren -> ""
                expanded.contains(index) -> "▾"
                else -> "▸"
            }
            val label = buildString {
                append(node.tag)
                if (node.id.isNotBlank()) append('#').append(node.id)
                if (node.cls.isNotBlank()) append('.').append(node.cls.substringBefore(' '))
                if (node.text.isNotBlank()) append("  ").append(node.text)
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4 + node.depth * 10), 0, dp(8), 0)
                if (picked?.optString("selector") == node.selector) {
                    setBackgroundColor(0x142F7CF6)
                }
            }
            row.addView(TextView(context).apply {
                text = if (arrow.isEmpty()) "  " else arrow
                setTextSize(11f)
                typeface = Typeface.MONOSPACE
                setPadding(dp(2), dp(3), dp(2), dp(3))
                setTextColor(context.getColor(R.color.text_secondary))
                setOnClickListener {
                    if (node.hasChildren) {
                        if (!expanded.add(index)) expanded.remove(index)
                        renderTreeRows(list)
                    }
                }
            })
            row.addView(TextView(context).apply {
                text = label
                setTextSize(10.5f)
                typeface = Typeface.MONOSPACE
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, dp(3))
                setTextColor(
                    if (node.id.isNotBlank()) accent
                    else context.getColor(R.color.text_primary)
                )
                setOnClickListener {
                    highlight(node.selector)
                    selectNode(node.selector)
                }
                setOnLongClickListener {
                    showNodeMenu(node)
                    true
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row)
        }
        if (keep != null && keep.size > shown) {
            list.addView(hint("匹配 ${keep.size} 个，已显示前 $shown 个").apply {
                setPadding(dp(10), dp(4), dp(10), dp(4))
            })
        }
    }

    private fun showNodeMenu(node: Node) {
        val items = arrayOf("复制选择器", "复制 HTML", "高亮", "删除元素")
        android.app.AlertDialog.Builder(context)
            .setTitle("<${node.tag}>")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> copy(node.selector)
                    1 -> copyNodeHtml(node.selector)
                    2 -> highlight(node.selector)
                    else -> confirmDeleteNode(node.selector)
                }
            }
            .show()
    }

    private fun copyNodeHtml(selector: String) {
        val q = JSONObject.quote(selector)
        webViewProvider()?.evaluateJavascript(
            "(function(){try{var e=document.querySelector($q);if(!e)return '';" +
                "return e.outerHTML.slice(0,4000);}catch(x){return ''}})()"
        ) { raw ->
            val html = unquote(raw)
            if (html.isNotBlank()) copy(html)
        }
    }

    private fun confirmDeleteNode(selector: String) {
        android.app.AlertDialog.Builder(context)
            .setTitle("删除元素")
            .setMessage(selector + "\n\n删除后可用刷新页面恢复。")
            .setPositiveButton("删除") { _, _ ->
                val q = JSONObject.quote(selector)
                applyAndRefresh(
                    "(function(){try{var e=document.querySelector($q);if(!e)return 0;e.remove();return 1;}catch(x){return 0}})()"
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun labelOf(node: Node): String = buildString {
        append(node.tag)
        if (node.id.isNotBlank()) append('#').append(node.id)
        if (node.cls.isNotBlank()) append('.').append(node.cls)
        if (node.text.isNotBlank()) append(' ').append(node.text)
    }

    private fun visibleInTree(index: Int): Boolean {
        var parent = nodes[index].parent
        while (parent >= 0) {
            if (!expanded.contains(parent)) return false
            parent = nodes[parent].parent
        }
        return true
    }

    private fun keepForQuery(): Set<Int> {
        val query = elementsQuery.trim()
        if (query.isEmpty()) return emptySet()
        val keep = mutableSetOf<Int>()
        nodes.forEachIndexed { index, node ->
            if (labelOf(node).contains(query, true)) {
                keep.add(index)
                var parent = node.parent
                while (parent >= 0) {
                    keep.add(parent)
                    parent = nodes[parent].parent
                }
            }
        }
        return keep
    }

    private fun selectNode(selector: String) {
        val q = JSONObject.quote(selector)
        val script = "(function(){try{var s=$q;var e=document.querySelector(s);if(!e)return 'NOT_FOUND';" +
            "var r=e.getBoundingClientRect();var cs=getComputedStyle(e);var c=e.className;" +
            "if(c&&c.baseVal!==undefined)c=c.baseVal;" +
            "return JSON.stringify({tag:e.tagName.toLowerCase(),id:e.id||'',cls:(c||'').toString()," +
            "text:(e.innerText||'').replace(/\\s+/g,' ').slice(0,200),selector:s," +
            "rect:{x:Math.round(r.left),y:Math.round(r.top),w:Math.round(r.width),h:Math.round(r.height)}," +
            "style:{display:cs.display,position:cs.position,color:cs.color,fontSize:cs.fontSize," +
            "opacity:cs.opacity,margin:cs.margin,padding:cs.padding,border:cs.border," +
            "width:cs.width,height:cs.height,boxSizing:cs.boxSizing}});" +
            "}catch(x){return 'ERR'}})()"
        webViewProvider()?.evaluateJavascript(script) { raw ->
            val json = runCatching { JSONObject(unquote(raw)) }.getOrNull()
            if (json != null) {
                picked = json
                render()
            }
        }
    }

    private fun highlight(selector: String) {
        val q = JSONObject.quote(selector)
        val script = "(function(){try{var e=document.querySelector($q);if(!e)return 0;" +
            "e.style.outline='2px solid #2F7CF6';e.style.outlineOffset='1px';" +
            "e.scrollIntoView({block:'center'});return 1;}catch(x){return 0}})()"
        webViewProvider()?.evaluateJavascript(script, null)
    }

    private fun clearHighlight() {
        val p = picked ?: return
        val q = JSONObject.quote(p.optString("selector"))
        webViewProvider()?.evaluateJavascript(
            "(function(){try{var e=document.querySelector($q);if(e){e.style.outline='';}return 1;}catch(x){return 0}})()",
            null
        )
    }

    private fun editAttributes() {
        val p = picked ?: return
        val idField = EditText(context).apply { hint = "id"; setText(p.optString("id")) }
        val clsField = EditText(context).apply { hint = "class"; setText(p.optString("cls")) }
        val textField = EditText(context).apply { hint = "文本内容（留空不改）" }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(idField)
            addView(clsField)
            addView(textField)
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("<${p.optString("tag")}> 属性")
            .setView(box)
            .setPositiveButton("应用") { _, _ ->
                applyAttributes(
                    p.optString("selector"),
                    idField.text.toString(),
                    clsField.text.toString(),
                    textField.text.toString()
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyAttributes(selector: String, id: String, cls: String, textValue: String) {
        val q = JSONObject.quote(selector)
        val script = "(function(){try{var e=document.querySelector($q);if(!e)return 0;" +
            "e.id=" + JSONObject.quote(id) + ";" +
            "e.className=" + JSONObject.quote(cls) + ";" +
            (if (textValue.isBlank()) "" else "e.textContent=" + JSONObject.quote(textValue) + ";") +
            "return 1;}catch(x){return 0}})()"
        applyAndRefresh(script)
    }

    private fun editStyles() {
        val p = picked ?: return
        val field = EditText(context).apply {
            hint = "每行一条，例如：\ncolor: #ff0000\ndisplay: none\npadding: 8px"
            setTextSize(12f)
            minLines = 4
            maxLines = 8
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("<${p.optString("tag")}> 样式（内联）")
            .setView(field)
            .setPositiveButton("应用") { _, _ ->
                val pairs = field.text.toString().lines().mapNotNull { line ->
                    val idx = line.indexOf(':')
                    if (idx <= 0) return@mapNotNull null
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    if (key.isEmpty() || value.isEmpty()) null else key to value
                }
                if (pairs.isEmpty()) return@setPositiveButton
                val calls = pairs.joinToString("") { (k, v) ->
                    "e.style.setProperty(" + JSONObject.quote(k) + "," + JSONObject.quote(v) + ");"
                }
                val q = JSONObject.quote(p.optString("selector"))
                applyAndRefresh(
                    "(function(){try{var e=document.querySelector($q);if(!e)return 0;$calls" +
                        "return 1;}catch(x){return 0}})()"
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyHtml() {
        val p = picked ?: return
        val q = JSONObject.quote(p.optString("selector"))
        webViewProvider()?.evaluateJavascript(
            "(function(){try{var e=document.querySelector($q);if(!e)return '';" +
                "return e.outerHTML.slice(0,4000);}catch(x){return ''}})()"
        ) { raw ->
            val html = unquote(raw)
            if (html.isBlank()) {
                android.widget.Toast.makeText(context, "未找到元素", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                copy(html)
            }
        }
    }

    private fun editOverride() {
        val p = picked ?: return
        val selectorField = EditText(context).apply {
            setText(p.optString("selector"))
            setTextSize(11f)
            setSingleLine(true)
        }
        val propsField = EditText(context).apply {
            hint = "每行一条，例如：\nbackground: #ff0 !important\ndisplay: block"
            setTextSize(12f)
            minLines = 4
            maxLines = 8
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(selectorField)
            addView(propsField)
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("覆盖样式（!important）")
            .setView(box)
            .setPositiveButton("应用") { _, _ ->
                val pairs = propsField.text.toString().lines().mapNotNull { line ->
                    val idx = line.indexOf(':')
                    if (idx <= 0) return@mapNotNull null
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    if (key.isEmpty() || value.isEmpty()) null else key to value
                }
                if (pairs.isEmpty()) return@setPositiveButton
                overrideRules.add(
                    OverrideRule(selectorField.text.toString().trim(), pairs)
                )
                rebuildOverride()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 用当前规则列表重写 <style>，有则更新、无则删除。 */
    private fun rebuildOverride() {
        val rulesJson = JSONArray().apply {
            overrideRules.forEach { rule ->
                put(
                    JSONObject()
                        .put("selector", rule.selector)
                        .put(
                            "props",
                            JSONArray().apply {
                                rule.props.forEach { (k, v) -> put(JSONArray().put(k).put(v)) }
                            }
                        )
                )
            }
        }
        val script = "(function(){try{var id='sii-devtools-override';" +
            "var st=document.getElementById(id);var rules=" + rulesJson + ";" +
            "if(!rules.length){if(st)st.remove();return 'OK';}" +
            "if(!st){st=document.createElement('style');st.id=id;" +
            "(document.head||document.documentElement).appendChild(st);}" +
            "var text='';for(var i=0;i<rules.length;i++){var r=rules[i];var body='';" +
            "for(var j=0;j<r.props.length;j++){body+=r.props[j][0]+':'+r.props[j][1]+' !important;'}" +
            "text+=r.selector+'{'+body+'}';}st.textContent=text;return 'OK';" +
            "}catch(x){return 'ERR:'+x}})()"
        webViewProvider()?.evaluateJavascript(script, null)
    }

    private fun clearOverride() {
        overrideRules.clear()
        rebuildOverride()
        android.widget.Toast.makeText(context, "已清除覆盖", android.widget.Toast.LENGTH_SHORT).show()
        render()
    }

    private fun unquote(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching { org.json.JSONTokener(raw).nextValue().toString() }
            .getOrDefault(raw)
    }

    private fun applyAndRefresh(script: String) {        webViewProvider()?.evaluateJavascript(script) { result ->
            if (!isShowing) return@evaluateJavascript
            if (result?.trim() == "1") {
                android.widget.Toast.makeText(context, "已应用", android.widget.Toast.LENGTH_SHORT).show()
                loadTree()
            } else {
                android.widget.Toast.makeText(context, "未找到元素", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- 网络 ---

    private fun renderNetwork() {
        val wv = webViewProvider()
        if (wv == null) {
            content.addView(hint("没有可用的网页"))
            return
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(8), dp(2))
        }
        val filters = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(filters, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(context).apply {
            text = "清空"
            setTextSize(11f)
            setOnClickListener {
                PageRequests.clear(wv)
                networkSelected = -1
                render()
            }
        })
        content.addView(bar)
        listOf(
            "ALL" to "全部", "document" to "文档", "xhr" to "XHR",
            "media" to "媒体", "image" to "图片", "other" to "其他"
        ).forEach { (key, label) ->
            filters.addView(chip(label, networkFilter == key) {
                networkFilter = key
                networkSelected = -1
                render()
            })
        }

        val all = PageRequests.list(wv).reversed()
        val entries = all.filter { networkFilter == "ALL" || it.kind == networkFilter }
        if (entries.isEmpty()) {
            content.addView(hint("暂无记录"))
            return
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        entries.forEachIndexed { i, entry ->
            list.addView(TextView(context).apply {
                text = "${entry.method}  ${entry.kind}  ${shortUrl(entry.url)}" +
                    if (entry.blocked) "  [已拦截]" else ""
                setTextSize(10.5f)
                typeface = Typeface.MONOSPACE
                setPadding(dp(8), dp(5), dp(8), dp(5))
                setTextColor(
                    if (entry.blocked) ThemeAccent.color(context)
                    else context.getColor(R.color.text_primary)
                )
                setBackgroundColor(
                    if (networkSelected == i) 0x142F7CF6 else Color.TRANSPARENT
                )
                setOnClickListener {
                    networkSelected = if (networkSelected == i) -1 else i
                    render()
                }
            })
        }
        content.addView(
            ScrollView(context).apply { addView(list) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0,
                if (networkSelected >= 0) 3f else 1f
            )
        )
        if (networkSelected in entries.indices) {
            val entry = entries[networkSelected]
            val detail = TextView(context).apply {
                text = entry.url + "\n\n" +
                    "${entry.method}  ${entry.kind}" +
                    (if (entry.blocked) "  已拦截" else "") +
                    (if (entry.mainFrame) "  主框架" else "") +
                    "\n" + android.text.format.DateFormat.format("HH:mm:ss", entry.time)
                setTextSize(10f)
                typeface = Typeface.MONOSPACE
                setTextColor(context.getColor(R.color.text_primary))
                setPadding(dp(10), dp(4), dp(10), dp(6))
                setTextIsSelectable(true)
            }
            content.addView(
                ScrollView(context).apply { addView(detail) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f)
            )
        }
    }

    // --- 信息 ---

    private fun renderInfo() {
        val contentCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val card = CardList.pageCard(context)
        contentCard.addView(card)
        content.addView(
            ScrollView(context).apply { addView(contentCard) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        val script = "(function(){try{return JSON.stringify({" +
            "url:location.href,title:document.title,ua:navigator.userAgent," +
            "vw:window.innerWidth,vh:window.innerHeight,dpr:window.devicePixelRatio||1," +
            "ls:(function(){try{return localStorage.length}catch(e){return -1}})()," +
            "ss:(function(){try{return sessionStorage.length}catch(e){return -1}})()," +
            "cookie:(document.cookie||'').split(';').filter(function(s){return s.trim()}).length" +
            "});}catch(e){return 'ERR:'+e}})()"
        webViewProvider()?.injectJavaScript(script) { raw ->
            val json = runCatching { JSONObject(raw.orEmpty()) }.getOrNull()
            if (json == null) {
                CardList.addRows(card, listOf(hint("读取失败")))
                return@injectJavaScript
            }
            CardList.addRows(
                card,
                listOf(
                    infoRow("URL", json.optString("url")),
                    infoRow("标题", json.optString("title")),
                    infoRow("视口", "${json.optInt("vw")} × ${json.optInt("vh")}"),
                    infoRow("DPR", json.optString("dpr")),
                    infoRow("localStorage", json.optInt("ls").toString()),
                    infoRow("sessionStorage", json.optInt("ss").toString()),
                    infoRow("Cookie", json.optInt("cookie").toString()),
                    infoRow("UA", json.optString("ua"))
                )
            )
        }
    }

    // --- 点选 ---

    private fun setPicker(on: Boolean) {
        val wv = webViewProvider() ?: return
        pickerOn = on
        if (on) {
            heightPercent = 35
            applyHeight()
            wv.evaluateJavascript("window.__siiPicked=''", null)
            wv.evaluateJavascript(PICK_INIT, null)
            wv.evaluateJavascript("window.__siiPickOn=true", null)
            host.removeCallbacks(pickPoller)
            host.post(pickPoller)
        } else {
            wv.evaluateJavascript("try{window.__siiPickOff&&window.__siiPickOff()}catch(e){}", null)
            host.removeCallbacks(pickPoller)
            heightPercent = 55
            applyHeight()
        }
        if (currentTab == 1) render()
    }

    /** 页面已加载时 addJavascriptInterface 不生效，改用轮询读取选中的元素。 */
    private val pickPoller = object : Runnable {
        override fun run() {
            if (!isShowing || !pickerOn) return
            val wv = webViewProvider() ?: return
            wv.evaluateJavascript(
                "(function(){var v=window.__siiPicked;window.__siiPicked='';return v||'';})()"
            ) { raw ->
                val json = unquote(raw)
                if (json.isNotBlank()) onPicked(json) else host.postDelayed(this, 250)
            }
        }
    }

    private fun onPicked(json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        picked = obj
        if (pickerOn) {
            pickerOn = false
            heightPercent = 55
            applyHeight()
        }
        currentTab = 1
        renderTabs()
        render()
    }

    // --- 通用 ---

    private fun chip(label: String, active: Boolean, onClick: () -> Unit): View =
        TextView(context).apply {
            text = label
            setTextSize(10.5f)
            setPadding(dp(9), dp(4), dp(9), dp(4))
            setTextColor(if (active) ThemeAccent.color(context) else context.getColor(R.color.text_secondary))
            background = GradientDrawable().apply {
                cornerRadius = dp(13).toFloat()
                setColor(
                    if (active) ((0x1A shl 24) or (ThemeAccent.color(context) and 0x00FFFFFF))
                    else context.getColor(R.color.card)
                )
            }
            setOnClickListener { onClick() }
        }

    private fun infoRow(title: String, value: String): View {
        val name = TextView(context).apply {
            text = title
            setTextSize(11f)
            setTextColor(context.getColor(R.color.text_secondary))
        }
        val valueView = TextView(context).apply {
            text = value
            setTextSize(11f)
            setTextColor(context.getColor(R.color.text_primary))
            setTextIsSelectable(true)
        }
        return CardList.row(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            addView(name)
            addView(valueView)
        }
    }

    private fun hint(text: String): View = TextView(context).apply {
        this.text = text
        setTextSize(11f)
        setTextColor(context.getColor(R.color.text_secondary))
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun copy(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("selector", text))
        android.widget.Toast.makeText(context, "已复制 $text", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun shortUrl(url: String): String {
        val clean = url.substringAfter("://", url)
        return if (clean.length > 80) clean.take(80) + "…" else clean
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        var lastTab = 0
        var lastHeight = 55
        var lastElementsRatio = 0.55f
        val PICK_INIT = "(function(){" +
            "if(window.__siiPickInit2)return 'OK';window.__siiPickInit2=true;window.__siiPickOn=false;" +
            "var lastEl=null,lastOutline='';" +
            "function sel(el){if(el.id)return '#'+CSS.escape(el.id);var parts=[];" +
            "while(el&&el.nodeType===1&&parts.length<5){var p=el.nodeName.toLowerCase();" +
            "if(el.id){parts.unshift('#'+CSS.escape(el.id));break;}" +
            "var c=(el.className&&el.className.baseVal!==undefined?el.className.baseVal:el.className||'').toString().trim().split(/\\s+/).filter(Boolean)[0];" +
            "if(c)p+='.'+CSS.escape(c);var par=el.parentElement;" +
            "if(par){var i=1,s=el;while((s=s.previousElementSibling)){if(s.nodeName===el.nodeName)i++;}p+=':nth-of-type('+i+')';}" +
            "parts.unshift(p);el=el.parentElement;}return parts.join(' > ');}" +
            "function info(el){var r=el.getBoundingClientRect();var cs=getComputedStyle(el);" +
            "return JSON.stringify({tag:el.tagName.toLowerCase(),id:el.id||''," +
            "cls:(el.className&&el.className.baseVal!==undefined?el.className.baseVal:el.className||'').toString()," +
            "text:(el.innerText||'').slice(0,200),selector:sel(el)," +
            "rect:{x:Math.round(r.left),y:Math.round(r.top),w:Math.round(r.width),h:Math.round(r.height)}," +
            "style:{display:cs.display,position:cs.position,color:cs.color,fontSize:cs.fontSize,opacity:cs.opacity," +
            "margin:cs.margin,padding:cs.padding,border:cs.border,width:cs.width,height:cs.height,boxSizing:cs.boxSizing}});}" +
            "window.__siiPickClear=function(){if(lastEl){lastEl.style.outline=lastOutline;lastEl=null;}};" +
            "window.__siiPickOff=function(){window.__siiPickOn=false;try{window.__siiPickClear()}catch(e){}};" +
            "document.addEventListener('mousemove',function(e){if(!window.__siiPickOn)return;var el=e.target;" +
            "if(lastEl===el)return;try{window.__siiPickClear()}catch(x){}lastEl=el;lastOutline=el.style.outline;" +
            "el.style.outline='2px solid #FF6D00';},true);" +
            "document.addEventListener('click',function(e){if(!window.__siiPickOn)return;" +
            "e.preventDefault();e.stopPropagation();var el=e.target;window.__siiPickOn=false;" +
            "lastEl=el;lastOutline='';el.style.outline='2px solid #2F7CF6';" +
            "window.__siiPicked=info(el);return false;},true);" +
            "return 'OK';})()"
    }
}
