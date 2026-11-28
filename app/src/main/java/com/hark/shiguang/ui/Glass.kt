package com.hark.shiguang.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * 1.7.0 v2「液态玻璃」材质（docs/DESIGN.md §4）。
 *
 * 一个组件 [GlassSurface]（或 Modifier.[glass]），四个参数：tint（着色）、blur（模糊半径）、highlight（高光边强度）、shadow（投影）。
 *
 * 真模糊（Android 12 / API 31+，玻璃风格）：页面内容所在的容器挂 Modifier.[glassSource]，把内容录进一个 RenderNode；
 * 浮在它上面的玻璃（底栏、顶栏胶囊、迷你播放条…）各自用一个带 RenderEffect 模糊的小 RenderNode 引用这份内容、
 * 平移到自己的位置再画——只模糊玻璃自己那一块，不对整屏实时模糊。内容滚动时子层的显示列表更新，玻璃里跟着变，
 * 不需要重录。玻璃必须在 glassSource 外面（否则会自己引用自己）；在里面的玻璃用 backdrop = null，走降级外观。
 *
 * 降级（API < 31、没有 backdrop、软件绘制）：更高不透明度的着色（74%）+ 极细噪点 + 高光边——像磨砂玻璃，不会是一块脏灰。
 * 1.7.29：「简洁」风格已删除，界面固定为玻璃；Android 12 以下照旧走上面的降级外观。
 *
 * 数量上限：同屏带真模糊的玻璃 ≤ [Glass.MAX_BLUR] 块（底栏、顶栏胶囊、迷你播放条）；其余玻璃都是降级外观。
 */
object Glass {
    val blurSupported: Boolean get() = Build.VERSION.SDK_INT >= 31
    const val MAX_BLUR = 3

    val BLUR = 22.dp
    val SHADOW = 14.dp

    /** 着色底色：深色 = 深蓝黑，浅色 = 白。 */
    val tint: Color get() = if (C.P.dark) Color(0xFF0D1322) else Color.White
    /** 着色透明度：有真模糊时 58%（模糊层先压暗 / 提亮过，见 [backdropFilter]）；降级 74%。最差背景下文字仍 ≥ 4.5:1（contrast_v2.py）。 */
    fun tintAlpha(blurred: Boolean): Float = if (blurred) 0.58f else 0.74f

    /** 高光边：上沿亮、下沿弱，像光落在玻璃边上。 */
    fun rimBrush(strength: Float, dark: Boolean = C.P.dark): Brush {
        val top = if (dark) 0.30f else 0.95f
        val bottom = if (dark) 0.08f else 0.45f
        return Brush.verticalGradient(listOf(Color.White.copy(alpha = top * strength), Color.White.copy(alpha = 0.04f * strength), Color.White.copy(alpha = bottom * strength)))
    }

    /** 玻璃内上半部的柔光（「镜面反光」），只在玻璃风格下画。 */
    fun sheenBrush(h: Float, dark: Boolean = C.P.dark): Brush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) 0.10f else 0.35f), 0.55f to Color.Transparent, startY = 0f, endY = h)

    private var noise: ImageBitmap? = null
    /** 72×72 的随机细噪点（白黑各半、透明度 ≤ 7%），平铺；降级玻璃上用它去掉「塑料感」。 */
    fun noiseBrush(): ShaderBrush {
        val img = noise ?: run {
            val n = 72
            val rnd = java.util.Random(7)
            val px = IntArray(n * n) {
                val a = rnd.nextInt(18)
                if (rnd.nextBoolean()) android.graphics.Color.argb(a, 255, 255, 255) else android.graphics.Color.argb(a, 0, 0, 0)
            }
            android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap().also { noise = it }
        }
        return ShaderBrush(ImageShader(img, TileMode.Repeated, TileMode.Repeated))
    }
}

/** 一个可被玻璃「看到」的内容区（见文件头）。 */
@Stable
class GlassBackdrop {
    internal var node: Any? = null
    internal var ready = false
    /** 正在录制时，任何引用它的玻璃都不画模糊层（防止玻璃误放进 glassSource 里面时自己引用自己）。 */
    internal var recording = false
    internal var origin by mutableStateOf(Offset.Zero)
}

/** 把这块内容录成玻璃可以引用的 RenderNode（API 31+；其余情况原样绘制）。 */
fun Modifier.glassSource(b: GlassBackdrop): Modifier =
    if (Build.VERSION.SDK_INT < 31) this
    else this.onGloballyPositioned { b.origin = it.positionInRoot() }
        .drawWithContent { if (!BackdropImpl.record(this, b)) { b.ready = false; drawContent() } }

private class GlassHolder {
    var node: Any? = null
    var radius = -1f
    var dark: Boolean? = null
    var pos by mutableStateOf(Offset.Zero)
}

