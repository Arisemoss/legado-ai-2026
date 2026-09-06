package io.legado.app.ai.ui

import android.graphics.Bitmap

/**
 * 毛玻璃效果（纯软件实现）：
 * 编译目标 SDK 29 下无法使用 RenderEffect(API31)/RenderScript(已弃用)，
 * 这里采用经典 frosted-glass 技法——降采样 → 多趟盒式模糊 → 交由 ImageView
 * 放大铺满。成本极低（一次性、百像素级运算），观感接近高斯模糊，
 * 全部 API ≥21 可用，无需任何额外依赖。
 */
object GlassEffect {

    /** 返回重度模糊的小尺寸位图；调用方交给 ImageView 缩放显示即为磨砂背景 */
    fun frosted(src: Bitmap, maxSide: Int = 128, radius: Int = 6, passes: Int = 3): Bitmap {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val w = Math.max(1, (src.width * scale).toInt())
        val h = Math.max(1, (src.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        if (w < 3 || h < 3 || radius <= 0) return small
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(passes.coerceAtLeast(1)) {
            boxBlurH(px, w, h, radius)
            boxBlurV(px, w, h, radius)
        }
        small.setPixels(px, 0, w, 0, 0, w, h)
        return small
    }

    /** 水平盒式模糊（滑动窗口 O(n)），结果写回 px */
    private fun boxBlurH(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        val n = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val p = px[row + i.coerceIn(0, w - 1)]
                a += p ushr 24 and 0xFF; rr += p ushr 16 and 0xFF; g += p ushr 8 and 0xFF; b += p and 0xFF
            }
            for (x in 0 until w) {
                tmp[row + x] = (a / n shl 24) or (rr / n shl 16) or (g / n shl 8) or (b / n)
                val pOut = px[row + (x - r).coerceIn(0, w - 1)]
                val pIn = px[row + (x + r + 1).coerceIn(0, w - 1)]
                a += (pIn ushr 24 and 0xFF) - (pOut ushr 24 and 0xFF)
                rr += (pIn ushr 16 and 0xFF) - (pOut ushr 16 and 0xFF)
                g += (pIn ushr 8 and 0xFF) - (pOut ushr 8 and 0xFF)
                b += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
        System.arraycopy(tmp, 0, px, 0, px.size)
    }

    /** 垂直盒式模糊（滑动窗口 O(n)），结果写回 px */
    private fun boxBlurV(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        val n = 2 * r + 1
        for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val p = px[i.coerceIn(0, h - 1) * w + x]
                a += p ushr 24 and 0xFF; rr += p ushr 16 and 0xFF; g += p ushr 8 and 0xFF; b += p and 0xFF
            }
            for (y in 0 until h) {
                tmp[y * w + x] = (a / n shl 24) or (rr / n shl 16) or (g / n shl 8) or (b / n)
                val pOut = px[(y - r).coerceIn(0, h - 1) * w + x]
                val pIn = px[(y + r + 1).coerceIn(0, h - 1) * w + x]
                a += (pIn ushr 24 and 0xFF) - (pOut ushr 24 and 0xFF)
                rr += (pIn ushr 16 and 0xFF) - (pOut ushr 16 and 0xFF)
                g += (pIn ushr 8 and 0xFF) - (pOut ushr 8 and 0xFF)
                b += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
        System.arraycopy(tmp, 0, px, 0, px.size)
    }
}
