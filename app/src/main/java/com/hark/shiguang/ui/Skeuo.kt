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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
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
 * 1.6.0「质感」风格（原「拟物」，用户 2026-10-23 要锤子式的拟物阴影；2026-11-25 要求按设计技能重做）。
 *
 * 保留锤子拟物的骨架——卡片浮在纸面上、开关是凹槽里的圆钮、按下会「陷下去」——但去掉了米黄底、纸面颗粒和
 * 上亮下暗的大渐变（unslop §2：表面保持中性，颜色留给照片；渐变只允许同一中性色内的小幅明度变化）。
 * 阴影改成中性黑、小而柔（craft-floor：阴影要有偏移和柔和模糊）；深色主题的层级主要靠面的明度台阶 + 发丝线。
 *
 * 页面不手写阴影，只用这里的 Modifier（Card / NavRow / ToggleRow / 按钮 / 圆形按钮 / 底栏 / 顶栏胶囊 / 分段 / 输入框）。
 * 设置 → 外观 →「界面风格」可切到简洁：纯色面 + 发丝线，没有阴影。
 *
 * 性能：阴影只用 Modifier.shadow（RenderNode 硬件阴影）；API 26/27 不认阴影颜色，改画几层半透明轮廓（[skeuoShadow]）；
 * 照片网格不加阴影。
 */
object Skeuo {
    /** 设置 → 外观 → 界面风格：true = 质感（默认），false = 简洁。 */
    val on: Boolean get() = ThemeState.skeuo
    private val dark: Boolean get() = C.P.dark

    /** 环境阴影（大而淡）与主光阴影（近而实），中性黑。 */
    val ambient: Color get() = if (dark) Color(0x66000000) else Color(0x1A000000)
    val spot: Color get() = if (dark) Color(0x99000000) else Color(0x2E000000)
    /** 上沿高光线（卡片、按钮的上边）。 */
    val highlight: Color get() = if (dark) Color(0x14FFFFFF) else Color(0xCCFFFFFF)
    /** 下沿 / 内凹的暗边。 */
    val edge: Color get() = if (dark) Color(0x66000000) else Color(0x14000000)

    /** 卡片表面：同一中性色内极小的明度变化（上沿略亮）。 */
    fun surfaceBrush(base: Color = C.Surface): Brush =
        Brush.verticalGradient(listOf(lerp(base, Color.White, if (dark) 0.025f else 0.3f), base))

    /** 内凹表面（开关轨道、输入框、搜索框、分段控件底）：上沿略暗。 */
    fun insetBrush(base: Color = C.Surface2): Brush =
        Brush.verticalGradient(listOf(lerp(base, Color.Black, if (dark) 0.16f else 0.04f), base))

    /** 凸起按钮：上沿略亮；按下时整体压暗一点（形变交给缩放，不再反转渐变）。 */
    fun raisedBrush(base: Color, pressed: Boolean): Brush = if (!pressed)
        Brush.verticalGradient(listOf(lerp(base, Color.White, 0.08f), base))
    else Brush.verticalGradient(listOf(lerp(base, Color.Black, 0.08f), lerp(base, Color.Black, 0.04f)))
}

/** 阴影层级（dp）。滚动列表里的卡片只用 [card]，避免叠很多层大阴影。 */
object Elevation {
    val card = 2.dp
    val raised = 3.dp
    val pressed = 1.dp
    val photo = 2.dp
}

/**
 * 用户 2026-10-24 要求继续优化：拟物阴影。API 28+ 用硬件阴影（ambient / spot 自定义暖色）；API 26/27 系统忽略阴影颜色，
 * 硬件阴影是纯黑、显得很重，改为在形状后面画 [SkeuoShadow.LAYERS] 层逐渐外扩、逐层变淡的半透明轮廓（同样的暖棕色调，
 * 略向下偏移），观感接近 28+ 的柔和阴影。轮廓按尺寸缓存（drawWithCache），滚动时不重复创建。
 */
internal fun Modifier.skeuoShadow(elevation: Dp, shape: Shape): Modifier =
    if (SkeuoShadow.nativeColors(android.os.Build.VERSION.SDK_INT)) shadow(elevation, shape, clip = false, ambientColor = Skeuo.ambient, spotColor = Skeuo.spot)
    else {
        val tint = Skeuo.spot
        drawWithCache {
            val layers = SkeuoShadow.layers(elevation.toPx(), tint.alpha)
            val outlines = layers.map { l -> shape.createOutline(Size(size.width + 2 * l.spread, size.height + 2 * l.spread), layoutDirection, this) }
            onDrawBehind {
                layers.forEachIndexed { i, l ->
                    translate(-l.spread, -l.spread + l.dy) { drawOutline(outlines[i], tint.copy(alpha = l.alpha)) }
                }
            }
        }
    }

