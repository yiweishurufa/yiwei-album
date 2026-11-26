package com.hark.shiguang.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 1.7.13 设置 → 存储空间（ROADMAP C7）：把一维相册在本机的占用按类分开看，一键清理缓存。
 * 只读本机 App 目录（cacheDir / filesDir / databases），不改任何存储格式；1.7.17 起设置首页的「清理缓存」「缩略图缓存上限」两行并进这一页（[StorageUsage.clearCache]），
 * 照片索引、人物、影视 / 音乐资料、诊断日志、更新包不在一键清理范围内（清了要重新扫描 / 重新下载）。
 */
object StorageUsage {
    enum class Kind(val label: String, val sub: String, val clearable: Boolean) {
        THUMBS("照片缩略图", "浏览时缓存的缩略图", true),
        VIDEO("视频缓存", "实况视频、字幕", true),
        OTHER("其他缓存", "地图、分享和编辑的临时文件", true),
        MEDIA("影视与音乐资料", "海报、封面、歌词和媒体库，在影音设置里清空", false),
        INDEX("照片索引与人物", "各账号的照片索引、人物、标签", false),
        LOGS("日志与更新包", "诊断日志、崩溃记录、下载的更新", false),
    }

    data class Part(val kind: Kind, val bytes: Long)

    /** 每次在存储空间页清理后 +1，设置页「存储空间」一行的「可清理 X」据此重新统计。 */
    var version by mutableIntStateOf(0)

    /** cacheDir 里不被清理的：诊断日志、更新包、崩溃记录（B38）。 */
    internal fun keptInCache(name: String) = name == "diag" || name == "update" || name.startsWith("crash")

    /** cacheDir 一级目录 / 文件名 → 分类。 */
    internal fun cacheKind(name: String): Kind = when {
        keptInCache(name) -> Kind.LOGS
        name == "thumbs" || name == "davthumb" -> Kind.THUMBS
        name == "live" || name == "subs" -> Kind.VIDEO
        else -> Kind.OTHER
    }

    /** filesDir 下 [rel]（相对路径，/ 分隔）→ 分类：{dav|fn}/<账号>/movies|music 是影视与音乐资料，crash 是日志，其余是索引。 */
    internal fun filesKind(rel: String): Kind {
        val seg = rel.split('/')
        return when {
            seg.firstOrNull() == "crash" -> Kind.LOGS
            seg.size >= 3 && (seg[0] == "dav" || seg[0] == "fn") && (seg[2] == "movies" || seg[2] == "music") -> Kind.MEDIA
            else -> Kind.INDEX
        }
    }

    private fun size(f: File): Long = if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** 在 IO 线程调用。按 [Kind] 顺序返回每类的字节数（0 也返回）。 */
    fun measure(ctx: Context): List<Part> {
        val sum = LongArray(Kind.values().size)
        ctx.cacheDir.listFiles()?.forEach { sum[cacheKind(it.name).ordinal] += runCatching { size(it) }.getOrDefault(0L) }
        val files = ctx.filesDir
        files.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(files).invariantSeparatorsPath
            sum[filesKind(rel).ordinal] += f.length()
        }
        // Room 照片索引（databases/）算进索引
        ctx.getDatabasePath("x").parentFile?.takeIf { it.exists() }?.let { sum[Kind.INDEX.ordinal] += runCatching { size(it) }.getOrDefault(0L) }
        return Kind.values().map { Part(it, sum[it.ordinal]) }
    }

    /** 可清理缓存大小：与 [clearCache] 清的范围一致，避免清完仍显示有缓存。 */
    fun clearableCacheSize(dir: File): Long =
        dir.listFiles()?.filter { !keptInCache(it.name) }?.sumOf { c -> runCatching { size(c) }.getOrDefault(0L) } ?: 0L

    /** 清理缓存（IO 线程）：cacheDir 里除日志 / 更新包 / 崩溃记录以外的全部 + 内存里的地址表。返回清理后剩余的可清理大小。 */
    fun clearCache(ctx: Context): Long {
        // B38: never delete the diagnostic log, downloaded update packages, or crash reports —
        // they are the evidence when something goes wrong, and the update would need re-downloading.
        ctx.cacheDir.listFiles()?.filter { !keptInCache(it.name) }?.forEach { it.deleteRecursively() }
        // H13: the in-memory url tables go too (redirect targets + entries of photos no longer in any library)
        DavThumb.clearUrlCache(); PlayHttp.resolved.clear()
        runCatching { CloudPhotos.trimTo(Dav.loadedLibs().flatMap { it.photos.toList() }) }.onFailure { Diag.e("CACHE", it) }
        return clearableCacheSize(ctx.cacheDir)
    }
}

