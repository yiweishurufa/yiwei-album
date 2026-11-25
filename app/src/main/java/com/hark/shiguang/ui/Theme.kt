package com.hark.shiguang.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.R
import com.hark.shiguang.Store

/*
 * 1.6.0 设计语言「暗房与相纸」（完整规范见 docs/DESIGN.md）：
 * 界面是安静的中性灰（略带暖），颜色留给照片；强调色只出现在主操作、选中态和关键数字上。
 * 两种风格：质感（存储值 "skeuo"，默认）= 中性纸面 + 柔和小阴影 + 上沿高光线；简洁（"flat"）= 纯色面 + 发丝线。
 * 对比度由 /workspace/work/yiwei-album-iteration/redesign/contrast.py 计算，数值写在 DESIGN.md §2。
 */
enum class ThemeMode(val title: String) { LIGHT("浅色"), DARK("深色"), SYSTEM("跟随系统") }

/** [color] 用于深色主题（亮一档），[soft] 用于浅色主题（暗一档）；两者作文字时对卡片面都 ≥ 4.5:1。 */
data class Accent(val name: String, val color: Color, val soft: Color)
val Accents = listOf(
    Accent("蓝", Color(0xFF7AA7FF), Color(0xFF2F62D6)),
    Accent("绿", Color(0xFF4FCB8A), Color(0xFF17744A)),
    Accent("橙", Color(0xFFFFA45C), Color(0xFFA64E0F)),
    Accent("粉", Color(0xFFFF8AA0), Color(0xFFB22D4D)),
    Accent("紫", Color(0xFFB89CFF), Color(0xFF6F4BD8)),
    // 1.6.0：原「琥珀」和「橙」几乎同色，改成不带色相的「石墨」，让照片成为唯一的颜色（存储仍是索引 5）
    Accent("石墨", Color(0xFFD9D7D2), Color(0xFF3A3936)),
)

data class Palette(
    val dark: Boolean,
    val bg: Color, val surface: Color, val surface2: Color, val line: Color,
    val text: Color, val sub: Color, val faint: Color,
    val accent: Color, val accent2: Color, val onAccent: Color,
    val gap: Dp = 2.dp, val corner: Dp = 3.dp, val card: Dp = 20.dp,
    val display: FontFamily = FontFamily.Default,
)

/** 深色主题的强调色是浅色，按钮上的字用近黑（白字对比度只有 2 左右）。 */
private val InkOnAccent = Color(0xFF0E0E10)

// 简洁 · 深色：text/bg 17.1，sub/bg 7.7，faint/bg 4.5
private fun darkPalette(a: Accent) = Palette(
    true, bg = Color(0xFF0E0E10), surface = Color(0xFF18181B), surface2 = Color(0xFF222226), line = Color(0x14FFFFFF),
    text = Color(0xFFF2F1EE), sub = Color(0xFFA6A49F), faint = Color(0xFF7C7A76),
    accent = a.color, accent2 = a.soft, onAccent = InkOnAccent, display = Type.numFamily,
)

// 质感 · 浅色：中性纸面（只带一点暖），不再是米黄。text/surface 16.3，sub/surface 6.9，sub/bg 6.0
private fun lightSkeuoPalette(a: Accent) = Palette(
    false, bg = Color(0xFFEFECE6), surface = Color(0xFFFCFBF8), surface2 = Color(0xFFE6E3DC), line = Color(0x17000000),
    text = Color(0xFF1E1D1B), sub = Color(0xFF5A5853), faint = Color(0xFF83817B),
    accent = a.soft, accent2 = a.color, onAccent = Color.White, display = Type.numFamily,
)