/**
 * 玻璃材质。[backdrop] 为 null 或系统不支持时走降级外观。[highlight] 0–1 为高光边强度；[shadow] 为投影高度（只画在形状外面，
 * 不会透过半透明的玻璃看到投影）。
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(Radius.sheet),
    backdrop: GlassBackdrop? = null,
    tint: Color = Color.Unspecified,
    blur: Dp = Glass.BLUR,
    shadow: Dp = Glass.SHADOW,
    highlight: Float = 1f,
    onDark: Boolean = false,
): Modifier = composed {
    val holder = remember { GlassHolder() }
    // onDark：大图查看器、播放器这类永远是黑底的界面，不随浅色主题变白玻璃
    val dark = onDark || C.P.dark
    val baseTint = if (tint.isSpecified) tint else if (onDark) Color(0xFF0D1322) else Glass.tint
    val useBackdrop = backdrop != null && Glass.blurSupported
    (if (useBackdrop) Modifier.onGloballyPositioned { holder.pos = it.positionInRoot() } else Modifier).then(
        Modifier.drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val shadowPx = shadow.toPx()
            val layers = if (shadowPx > 0f) SkeuoShadow.layers(shadowPx, if (dark) 0.55f else 0.16f) else emptyList()
            val shadowOutlines = layers.map { l -> shape.createOutline(Size(size.width + 2 * l.spread, size.height + 2 * l.spread), layoutDirection, this) }
            val rim = Glass.rimBrush(highlight, dark)
            val sheen = Glass.sheenBrush(size.height, dark)
            val noise = Glass.noiseBrush()
            val stroke = Stroke(1.dp.toPx())
            val blurPx = blur.toPx()
            onDrawBehind {
                if (layers.isNotEmpty()) clipPath(path, ClipOp.Difference) {
                    layers.forEachIndexed { i, l ->
                        translate(-l.spread, -l.spread + l.dy) { drawOutline(shadowOutlines[i], Color.Black.copy(alpha = l.alpha)) }
                    }
                }
                val blurred = useBackdrop && backdrop != null && Build.VERSION.SDK_INT >= 31 &&
                    BackdropImpl.draw(this, holder, backdrop, path, blurPx, dark)
                drawPath(path, baseTint.copy(alpha = baseTint.alpha * Glass.tintAlpha(blurred)))
                if (!blurred) drawPath(path, noise)
                drawPath(path, sheen)
                drawPath(path, rim, style = stroke)
            }
        })
}

/** 统一的玻璃容器：材质画在后面，内容按 [shape] 裁剪。 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.sheet),
    backdrop: GlassBackdrop? = null,
    tint: Color = Color.Unspecified,
    blur: Dp = Glass.BLUR,
    shadow: Dp = Glass.SHADOW,
    highlight: Float = 1f,
    onDark: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape, backdrop, tint, blur, shadow, highlight, onDark).clip(shape), content = content)
}

/**
 * 按压时玻璃轻微形变：横向鼓出、纵向压扁（像按在一滴液体上），松手带弹性回位。移除动画时只做透明度提示。
 */
fun Modifier.glassPress(interaction: InteractionSource, amount: Float = 1f): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val t by animateFloatAsState(if (pressed) 1f else 0f, if (pressed) Motion.pressIn() else Motion.liquid(), label = "glassPress")
    graphicsLayer {
        if (Motion.reduced) { alpha = 1f - 0.12f * t; return@graphicsLayer }
        scaleX = 1f + 0.035f * amount * t
        scaleY = 1f - 0.05f * amount * t
    }
}

@RequiresApi(31)
private object BackdropImpl {
    fun record(scope: ContentDrawScope, b: GlassBackdrop): Boolean {
        val nc = scope.drawContext.canvas.nativeCanvas
        if (!nc.isHardwareAccelerated) return false
        val w = scope.size.width.toInt(); val h = scope.size.height.toInt()
        if (w <= 0 || h <= 0) return false
        val node = (b.node as? RenderNode) ?: RenderNode("glassSource").also { b.node = it }
        node.setPosition(0, 0, w, h)
        val rc = node.beginRecording()
        b.recording = true
        try {
            scope.draw(scope, scope.layoutDirection, androidx.compose.ui.graphics.Canvas(rc), scope.size) { scope.drawContent() }
        } finally { node.endRecording(); b.recording = false }
        nc.drawRenderNode(node)
        b.ready = true
        return true
    }

