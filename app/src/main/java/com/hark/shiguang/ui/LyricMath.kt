package com.hark.shiguang.ui

import kotlin.math.abs

/**
 * 1.7.35 歌词 v2 的纯计算（单测见 LyricMathTest）：
 * - 距离当前句越远越淡（[alpha]），12+ 近处几行轻模糊（[blurDp]，最多 3dp，只给 1–4 行）；
 * - 换句时各行错开落位（[lag]）：列表先一步滚到位，每行再带着自己的延迟从旧位置滑回来；
 * - 字号随系统字体放大，但有上限（[fontSp]），免得一行只放得下几个字。
 */
internal object LyricMath {
    /** 当前句字号（sp，100% 字体时）。 */
    const val BASE_SP = 28f
    /** 放大字体后最多相当于这么大（sp × 字体缩放）。 */
    const val MAX_SP = 34f
    /** 换句时每行的落位时长（ms）。 */
    const val LAG_MS = 460
    /** 用户手动滑动后多久回到跟随（ms）。 */
    const val RESUME_MS = 3000L

    /**
     * 透明度：当前 1.0，相邻 0.55，再远 0.45，3 行以外 0.4（深色）。浅色主题同样的透明度对比度偏低，整体抬高（0.65 / 0.55 / 0.52）。
     * 验算（白字 #F3F5FA 在 #070A12 上 / 墨字 #0E1320 在 #EEF2F8 上）：最远行深色 3.6:1、浅色约 3.6:1，远处句子仍认得出；必须读的当前句是满色（≥ 16:1）。
     * 手动滑动（不跟随）时统一 0.75，方便找句子。
     */
    fun alpha(distance: Int, following: Boolean, light: Boolean = false): Float = when {
        !following -> 0.75f
        distance <= 0 -> 1f
        distance == 1 -> if (light) 0.65f else 0.55f
        distance == 2 -> if (light) 0.55f else 0.45f
        else -> if (light) 0.52f else 0.4f
    }

    /** 模糊半径（dp）：只给离当前 1–4 行的少量行，越远越糊，最多 3dp；当前句与更远的行不糊（更远的本来就在视口边缘、也省 GPU）。 */
    fun blurDp(distance: Int): Float = when (distance) {
        1 -> 0.8f
        2 -> 1.6f
        3 -> 2.4f
        4 -> 3f
        else -> 0f
    }

    /** 字号（sp，交给 Compose 时还会乘字体缩放）：保证 BASE × 缩放 ≤ MAX。 */
    fun fontSp(fontScale: Float): Float {
        val s = if (fontScale.isNaN() || fontScale <= 0f) 1f else fontScale
        return if (BASE_SP * s <= MAX_SP) BASE_SP else MAX_SP / s
    }

    /** 第 [index] 行相对当前句 [focus] 的起步延迟：上面的（已唱过的）先动，往下每行晚 [step] ms，最多 [maxSteps] 档。 */
    fun delayMs(index: Int, focus: Int, step: Int = Motion.STAGGER, maxSteps: Int = Motion.STAGGER_MAX): Int =
        (index - focus + 1).coerceIn(0, maxSteps) * step

    /** 落位进度 0..1（缓出三次方）：[tMs] 换句后过去的时间。 */
    fun settle(tMs: Float, delayMs: Int, durMs: Int = LAG_MS): Float {
        if (durMs <= 0) return 1f
        val x = ((tMs - delayMs) / durMs).coerceIn(0f, 1f)
        val k = 1f - x
        return 1f - k * k * k
    }

    /** 这一行此刻还要往回偏多少像素：[shift] 是这次列表一下子滚过的距离。 */
    fun lag(shift: Float, tMs: Float, delayMs: Int, durMs: Int = LAG_MS): Float = shift * (1f - settle(tMs, delayMs, durMs))

    /** 两次当前句之间隔得太远（拖进度 / 点歌词跳转）：直接定位，不做错开滚动。 */
    fun isJump(prevFocus: Int, focus: Int): Boolean = prevFocus < 0 || abs(focus - prevFocus) > 2

    /** 间奏三颗点：第 [i] 颗（0..2）在进度 [p] 时亮了多少（0..1）。 */
    fun dot(i: Int, p: Float): Float = ((p * 3f) - i).coerceIn(0f, 1f)
}
