package com.hark.shiguang.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.Store

/** 1.0.0 design system: one dark and one light palette, five accent colors (default dark + blue). */
enum class ThemeMode(val title: String) { LIGHT("浅色"), DARK("深色"), SYSTEM("跟随系统") }

data class Accent(val name: String, val color: Color, val soft: Color)
val Accents = listOf(
    Accent("蓝", Color(0xFF4F8CFF), Color(0xFF2F6BE0)),
    Accent("绿", Color(0xFF34C77B), Color(0xFF23A562)),
    Accent("橙", Color(0xFFF5A524), Color(0xFFD9860C)),
    Accent("粉", Color(0xFFF2617A), Color(0xFFD9475F)),
    Accent("紫", Color(0xFFA683F5), Color(0xFF8862E0)),
    Accent("琥珀", Color(0xFFF2A33A), Color(0xFFC97F12)),
)

data class Palette(
    val dark: Boolean,
    val bg: Color, val surface: Color, val surface2: Color, val line: Color,
    val text: Color, val sub: Color, val faint: Color,
    val accent: Color, val accent2: Color, val onAccent: Color,
    val gap: Dp = 2.dp, val corner: Dp = 4.dp, val card: Dp = 18.dp,
    val display: FontFamily = FontFamily.Default,
)

private fun darkPalette(a: Accent) = Palette(
    true, bg = Color(0xFF0F1013), surface = Color(0xFF18191D), surface2 = Color(0xFF232429), line = Color(0x17FFFFFF),
    text = Color(0xFFF1F2F4), sub = Color(0xFFA0A3AB), faint = Color(0xFF6F737B),
    accent = a.color, accent2 = a.soft, onAccent = Color.White,
)

/**
 * 用户 2026-10-23 锤子拟物风格：暖灰 / 米白的纸面底，卡片是带一点暖意的白；文字用带暖色相的墨色，不用纯黑。
 * 对比度（WCAG，脚本算）：text/surface 15.6:1，sub/surface 6.7:1，sub/bg 5.8:1；深色 13.6 / 6.4 / 7.3。
 */
private fun lightSkeuoPalette(a: Accent) = Palette(
    false, bg = Color(0xFFEDEAE4), surface = Color(0xFFFBFAF7), surface2 = Color(0xFFEAE6DF), line = Color(0x1C3A2A1A),
    text = Color(0xFF22201C), sub = Color(0xFF5E5951), faint = Color(0xFF8F8981),
    accent = a.soft, accent2 = a.color, onAccent = Color.White,
)

/** 深色拟物：底更深、卡片略亮，层级靠阴影 + 低透明白高光区分。 */
private fun darkSkeuoPalette(a: Accent) = Palette(
    true, bg = Color(0xFF151619), surface = Color(0xFF212226), surface2 = Color(0xFF2B2C31), line = Color(0x14FFFFFF),
    text = Color(0xFFEFEDE9), sub = Color(0xFFA8A49E), faint = Color(0xFF75726C),
    accent = a.color, accent2 = a.soft, onAccent = Color.White,
)

private fun lightPalette(a: Accent) = Palette(
    false, bg = Color(0xFFF3F4F6), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFECEEF1), line = Color(0x14000000),
    text = Color(0xFF14161A), sub = Color(0xFF5A5E66), faint = Color(0xFF8E929A),
    accent = a.soft, accent2 = a.color, onAccent = Color.White,
)

object ThemeState {
    var mode by mutableStateOf(runCatching { ThemeMode.valueOf(Store.themeMode) }.getOrDefault(ThemeMode.DARK))
    var accent by mutableIntStateOf(Store.accent.coerceIn(0, Accents.lastIndex))
    var systemDark by mutableStateOf(true)
    /** 用户 2026-10-23：界面风格 拟物（默认）/ 简洁。存储键 ui.style = "skeuo" / "flat"。 */
    var skeuo by mutableStateOf(Store.getStr("ui.style", "skeuo") != "flat")
    val isDark: Boolean get() = when (mode) { ThemeMode.LIGHT -> false; ThemeMode.DARK -> true; ThemeMode.SYSTEM -> systemDark }
    val current: Palette get() = Accents[accent].let {
        if (skeuo) { if (isDark) darkSkeuoPalette(it) else lightSkeuoPalette(it) }
        else if (isDark) darkPalette(it) else lightPalette(it)
    }
    fun pickStyle(skeuomorphic: Boolean) { skeuo = skeuomorphic; Store.putStr("ui.style", if (skeuomorphic) "skeuo" else "flat") }
    fun pickMode(m: ThemeMode) { mode = m; Store.themeMode = m.name }
    fun pickAccent(i: Int) { accent = i; Store.accent = i }
    fun reload() {
        mode = runCatching { ThemeMode.valueOf(Store.themeMode) }.getOrDefault(ThemeMode.DARK)
        accent = Store.accent.coerceIn(0, Accents.lastIndex)
    }
}