    /** 模糊层的颜色处理：饱和度 ×1.5；深色压暗到 70%，浅色提亮（×0.75 + 64）——保证玻璃上的字在任何背景上都读得清。 */
    private fun backdropFilter(dark: Boolean): ColorMatrixColorFilter {
        val m = ColorMatrix().apply { setSaturation(1.5f) }
        val k = if (dark) 0.7f else 0.75f
        val add = if (dark) 0f else 64f
        m.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, add, 0f, k, 0f, 0f, add, 0f, 0f, k, 0f, add, 0f, 0f, 0f, 1f, 0f)))
        return ColorMatrixColorFilter(m)
    }

    fun draw(scope: DrawScope, holder: GlassHolder, b: GlassBackdrop, path: Path, blurPx: Float, dark: Boolean): Boolean {
        val src = b.node as? RenderNode ?: return false
        if (!b.ready || b.recording) return false
        val nc = scope.drawContext.canvas.nativeCanvas
        if (!nc.isHardwareAccelerated) return false
        val w = scope.size.width.toInt(); val h = scope.size.height.toInt()
        if (w <= 0 || h <= 0) return false
        val node = (holder.node as? RenderNode) ?: RenderNode("glass").also { holder.node = it }
        if (holder.radius != blurPx || holder.dark != dark) {
            node.setRenderEffect(RenderEffect.createChainEffect(
                RenderEffect.createColorFilterEffect(backdropFilter(dark)),
                RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)))
            holder.radius = blurPx; holder.dark = dark
        }
        node.setPosition(0, 0, w, h)
        val rc = node.beginRecording()
        try {
            val o = b.origin; val p = holder.pos
            rc.translate(o.x - p.x, o.y - p.y)
            rc.drawRenderNode(src)
        } finally { node.endRecording() }
        scope.clipPath(path) { drawIntoCanvas { it.nativeCanvas.drawRenderNode(node) } }
        return true
    }
}

/**
 * 1.7.0 环境光晕：页面底色上两团很大的柔光——一团取自当前照片 / 封面（[Ambient.target]，没有时用强调色），一团固定冷蓝。
 * 只在玻璃风格下画；颜色由 [ShiGuangTheme] 缓慢过渡（1.4 s）。
 */
object Ambient {
    /** 想要的光晕颜色；null = 用强调色。 */
    var target by mutableStateOf<Color?>(null)
    /** 正在显示的（过渡中的）颜色，由主题写入。 */
    var shown by mutableStateOf(Color.Unspecified)

    private val cache = android.util.LruCache<String, Int>(96)

    /** 从一张图取一个「有颜色的平均色」：按饱和度加权平均，再把明度 / 饱和度拉到适合做光晕的范围。 */
    suspend fun colorOf(ctx: android.content.Context, url: String): Color? = withContext(Dispatchers.IO) {
        cache.get(url)?.let { return@withContext Color(it) }
        val req = coil.request.ImageRequest.Builder(ctx).data(url).size(32).allowHardware(false).build()
        val bmp = (runCatching { coil.Coil.imageLoader(ctx).execute(req).drawable }.getOrNull() as? android.graphics.drawable.BitmapDrawable)?.bitmap
            ?: return@withContext null
        val c = AmbientMath.pick(IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }) ?: return@withContext null
        cache.put(url, c)
        Color(c)
    }
}