/** API 26/27 绘制阴影的参数（纯计算，便于单测）。 */
internal object SkeuoShadow {
    const val LAYERS = 4
    /** 系统从 API 28 起才支持硬件阴影的自定义颜色。 */
    fun nativeColors(sdk: Int): Boolean = sdk >= 28
    /** 一层轮廓：向外扩 [spread] px、向下偏 [dy] px、透明度 [alpha]。 */
    data class Layer(val spread: Float, val dy: Float, val alpha: Float)
    /**
     * 层由内到外：外扩到 0.9 × 高度，越外越淡（外层透明度约为内层的 0.4），各层叠加后贴边处最深、外缘最淡；
     * 每层透明度 = 基础透明度 / 层数 × 衰减，整体比硬件纯黑阴影轻。高度 ≤ 0 或基础透明度 ≤ 0 时不画。
     */
    fun layers(elevationPx: Float, baseAlpha: Float): List<Layer> {
        if (elevationPx <= 0f || baseAlpha <= 0f) return emptyList()
        return (1..LAYERS).map { i ->
            val f = i / LAYERS.toFloat()
            Layer(spread = elevationPx * 0.9f * f, dy = elevationPx * 0.45f, alpha = (baseAlpha * (1f - 0.6f * f) / LAYERS).coerceIn(0f, 1f))
        }
    }
}

/** 页面底色：C.Bg 纯色；1.7.0 玻璃风格再叠环境光晕（Glass.kt · ambientGlow）。所有页面根节点用它代替 background(C.Bg)。 */
fun Modifier.appBackground(): Modifier = background(C.Bg).ambientGlow()

/**
 * 凸起卡片：外阴影（ambient + spot 两层）+ 上亮下暗的表面 + 顶部 1px 高光描边。
 * 简洁风格时退回原来的「圆角 + 纯色」，所以可以无条件替换 clip(shape).background(C.Surface)。
 */
fun Modifier.skeuoCard(shape: Shape = RoundedCornerShape(C.Card), elevation: Dp = Elevation.card, color: Color = C.Surface): Modifier =
    if (!Skeuo.on) clip(shape).background(color)
    // 1.7.0：玻璃风格下卡片是磨砂玻璃（卡片色 74% + 细噪点 + 上沿高光边），环境光晕从底下透上来；投影很浅
    else glass(shape, backdrop = null, tint = color, shadow = elevation * 2, highlight = 0.7f).clip(shape)

/** 有照片封面的卡片（人物 / 地点 / 回忆 / 旅行）：只加一层轻阴影，不改表面，照片保持干净。 */
fun Modifier.skeuoPhoto(shape: Shape = RoundedCornerShape(C.Card)): Modifier =
    if (!Skeuo.on) clip(shape).background(C.Surface)
    else skeuoShadow(Elevation.photo, shape).clip(shape).background(C.Surface)

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
    else skeuoShadow(elevation, shape)
        .clip(shape).background(Skeuo.raisedBrush(base, pressed))
        .border(0.8.dp, Brush.verticalGradient(if (pressed) listOf(Skeuo.edge, Color.Transparent) else listOf(Color.White.copy(alpha = if (C.P.dark) 0.16f else 0.55f), Color.Transparent)), shape)

/**
 * 跟随 [interaction] 的按压状态：整体缩到 [Motion.PRESS_SCALE]（按下快、松手稍慢）；质感风格下阴影 3dp → 1dp、表面压暗。
 * 简洁风格同样缩放，只是没有阴影。
 */
@Composable
fun Modifier.skeuoPressable(interaction: MutableInteractionSource, shape: Shape, base: Color, scale: Float = Motion.PRESS_SCALE, flat: Boolean = false): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scale else 1f, if (pressed) Motion.pressIn() else Motion.pressOut(), label = "pressScale")
    val scaled = graphicsLayer { scaleX = s; scaleY = s }
    if (!Skeuo.on || flat) return scaled.clip(shape).background(if (pressed) lerp(base, if (C.P.dark) Color.White else Color.Black, 0.06f) else base)
    val elev by animateDpAsState(if (pressed) Elevation.pressed else Elevation.raised, Motion.pressIn(), label = "skeuoElev")
    return scaled.skeuoPressed(pressed, shape, base, elev)
}

/**
 * 底部导航 / 底栏：和页面同色的不透明底 + 顶部一条发丝线（unslop：导航安静，不发光）。
 * 质感风格在发丝线上方再加一道很淡的投影，让它像压在纸面上。
 */
fun Modifier.skeuoBar(): Modifier = drawBehind {
    if (Skeuo.on) {
        val h = 8.dp.toPx()
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Skeuo.ambient.copy(alpha = Skeuo.ambient.alpha * 0.5f)), startY = -h, endY = 0f),
            topLeft = Offset(0f, -h), size = size.copy(height = h))
    }
}.background(C.Bg.copy(alpha = 0.98f)).drawBehind {
    drawLine(C.Line, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f)
}

/**
 * 质感开关：内凹的轨道 + 凸起的圆钮。打开时轨道是强调色，钮滑到右边（临界阻尼弹簧）。简洁风格时用 Material 开关。
 */
@Composable
fun SkeuoToggle(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val t by animateFloatAsState(if (on) 1f else 0f, Motion.move(), label = "skeuoToggle")
    val dark = C.P.dark
    val interaction = remember { MutableInteractionSource() }
    val track = lerp(C.Surface2, C.Accent, t)
    Box(Modifier.alpha(if (enabled) 1f else 0.5f).size(50.dp, 30.dp)
        .toggleable(on, interaction, null, enabled, Role.Switch) { onChange(it) }
        .skeuoInset(CircleShape, track).padding(3.dp)) {
        val knob = if (dark) Color(0xFFE9E8E4) else Color(0xFFFFFFFF)
        Box(Modifier.offset(x = 20.dp * t).size(24.dp).skeuoPressable(interaction, CircleShape, knob, scale = 0.92f))
    }
}
