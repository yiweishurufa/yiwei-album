package com.hark.shiguang.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import coil.request.ImageRequest
import com.hark.shiguang.data.Photo
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

@Composable
fun shimmerBrush(): Brush {
    // loading-states：骨架只在首屏用；「移除动画」时不扫光，只给静态底色
    // 1.7.10 v2：玻璃风格下骨架是半透明的「磨砂」块（正文色 6% → 扫光 12%），环境光晕能透出来，不再是一块块实心灰；简洁风格保持实色
    val base = if (Glass.on) C.Text.copy(alpha = 0.06f) else C.Surface
    val hi = if (Glass.on) C.Text.copy(alpha = 0.12f) else androidx.compose.ui.graphics.lerp(C.Surface, C.Surface2, 0.7f)
    if (Motion.reduced) return androidx.compose.ui.graphics.SolidColor(base)
    val t = rememberInfiniteTransition(label = "s")
    val x by t.animateFloat(0f, 1400f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "x")
    return Brush.linearGradient(listOf(base, hi, base), start = Offset(x - 400f, 0f), end = Offset(x, 400f))
}

/** 1.7.10：骨架里的「字行」底色（不扫光）：玻璃风格正文色 6%，简洁风格凹槽色。 */
val skeletonLine: Color @Composable get() = if (Glass.on) C.Text.copy(alpha = 0.06f) else C.Surface2

/**
 * 1.7.23：一个数字 / 一小段文字还在算时占位的骨架小条（设置「存储空间」右侧、存储空间页总量）。
 * 读屏读 [desc]；移除动画时不扫光（[shimmerBrush] 已处理）。
 */
@Composable
fun SkeletonBar(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp = 12.dp, desc: String = "正在统计", modifier: Modifier = Modifier) {
    Box(modifier.size(width, height).clip(RoundedCornerShape(Radius.xs)).background(shimmerBrush()).semantics { contentDescription = desc })
}

/** 1.7.10：歌曲 / 文件这类列表行的骨架（与行同形：方形封面 + 两行字 + 右侧短条），首屏用。 */
@Composable
fun ListRowSkeleton(rows: Int = 8, cover: androidx.compose.ui.unit.Dp = 48.dp, coverShape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(Radius.s)) {
    Column(Modifier.fillMaxWidth().padding(top = Space.xs).semantics { contentDescription = "正在加载" }) {
        val b = shimmerBrush()
        repeat(rows) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(cover).clip(coverShape).background(b))
                Column(Modifier.weight(1f).padding(start = Space.m)) {
                    Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(Radius.xs)).background(skeletonLine))
                    Box(Modifier.padding(top = Space.s).fillMaxWidth(0.4f).height(10.dp).clip(RoundedCornerShape(Radius.xs)).background(skeletonLine))
                }
                Box(Modifier.size(36.dp, 10.dp).clip(RoundedCornerShape(Radius.xs)).background(skeletonLine))
            }
        }
    }
}

@Composable
fun NetImage(url: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, crossfade: Boolean = true) {
    // H11: plain AsyncImage over a static placeholder color — no subcomposition and no endless shimmer per cell,
    // which made poster grids and song rows drop frames. shimmerBrush() stays for first-screen skeletons only.
    val ctx = LocalContext.current
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.background(if (failed) C.Surface2 else C.Surface), contentAlignment = Alignment.Center) {
        coil.compose.AsyncImage(
            // 1.7.18：请求对象跟着 url 记住，滚动 / 选择引起的重组不再每次新建
            model = remember(url, crossfade) { ImageRequest.Builder(ctx).data(url).crossfade(crossfade).build() },
            contentDescription = null, contentScale = scale, modifier = Modifier.matchParentSize(),
            onState = { failed = it is coil.compose.AsyncImagePainter.State.Error },
        )
        if (failed) Icon(KI.fileImage(), "图片加载失败", tint = C.Faint, modifier = Modifier.size(22.dp))
    }
}

fun fmtDuration(sec: Int): String = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