// 质感 · 深色：层级靠面的明度台阶 + 发丝线，阴影只是辅助。text/surface 14.9，sub/surface 7.0
private fun darkSkeuoPalette(a: Accent) = Palette(
    true, bg = Color(0xFF121214), surface = Color(0xFF1D1D20), surface2 = Color(0xFF28282C), line = Color(0x17FFFFFF),
    text = Color(0xFFF2F1EE), sub = Color(0xFFA9A7A2), faint = Color(0xFF7E7C78),
    accent = a.color, accent2 = a.soft, onAccent = InkOnAccent, display = Type.numFamily,
)

// 简洁 · 浅色：text/surface 17.2，sub/surface 6.8，sub/bg 6.2
private fun lightPalette(a: Accent) = Palette(
    false, bg = Color(0xFFF5F4F1), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFECEBE7), line = Color(0x14000000),
    text = Color(0xFF1B1B1D), sub = Color(0xFF5C5B57), faint = Color(0xFF85847F),
    accent = a.soft, accent2 = a.color, onAccent = Color.White, display = Type.numFamily,
)

object ThemeState {
    var mode by mutableStateOf(runCatching { ThemeMode.valueOf(Store.themeMode) }.getOrDefault(ThemeMode.DARK))
    var accent by mutableIntStateOf(Store.accent.coerceIn(0, Accents.lastIndex))
    var systemDark by mutableStateOf(true)
    /** 界面风格 质感（默认）/ 简洁。存储键 ui.style = "skeuo" / "flat"（1.6.0 起「拟物」改名「质感」，键不变）。 */
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
    /** 强调色的浅底（选中的胶囊、底栏选中块）：强调色 14%。 */
    val AccentSoft get() = P.accent.copy(alpha = if (P.dark) 0.16f else 0.12f)
    // 功能色：只表达含义（成功 / 警告 / 错误），比原来低一档饱和度，放进中性界面里不跳
    val Orange get() = if (P.dark) Color(0xFFFFA45C) else Color(0xFFA64E0F)
    val Violet get() = if (P.dark) Color(0xFFB89CFF) else Color(0xFF6F4BD8)
    val Gold get() = if (P.dark) Color(0xFFF2C25B) else Color(0xFF8A6410)
    val Green get() = if (P.dark) Color(0xFF4FCB8A) else Color(0xFF17744A)
    val Pink get() = if (P.dark) Color(0xFFFF8AA0) else Color(0xFFB22D4D)
    val Blue get() = if (P.dark) Color(0xFF7AA7FF) else Color(0xFF2F62D6)
    val Danger get() = if (P.dark) Color(0xFFFF7468) else Color(0xFFC4372B)
    val Scrim: Brush get() = Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))
    val TopScrim: Brush get() = Brush.verticalGradient(listOf(P.bg, P.bg.copy(alpha = 0.92f), P.bg.copy(alpha = 0f)))
    /** 不带强调色的小控件底（圆形按钮、未选中胶囊）。 */
    val Chip get() = if (P.dark) Color(0x14FFFFFF) else Color(0x0D000000)
    val Gap get() = P.gap
    val Corner get() = P.corner
    val Card get() = P.card
}

/**
 * 间距：4 的倍数，同组小、组间大（unslop §1、layout-system）。页面左右边距 16，卡片内边距 16，列表行最小高 56。
 */
object Space {
    val xxs = 2.dp; val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val xxl = 32.dp; val xxxl = 48.dp
    /** Page side margin. */
    val page = 16.dp
    /** Minimum touch target (Material 48 dp). */
    val touch = 48.dp
    /** 列表行 / 设置行最小高度。 */
    val row = 56.dp
}

/** 圆角（unslop §7 嵌套圆角：内圆角 = 外圆角 − 内边距，最小 4）。 */
object Radius {
    /** 照片缩略图。 */
    val photo = 3.dp
    /** 角标、小徽章。 */
    val xs = 6.dp
    /** 胶囊、小控件、分段内的选中块。 */
    val s = 10.dp
    /** 按钮、输入框、列表行按压底。 */
    val m = 14.dp
    /** 卡片、封面（= C.Card）。 */
    val l = 20.dp
    /** 底部面板上沿、对话框。 */
    val sheet = 28.dp
}

