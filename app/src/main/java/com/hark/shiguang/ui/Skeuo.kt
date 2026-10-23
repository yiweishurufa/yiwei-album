package com.hark.shiguang.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 用户 2026-10-23：「UI 方面我想要那种锤子风格，拟物阴影」。
 *
 * 参考 Smartisan OS 2.x–3.x：暖灰 / 米白纸面底子带极轻的颗粒；卡片和列表组是「白色凸起卡片 + 细分隔线（刻线）」，
 * 圆角 + 柔和的多层阴影 + 顶部 1px 高光；开关、按钮像实物一样凸起，按下时变成内凹（阴影变浅变近、渐变反转）；
 * 图标线条细、颜色克制；强调色沿用用户选的强调色。深色主题对应做深色拟物：阴影更深，高光用低透明白。
 *
 * 这里是集中式 token 和可复用 Modifier：页面不手写阴影，只改全局组件（Card / NavRow / ToggleRow / GradientButton /
 * RoundIcon / 底部导航 / 顶栏胶囊 / 分段控件 / 输入框），效果自动铺开。设置 → 外观 →「界面风格」可切回简洁（扁平）。
 *
 * 性能：阴影只用 Modifier.shadow（RenderNode 硬件阴影，不做大半径模糊）+ 一次渐变 + 一条描边；
 * 照片网格不加阴影；纸面颗粒是一张 96×96 的小图平铺成 Shader，只画在页面底色上。
 */
object Skeuo {
    /** 设置 → 外观 → 界面风格：true = 拟物（默认），false = 简洁。 */
    val on: Boolean get() = ThemeState.skeuo
    private val dark: Boolean get() = C.P.dark

    /** 环境阴影（大而淡）与主光阴影（近而实），两层叠出「浮起」的感觉。浅色用暖棕调，不用纯黑。 */
    val ambient: Color get() = if (dark) Color(0x80000000) else Color(0x33402C18)
    val spot: Color get() = if (dark) Color(0xB3000000) else Color(0x4D3A2614)
    /** 顶部高光（卡片、按钮的上沿）。 */
    val highlight: Color get() = if (dark) Color(0x1FFFFFFF) else Color(0xF2FFFFFF)
    /** 底部 / 内凹的暗边。 */
    val edge: Color get() = if (dark) Color(0x8C000000) else Color(0x1F2A1A0A)

    /** 卡片表面：上亮下暗的极浅渐变，形成凸起。 */
    fun surfaceBrush(base: Color = C.Surface): Brush =
        Brush.verticalGradient(listOf(lerp(base, Color.White, if (dark) 0.035f else 0.55f), base))

    /** 内凹表面（开关轨道、输入框、搜索框、分段控件底）：上暗下亮。 */
    fun insetBrush(base: Color = C.Surface2): Brush =
        Brush.verticalGradient(listOf(lerp(base, Color.Black, if (dark) 0.22f else 0.06f), base))

    /** 凸起按钮的渐变：上亮下暗；按下时反过来并整体压暗一点。 */
    fun raisedBrush(base: Color, pressed: Boolean): Brush = if (!pressed)
        Brush.verticalGradient(listOf(lerp(base, Color.White, 0.16f), lerp(base, Color.Black, 0.06f)))
    else Brush.verticalGradient(listOf(lerp(base, Color.Black, 0.12f), lerp(base, Color.Black, 0.02f)))

    /** 页面底色：极轻的纵向渐变（上方略亮，像光从上面来）。 */
    val pageBrush: Brush get() = Brush.verticalGradient(listOf(lerp(C.Bg, Color.White, if (dark) 0.025f else 0.35f), C.Bg))