/** Theme tokens. Every getter reads snapshot state, so composables recompose on theme switch. */
object C {
    val P: Palette get() = ThemeState.current
    val Bg get() = P.bg
    val Surface get() = P.surface
    val Surface2 get() = P.surface2
    val Line get() = P.line
    val Text get() = P.text
    val Sub get() = P.sub
    val Faint get() = P.faint
    val Accent get() = P.accent
    val Accent2 get() = P.accent2
    val OnAccent get() = P.onAccent
    val Orange get() = Color(0xFFF5A524)
    val Violet get() = Color(0xFFA683F5)
    val Gold get() = Color(0xFFF5C044)
    val Green get() = Color(0xFF34C77B)
    val Pink get() = Color(0xFFF2617A)
    val Blue get() = Color(0xFF4F8CFF)
    val Danger get() = if (P.dark) Color(0xFFFF6B5E) else Color(0xFFD13B2A)
    val Scrim: Brush get() = Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))
    val TopScrim: Brush get() = Brush.verticalGradient(listOf(P.bg, P.bg.copy(alpha = 0.92f), P.bg.copy(alpha = 0f)))
    val Chip get() = if (P.dark) Color(0x14FFFFFF) else Color(0x0D000000)
    val Gap get() = P.gap
    val Corner get() = P.corner
    val Card get() = P.card
}

/**
 * 1.1.0 视觉规范 token（新代码只引用这些，不写裸数值）。产品气质：安静、干净，把照片放在最前面。
 * - 颜色：C.*（主色只用于主操作、选中态、关键数字；功能色 Danger / Green / Gold）。
 * - 间距 [Space]：4 的倍数，同组小、组间大。
 * - 圆角 [Radius]：三级 + 底部面板。
 * - 字号 [TextSize]：6 级，比例约 1.2；字重最多 Normal / Medium / SemiBold(Bold 只给大标题)。
 */
object Space {
    val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val xxl = 32.dp
    /** Page side margin. */
    val page = 16.dp
    /** Minimum touch target (Material). */
    val touch = 48.dp
}

object Radius {
    /** Chips, small badges. */
    val s = 8.dp
    /** Buttons, inputs, list rows. */
    val m = 12.dp
    /** Cards, tiles (= C.Card). */
    val l = 18.dp
    /** Top corners of bottom sheets. */
    val sheet = 28.dp
}

object TextSize {
    val caption = 12.sp; val body = 14.sp; val bodyL = 15.sp; val title = 17.sp; val headline = 20.sp; val display = 30.sp
}

private fun typo(p: Palette) = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, color = p.text),
    headlineSmall = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = p.text),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = p.text),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.text),
    bodyMedium = TextStyle(fontSize = 14.sp, color = p.text),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, color = p.sub),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = p.sub, letterSpacing = 0.2.sp),
)

@Composable
fun ShiGuangTheme(content: @Composable () -> Unit) {
    ThemeState.systemDark = isSystemInDarkTheme()
    val p = ThemeState.current
    val bg by animateColorAsState(p.bg, tween(300), label = "bg")
    val scheme = if (p.dark) darkColorScheme(
        primary = p.accent, secondary = p.accent2, tertiary = p.accent2, background = p.bg, surface = p.surface,
        surfaceVariant = p.surface2, onBackground = p.text, onSurface = p.text, onPrimary = p.onAccent,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surface2, surfaceContainerLow = p.surface, onSurfaceVariant = p.sub,
    ) else lightColorScheme(
        primary = p.accent, secondary = p.accent2, tertiary = p.accent2, background = p.bg, surface = p.surface,
        surfaceVariant = p.surface2, onBackground = p.text, onSurface = p.text, onPrimary = p.onAccent,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surface2, surfaceContainerLow = p.surface, onSurfaceVariant = p.sub,
    )
    // 对话框、底部面板的圆角统一（AlertDialog 用 extraLarge）
    val shapes = androidx.compose.material3.Shapes(extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(if (ThemeState.skeuo) 22.dp else 28.dp))
    MaterialTheme(colorScheme = scheme, typography = typo(p), shapes = shapes) {
        Box(Modifier.fillMaxSize().background(bg)) { content() }
    }
}