/** 旧字号 token（保留给已有调用）。新代码用 MaterialTheme.typography 的角色。 */
object TextSize {
    val caption = 12.sp; val body = 14.sp; val bodyL = 15.sp; val title = 17.sp; val headline = 20.sp; val display = 32.sp
}

/**
 * 字体（typography-system、unslop §5）：中文正文用系统字体；数字与拉丁字母的展示位（月份大标题、年份、时长、计数）
 * 用 Allo（SIL OFL 1.1，只取了 ASCII 子集，约 28 KB/字重），并开 tabular 等宽数字，滚动时数字不跳。
 * Allo 没有的字（中文）由系统字体按字回退。
 */
object Type {
    val numFamily: FontFamily = FontFamily(
        Font(R.font.allo_num_medium, FontWeight.Medium),
        Font(R.font.allo_num_semibold, FontWeight.SemiBold),
        Font(R.font.allo_num_semibold, FontWeight.Bold),
    )
    const val TNUM = "tnum"
    /** 计数、时长、文件大小等：系统字体 + 等宽数字。 */
    val num: TextStyle get() = TextStyle(fontFeatureSettings = TNUM)
    /** 照片上的时长角标。 */
    val badge: TextStyle get() = TextStyle(fontFamily = numFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, fontFeatureSettings = TNUM)
}

/**
 * 字号阶梯（Material 3 角色，比例约 1.2；行高中文 1.4–1.5 倍）。字重只用 Normal / Medium / SemiBold：
 * 层级靠字号和颜色，不靠加粗（unslop §5）。小字不收紧字距；只有 ≥ 26sp 的展示字收 −0.02em 左右。
 */
private fun typo(p: Palette) = Typography(
    displayLarge = TextStyle(fontFamily = Type.numFamily, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp, color = p.text, fontFeatureSettings = Type.TNUM),
    displayMedium = TextStyle(fontFamily = Type.numFamily, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp, color = p.text, fontFeatureSettings = Type.TNUM),
    displaySmall = TextStyle(fontFamily = Type.numFamily, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, color = p.text, fontFeatureSettings = Type.TNUM),
    headlineLarge = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, color = p.text),
    headlineMedium = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = p.text),
    headlineSmall = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, color = p.text),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = p.text),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = p.text),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = p.text),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = p.text),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = p.text),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, color = p.sub),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = p.text),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = p.sub),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, color = p.sub, letterSpacing = 0.2.sp),
)

/**
 * 动效（emil-design-eng、interaction-physics、animate、choreograph、android.md）。所有动画参数集中在这里，页面不写裸数值。
 * - 按压：按下快（无回弹、约 100 ms 到位），松手稍慢带一点点余量；缩放 0.97（大卡片 0.985），不从 0 放大。
 * - 状态变化（颜色、选中）：180 ms 强 ease-out。进入 240 ms，退出更快 160 ms。
 * - 面板 / 页面 / 大图：弹簧（可被手势打断并保留速度），bounce ≤ 0.1。
 * - 系统「移除动画」（开发者选项动画时长 0）或「关闭动画」时 [reduced] = true：位移、缩放改成直接切换，只保留短淡入淡出。
 */
object Motion {
    /** 由 [ShiGuangTheme] 每次组合时从系统动画时长缩放读取。 */
    var reduced by mutableStateOf(false)
        internal set

    /** emil-design-eng 的强 ease-out：cubic-bezier(0.23, 1, 0.32, 1)。 */
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    /** 屏幕内位移：ease-in-out。 */
    val EaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

    const val FAST = 120
    const val STATE = 180
    const val ENTER = 240
    const val EXIT = 160
    const val PAGE = 300
    /** 列表进入的错开间隔（30–80 ms，只给前几项）。 */
    const val STAGGER = 32
    const val STAGGER_MAX = 6

    const val PRESS_SCALE = 0.97f
    const val PRESS_SCALE_LARGE = 0.985f

