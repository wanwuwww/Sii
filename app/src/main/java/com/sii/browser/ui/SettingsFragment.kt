// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import com.sii.browser.R
import com.sii.browser.di.AppServices

/**
 * 设置一级页：顶部搜索 + 5 个分组入口。
 *
 * 搜索结果点击后跳到对应设置页，并把关键词传过去做滚动定位。
 */
class SettingsFragment : Fragment() {

    private lateinit var container: LinearLayout

    private data class SearchEntry(
        val title: String,
        val group: String,
        val aliases: String = "",
        val diyTab: Int = -1
    )

    private val baseSearchIndex = listOf(
        SearchEntry("语言", "通用", "language"),
        SearchEntry("搜索引擎", "通用", "engine"),
        SearchEntry("默认浏览器", "通用", "default browser"),
        SearchEntry("翻译", "通用", "translate"),
        SearchEntry("朗读", "通用", "read aloud"),
        SearchEntry("剪贴板保护", "通用", "clipboard"),
        SearchEntry("站点默认", "通用", "站点规则"),
        SearchEntry("桌面站点", "通用", "desktop site"),
        SearchEntry("脚本", "通用", "userscript"),
        SearchEntry("下载", "通用", "download"),
        SearchEntry("仅 Wi-Fi 图片", "通用", "图片"),
        SearchEntry("手势", "通用", "gesture"),
        SearchEntry("长按定制", "通用", "长按菜单"),
        SearchEntry("行为", "通用", "启动行为 操作"),
        SearchEntry("返回二次确认", "通用", "退出 exit back"),
        SearchEntry("屏幕方向", "通用", "orientation"),
        SearchEntry("保持亮屏", "通用", "keep screen on"),
        SearchEntry("广告拦截", "隐私与安全", "adblock"),
        SearchEntry("无痕", "隐私与安全", "incognito"),
        SearchEntry("隐私保护", "隐私与安全", "privacy"),
        SearchEntry("密码库", "隐私与安全", "password"),
        SearchEntry("清除数据", "隐私与安全", "clear data"),
        SearchEntry("浏览器标识", "高级", "UA user agent"),
        SearchEntry("网络", "高级", "network"),
        SearchEntry("MCP 服务", "高级", "mcp ai 自动化 抓包 接口"),
        SearchEntry("导入导出", "高级", "import export"),
        SearchEntry("实验室", "高级", "lab"),
        SearchEntry("检查更新", "关于", "update"),
        SearchEntry("更新提示", "关于", "update prompt 新版本 忽略 启动"),
        SearchEntry("崩溃日志", "关于", "crash log"),
        SearchEntry("开源许可", "关于", "license"),
        SearchEntry("开源仓库", "关于", "repository github"),
        SearchEntry("搜索框位置", "外观与主页", "主页布局", diyTab = 1),
        SearchEntry("显示主页搜索框", "外观与主页", "搜索框 显示 隐藏", diyTab = 1),
        SearchEntry("主页顶部搜索框", "外观与主页", "地址栏 顶部 搜索框", diyTab = 1),
        SearchEntry("Logo", "外观与主页", "显示内容 时间", diyTab = 0),
        SearchEntry("常用网站", "外观与主页", "主页图标", diyTab = 4),
        SearchEntry("地址栏", "外观与主页", "address", diyTab = 2),
        SearchEntry("工具栏", "外观与主页", "toolbar", diyTab = 5),
        SearchEntry("菜单", "外观与主页", "menu 固定菜单位置", diyTab = 6),
        SearchEntry("标签页", "外观与主页", "tabs 标签卡 网格", diyTab = 7),
        SearchEntry("主题", "外观与主页", "夜间 深色 玻璃", diyTab = 8)
    )