@Composable
fun Thumb(p: Photo, corner: Dp, small: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(corner)).background(C.Surface).clickable(onClickLabel = "查看", onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = (if (p.isVideo) "视频" + (if (p.duration > 0) "，时长${p.duration / 60}分${p.duration % 60}秒" else "") else if (p.isLive) "实况照片" else "照片") + (if (p.collected) "，已收藏" else "")
            }
    ) {
        NetImage(p.thumbS, Modifier.fillMaxSize(), crossfade = !small)
        if (!small && (p.isVideo || p.isLive || p.collected)) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0x99000000))))
        }
        if (!small) Row(Modifier.align(Alignment.BottomStart).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.collected) Icon(KI.heartSolid(), null, tint = Color.White, modifier = Modifier.size(13.dp))
        }
        if (!small) Row(Modifier.align(Alignment.BottomEnd).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.isVideo) {
                Icon(KI.playSolid(), null, tint = Color.White, modifier = Modifier.size(14.dp))
                if (p.duration > 0) Text(fmtDuration(p.duration), color = Color.White, style = Type.badge)
            } else if (p.isLive) {
                Icon(KI.live(), null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

sealed class Entry {
    data class Header(val key: String, val title: String, val sub: String) : Entry()
    data class Cell(val photo: Photo, val index: Int) : Entry()
}

private val zh = Locale.SIMPLIFIED_CHINESE

fun dayTitle(day: String): Pair<String, String> {
    // day: "YYYY:MM:DD"
    val parts = day.split(':', '-')
    val d = runCatching { LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt()) }.getOrNull()
        ?: return (day to "")
    val today = LocalDate.now()
    val title = when (d) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> if (d.year == today.year) "${d.monthValue}月${d.dayOfMonth}日" else "${d.year}年${d.monthValue}月${d.dayOfMonth}日"
    }
    return title to d.dayOfWeek.getDisplayName(JTextStyle.SHORT, zh)
}

/** Where a photo was taken, for date headers: NAS geo text or the WebDAV on-device analysis. */
fun placeOf(p: Photo): String = if (p.isCloud) com.hark.shiguang.Dav.lib(p.source).meta[p.cloudPath]?.city.orEmpty()
    else p.geo.split(',', '，', ' ').map { it.trim() }.lastOrNull { it.isNotEmpty() && it.any { c -> !c.isDigit() && c != '.' && c != '-' } }.orEmpty()

fun buildEntries(photos: List<Photo>, grouped: Boolean): List<Entry> {
    if (!grouped) return photos.mapIndexed { i, p -> Entry.Cell(p, i) }
    val out = ArrayList<Entry>(photos.size + 64)
    val places = HashMap<String, String>()
    photos.groupBy { it.day }.forEach { (d, l) -> places[d] = l.asSequence().map { placeOf(it) }.filter { it.isNotEmpty() }.distinct().take(2).joinToString("、") }
    var last = ""
    photos.forEachIndexed { i, p ->
        val d = p.day
        if (d != last) {
            val (t, s) = dayTitle(d); val pl = places[d].orEmpty()
            out += Entry.Header("h$d$i", t, if (pl.isEmpty()) s else "$s · $pl"); last = d
        }
        out += Entry.Cell(p, i)
    }
    return out
}

@Composable
fun SkeletonGrid(columns: Int, top: Dp) {
    val b = shimmerBrush()
    Column(Modifier.fillMaxSize().padding(top = top + 22.dp, start = 2.dp, end = 2.dp)) {
        Box(Modifier.padding(start = 12.dp, bottom = 10.dp).size(110.dp, 16.dp).clip(RoundedCornerShape(6.dp)).background(b))
        repeat(7) {
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(columns) { Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(b)) }
            }
        }
    }
}

@Composable
fun StateMessage(icon: ImageVector, title: String, sub: String, action: String? = null, action2: String? = null, onAction2: (() -> Unit)? = null, onAction: (() -> Unit)? = null) {
    // 1.6.0 空状态：不再是「彩色圆底 + 图标」的模板，只留一个安静的大线性图标 + 标题 + 一句话 + 一个主操作（unslop §3、craft-floor）
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = Space.xxl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = C.Faint, modifier = Modifier.size(44.dp).statePop())
        Spacer(Modifier.height(Space.l))
        Column(Modifier.enterRise(1), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = C.Sub, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.s).widthIn(max = 300.dp))
        }
        Column(Modifier.enterRise(2), horizontalAlignment = Alignment.CenterHorizontally) {
            if (action != null && onAction != null) {
                Spacer(Modifier.height(Space.xl))
                GradientButton(action, Modifier.widthIn(min = 160.dp), onClick = onAction)
            }
            if (action2 != null && onAction2 != null) QuietButton(action2, C.Accent, Modifier.padding(top = 6.dp), onAction2)
        }
    }
}