/** 缓存大小显示：不足 1 MB 用 KB，不再显示「0 MB」；1 GB 以上用 GB。 */
internal fun sizeLabel(bytes: Long): String = when {
    bytes < 1024L -> "${bytes.coerceAtLeast(0L)} B"
    bytes < 1024L * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / 1048576.0)} MB"
    else -> "${"%.2f".format(bytes / 1073741824.0)} GB"
}

/** 每类的颜色（环形图 / 色点）；可清理的三类用暖亮色，保留的三类用冷色和灰，一眼分得出「能清的有多少」。 */
private fun kindColor(k: StorageUsage.Kind): Color = when (k) {
    StorageUsage.Kind.THUMBS -> C.Accent
    StorageUsage.Kind.VIDEO -> Color(0xFFF5A524)
    StorageUsage.Kind.OTHER -> Color(0xFFEF5DA8)
    StorageUsage.Kind.MEDIA -> Color(0xFF8B7CFF)
    StorageUsage.Kind.INDEX -> Color(0xFF2EC4A6)
    StorageUsage.Kind.LOGS -> C.Faint
}

@Composable
fun StorageScreen() {
    val ctx = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val scope = rememberCoroutineScope()
    var parts by remember { mutableStateOf<List<StorageUsage.Part>?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    // 1.7.17：缩略图缓存上限从设置首页移到这里（存储键 thumbCacheMb 不变）
    var limitOpen by remember { mutableStateOf(false) }
    var limitMb by remember { mutableIntStateOf(Store.thumbCacheMb) }
    LaunchedEffect(tick) { parts = withContext(Dispatchers.IO) { runCatching { StorageUsage.measure(ctx) }.getOrNull() ?: emptyList() } }
    val total = parts?.sumOf { it.bytes } ?: 0L
    val clearable = parts?.filter { it.kind.clearable }?.sumOf { it.bytes } ?: 0L
    // 环形图从 0 长到位（移除动画时直接到位）；每次重新统计后重播
    val grow = remember { Animatable(if (Motion.reduced) 1f else 0f) }
    LaunchedEffect(parts) { if (parts != null && !Motion.reduced) { grow.snapTo(0f); grow.animateTo(1f, Motion.zoom()) } }

    Page("存储空间", actions = {
        InfoDot("这里只统计一维相册在这台手机上占用的空间，不包括飞牛 / WebDAV 里的照片。「清理缓存」只删缩略图、视频缓存和临时文件，" +
            "下次浏览时会重新下载；照片索引、人物、影视与音乐资料、诊断日志和更新包不会被清理。", "存储空间")
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 40.dp)) {
            // ---- 总览：环形图 + 总量 + 一键清理（玻璃卡片）
            Card {
                Column(Modifier.fillMaxWidth().padding(vertical = 22.dp, horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val ringDesc = parts?.let { ps -> "一维相册共占用 ${sizeLabel(total)}，" + ps.filter { it.bytes > 0 }.joinToString("，") { "${it.kind.label} ${sizeLabel(it.bytes)}" } } ?: "正在统计"
                    Box(Modifier.size(196.dp).semantics { contentDescription = ringDesc }, contentAlignment = Alignment.Center) {
                        val track = C.Text.copy(alpha = 0.08f)
                        val colors = StorageUsage.Kind.values().map { kindColor(it) }
                        Canvas(Modifier.fillMaxSize()) {
                            val w = 18.dp.toPx()
                            val r = (size.minDimension - w) / 2f
                            val tl = Offset(center.x - r, center.y - r); val sz = Size(r * 2, r * 2)
                            drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(w))
                            val ps = parts ?: return@Canvas
                            if (total <= 0L) return@Canvas
                            val shown = ps.filter { it.bytes > 0 }
                            // 每段之间留 3° 缝；很小的一段至少画 2°，不至于看不见
                            val gap = if (shown.size > 1) 3f else 0f
                            val avail = 360f - gap * shown.size
                            var a = -90f
                            val g = grow.value
                            shown.forEach { p ->
                                val sweep = maxOf(2f, avail * p.bytes / total) * g
                                drawArc(colors[p.kind.ordinal], a + gap / 2, sweep, false, tl, sz, style = Stroke(w, cap = StrokeCap.Butt))
                                a += (sweep + gap * g)
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clearAndSetSemantics { }) {
                            Text(if (parts == null) "…" else sizeLabel(total), color = C.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = C.P.display, fontFeatureSettings = Type.TNUM))
                            Text("一维相册占用", color = C.Sub, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(when { parts == null -> "正在统计…"; clearable == 0L -> "没有可清理的缓存"; else -> "可清理 ${sizeLabel(clearable)}" },
                        color = C.Sub, style = MaterialTheme.typography.bodyMedium.merge(Type.num))
                    Spacer(Modifier.height(12.dp))
                    GradientButton(if (clearing) "清理中…" else "清理缓存", Modifier.fillMaxWidth(), loading = clearing) {
                        if (clearing || parts == null) return@GradientButton
                        if (clearable == 0L) { toast(ctx, "没有可清理的缓存"); return@GradientButton }
                        clearing = true
                        scope.launch {
                            try {
                                val before = clearable
                                val left = withContext(Dispatchers.IO) { StorageUsage.clearCache(ctx) }
                                val freed = (before - left).coerceAtLeast(0L)
                                Haptics.confirm(view)
                                toast(ctx, if (freed > 0) "缓存已清理，释放 ${sizeLabel(freed)}" else "缓存已清理")
                            } finally { clearing = false; tick++; StorageUsage.version++ }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card {
                NavRow(KI.drive(), "缩略图缓存上限", value = CacheLimit.label(limitMb)) { limitOpen = true }
            }

            // ---- 分类明细
            Section("分类")
            Card {
                val ps = parts
                if (ps == null) ListRowSkeleton(rows = 4, cover = 12.dp, coverShape = CircleShape)
                else ps.forEachIndexed { i, p ->
                    if (i > 0) Div()
                    StorageRow(p, total)
                }
            }
            Text("「清理缓存」只清上面带「可清理」的三类。照片索引和人物清掉要重新扫描，所以不在这里清；影视、音乐资料请到「影音设置」里按来源清空。",
                color = C.Sub, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
        }
    }
    if (limitOpen) ActionListDialog("缩略图缓存上限", sub = "超过上限会自动删掉最久没看的缩略图，重启 App 后生效", onDismiss = { limitOpen = false },
        actions = CacheLimit.OPTIONS_MB.map { mb -> (CacheLimit.label(mb) + if (mb == limitMb) "  ✓" else "") to { Store.thumbCacheMb = mb; limitMb = mb; limitOpen = false } })
}

@Composable
private fun StorageRow(p: StorageUsage.Part, total: Long) {
    val color = kindColor(p.kind)
    val frac = if (total > 0) (p.bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    val desc = "${p.kind.label}，${sizeLabel(p.bytes)}" + (if (p.kind.clearable) "，可清理" else "") + "。${p.kind.sub}"
    Row(Modifier.fillMaxWidth().heightIn(min = Space.row).clearAndSetSemantics { contentDescription = desc }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.kind.label, color = C.Text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                if (p.kind.clearable) {
                    Spacer(Modifier.width(8.dp))
                    Text("可清理", color = C.Sub, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clip(CircleShape).background(C.Text.copy(alpha = 0.07f)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            Text(p.kind.sub, color = C.Sub, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            // 占比条（4dp 圆头），与环形图同色
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.Text.copy(alpha = 0.08f))) {
                if (frac > 0f) Box(Modifier.fillMaxWidth(maxOf(frac, 0.015f)).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(color))
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(sizeLabel(p.bytes), color = C.Text, style = MaterialTheme.typography.bodyMedium.merge(Type.num), fontWeight = FontWeight.SemiBold)
    }
}
