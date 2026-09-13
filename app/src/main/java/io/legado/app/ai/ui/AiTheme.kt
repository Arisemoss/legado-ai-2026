package io.legado.app.ai.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.ColorUtils

/**
 * AI 页面主题色工具。
 *
 * 为什么不能只用 ?attr/colorPrimary：本应用主题色是「运行时可换」的——ThemeStore 把用户选的颜色
 * 存在 pref 里、布局与代码取色都走 ThemeStore.primaryColor，而主题属性 colorPrimary 仍是静态默认色
 * （@color/primary = md_light_blue_600）。直接依赖 ?attr 会出现「顶栏跟随用户主题、气泡/悬浮球却是默认蓝」
 * 的割裂（真机已复现）。所以统一在这里从 ThemeStore 取色并生成 drawable。
 */
object AiTheme {

    fun primary(context: Context): Int = context.primaryColor

    /** 主色上的对比色（亮主色→黑，暗主色→白） */
    fun onPrimary(context: Context): Int =
        if (ColorUtils.isColorLight(context.primaryColor)) Color.BLACK else Color.WHITE

    /** 实心圆：发送按钮 / 悬浮球 / AI 头像 */
    fun circle(context: Context, color: Int = primary(context)): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    /** 用户气泡：主色底，右下角小圆角（与 ai_bg_bubble_user 造型一致） */
    fun userBubble(context: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(primary(context))
        val r = dp(context, 20f)
        val small = dp(context, 6f)
        cornerRadii = floatArrayOf(r, r, r, r, small, small, r, r)
    }

    /** 圆角实心主色块：确认卡的「同意执行」按钮 */
    fun roundedPrimary(context: Context, radiusDp: Float = 22f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(primary(context))
            cornerRadius = dp(context, radiusDp)
        }

    /** chip：卡片底 + 主色描边（替代 ai_bg_chip 的 ?attr 描边） */
    fun chip(context: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(ContextCompat.getColor(context, R.color.background_card))
        setStroke(dp(context, 1f).toInt().coerceAtLeast(1), primary(context))
        cornerRadius = dp(context, 18f)
    }

    private fun dp(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density
}