/**
 * 1.7.27 空状态 / 错误页的图标入场：从 0.6 倍、低一点的位置带一点回弹（Motion.pop）升到原位并淡入，只在第一次出现时播一次
 * （同一页来回重组、旋转屏幕都不再播）。标题和按钮随后用 [enterRise] 错开上浮。移除动画时直接显示。
 */
fun Modifier.statePop(): Modifier = this.composed {
    if (Motion.reduced) return@composed this
    var played by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (played) return@composed this
    val t = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, Motion.pop()); played = true }
    val rise = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
    graphicsLayer {
        val v = t.value
        val sc = 0.6f + 0.4f * v; scaleX = sc; scaleY = sc
        alpha = v.coerceIn(0f, 1f); translationY = (1f - v) * rise
    }
}

/** 审查 12：影音 / 音乐没有来源时，飞牛和 WebDAV 两种都能直接添加。 */
@Composable
fun NoSourceState(icon: ImageVector, sub: String) =
    StateMessage(icon, "先添加一个来源", sub, "登录飞牛", action2 = "添加 WebDAV", onAction2 = { com.hark.shiguang.Nav.push(com.hark.shiguang.Route.DavAccounts) }) { com.hark.shiguang.Nav.push(com.hark.shiguang.Route.Login) }

/**
 * 1.1.0 统一错误状态：按 [ErrorKind] 给出说人话的标题、原因和处理按钮（连不上 → 重试，密码错 → 修改账号，
 * 证书不受信任 → 证书设置，空间不足 → 存储空间页）。[detail] 是原始错误文字，小字附在下面方便反馈。
 * [onRetry] 用于「重试」类；其余按钮默认跳到对应页面，调用方可用 [onFix] 覆盖。
 */
@Composable
fun ErrorState(message: String?, onRetry: () -> Unit, onFix: ((com.hark.shiguang.ErrorKind) -> Unit)? = null) {
    val k = com.hark.shiguang.ErrorKind.of(message)
    val icon = when (k) {
        com.hark.shiguang.ErrorKind.NETWORK -> KI.cloud()
        com.hark.shiguang.ErrorKind.AUTH -> KI.lock()
        com.hark.shiguang.ErrorKind.CERT -> KI.shield()
        com.hark.shiguang.ErrorKind.SPACE -> KI.drive()
        com.hark.shiguang.ErrorKind.NOT_FOUND -> KI.folder()
        com.hark.shiguang.ErrorKind.OTHER -> KI.alert()
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = Space.xxl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        // 网络 / 其它错误用中性色（多半能重试）；密码、证书、空间这类要用户处理的用错误色
        Icon(icon, null, tint = if (k == com.hark.shiguang.ErrorKind.OTHER || k == com.hark.shiguang.ErrorKind.NETWORK) C.Faint else C.Danger, modifier = Modifier.size(44.dp).statePop())
        Spacer(Modifier.height(Space.l))
        Text(k.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(k.hint, style = MaterialTheme.typography.bodyMedium, color = C.Sub, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.s).widthIn(max = 300.dp))
        if (!message.isNullOrBlank() && message != k.title) Text(message, color = C.Faint, fontSize = TextSize.caption, textAlign = TextAlign.Center, maxLines = 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.s).widthIn(max = 300.dp))
        Spacer(Modifier.height(Space.xl))
        GradientButton(k.action, Modifier.widthIn(min = 160.dp)) {
            when {
                k == com.hark.shiguang.ErrorKind.NETWORK || k == com.hark.shiguang.ErrorKind.OTHER -> onRetry()
                onFix != null -> onFix(k)
                k == com.hark.shiguang.ErrorKind.AUTH -> com.hark.shiguang.Nav.push(if (HomeState.source == "dav") com.hark.shiguang.Route.DavAccounts else com.hark.shiguang.Route.ConnectionSettings)
                // 用户 2026-10-23：证书设置移进了「高级」，直接打开高级页，不再让人在设置首页里找
                k == com.hark.shiguang.ErrorKind.CERT -> com.hark.shiguang.Nav.push(com.hark.shiguang.Route.AdvancedSettings)
                // 1.7.17：空间不足直接打开「存储空间」页（清理缓存在那里），不再只跳到设置首页让人自己找
                k == com.hark.shiguang.ErrorKind.SPACE -> com.hark.shiguang.Nav.push(com.hark.shiguang.Route.X(ExtScreen.Storage))
                else -> { HomeState.tab = 4; com.hark.shiguang.Nav.reset(com.hark.shiguang.Route.Home) }   // 位置：设置页里有对应入口
            }
        }
        if (k != com.hark.shiguang.ErrorKind.NETWORK && k != com.hark.shiguang.ErrorKind.OTHER)
            QuietButton("重试", C.Sub, Modifier.padding(top = Space.xs), onRetry)
    }
}