    /** 诊断接口只在测试包出现，正式包的搜索索引里也不留。 */
    private val searchIndex: List<SearchEntry> by lazy {
        val debuggable = (requireContext().applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) baseSearchIndex
        else baseSearchIndex + SearchEntry("MCP 诊断接口", "高级", "mcp 诊断 界面状态 导航日志 diag")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.settings_back)?.setOnClickListener {
            if (parentFragmentManager.isStateSaved) return@setOnClickListener
            parentFragmentManager.popBackStack()
        }
        view.findViewById<TextView>(R.id.settings_title)?.setText(R.string.settings)
        container = view.findViewById(R.id.settings_container)
        val search = view.findViewById<EditText>(R.id.settings_search)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty().trim()
                if (query.isEmpty()) showEntries() else showResults(query)
            }
        })
        showEntries()
    }

    private fun showEntries() {
        container.removeAllViews()
        val card = CardList.settingsCard(
            requireContext(), AppServices.settings.settingsCardAlpha()
        )
        CardList.addRows(
            card,
            listOf(
                entryRow(R.string.settings_general, R.string.settings_general_desc, SettingsDetailFragment.VIEW_GENERAL),
                entryRow(R.string.settings_privacy_security, R.string.settings_privacy_security_desc, SettingsDetailFragment.VIEW_PRIVACY),
                entryRow(R.string.settings_appearance, R.string.settings_appearance_desc, SettingsDetailFragment.VIEW_APPEARANCE),
                entryRow(R.string.settings_advanced, R.string.settings_advanced_desc, SettingsDetailFragment.VIEW_ADVANCED),
                entryRow(R.string.settings_about, R.string.settings_about_desc, SettingsDetailFragment.VIEW_ABOUT)
            )
        )
        container.addView(card)
    }

    private fun showResults(query: String) {
        container.removeAllViews()
        val matches = searchIndex.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.aliases.contains(query, ignoreCase = true) ||
                it.group.contains(query, ignoreCase = true)
        }
        if (matches.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = "没有找到相关设置"
                setTextSize(13f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
                setPadding(0, 24, 0, 0)
            }
            container.addView(empty)
            return
        }
        val card = CardList.settingsCard(
            requireContext(), AppServices.settings.settingsCardAlpha()
        )
        CardList.addRows(card, matches.map { resultRow(it) })
        container.addView(card)
    }

    private fun resultRow(entry: SearchEntry): View {
        return CardList.row(requireContext()).apply {
            val column = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(requireContext()).apply {
                    text = entry.title
                    setTextSize(15f)
                    setTextColor(requireContext().getColor(R.color.text_primary))
                })
                addView(TextView(requireContext()).apply {
                    text = entry.group
                    setTextSize(11.5f)
                    setTextColor(requireContext().getColor(R.color.text_secondary))
                    alpha = 0.55f
                    setPadding(0, CardList.dp(requireContext(), 3), 0, 0)
                })
            }
            addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(requireContext()).apply {
                text = "›"
                setTextSize(14f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
            })
            setOnClickListener { openResult(entry) }
        }
    }

    private fun openResult(entry: SearchEntry) {
        val target: Fragment = if (entry.diyTab >= 0) {
            HomeCustomizeFragment().apply {
                arguments = bundleOf(HomeCustomizeFragment.ARG_TAB to entry.diyTab)
            }
        } else {
            SettingsDetailFragment().apply {
                arguments = bundleOf(
                    SettingsDetailFragment.ARG_VIEW to pageFor(entry.group),
                    SettingsDetailFragment.ARG_FOCUS to entry.title
                )
            }
        }
        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(R.id.shell_root, target)
            .addToBackStack(null)
            .commit()
    }

    private fun pageFor(group: String): String = when (group) {
        "通用" -> SettingsDetailFragment.VIEW_GENERAL
        "隐私与安全" -> SettingsDetailFragment.VIEW_PRIVACY
        "高级" -> SettingsDetailFragment.VIEW_ADVANCED
        "关于" -> SettingsDetailFragment.VIEW_ABOUT
        else -> SettingsDetailFragment.VIEW_GENERAL
    }

    private fun entryRow(titleRes: Int, descRes: Int, group: String): View {
        return CardList.row(requireContext()).apply {
            val column = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(requireContext()).apply {
                    setText(titleRes)
                    setTextSize(15f)
                    setTextColor(requireContext().getColor(R.color.text_primary))
                })
                addView(TextView(requireContext()).apply {
                    setText(descRes)
                    setTextSize(11.5f)
                    setTextColor(requireContext().getColor(R.color.text_secondary))
                    alpha = 0.55f
                    setPadding(0, CardList.dp(requireContext(), 3), 0, 0)
                    setLineSpacing(0f, 1.35f)
                })
            }
            addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(requireContext()).apply {
                text = "›"
                setTextSize(14f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
            })
            setOnClickListener {
                val target = if (group == SettingsDetailFragment.VIEW_APPEARANCE) {
                    HomeCustomizeFragment()
                } else {
                    SettingsDetailFragment().apply {
                        arguments = bundleOf(SettingsDetailFragment.ARG_VIEW to group)
                    }
                }
                NavAnim.apply(parentFragmentManager.beginTransaction())
                    .replace(R.id.shell_root, target)
                    .addToBackStack(null)
                    .commit()
            }
        }
    }
}
