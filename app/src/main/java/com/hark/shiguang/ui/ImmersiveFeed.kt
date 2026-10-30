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
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = bottom), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item(key = "header") { header() }
        itemsIndexed(entries, key = { _, e -> when (e) { is Entry.Header -> "h" + e.key; is Entry.Cell -> "c" + e.photo.key } }) { _, e ->
            when (e) {
                is Entry.Header -> Text(listOf(e.title, e.sub).filter { it.isNotEmpty() }.joinToString("  "), color = C.Sub, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
                is Entry.Cell -> {
                    val p = e.photo
                    val ratio = if (p.width > 0 && p.height > 0) (p.width.toFloat() / p.height).coerceIn(0.56f, 1.8f) else 1f
                    Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(C.Surface2).clickable { onOpen(e.index) }) {
                        NetImage(p.thumbM, Modifier.fillMaxSize())
                        if (p.isVideo || p.isLive) Text(if (p.isVideo) (if (p.duration > 0) "▶ %d:%02d".format(p.duration / 60, p.duration % 60) else "▶ 视频") else "实况",
                            color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                                .clip(RoundedCornerShape(6.dp)).background(Color(0x80000000)).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
        }
        if (loadingMore) item(key = "more") {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp)) }
        }
    }
}
