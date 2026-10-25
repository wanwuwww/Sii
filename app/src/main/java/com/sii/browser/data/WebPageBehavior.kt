package com.sii.browser.data

/**
 * 网页行为位掩码开关。
 * 一个 int 的每一位表示一个网页行为开关（JavaScript、图片、位置、剪贴板等）。
 * 位的具体语义由设置页模块（原 x9 包）逐位确认后命名。
 */
class WebPageBehavior(value: Int) {

    companion object {
        /** 位 1：软键盘弹出时自动缩放内容。 */
        const val BIT_AUTO_RESIZE = 1

        /** 位 2：无图模式（不自动加载图片）。 */
        const val BIT_NO_IMAGE = 2
    }

    var value: Int = value
        private set

    fun isEnabled(bit: Int): Boolean = (value and bit) == bit

    fun setEnabled(bit: Int, enabled: Boolean) {
        value = if (enabled) bit or value else (bit.inv()) and value
    }

    fun setValue(newValue: Int) {
        value = newValue
    }

    override fun equals(other: Any?): Boolean =
        other is WebPageBehavior && other.value == value

    override fun hashCode(): Int = value

    override fun toString(): String = "WebPageBehavior{value=$value}"
}
