// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.sii.browser.R
import com.sii.browser.data.db.PasswordItem
import com.sii.browser.di.AppServices
import kotlinx.coroutines.launch

/**
 * 密码条目详情/编辑：站点、用户名、密码，另提供填充到当前页面与删除。
 */
class PasswordDetailFragment : Fragment() {

    private var itemId = 0L
    private var existing: PasswordItem? = null
    private lateinit var siteInput: EditText
    private lateinit var userInput: EditText
    private lateinit var passInput: EditText
    private lateinit var showToggle: TextView
    private var fillRow: View? = null
    private var passwordVisible = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        itemId = arguments?.getLong(ARG_ID) ?: 0L
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(R.color.bg))
        }
        root.addView(
            PageHeader.build(
                context,
                getString(if (itemId == 0L) R.string.pwd_new_title else R.string.pwd_detail_title),
                onBack = { parentFragmentManager.popBackStack() },
                actionLabel = getString(R.string.save),
                onAction = { save() }
            )
        )
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(context.getColor(R.color.divider))
        })
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(20))
        }
        root.addView(
            CardList.scroll(context, content),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        siteInput = input(getString(R.string.pwd_site), InputType.TYPE_CLASS_TEXT)
        userInput = input(getString(R.string.pwd_username), InputType.TYPE_CLASS_TEXT)
        passInput = input(
            getString(R.string.pwd_password),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        showToggle = TextView(context).apply {
            text = getString(R.string.pwd_show)
            setTextSize(12.5f)
            setTextColor(ThemeAccent.color(context))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { setPasswordVisible(!passwordVisible) }
        }

        val fields = CardList.pageCard(context)
        CardList.addRows(
            fields,
            listOf(
                fieldRow(getString(R.string.pwd_site), siteInput),
                fieldRow(getString(R.string.pwd_username), userInput),
                fieldRow(getString(R.string.pwd_password), passInput, showToggle)
            )
        )
        content.addView(fields)

        val actions = CardList.pageCard(context)
        val fill = CardList.row(context).apply {
            addView(TextView(context).apply {
                text = getString(R.string.pwd_fill_current)
                setTextSize(14f)
                setTextColor(context.getColor(R.color.text_primary))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { fillIntoPage() }
        }
        fillRow = fill
        val delete = CardList.row(context).apply {
            addView(TextView(context).apply {
                text = getString(R.string.delete)
                setTextSize(14f)
                setTextColor(context.getColor(R.color.danger))
            })
            setOnClickListener { confirmDelete() }
        }
        if (itemId == 0L) {
            CardList.addRows(actions, listOf(fill))
        } else {
            CardList.addRows(actions, listOf(fill, delete))
        }
        content.addView(actions)

        siteInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                applyFillState()
            }
        })
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (itemId == 0L) {
            AppServices.currentPageHost?.invoke()?.let { siteInput.setText(it) }
            applyFillState()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val item = AppServices.passwordRepository.byId(itemId) ?: return@launch
            existing = item
            siteInput.setText(item.host)
            userInput.setText(item.username)
            val plain = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                AppServices.passwordRepository.decrypt(item)
            }
            if (!isAdded) return@launch
            passInput.setText(plain.orEmpty())
            applyFillState()
        }
    }

    override fun onResume() {
        super.onResume()
        applyFillState()
    }

    private fun input(hint: String, inputType: Int): EditText {
        val context = requireContext()
        return EditText(context).apply {
            this.hint = hint
            this.inputType = inputType
            setTextSize(14f)
            setPadding(0, dp(6), 0, dp(6))
            isSingleLine = true
            background = null
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
    }

    private fun fieldRow(label: String, field: EditText, action: View? = null): View {
        val context = requireContext()
        return CardList.row(context).apply {
            addView(TextView(context).apply {
                text = label
                setTextSize(14f)
                setTextColor(context.getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(field)
            if (action != null) addView(action)
        }
    }

    private fun setPasswordVisible(visible: Boolean) {
        passwordVisible = visible
        passInput.transformationMethod =
            if (visible) null else android.text.method.PasswordTransformationMethod()
        passInput.setSelection(passInput.text.length)
        showToggle.text = getString(if (visible) R.string.pwd_hide else R.string.pwd_show)
    }

    /** 当前网页域名与条目站点一致时才可填充。 */
    private fun fillAvailable(): Boolean {
        if (AppServices.fillPassword == null) return false
        val pageHost = AppServices.currentPageHost?.invoke()?.removePrefix("www.") ?: return false
        val siteHost = normalizeHost(siteInput.text.toString())
        return siteHost.isNotEmpty() && siteHost.equals(pageHost, true)
    }

    private fun applyFillState() {
        val row = fillRow ?: return
        val available = fillAvailable()
        row.alpha = if (available) 1f else 0.4f
    }

    private fun fillIntoPage() {
        if (!fillAvailable()) {
            Toast.makeText(requireContext(), R.string.pwd_fill_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val username = userInput.text.toString()
        val password = passInput.text.toString()
        if (password.isEmpty()) {
            Toast.makeText(requireContext(), R.string.pwd_required, Toast.LENGTH_SHORT).show()
            return
        }
        val fill = AppServices.fillPassword ?: return
        fill(username, password)
        Toast.makeText(requireContext(), R.string.pwd_filled, Toast.LENGTH_SHORT).show()
        closeToBrowser()
    }

    private fun save() {
        val host = normalizeHost(siteInput.text.toString())
        val username = userInput.text.toString().trim()
        val password = passInput.text.toString()
        if (host.isEmpty() || password.isEmpty()) {
            Toast.makeText(requireContext(), R.string.pwd_required, Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val item = existing
            val ok = if (item == null) {
                AppServices.passwordRepository.save(host, username, password)
            } else {
                AppServices.passwordRepository.update(item, host, username, password)
            }
            if (!isAdded) return@launch
            if (ok) {
                Toast.makeText(requireContext(), R.string.pwd_saved, Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            } else {
                Toast.makeText(requireContext(), R.string.password_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDelete() {
        val item = existing ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pwd_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    AppServices.passwordRepository.delete(item.id)
                    if (isAdded) parentFragmentManager.popBackStack()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun closeToBrowser() {
        AppServices.passwordUnlockedAt = 0L
        val fm = parentFragmentManager
        if (!fm.popBackStackImmediate(PasswordFragment.BACK_STACK, FragmentManager.POP_BACK_STACK_INCLUSIVE)) {
            fm.popBackStack()
        }
    }

    private fun normalizeHost(raw: String): String {
        val value = raw.trim().lowercase()
        if (value.isEmpty()) return ""
        val withScheme = if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            "https://$value"
        }
        return runCatching { android.net.Uri.parse(withScheme).host.orEmpty() }
            .getOrDefault("")
            .removePrefix("www.")
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ARG_ID = "password_id"
    }
}
