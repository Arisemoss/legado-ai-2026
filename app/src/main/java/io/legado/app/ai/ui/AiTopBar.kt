package io.legado.app.ai.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import io.legado.app.R
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import splitties.views.topPadding

/**
 * AI 页面统一顶栏：主题主色背景 + 状态栏 insets + 返回 + 标题/副标题 + 右侧动作槽。
 *
 * 统一目标（修复「AI 各页各画各的顶栏、压在状态栏下」）：
 *  - 背景取 ThemeStore 主题主色，标题/图标自动用主色上的对比色（亮主色→黑、暗主色→白）；
 *  - 自己消费状态栏 insets（BaseActivity 默认 fullScreen，自定义顶栏必须自己补）；
 *  - 高度/字号/返回箭头与基座 TitleBar 对齐，替换 AI Hub / 运行日志 / 配置向导 三套旧顶栏。
 */
class AiTopBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val titleView = TextView(context)
    private val subtitleView = TextView(context)
    private val actions = LinearLayout(context)
    private var backListener: OnClickListener? = null

    /** 主色上的对比色：标题、返回箭头、动作图标统一使用 */
    val onPrimaryColor: Int =
        if (ColorUtils.isColorLight(context.primaryColor)) Color.BLACK else Color.WHITE

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(context.primaryColor)
        minimumHeight = dp(52)
        setOnApplyWindowInsetsListenerCompat { _, insets ->
            topPadding = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            insets
        }

        addView(ImageView(context).apply {
            val size = dp(44)
            layoutParams = LayoutParams(size, size)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(onPrimaryColor)
            contentDescription = "返回"
            setOnClickListener { v ->
                backListener?.onClick(v) ?: (context as? Activity)?.finish()
            }
        })

        val titles = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        titleView.apply {
            setTextColor(onPrimaryColor)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitleView.apply {
            setTextColor(onPrimaryColor)
            alpha = 0.75f
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = GONE
        }
        titles.addView(titleView)
        titles.addView(subtitleView)
        addView(titles)

        actions.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        addView(actions)
    }

    fun setTitle(text: CharSequence) {
        titleView.text = text
    }

    fun setSubtitle(text: CharSequence?) {
        subtitleView.text = text
        subtitleView.visibility = if (text.isNullOrBlank()) GONE else VISIBLE
    }

    fun setOnBackClickListener(listener: OnClickListener?) {
        backListener = listener
    }

    /** 右侧动作：矢量图标按钮，自动套用主色对比色 */
    fun addAction(iconRes: Int, desc: String, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            val size = dp(44)
            layoutParams = LayoutParams(size, size)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setImageResource(iconRes)
            setColorFilter(onPrimaryColor)
            contentDescription = desc
            setOnClickListener { onClick() }
            actions.addView(this)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
