package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 沉浸视图第一步（下一版合并计划第 6 批，参照飞牛官方相册 10-29 新增的「沉浸视图」）：
 * 图库从网格切成单列大图流——去掉网格边距，每张按原比例铺满宽度，日期只留一行小字，点开进大图。
 * 视频 / 实况先显示角标，自动播放留到下一阶段。
 */
@Composable
fun ImmersiveFeed(
    entries: List<Entry>,
    loadingMore: Boolean,
    onEnd: () -> Unit,
    onOpen: (Int) -> Unit,
    state: androidx.compose.foundation.lazy.LazyListState,
    bottom: Dp,
    header: @Composable () -> Unit,
) {
    val nearEnd by remember(entries) { derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= entries.size - 8 } }
    LaunchedEffect(nearEnd, entries.size) { if (nearEnd && entries.isNotEmpty()) onEnd() }
    // 1.6.4（DESIGN 阶段 2「沉浸大图流」）：卡片左右留 8dp、圆角 14、卡间 8dp；日期标题与网格同款（Allo 等宽日期 + 次要色副标题）；
    // 视频时长 / 实况角标用小号图标 + Allo 数字；点开时从卡片位置展开（与网格同一套 Hero 共享元素转场）
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = bottom), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        item(key = "header") { header() }
        itemsIndexed(entries, key = { _, e -> when (e) { is Entry.Header -> "h" + e.key; is Entry.Cell -> "c" + e.photo.key } }) { _, e ->
            when (e) {
                is Entry.Header -> Row(Modifier.padding(start = Space.l, end = Space.l, top = Space.l).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.Bottom) {
                    Text(e.title, color = C.Text, style = MaterialTheme.typography.titleMedium.copy(fontFamily = C.P.display, fontWeight = FontWeight.SemiBold, fontFeatureSettings = Type.TNUM))
                    if (e.sub.isNotEmpty()) Text(e.sub, color = C.Sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.padding(start = Space.s, bottom = 1.dp))
                }
                is Entry.Cell -> {
                    val p = e.photo
                    val ratio = if (p.width > 0 && p.height > 0) (p.width.toFloat() / p.height).coerceIn(0.56f, 1.8f) else 1f
                    val badge = when {
                        p.isVideo -> if (p.duration > 0) "%d:%02d".format(p.duration / 60, p.duration % 60) else "视频"
                        p.isLive -> "实况"
                        else -> null
                    }
                    DisposableEffect(p.key) { onDispose { Hero.bounds.remove(p.key) } }
                    Box(Modifier.padding(horizontal = Space.s).fillMaxWidth().aspectRatio(ratio)
                        .onGloballyPositioned { Hero.bounds[p.key] = it.boundsInRoot() }
                        .clip(RoundedCornerShape(Radius.m)).background(C.Surface2)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "查看") { Hero.open(p); onOpen(e.index) }
                        .semantics { contentDescription = (if (p.isVideo) "视频" else if (p.isLive) "实况照片" else "照片") + (if (p.isVideo && p.duration > 0) "，时长 ${p.duration / 60} 分 ${p.duration % 60} 秒" else "") }) {
                        NetImage(p.thumbM, Modifier.fillMaxSize())
                        if (badge != null) Row(
                            Modifier.align(Alignment.TopEnd).padding(Space.s).clip(RoundedCornerShape(Radius.xs)).background(Color(0x66000000)).padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(if (p.isVideo) KI.play() else KI.live(), null, tint = Color.White, modifier = Modifier.size(12.dp))
                            Text(badge, color = Color.White, style = Type.badge, modifier = Modifier.padding(start = 3.dp))
                        }
                    }
                }
            }
        }
        if (loadingMore) item(key = "more") {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp)) }
        }
    }
}