    /** 纸面颗粒：96×96 随机灰点（透明度 ≤ 5%），平铺。只生成一次。 */
    private val noiseBitmap: ImageBitmap by lazy {
        val n = 96
        val rnd = java.util.Random(20141023)
        val px = IntArray(n * n) { val a = rnd.nextInt(14); (a shl 24) or 0x7F7468 }
        android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    val noise: Brush by lazy { ShaderBrush(ImageShader(noiseBitmap, TileMode.Repeated, TileMode.Repeated)) }
}

/** 阴影层级（dp）。滚动列表里的卡片只用 [card]，避免叠很多层大阴影。 */
object Elevation {
    val card = 3.dp
    val raised = 4.dp
    val pressed = 1.dp
    val photo = 2.dp
}

/** 页面底色：拟物时 = 渐变 + 纸面颗粒；简洁时 = 纯色 C.Bg。所有页面根节点用它代替 background(C.Bg)。 */
fun Modifier.appBackground(): Modifier =
    if (!Skeuo.on) background(C.Bg) else background(Skeuo.pageBrush).background(Skeuo.noise)

/**
 * 凸起卡片：外阴影（ambient + spot 两层）+ 上亮下暗的表面 + 顶部 1px 高光描边。
 * 简洁风格时退回原来的「圆角 + 纯色」，所以可以无条件替换 clip(shape).background(C.Surface)。
 */
fun Modifier.skeuoCard(shape: Shape = RoundedCornerShape(C.Card), elevation: Dp = Elevation.card, color: Color = C.Surface): Modifier =
    if (!Skeuo.on) clip(shape).background(color)
    else shadow(elevation, shape, clip = false, ambientColor = Skeuo.ambient, spotColor = Skeuo.spot)
        .clip(shape).background(Skeuo.surfaceBrush(color))
        .border(0.8.dp, Brush.verticalGradient(listOf(Skeuo.highlight, Color.Transparent, Skeuo.edge.copy(alpha = Skeuo.edge.alpha * 0.5f))), shape)

/** 有照片封面的卡片（人物 / 地点 / 回忆 / 旅行）：只加一层轻阴影，不改表面，照片保持干净。 */
fun Modifier.skeuoPhoto(shape: Shape = RoundedCornerShape(C.Card)): Modifier =
    if (!Skeuo.on) clip(shape).background(C.Surface)
    else shadow(Elevation.photo, shape, clip = false, ambientColor = Skeuo.ambient, spotColor = Skeuo.spot).clip(shape).background(C.Surface)

/** 内凹槽：开关轨道、输入框、搜索框、分段控件底。上沿暗、下沿亮。 */
fun Modifier.skeuoInset(shape: Shape = RoundedCornerShape(Radius.m), color: Color = C.Surface2): Modifier =
    if (!Skeuo.on) clip(shape).background(color)
    else clip(shape).background(Skeuo.insetBrush(color))
        .border(0.8.dp, Brush.verticalGradient(listOf(Skeuo.edge, Color.Transparent, Skeuo.highlight.copy(alpha = Skeuo.highlight.alpha * 0.6f))), shape)

/**
 * 凸起 / 按下的静态外观：[pressed] = false 时凸起（阴影 [Elevation.raised]，上亮下暗），true 时内凹
 * （阴影 [Elevation.pressed]，渐变反转、上沿暗边）。需要动画请用 [skeuoPressable]。
 */
fun Modifier.skeuoPressed(pressed: Boolean, shape: Shape, base: Color, elevation: Dp = if (pressed) Elevation.pressed else Elevation.raised): Modifier =
    if (!Skeuo.on) clip(shape).background(base)
    else shadow(elevation, shape, clip = false, ambientColor = Skeuo.ambient, spotColor = Skeuo.spot)
        .clip(shape).background(Skeuo.raisedBrush(base, pressed))
        .border(0.8.dp, Brush.verticalGradient(if (pressed) listOf(Skeuo.edge, Color.Transparent) else listOf(Color.White.copy(alpha = if (C.P.dark) 0.16f else 0.55f), Color.Transparent)), shape)

/** 跟随 [interaction] 的按压状态：按下时阴影 4dp → 1dp（90ms），表面从凸起变内凹。 */
@Composable
fun Modifier.skeuoPressable(interaction: MutableInteractionSource, shape: Shape, base: Color): Modifier {
    if (!Skeuo.on) return clip(shape).background(base)
    val pressed by interaction.collectIsPressedAsState()
    val elev by animateDpAsState(if (pressed) Elevation.pressed else Elevation.raised, tween(90), label = "skeuoElev")
    return skeuoPressed(pressed, shape, base, elev)
}

/** 底部导航 / 底栏：上方一道柔和的投影 + 表面渐变 + 顶部高光线。 */
fun Modifier.skeuoBar(): Modifier =
    if (!Skeuo.on) background(C.Bg.copy(alpha = 0.97f))
    else drawBehind {
        val h = 10.dp.toPx()
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Skeuo.ambient.copy(alpha = Skeuo.ambient.alpha * 0.6f)), startY = -h, endY = 0f),
            topLeft = Offset(0f, -h), size = size.copy(height = h))
    }.background(Skeuo.surfaceBrush(C.Surface)).drawBehind {
        drawLine(Skeuo.highlight, Offset(0f, 0.5f), Offset(size.width, 0.5f), strokeWidth = 1f)
    }

/**
 * 拟物开关：内凹的轨道 + 凸起的圆钮。打开时轨道是强调色，钮滑到右边。简洁风格时用 Material 开关。
 */
@Composable
fun SkeuoToggle(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val t by animateFloatAsState(if (on) 1f else 0f, tween(170), label = "skeuoToggle")
    val dark = C.P.dark
    val interaction = remember { MutableInteractionSource() }
    val track = lerp(C.Surface2, C.Accent, t)
    Box(Modifier.alpha(if (enabled) 1f else 0.5f).size(50.dp, 30.dp)
        .toggleable(on, interaction, null, enabled, Role.Switch) { onChange(it) }
        .skeuoInset(CircleShape, track).padding(3.dp)) {
        val knob = if (dark) Color(0xFF5B5C61) else Color(0xFFFDFCFA)
        Box(Modifier.offset(x = 20.dp * t).size(24.dp).skeuoPressable(interaction, CircleShape, knob))
    }
}
