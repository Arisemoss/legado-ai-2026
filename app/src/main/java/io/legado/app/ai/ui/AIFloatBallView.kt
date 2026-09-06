package io.legado.app.ai.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.service.help.ReadBook
import io.legado.app.utils.getPrefFloat
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefFloat
import io.legado.app.utils.putPrefString

/**
 * 阅读页右下角 AI 悬浮球（RikkaHub/AssistiveTouch 风格）：
 * - 默认停靠右下角，可拖拽，松手自动吸附最近边缘并半透明化（贴边隐藏）
 * - 位置按「边 + 纵向比例」记忆，下次进入阅读自动恢复
 * - 点击携带当前书名/章节预设打开 AI 助手
 */
class AIFloatBallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    companion object {
        private const val DOCK_ALPHA = 0.5f
        private const val ACTIVE_ALPHA = 1f
        private const val SIZE_DP = 48
    }

    private val ball = ImageView(context)
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false
    private var dockAnimator: ValueAnimator? = null

    init {
        val size = (SIZE_DP * resources.displayMetrics.density).toInt()
        layoutParams = layoutParams ?: MarginLayoutParams(size, size)
        minimumWidth = size
        minimumHeight = size

        val pad = (10 * resources.displayMetrics.density).toInt()
        ball.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        ball.setPadding(pad, pad, pad, pad)
        ball.setBackgroundResource(R.drawable.ai_bg_send_circle)
        ball.setImageResource(R.drawable.ic_ai_float)
        ball.setColorFilter(androidx.core.content.ContextCompat.getColor(context, R.color.ai_on_accent_container))
        addView(ball)

        contentDescription = "AI 助手"
        alpha = DOCK_ALPHA
        post { restorePosition() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = downRawX
                lastRawY = downRawY
                dragging = false
                dockAnimator?.cancel()
                animate().alpha(ACTIVE_ALPHA).setDuration(120).start()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging &&
                    (dx * dx + dy * dy) > (8f * resources.displayMetrics.density) *
                    (8f * resources.displayMetrics.density)
                ) {
                    dragging = true
                }
                if (dragging) {
                    x += event.rawX - lastRawX
                    y += event.rawY - lastRawY
                    clampToParent()
                }
                lastRawX = event.rawX
                lastRawY = event.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dockToNearestEdge()
                } else {
                    animate().alpha(DOCK_ALPHA).setDuration(200).start()
                    performClick()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        val ctx = context
        ctx.startActivity(
            Intent(ctx, AgentHubActivity::class.java).apply {
                putExtra("preset_book", ReadBook.book?.name)
                putExtra(
                    "preset_chapter",
                    ReadBook.curTextChapter?.title ?: ReadBook.book?.durChapterTitle
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        return true
    }

    /** 吸附到最近的左右边缘，半透明贴边，并记忆位置 */
    private fun dockToNearestEdge() {
        val parent = parent as? ViewGroup ?: return
        val centerX = x + width / 2f
        val targetX = if (centerX < parent.width / 2f) 0f else (parent.width - width).toFloat()
        clampToParent()
        dockAnimator?.cancel()
        dockAnimator = ValueAnimator.ofFloat(x, targetX).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                this@AIFloatBallView.x = it.animatedValue as Float
            }
            start()
        }
        animate().alpha(DOCK_ALPHA).setDuration(220).start()
        savePosition(targetX)
    }

    private fun restorePosition() {
        val parent = parent as? ViewGroup ?: return
        val side = context.getPrefString(PreferKey.aiFloatBallSide, "R") ?: "R"
        val ratio = context.getPrefFloat(PreferKey.aiFloatBallYRatio, 0.72f)
        x = if (side == "L") 0f else (parent.width - width).toFloat()
        y = ratio.coerceIn(0f, 1f) * (parent.height - height)
        alpha = DOCK_ALPHA
    }

    private fun savePosition(dockedX: Float) {
        val parent = parent as? ViewGroup ?: return
        val side = if (dockedX <= 0f) "L" else "R"
        val maxY = (parent.height - height).coerceAtLeast(1)
        val ratio = (y / maxY).coerceIn(0f, 1f)
        context.putPrefString(PreferKey.aiFloatBallSide, side)
        context.putPrefFloat(PreferKey.aiFloatBallYRatio, ratio)
    }

    private fun clampToParent() {
        val p = parent as? ViewGroup ?: return
        x = x.coerceIn(0f, (p.width - width).toFloat().coerceAtLeast(0f))
        y = y.coerceIn(0f, (p.height - height).toFloat().coerceAtLeast(0f))
    }
}
