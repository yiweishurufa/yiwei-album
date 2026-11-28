package com.hark.shiguang.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 1.7.30：用户 2026-11-28「更换主题色会闪退」。
 * 根因：设置 → 外观 → 强调色色球选中动画用 Motion.liquid 弹簧（会过冲到约 1.08），绘制时直接 `c.copy(alpha = k)`，
 * Compose 的 Color 对超出 [0, 1] 的 alpha 抛 IllegalArgumentException → 每次点新色球都闪退（1.7.9 起）。
 * 这里覆盖：每个主题组合都能算出调色板、旧 / 坏存储值回落默认、每种弹簧的每一帧经 unitAlpha 后都是合法颜色，
 * 以及源码里不再把弹簧动画值直接当 alpha。
 */
class ThemeStateTest {

    @Test fun parseModeAcceptsKnownValuesAndFallsBack() {
        assertEquals(ThemeMode.DARK, ThemePrefs.parseMode("DARK"))
        assertEquals(ThemeMode.LIGHT, ThemePrefs.parseMode("LIGHT"))
        assertEquals(ThemeMode.SYSTEM, ThemePrefs.parseMode("SYSTEM"))
        assertEquals(ThemeMode.LIGHT, ThemePrefs.parseMode(" light "))
        // 未知 / 旧值 / 空值 → 默认深色，不抛异常
        listOf(null, "", "flat", "skeuo", "AMOLED", "dark_blue", "0", "浅色").forEach {
            assertEquals("值 $it", ThemeMode.DARK, ThemePrefs.parseMode(it))
        }
    }

    @Test fun accentIndexIsClamped() {
        assertEquals(0, ThemePrefs.accentIndex(null))
        assertEquals(0, ThemePrefs.accentIndex(-3))
        for (i in Accents.indices) assertEquals(i, ThemePrefs.accentIndex(i))
        assertEquals(Accents.lastIndex, ThemePrefs.accentIndex(Accents.size))
        assertEquals(Accents.lastIndex, ThemePrefs.accentIndex(99))
        assertEquals(Accents.lastIndex, ThemePrefs.accentIndex(Int.MAX_VALUE))
    }

    @Test fun everyThemeCombinationBuildsValidColors() {
        for (mode in ThemeMode.values()) for (accent in -1..Accents.size + 1) for (sys in listOf(true, false)) {
            val p = ThemePrefs.palette(mode, accent, sys)
            assertEquals("$mode/$accent/$sys dark", ThemePrefs.isDark(mode, sys), p.dark)
            val a = Accents[ThemePrefs.accentIndex(accent)]
            assertEquals(if (p.dark) a.color else a.soft, p.accent)
            // 设置页、底栏、色球里用到的派生色（固定系数 × 透明度）都必须是合法颜色
            listOf(p.bg, p.surface, p.surface2, p.line, p.text, p.sub, p.faint, p.accent, p.accent2, p.onAccent).forEach { c ->
                for (f in listOf(0f, 0.08f, 0.16f, 0.24f, 0.4f, 0.55f, 0.78f, 1f)) c.copy(alpha = unitAlpha(c.alpha * f))
            }
        }
    }

    @Test fun unitAlphaClampsAndHandlesNaN() {
        assertEquals(0f, unitAlpha(Float.NaN))
        assertEquals(0f, unitAlpha(-0.2f))
        assertEquals(1f, unitAlpha(1.08f))
        assertEquals(1f, unitAlpha(Float.POSITIVE_INFINITY))
        assertEquals(0.5f, unitAlpha(0.5f))
    }

    /** 根因复现：liquid 弹簧确实过冲 > 1，原样当 alpha 会抛异常；经 unitAlpha 后每一帧都合法。 */
    @Test fun springOvershootWasTheCrash() {
        val peak = samples(Motion.liquid(), 0f, 1f).maxOrNull()!!
        assertTrue("liquid 应过冲（实测峰值 $peak）", peak > 1.01f)
        try { Color.Red.copy(alpha = peak); fail("Compose 应拒绝 alpha=$peak") } catch (_: IllegalArgumentException) {}
        Color.Red.copy(alpha = unitAlpha(peak))
    }

    /** 每种动效 token、两个方向、每一帧：经 unitAlpha 后都能构造颜色。 */
    @Test fun everyMotionSpecFrameIsSafeAsAlpha() {
        val specs: Map<String, FiniteAnimationSpec<Float>> = mapOf(
            "liquid" to Motion.liquid(), "pop" to Motion.pop(), "materialize" to Motion.materialize(), "pressIn" to Motion.pressIn(),
            "pressOut" to Motion.pressOut(), "zoom" to Motion.zoom(), "sheet" to Motion.sheet(), "breathe" to Motion.breathe(),
            "settle" to Motion.settle(), "page" to Motion.page(), "move" to Motion.move(), "state" to Motion.state(),
            "enter" to Motion.enter(), "exit" to Motion.exit(),
        )
        for ((name, spec) in specs) for ((from, to) in listOf(0f to 1f, 1f to 0f, 0.4f to 1f, 0.8f to 1f)) {
            samples(spec, from, to).forEach { v ->
                assertTrue("$name $from→$to 出现 NaN", !v.isNaN())
                Accents.forEach { a -> a.color.copy(alpha = unitAlpha(v)); a.soft.copy(alpha = unitAlpha(0.40f * v)) }
            }
        }
    }

    /**
     * 源码守卫：用会过冲的弹簧（liquid / pop / materialize / pressOut / zoom / sheet / breathe / settle）驱动的值，
     * 不得不经夹取直接写进 copy(alpha = …)。
     */
    @Test fun noRawSpringValueUsedAsColorAlpha() {
        val root = listOf(File("src/main/java/com/hark/shiguang"), File("app/src/main/java/com/hark/shiguang")).first { it.isDirectory }
        val springs = "Motion\\.(liquid|pop|materialize|pressOut|zoom|sheet|breathe|settle)\\(\\)"
        val animVal = Regex("val (\\w+) by [^\\n]*animateFloatAsState\\([^\\n]*$springs")
        val animObj = Regex("(\\w+)\\.animateTo\\([^\\n]*$springs")
        val bad = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            val names = (animVal.findAll(text).map { it.groupValues[1] } + animObj.findAll(text).map { it.groupValues[1] + ".value" }).toSet()
            text.lines().forEachIndexed { i, line ->
                Regex("copy\\(alpha = ([^,)]*)").findAll(line).forEach { m ->
                    val expr = m.groupValues[1]
                    if ("unitAlpha" in expr || "coerce" in expr) return@forEach
                    names.forEach { n -> if (Regex("(^|[^\\w.])" + Regex.escape(n) + "($|[^\\w])").containsMatchIn(expr)) bad += "${f.name}:${i + 1} $expr" }
                }
            }
        }
        assertTrue("弹簧动画值直接当 alpha（会过冲抛异常）：$bad", bad.isEmpty())
    }

    private fun samples(spec: FiniteAnimationSpec<Float>, from: Float, to: Float): List<Float> {
        val anim = TargetBasedAnimation(spec, Float.VectorConverter, from, to)
        val end = anim.durationNanos
        val step = 4_000_000L
        return (0..(end / step)).map { anim.getValueFromNanos(it * step) } + anim.getValueFromNanos(end)
    }
}
