package com.sii.browser.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.withContext

/**
 * 广告拦截主页：总开关、拦截统计、规则来源（内建/自定义/订阅）。
 * 订阅入口只保留一处，避免和来源卡片重复。
 */
class RulesFragment : Fragment() {

    private lateinit var container: LinearLayout
    private var latestRules: List<RuleSummary> = emptyList()

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) applyRulesImport(uri) }

    override fun onDestroyView() {
        if (::container.isInitialized) container.removeAllViews()
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
                getString(R.string.settings_rules),
                onBack = { parentFragmentManager.popBackStack() },
                onMenu = { _ -> showMenu() }
            )
        )
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(
            CardList.scroll(context, content),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        this@RulesFragment.container = content
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            AppServices.ruleRepository.all().collect { rules -> render(rules) }
        }
    }

    private fun render(rules: List<RuleSummary>) {
        latestRules = rules
        container.removeAllViews()
        val engine = AppServices.adBlock
        val subs = rules.filter { it.type == RuleType.SUBSCRIPTION }

        container.addView(CardList.sectionTitle(requireContext(), getString(R.string.adblock_status)))
        card(
            switchRow(
                getString(R.string.adblock_master),
                getString(R.string.adblock_stat, engine.blockedCount),
                engine.enabled
            ) { enabled ->
                engine.enabled = enabled
                AppServices.settings.setAdBlockEnabled(enabled)
                AppServices.refreshAdBlockWhitelist()
                render(latestRules)
            }
        )

        container.addView(CardList.sectionTitle(requireContext(), getString(R.string.adblock_source)))
        val custom = rules.firstOrNull { it.type == RuleType.CUSTOM }
        val builtinInfo = getString(
            R.string.adblock_builtin_desc,
            com.sii.browser.core.rules.BuiltinRules.RULES.size
        )
        card(
            switchRow(
                getString(R.string.adblock_builtin),
                builtinInfo,
                AppServices.settings.builtinRulesEnabled(),
                enabled = engine.enabled
            ) { enabled ->
                AppServices.settings.setBuiltinRulesEnabled(enabled)
                viewLifecycleOwner.lifecycleScope.launch {
                    AppServices.ruleRepository.loadIntoEngine(AppServices.adBlock)
                    render(latestRules)
                }
            },
            navRow(
                getString(R.string.adblock_custom),
                custom?.lineCount?.toString() ?: "0"
            ) { openCustomRules() },
            navRow(
                getString(R.string.adblock_subscriptions),
                getString(R.string.adblock_subscriptions_desc, subs.size)
            ) { openSubscriptions() }
        )

        // 拦截明细放在规则来源之后，作为诊断信息。
        val stats = engine.blockedHostStats()
        if (stats.isNotEmpty()) {
            container.addView(CardList.sectionTitle(requireContext(), getString(R.string.adblock_stats_title)))
            card(
                *stats.map { (host, count) ->
                    CardList.row(requireContext()).apply {
                        addView(TextView(requireContext()).apply {
                            text = host
                            setTextSize(13f)
                            setTextColor(requireContext().getColor(R.color.text_primary))
                            maxLines = 1
                            layoutParams = LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                            )
                        })
                        addView(TextView(requireContext()).apply {
                            text = getString(R.string.adblock_stats_count, count)
                            setTextSize(12f)
                            setTextColor(requireContext().getColor(R.color.text_secondary))
                        })
                    }
                }.toTypedArray()
            )
        }
    }

    private fun card(vararg rows: View) {
        val card = CardList.styledCard(requireContext(), page = true)
        CardList.addRows(card, rows.toList())
        container.addView(card)
    }

    private fun switchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        enabled: Boolean = true,
        onChange: (Boolean) -> Unit
    ): View = CardList.row(requireContext()).apply {
        alpha = if (enabled) 1f else 0.4f
        addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(requireContext()).apply {
                text = title
                setTextSize(14f)
                setTextColor(requireContext().getColor(R.color.text_primary))
            })
            addView(TextView(requireContext()).apply {
                text = subtitle
                setTextSize(11.5f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
                setPadding(0, dp(2), 0, 0)
            })
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        addView(Switch(requireContext()).apply {
            trackDrawable = androidx.core.content.ContextCompat.getDrawable(
                requireContext(), R.drawable.switch_track
            )
            thumbDrawable = androidx.core.content.ContextCompat.getDrawable(
                requireContext(), R.drawable.switch_thumb
            )
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(26))
            isChecked = checked
            isEnabled = enabled
            setOnCheckedChangeListener { _, value -> if (enabled) onChange(value) }
        })
    }

    private fun navRow(title: String, subtitle: String, onClick: () -> Unit): View =
        CardList.row(requireContext()).apply {
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(requireContext()).apply {
                    text = title
                    setTextSize(14f)
                    setTextColor(requireContext().getColor(R.color.text_primary))
                })
                addView(TextView(requireContext()).apply {
                    text = subtitle
                    setTextSize(11.5f)
                    setTextColor(requireContext().getColor(R.color.text_secondary))
                    setPadding(0, dp(2), 0, 0)
                })
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            addView(TextView(requireContext()).apply {
                text = "›"
                setTextSize(18f)
                setTextColor(requireContext().getColor(R.color.text_secondary))
            })
            setOnClickListener { onClick() }
        }

    private fun openCustomRules() {
        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(R.id.shell_root, CustomRulesFragment())
            .addToBackStack(null)
            .commit()
    }

    private fun openSubscriptions() {
        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(R.id.shell_root, SubscriptionFragment())
            .addToBackStack(null)
            .commit()
    }

    private fun showMenu() {
        AlertDialog.Builder(requireContext())
            .setItems(
                arrayOf(getString(R.string.rules_export), getString(R.string.rules_import))
            ) { _, which ->
                if (which == 0) exportRules() else importRules()
            }
            .show()
    }

    private fun exportRules() {
        viewLifecycleOwner.lifecycleScope.launch {
            val customRule = latestRules.firstOrNull { it.type == RuleType.CUSTOM }
            val custom = customRule?.let { AppServices.ruleRepository.contentOf(it.id) }.orEmpty()
            val subsArray = org.json.JSONArray()
            latestRules.filter { it.type == RuleType.SUBSCRIPTION }.forEach { rule ->
                subsArray.put(AppServices.ruleRepository.contentOf(rule.id))
            }
            val json = org.json.JSONObject().apply {
                put("custom", custom)
                put("subscriptions", subsArray)
            }.toString()
            val name = "sii-rules-${System.currentTimeMillis()}.json"
            val path = withContext(kotlinx.coroutines.Dispatchers.IO) {
                FileExchange.writeToDownloads(requireContext(), name, json)
            }
            Toast.makeText(
                requireContext(),
                if (path != null) getString(R.string.data_exported_path, path)
                else getString(R.string.rules_import_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun importRules() {
        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    private fun applyRulesImport(uri: android.net.Uri) {
        val text = FileExchange.readText(requireContext(), uri)
        if (text.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.rules_import_empty, Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = runCatching {
                val json = org.json.JSONObject(text)
                AppServices.ruleRepository.saveCustomRules(json.optString("custom"))
                json.optJSONArray("subscriptions")?.let { subs ->
                    for (i in 0 until subs.length()) {
                        AppServices.ruleRepository.addSubscription(subs.optString(i))
                    }
                }
                true
            }.getOrDefault(false)
            if (ok) AppServices.ruleRepository.loadIntoEngine(AppServices.adBlock)
            Toast.makeText(
                requireContext(),
                if (ok) R.string.rules_imported else R.string.rules_import_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
