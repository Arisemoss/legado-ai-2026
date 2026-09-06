package io.legado.app.ai.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * RikkaHub「极光」聊天背景的 View 版移植（Gemini 风动态渐变）：
 * 底层线性渐变 + 数个正弦漂移的径向光斑，无 blur 依赖、全 API 级别可用。
 * 光斑相位基于系统时钟连续推进，动画仅驱动重绘；不可见时自动暂停。
 */
class AuroraBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var darkMode = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var baseShader: Shader? = null
    private val blobShaders = ArrayList<Shader>()

    // 光斑参数：初始位置比例(x,y)、半径比例、角速度(rad/s)、颜色
    private class Blob(
        val fx: Float, val fy: Float, val fr: Float,
        val speed: Float, val phase0: Float,
        val driftX: Float, val driftY: Float,
        val colorLight: Int, val colorDark: Int
    )

    private val blobs = listOf(
        Blob(0.22f, 0.28f, 0.55f, 0.35f, 0.0f, 120f, 80f, 0xFF9EC5F0.toInt(), 0xFF3E6FB0.toInt()),
        Blob(0.82f, 0.18f, 0.48f, -0.27f, 1.6f, -90f, 110f, 0xFFA8E6E0.toInt(), 0xFF2E7D74.toInt()),
        Blob(0.62f, 0.72f, 0.60f, 0.22f, 3.1f, 140f, -70f, 0xFFC7D8F7.toInt(), 0xFF2B4A78.toInt()),
        Blob(0.15f, 0.86f, 0.45f, -0.31f, 4.4f, -110f, -90f, 0xFFF3D9C4.toInt(), 0xFF54456B.toInt())
    )

    private val animator = ValueAnimator.ofFloat(0f, 60f).apply {
        duration = 60_000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    init {
        rebuildShaders()
    }

    /** 亮暗模式切换后调用以重建配色 */
    fun setDarkMode(dark: Boolean) {
        if (darkMode == dark) return
        darkMode = dark
        rebuildShaders()
        invalidate()
    }

    private fun rebuildShaders() {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (darkMode) {
            baseShader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xFF1B2A45.toInt(), 0xFF15223A.toInt(), 0xFF0D1626.toInt(), 0xFF080B12.toInt()),
                floatArrayOf(0f, 0.30f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            baseShader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xFFAFD0F2.toInt(), 0xFFCBE0F6.toInt(), 0xFFF1F7FD.toInt(), Color.WHITE),
                floatArrayOf(0f, 0.24f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        blobShaders.clear()
        val maxDim = maxOf(w, h).toFloat()
        blobs.forEach { b ->
            val r = b.fr * maxDim
            val c = if (darkMode) b.colorDark else b.colorLight
            // 中心有色 → 边缘透明，天生柔和
            blobShaders.add(
                RadialGradient(r, r, r, c, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        baseShader?.let {
            paint.shader = it
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        val t = System.nanoTime() / 1_000_000_000f
        val w = width.toFloat()
        val h = height.toFloat()
        val maxDim = maxOf(width, height).toFloat()
        blobs.forEachIndexed { i, b ->
            val shader = blobShaders.getOrNull(i) ?: return@forEachIndexed
            val ang = b.phase0 + t * b.speed
            val cx = (b.fx + b.driftX * sin(ang) / maxDim.coerceAtLeast(1f)) * w
            val cy = (b.fy + b.driftY * cos(ang) / maxDim.coerceAtLeast(1f)) * h
            val r = b.fr * maxDim
            val save = canvas.save()
            canvas.translate(cx - r, cy - r)
            paint.shader = shader
            canvas.drawCircle(r, r, r, paint)
            canvas.restoreToCount(save)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // App 退后台时停帧省电；回前台自动恢复
        if (visibility == View.VISIBLE) animator.start() else animator.cancel()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