/** 光晕取色（纯计算，便于单测）。 */
internal object AmbientMath {
    /** 返回 ARGB；全是灰（饱和度都很低）时返回 null，交给强调色。 */
    fun pick(px: IntArray): Int? {
        if (px.isEmpty()) return null
        var r = 0.0; var g = 0.0; var b = 0.0; var wsum = 0.0
        val hsv = FloatArray(3)
        for (p in px) {
            val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
            rgbToHsv(pr, pg, pb, hsv)
            val w = (hsv[1] * hsv[1]) * (0.2 + hsv[2])
            r += pr * w; g += pg * w; b += pb * w; wsum += w
        }
        if (wsum < px.size * 0.01) return null
        rgbToHsv((r / wsum).toInt(), (g / wsum).toInt(), (b / wsum).toInt(), hsv)
        hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.70f, 0.95f)
        return hsvToRgb(hsv)
    }

    fun rgbToHsv(r: Int, g: Int, b: Int, out: FloatArray) {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf); val d = max - min
        val h = when {
            d == 0f -> 0f
            max == rf -> 60f * (((gf - bf) / d) % 6f)
            max == gf -> 60f * (((bf - rf) / d) + 2f)
            else -> 60f * (((rf - gf) / d) + 4f)
        }
        out[0] = if (h < 0) h + 360f else h
        out[1] = if (max == 0f) 0f else d / max
        out[2] = max
    }

    fun hsvToRgb(hsv: FloatArray): Int {
        val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
        val c = v * s; val x = c * (1 - kotlin.math.abs((h / 60f) % 2f - 1)); val m = v - c
        val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = ((f + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}

/** 让环境光晕跟随这张图（离开时不清除，下一张图接着过渡）。 */
@Composable
fun AmbientFrom(url: String?) {
    val ctx = LocalContext.current
    LaunchedEffect(url) { if (url != null) Ambient.colorOf(ctx, url)?.let { Ambient.target = it } }
}

private val CoolGlow = Color(0xFF3D5BFF)

/** 页面底上的环境光晕。 */
fun Modifier.ambientGlow(): Modifier = drawBehind {
    val c = Ambient.shown.takeIf { it.isSpecified } ?: C.Accent
    val dark = C.P.dark
    val r = size.maxDimension * 0.7f
    drawRect(Brush.radialGradient(listOf(c.copy(alpha = if (dark) 0.30f else 0.20f), c.copy(alpha = 0f)),
        center = Offset(size.width * 0.12f, size.height * 0.04f), radius = r))
    drawRect(Brush.radialGradient(listOf(CoolGlow.copy(alpha = if (dark) 0.16f else 0.09f), CoolGlow.copy(alpha = 0f)),
        center = Offset(size.width * 1.0f, size.height * 0.62f), radius = r * 0.85f))
}


/**
 * 玻璃圆钮（44dp，触摸区 48）：顶栏的日历 / 搜索、照片上的操作。[overPhoto] = 压在照片上：深色着色 + 白色图标，不画投影。
 */
@Composable
fun GlassIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, modifier: Modifier = Modifier, overPhoto: Boolean = false,
                    backdrop: GlassBackdrop? = null, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    val inter = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val shape = androidx.compose.foundation.shape.CircleShape
    Box(
        modifier.size(48.dp).glassPress(inter, 1.4f)
            .clickable(inter, null, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = null) { onClick() }
            .semantics { contentDescription = desc },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        // 有 backdrop（真模糊）时着色用默认深色玻璃；没有时压在照片上用 60% 黑
        Box(Modifier.size(42.dp).glass(shape, backdrop, tint = if (overPhoto && backdrop == null) Color.Black.copy(alpha = 0.6f) else Color.Unspecified,
            shadow = if (overPhoto) 0.dp else 8.dp, highlight = if (overPhoto) 0.7f else 1f, onDark = overPhoto), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.material3.Icon(icon, null, tint = if (tint.isSpecified) tint else if (overPhoto) Color.White else C.OnGlass, modifier = Modifier.size(21.dp))
        }
    }
}

/**
 * 1.7.0：只画玻璃的高光边（在内容之上），给自身已经是半透明底色的容器用：对话框、底部面板、下拉菜单。
 * 高光只在上沿明显，往下很快淡出，所以面板下边没有亮线。
 */
fun Modifier.glassRim(shape: Shape, strength: Float = 1f, fadeAt: Dp = 56.dp): Modifier = composed {
    val dark = C.P.dark
    this.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply { addOutline(outline) }
        val rim = Brush.verticalGradient(0f to Color.White.copy(alpha = (if (dark) 0.32f else 0.95f) * strength), 1f to Color.Transparent, startY = 0f, endY = fadeAt.toPx())
        val stroke = Stroke(1.dp.toPx())
        onDrawWithContent { drawContent(); drawPath(path, rim, style = stroke) }
    }
}

/** 对话框 / 面板 / 菜单这类「浮层」的底色：半透明（窗口背后有模糊时更透）。 */
fun glassOverlayColor(base: Color, windowBlur: Boolean): Color =
    base.copy(alpha = if (windowBlur) 0.80f else 0.95f)

/**
 * 1.7.0：在对话框 / 底部面板所在的窗口上打开「背后模糊」（API 31+；系统关闭了跨窗口模糊时自动无效）。
 * 返回是否请求了模糊（据此决定浮层底色透明度）。
 */
@Composable
fun WindowBlurBehind(radius: Dp = 30.dp): Boolean {
    if (Build.VERSION.SDK_INT < 31) return false
    val view = androidx.compose.ui.platform.LocalView.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { radius.roundToPx() }
    val wm = remember(view) { view.context.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager }
    val enabled = remember(wm) { wm?.isCrossWindowBlurEnabled == true }
    androidx.compose.runtime.DisposableEffect(view, px, enabled) {
        if (enabled) runCatching {
            val win = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            if (win != null) {
                win.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                win.attributes = win.attributes.also { it.blurBehindRadius = px }
            } else {
                val root = view.rootView
                val lp = root.layoutParams as? android.view.WindowManager.LayoutParams
                if (lp != null && root.isAttachedToWindow) {
                    lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    lp.blurBehindRadius = px
                    wm?.updateViewLayout(root, lp)
                }
            }
        }
        onDispose {}
    }
    return enabled
}
