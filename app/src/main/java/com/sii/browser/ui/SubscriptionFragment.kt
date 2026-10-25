package com.sii.browser.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sii.browser.R
import com.sii.browser.data.db.RuleSummary
import com.sii.browser.data.db.RuleType
import com.sii.browser.di.AppServices
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 规则订阅管理：
 * 页头 ⋮（添加订阅 / 更新全部）+ 自动更新卡片 + 每个订阅一张独立卡片
 * （名称、更新时间、启用开关），长按卡片删除。
 */
class SubscriptionFragment : Fragment() {

    private lateinit var container: LinearLayout
    private var subs: List<RuleSummary> = emptyList()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

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
        addView(PageHeader.build(
            requireContext(),
            getString(R.string.adblock_subscriptions),
            onBack = { parentFragmentManager.popBackStack() },
            onMenu = { anchor -> showMenu(anchor) }
        ))
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(CardList.scroll(requireContext(), content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        this@SubscriptionFragment.container = content
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            AppServices.ruleRepository.all().collect { rules ->
                subs = rules.filter { it.type == RuleType.SUBSCRIPTION }
                render()
            }
        }
    }

    private fun render() {
        container.removeAllViews()

        // 自动更新卡片
        val autoSwitch = Switch(requireContext()).apply {
            isChecked = AppServices.settings.subscriptionAutoUpdate()
            setOnCheckedChangeListener { _, value ->
                AppServices.settings.setSubscriptionAutoUpdate(value)
            }
        }
        container.addView(CardList.styledCard(requireContext(), page = true).apply {
            CardList.addRows(this, listOf(
                CardList.row(requireContext()).apply {
                    addView(LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(requireContext()).apply {
                            text = getString(R.string.settings_sub_auto)
                            setTextSize(14f)
                            setTextColor(requireContext().getColor(R.color.text_primary))
                        })
                        addView(TextView(requireContext()).apply {
                            text = getString(R.string.adblock_sub_auto_desc)
                            setTextSize(11.5f)
                            setTextColor(requireContext().getColor(R.color.text_secondary))
                        })
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(autoSwitch)
                },
                CardList.row(requireContext()).apply {
                    addView(TextView(requireContext()).apply {
                        text = getString(R.string.settings_sub_interval)
                        setTextSize(14f)
                        setTextColor(requireContext().getColor(R.color.text_primary))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(TextView(requireContext()).apply {
                        text = intervalLabel(AppServices.settings.subscriptionIntervalDays())
                        setTextSize(13f)
                        setTextColor(requireContext().getColor(R.color.text_secondary))
                    })
                    setOnClickListener { chooseInterval() }
                }
            ))
        })

        // 添加订阅：显式入口，不用去 ⋮ 里找
        container.addView(CardList.styledCard(requireContext(), page = true).apply {
            CardList.addRows(this, listOf(
                CardList.row(requireContext()).apply {
                    addView(TextView(requireContext()).apply {
                        text = "＋ " + getString(R.string.rules_add_subscription)
                        setTextSize(14f)
                        setTextColor(ThemeAccent.color(requireContext()))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    setOnClickListener { showAddDialog() }
                }
            ))
        })

        // 订阅列表：每个订阅一张卡片
        if (subs.isEmpty()) {
            container.addView(CardList.styledCard(requireContext(), page = true).apply {
                CardList.addRows(this, listOf(
                    CardList.row(requireContext()).apply {
                        addView(TextView(requireContext()).apply {
                            text = getString(R.string.adblock_subscriptions_empty)
                            setTextSize(13f)
                            setTextColor(requireContext().getColor(R.color.text_secondary))
                        })
                    }
                ))
            })
        } else {
            container.addView(CardList.sectionTitle(requireContext(), getString(R.string.adblock_subscriptions)))
            subs.forEach { rule -> container.addView(subscriptionCard(rule)) }
        }
    }

    private fun subscriptionCard(rule: RuleSummary): View {
        val name = displayName(rule)
        val subtitle = if (rule.title == LEGACY_WARNING_REMOVAL) {
            getString(R.string.adblock_sub_unsupported)
        } else if (rule.contentLength == 0) {
            getString(R.string.adblock_sub_never)
        } else {
            getString(
                R.string.adblock_sub_updated,
                dateFormat.format(Date(rule.updatedAt)),
                rule.lineCount
            )
        }
        val enabledSwitch = Switch(requireContext()).apply {
            isChecked = rule.enabled
            setOnCheckedChangeListener { _, value ->
                viewLifecycleOwner.lifecycleScope.launch {
                    AppServices.ruleRepository.setEnabled(rule, value)
                    AppServices.ruleRepository.loadIntoEngine(AppServices.adBlock)
                }
            }
        }
        return CardList.styledCard(requireContext(), page = true).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            CardList.addRows(this, listOf(
                CardList.row(requireContext()).apply {
                    addView(LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(requireContext()).apply {
                            text = name
                            setTextSize(14f)
                            setTextColor(requireContext().getColor(R.color.text_primary))
                        })
                        addView(TextView(requireContext()).apply {
                            text = subtitle
                            setTextSize(11.5f)
                            setTextColor(requireContext().getColor(R.color.text_secondary))
                        })
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(enabledSwitch)
                }
            ))
            setOnLongClickListener {
                showSubscriptionActions(rule)
                true
            }
        }
    }

    private fun chooseInterval() {
        val days = AppServices.settings.subscriptionIntervalDays()
        val labels = arrayOf("每 3 天", "每半个月", "每个月")
        val values = intArrayOf(3, 15, 30)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_sub_interval)
            .setSingleChoiceItems(labels, values.indexOf(days).coerceAtLeast(0)) { dialog, which ->
                AppServices.settings.setSubscriptionIntervalDays(values[which])
                dialog.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun intervalLabel(days: Int): String = when (days) {
        3 -> "每 3 天"
        30 -> "每个月"
        else -> "每半个月"
    }

    private fun showSubscriptionActions(rule: RuleSummary) {
        AlertDialog.Builder(requireContext())
            .setItems(arrayOf(getString(R.string.rename), getString(R.string.delete))) { _, which ->
                if (which == 0) showRenameDialog(rule) else confirmDelete(rule)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameDialog(rule: RuleSummary) {
        val input = EditText(requireContext()).apply {
            setText(rule.name)
            hint = getString(R.string.adblock_sub_rename_hint)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.adblock_sub_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    AppServices.ruleRepository.renameSubscription(rule.id, input.text.toString())
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(rule: RuleSummary) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.delete) + " " + displayName(rule) + "?")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    AppServices.ruleRepository.delete(rule.id)
                    AppServices.ruleRepository.loadIntoEngine(AppServices.adBlock)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMenu(anchor: View) {
        MenuPopup.show(anchor, listOf(
            MenuPopup.Item(getString(R.string.rules_add_subscription)) { showAddDialog() },
            MenuPopup.Item(getString(R.string.adblock_update_all)) { updateAll() }
        ))
    }

    private fun updateAll() {
        if (subs.isEmpty()) {
            Toast.makeText(requireContext(), R.string.adblock_subscriptions_empty, Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            var updated = 0
            subs.forEach { rule ->
                if (AppServices.ruleRepository.updateSubscription(rule.id, rule.title)) updated++
            }
            AppServices.ruleRepository.loadIntoEngine(AppServices.adBlock)
            Toast.makeText(
                requireContext(),
                getString(R.string.adblock_update_all_done, updated),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showAddDialog() {
        val options = PRESETS.map { it.first } + getString(R.string.adblock_manual_input)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rules_add_subscription)
            .setItems(options.toTypedArray()) { _, which ->
                if (which < PRESETS.size) {
                    addSubscription(PRESETS[which].second)
                } else {
                    showManualInputDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showManualInputDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.rules_subscription_url)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rules_add_subscription)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) addSubscription(url)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addSubscription(url: String) {
        if (subs.any { it.title == url }) {
            Toast.makeText(requireContext(), R.string.adblock_sub_exists, Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val id = AppServices.ruleRepository.addSubscription(url)
            val ok = AppServices.ruleRepository.updateSubscription(id, url)
            AppServices.ruleRepository.loadIntoEngine(AppServices.adBlock)
            Toast.makeText(
                requireContext(),
                if (ok) R.string.rules_updated else R.string.rules_update_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun displayName(rule: RuleSummary): String {
        if (rule.name.isNotBlank()) return rule.name
        val preset = presetName(rule.title)
        if (preset != rule.title) return preset
        return runCatching { android.net.Uri.parse(rule.title).host }
            .getOrNull().orEmpty().ifEmpty { rule.title }
    }

    private fun presetName(url: String): String =
        PRESETS.firstOrNull { it.second == url }?.first
            ?: LEGACY_NAMES[url]
            ?: url

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        private const val LEGACY_WARNING_REMOVAL =
            "https://easylist.to/easylist/easylist-warning-removal.txt"
        private val LEGACY_NAMES = mapOf(
            "https://easylist.to/easylistchina/easylistchina.txt" to "EasyList China",
            "https://easylist.to/easylistchina/cjx-annoyance.txt" to "CJX's Annoyance List",
            LEGACY_WARNING_REMOVAL to "EasyList Warning Removal"
        )

        /** 常用 ABP 订阅源（预设名称与下载地址）。 */
        val PRESETS = listOf(
            "EasyList China" to "https://easylist-downloads.adblockplus.org/easylistchina.txt",
            "CJX's Annoyance List" to "https://easylist-downloads.adblockplus.org/cjx-annoyance.txt",
            "EasyList" to "https://easylist.to/easylist/easylist.txt",
            "EasyPrivacy" to "https://easylist.to/easylist/easyprivacy.txt"
        )
    }
}