    fun <T> pressIn(): FiniteAnimationSpec<T> = if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 1600f)
    fun <T> pressOut(): FiniteAnimationSpec<T> = if (reduced) snap() else spring(dampingRatio = 0.75f, stiffness = 900f)
    /** 颜色 / 透明度 / 选中态。减少动画时仍保留（颜色变化不会引起晕动）。 */
    fun <T> state(): FiniteAnimationSpec<T> = tween(if (reduced) 90 else STATE, easing = EaseOut)
    fun <T> enter(): FiniteAnimationSpec<T> = if (reduced) tween(90) else tween(ENTER, easing = EaseOut)
    fun <T> exit(): FiniteAnimationSpec<T> = if (reduced) tween(60) else tween(EXIT, easing = EaseOut)
    /** 位移类（开关钮、指示块滑动）：临界阻尼弹簧，约 250 ms 到位。 */
    fun <T> move(): FiniteAnimationSpec<T> = if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 700f)
    /** 底部面板 / 对话框弹出：bounce 0.1。 */
    fun <T> sheet(): FiniteAnimationSpec<T> = if (reduced) tween(90) else spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
    /** 页面转场（共享 X 轴：位移 [PAGE_SHIFT] + 淡入）。 */
    fun <T> page(): FiniteAnimationSpec<T> = if (reduced) tween(90) else spring(dampingRatio = 1f, stiffness = 420f)
    /** 大图打开 / 关闭（共享元素式放大）：带一点点余量。 */
    fun <T> zoom(): FiniteAnimationSpec<T> = if (reduced) tween(90) else spring(dampingRatio = 0.86f, stiffness = 320f)
    /** 页面转场的横向位移（dp），只走一小段，不整屏推。 */
    val PAGE_SHIFT = 28.dp
    /** 列表 / 面板内容进入时上浮的距离。 */
    val RISE = 8.dp
}

@Composable
fun ShiGuangTheme(content: @Composable () -> Unit) {
    ThemeState.systemDark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    Motion.reduced = remember(ThemeState.systemDark) {
        runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val p = ThemeState.current
    val bg by animateColorAsState(p.bg, Motion.state(), label = "bg")
    val scheme = if (p.dark) darkColorScheme(
        primary = p.accent, secondary = p.accent2, tertiary = p.accent2, background = p.bg, surface = p.surface,
        surfaceVariant = p.surface2, onBackground = p.text, onSurface = p.text, onPrimary = p.onAccent,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surface2, surfaceContainerHighest = p.surface2, surfaceContainerLow = p.surface,
        surfaceContainerLowest = p.bg, onSurfaceVariant = p.sub, outline = p.faint, outlineVariant = p.line,
        primaryContainer = p.accent.copy(alpha = 0.16f).compositeOver(p.surface), onPrimaryContainer = p.text, error = Color(0xFFFF7468),
    ) else lightColorScheme(
        primary = p.accent, secondary = p.accent2, tertiary = p.accent2, background = p.bg, surface = p.surface,
        surfaceVariant = p.surface2, onBackground = p.text, onSurface = p.text, onPrimary = p.onAccent,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surface2, surfaceContainerHighest = p.surface2, surfaceContainerLow = p.surface,
        surfaceContainerLowest = p.bg, onSurfaceVariant = p.sub, outline = p.faint, outlineVariant = p.line,
        primaryContainer = p.accent.copy(alpha = 0.12f).compositeOver(p.surface), onPrimaryContainer = p.text, error = Color(0xFFC4372B),
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(Radius.xs), small = RoundedCornerShape(Radius.s), medium = RoundedCornerShape(Radius.m),
        large = RoundedCornerShape(Radius.l), extraLarge = RoundedCornerShape(Radius.sheet),
    )
    MaterialTheme(colorScheme = scheme, typography = typo(p), shapes = shapes) {
        Box(Modifier.fillMaxSize().background(bg)) { content() }
    }
}
