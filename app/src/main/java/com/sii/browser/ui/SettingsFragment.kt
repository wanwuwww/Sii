package com.sii.browser.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import com.sii.browser.R

/**
 * 设置一级页：5 个分组入口（通用 / 隐私与安全 / 外观与主页 / 高级 / 关于），
 * 点击进入对应的二级页 [SettingsDetailFragment]。
 */
class SettingsFragment : Fragment() {

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
        val container = view.findViewById<LinearLayout>(R.id.settings_container)
        container.removeAllViews()
        val card = CardList.settingsCard(
            requireContext(), com.sii.browser.di.AppServices.settings.settingsCardAlpha()
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