/**
 * 1.7.24：主按钮下面的次要文字按钮（空状态的第二个操作、错误页的「重试」）。和 v2 主按钮同一套按压：
 * 液体形变（glassPress，移除动画时只变暗），不再是 Material 的灰色水波；整块 ≥ 48dp 高，读屏是按钮。
 */
@Composable
fun QuietButton(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(modifier.heightIn(min = 48.dp).glassPress(interaction, 0.6f).clip(CircleShape)
        .clickable(interaction, null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(horizontal = Space.l),
        contentAlignment = Alignment.Center) {
        Text(text, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 主按钮（一屏最多一个）。1.7.0 v2：全圆角胶囊、高 52；玻璃风格下表面是上亮下实的强调色 + 上沿白色高光边 + 同色柔光晕
 * （像一颗发光的彩色玻璃），按下液体形变并压暗。简洁风格：实心强调色。名字沿用 GradientButton 以免改动 40 多处调用。
 */
@Composable
fun GradientButton(text: String, modifier: Modifier = Modifier, loading: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = CircleShape
    val accent = C.Accent
    val glass = Glass.on
    Box(
        modifier.heightIn(min = 52.dp).glassPress(interaction, 0.6f)
            .then(if (glass) Modifier.drawWithCache {
                val layers = SkeuoShadow.layers(14.dp.toPx(), 0.55f)
                val outlines = layers.map { l -> shape.createOutline(androidx.compose.ui.geometry.Size(size.width + 2 * l.spread, size.height + 2 * l.spread), layoutDirection, this) }
                onDrawBehind { layers.forEachIndexed { i, l -> translate(-l.spread, -l.spread + l.dy) { drawOutline(outlines[i], accent.copy(alpha = l.alpha)) } } }
            } else Modifier)
            .clip(shape)
            .background(if (glass) Brush.verticalGradient(listOf(androidx.compose.ui.graphics.lerp(accent, Color.White, 0.18f), accent)) else androidx.compose.ui.graphics.SolidColor(accent))
            .then(if (pressed) Modifier.background(Color.Black.copy(alpha = 0.1f)) else Modifier)
            .then(if (glass) Modifier.border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.5f), Color.Transparent)), shape) else Modifier)
            .clickable(interaction, null, enabled = !loading, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = Space.xl + 4.dp),
        contentAlignment = Alignment.Center
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = C.OnAccent, strokeWidth = 2.dp)
        else Text(text, color = C.OnAccent, style = MaterialTheme.typography.labelLarge, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** 次要按钮：1.7.0 玻璃胶囊（磨砂 + 高光边）+ 正文色，和主按钮同高、同样的按压形变。简洁风格中性底。 */
@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier.heightIn(min = 52.dp).glassPress(interaction, 0.6f).glass(CircleShape, null, tint = C.Surface, shadow = 6.dp).clip(CircleShape)
            .clickable(interaction, null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = Space.xl + 4.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = C.Text, style = MaterialTheme.typography.labelLarge, fontSize = 16.sp, maxLines = 1) }
}

/**
 * 选择胶囊（筛选、分类）。选中 = 强调色 14% 浅底 + 强调色文字（不用实心色块抢照片）；32dp 高，触摸区补到 48dp。
 */
