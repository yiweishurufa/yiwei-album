package com.hark.shiguang.ui

/**
 * 1.7.36 正在播放页「舞台」背景取色（纯计算，单测见 StageMathTest）。
 * 从封面取出的主色（[AmbientMath.pick]，灰图时用强调色）衍生 3 个色：主色、色相 +25°、色相 −25°，一深一浅错开（参照 QQ 音乐「魔法色」）。
 * 背景跟随深浅主题：深色主题压暗、浅色主题提亮；每个色都逐步调整到「正文色 ≥ 7:1、次要文字 ≥ 4.5:1」，
 * 按最坏情况（色块完全不透明、正好在文字后面）验算，所以文字永远压得住。
 */
internal object StageMath {
    /** 深色 / 浅色主题下的正文色与次要文字色（同 Theme.kt 两套调色板）。 */
    const val DARK_TEXT = 0xFFF3F5FA.toInt()
    const val DARK_SUB = 0xFFA7B0C2.toInt()
    const val LIGHT_TEXT = 0xFF0E1320.toInt()
    const val LIGHT_SUB = 0xFF4B5468.toInt()
    const val MIN_TEXT = 7.0
    const val MIN_SUB = 4.5

    fun palette(seed: Int, dark: Boolean): IntArray {
        val hsv = FloatArray(3)
        AmbientMath.rgbToHsv((seed shr 16) and 0xFF, (seed shr 8) and 0xFF, seed and 0xFF, hsv)
        val h = hsv[0]
        // （色相偏移，明度偏移）：主色居中，一个偏深、一个偏浅
        val plan = listOf(0f to 0f, 25f to -0.08f, -25f to 0.06f)
        return IntArray(3) { i ->
            val (dh, dv) = plan[i]
            val hue = ((h + dh) % 360f + 360f) % 360f
            if (dark) fit(hue, hsv[1].coerceIn(0.45f, 0.85f), (0.40f + dv).coerceIn(0.15f, 0.6f), DARK_TEXT, DARK_SUB, darker = true)
            else fit(hue, (hsv[1] * 0.55f).coerceIn(0.18f, 0.42f), (0.93f + dv / 2).coerceIn(0.8f, 1f), LIGHT_TEXT, LIGHT_SUB, darker = false)
        }
    }

    /** 从起点往「更深」（深色主题）或「更浅更淡」（浅色主题）一步步挪，直到两种文字都过线。 */
    private fun fit(h: Float, s0: Float, v0: Float, text: Int, sub: Int, darker: Boolean): Int {
        var s = s0; var v = v0
        repeat(80) {
            val c = AmbientMath.hsvToRgb(floatArrayOf(h, s, v))
            if (contrast(text, c) >= MIN_TEXT && contrast(sub, c) >= MIN_SUB) return c
            if (darker) v = (v - 0.02f).coerceAtLeast(0f)
            else { v = (v + 0.02f).coerceAtMost(1f); s = (s - 0.02f).coerceAtLeast(0f) }
        }
        return if (darker) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    private fun lin(c: Int): Double { val x = c / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
    fun luminance(c: Int): Double = 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * 三个色块中心的漂移（李萨如曲线，周期 18–24 秒）：[t] 秒 → 相对页面宽高的 (x, y)，范围大致 0..1。
     * 暂停时调用方不再推进 [t]，色块就停在原地。
     */
    fun center(i: Int, t: Float): Pair<Float, Float> {
        val base = arrayOf(0.22f to 0.18f, 0.82f to 0.38f, 0.38f to 0.86f)[i.coerceIn(0, 2)]
        val period = floatArrayOf(18f, 21f, 24f)[i.coerceIn(0, 2)]
        val w = (2.0 * Math.PI / period).toFloat()
        val amp = 0.16f
        return (base.first + amp * kotlin.math.sin(w * t + i * 1.7f)) to (base.second + amp * kotlin.math.cos(w * 0.8f * t + i * 0.9f))
    }
}