@Composable
fun ChoiceChip(text: String, selected: Boolean, modifier: Modifier = Modifier, role: androidx.compose.ui.semantics.Role = androidx.compose.ui.semantics.Role.Tab, onClick: () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    // 1.7.0 v2：全圆角胶囊；玻璃风格下未选中是磨砂玻璃，选中是实心强调色 + 近黑 / 白字（更饱和、一眼可见）
    val glass = Glass.on
    val bg by androidx.compose.animation.animateColorAsState(if (selected) (if (glass) C.Accent else C.AccentSoft) else if (glass) Color.Transparent else C.Chip, Motion.state(), label = "chipBg")
    val fg by androidx.compose.animation.animateColorAsState(if (selected) (if (glass) C.OnAccent else C.Accent) else C.Sub, Motion.state(), label = "chipFg")
    Box(modifier.minimumInteractiveComponentSize().pressScale().then(if (glass) Modifier.glass(CircleShape, null, tint = C.Surface, shadow = 0.dp, highlight = 0.7f) else Modifier).clip(CircleShape).background(bg)
        .clickable(role = role) { if (!selected) Haptics.tick(view); onClick() }.semantics { this.selected = selected }
        .heightIn(min = 32.dp).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun RoundIcon(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = C.Text, bg: Color = C.Chip, size: Dp = 40.dp, desc: String? = null, onClick: () -> Unit) {
    // 顶栏的圆形按钮：拟物时是凸起的小圆钮（按下内凹），只在页面的默认底色上这样画；调用方给了别的底色（例如照片上的半透明黑）就保持原样
    // 1.6.0：两种风格都按下缩放；质感风格是浮起的小圆钮，简洁风格是中性浅底。照片上的半透明黑底保持原样（只缩放）
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val base = if (bg != C.Chip) bg else if (Skeuo.on) C.Surface else C.Chip
    Box(
        modifier.size(size).skeuoPressable(interaction, CircleShape, base, scale = 0.92f, flat = bg != C.Chip || !Skeuo.on)
            .clickable(interaction, null, onClick = onClick)
            .then(if (desc != null) Modifier.semantics { contentDescription = desc; role = androidx.compose.ui.semantics.Role.Button } else Modifier),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

/**
 * 优化 11 / 12（用户 2026-11-02）：后台刷新 / 刷新失败时列表上方的一行小字，不挡内容。
 * [error] 不为空 = 刷新失败（离线、登录过期…），列表是上次缓存的内容。
 */
@Composable
fun QuietStatusLine(scanning: Boolean, error: String?, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        if (scanning) {
            CircularProgressIndicator(color = C.Sub, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
            Text("正在更新…", color = C.Sub, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
        } else if (error != null) {
            Icon(KI.cloud(), null, tint = C.Sub, modifier = Modifier.size(14.dp))
            Text(offlineText(error), color = C.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
            if (onRetry != null) TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试", color = C.Accent, fontSize = 13.sp) }
        }
    }
}

/** 刷新失败时的一句话：列表照常显示上次的内容，打开具体文件时再提示。 */
fun offlineText(error: String): String = when (com.hark.shiguang.ErrorKind.of(error)) {
    com.hark.shiguang.ErrorKind.AUTH -> "登录已过期，显示的是上次的列表"
    com.hark.shiguang.ErrorKind.NETWORK -> "连不上网盘 / NAS，显示的是上次的列表"
    else -> "更新失败，显示的是上次的列表"
}

/**
 * 第 4 批：影视 / 音乐共用的扫描进度卡（ScanStatusCard 式统一进度卡）。
 * 以前 MoviesTab.ScanBar 和 MusicTab.MusicScanBar 是两份几乎一样的实现（标题行 + 错误行 + 进度条），
 * 现在都调这个；样式保持原样（Surface 底、14dp 圆角、3dp 进度条），只抽公共部分，不改行为。
 * [progress] 为 null = 不确定进度（转圈条）；[error] 只在非扫描时显示。
 */
@Composable
fun ScanProgressCard(
    title: String,
    countText: String?,
    scanning: Boolean,
    error: String?,
    progress: Float?,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().skeuoCard(RoundedCornerShape(Radius.m)).padding(horizontal = Space.l, vertical = Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (countText != null) Text(countText, color = C.Sub, fontSize = 12.sp, style = Type.num)
        }
        if (error != null && !scanning) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            // H16: an error card always offers the way out
            if (onRetry != null) TextButton(onClick = onRetry) { Text("重试", color = C.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
        if (scanning) {
            if (progress == null) LinearProgressIndicator(color = C.Accent, trackColor = C.Surface2, modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)))
            else LinearProgressIndicator(progress = { progress }, color = C.Accent, trackColor = C.Surface2, modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)))
        }
    }
}
