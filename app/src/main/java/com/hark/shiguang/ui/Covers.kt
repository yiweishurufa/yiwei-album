package com.hark.shiguang.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 1.6.2 阶段 3：合集页与发现页共用的封面组件（DESIGN §3 §5 §7；ROADMAP C3「人物 / 地点卡片统一」）。
 * - [CoverTile]：方形封面 + 下方标题 / 计数。相册、分类、特殊入口（收藏 / 最近 / 隐藏）。
 * - [CoverCard]：竖向封面，标题压在照片底部。地点、旅行。
 * - [PersonChip]：圆形头像 + 名字（+ 张数）。飞牛 / WebDAV 人物同一款。
 * - [ContentHeader]：内容分区标题（titleMedium + 次要色计数 + 右侧文字按钮）。
 * 封面统一圆角 [Radius.l]、按压 0.985；首屏前几张带 [enterRise] 错开进入；读屏把整张卡读成一个按钮。
 */
object CoverSize {
    val tile = 128.dp
    val cardW = 150.dp
    val cardH = 180.dp
    val avatar = 68.dp
    val person = 76.dp
    /** 横滑行里卡片之间。 */
    val gap = 12.dp
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoverTile(title: String, sub: String, cover: String?, icon: ImageVector? = null, index: Int = Int.MAX_VALUE, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(
        Modifier.enterRise(index).width(CoverSize.tile).pressScale(Motion.PRESS_SCALE_LARGE)
            .clip(RoundedCornerShape(Radius.m))
            .combinedClickable(onLongClick = onLong, onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = if (sub.isEmpty()) title else "$title，$sub" }
    ) {
        Box(Modifier.size(CoverSize.tile).skeuoPhoto(RoundedCornerShape(Radius.l)).background(C.Surface2), contentAlignment = Alignment.Center) {
            if (cover != null) NetImage(cover, Modifier.fillMaxSize())
            // 没有封面的入口：线性图标用次要色，不再铺彩色（DESIGN §1 去 AI 味：彩色圆底 + 图标）
            else if (icon != null) Icon(icon, null, tint = C.Sub, modifier = Modifier.size(28.dp))
        }
        Text(title, color = C.Text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Space.xxs, top = Space.s))
        if (sub.isNotEmpty()) Text(sub, color = C.Sub, style = MaterialTheme.typography.bodySmall.merge(Type.num), maxLines = 1,
            modifier = Modifier.padding(start = Space.xxs))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoverCard(title: String, sub: String, cover: String?, small: Boolean = false, index: Int = Int.MAX_VALUE, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val w = if (small) 120.dp else CoverSize.cardW; val h = if (small) 120.dp else CoverSize.cardH
    Box(
        Modifier.enterRise(index).size(w, h).pressScale(Motion.PRESS_SCALE_LARGE).skeuoPhoto(RoundedCornerShape(Radius.l))
            .combinedClickable(onLongClick = onLong, onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = if (sub.isEmpty()) title else "$title，$sub" }
    ) {
        NetImage(cover, Modifier.fillMaxSize())
        // 只压暗底部 45%：标题可读，照片上半部分保持干净
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0x99000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(Space.m)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall.merge(Type.num), maxLines = 1)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonChip(name: String, image: String?, count: Int? = null, index: Int = Int.MAX_VALUE, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val shown = name.ifEmpty { "未命名" }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.enterRise(index).width(CoverSize.person).pressScale(Motion.PRESS_SCALE).clip(RoundedCornerShape(Radius.m))
            .combinedClickable(onLongClick = onLong, onClickLabel = "查看${name.ifEmpty { "未命名人物" }}的照片", onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = if (count != null) "$shown，$count 张" else shown }
            .padding(vertical = Space.xs)
    ) {
        Box(Modifier.size(CoverSize.avatar).skeuoPhoto(CircleShape).background(C.Surface2)) { NetImage(image, Modifier.fillMaxSize()) }
        Text(shown, style = MaterialTheme.typography.bodySmall, color = if (name.isEmpty()) C.Sub else C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Space.s))
        if (count != null) Text("$count 张", style = MaterialTheme.typography.labelSmall.merge(Type.num), color = C.Sub, maxLines = 1)
    }
}

/** 封面加载中的占位：形状与真实卡片一致（DESIGN 阶段 7「骨架屏形状与真实布局一致」）。 */
@Composable
fun CoverTileSkeleton() {
    Column(Modifier.width(CoverSize.tile)) {
        Box(Modifier.size(CoverSize.tile).clip(RoundedCornerShape(Radius.l)).background(shimmerBrush()))
        Box(Modifier.padding(top = Space.s).size(72.dp, 12.dp).clip(RoundedCornerShape(Radius.xs)).background(shimmerBrush()))
    }
}

@Composable
fun CoverCardSkeleton() {
    Box(Modifier.size(CoverSize.cardW, CoverSize.cardH).clip(RoundedCornerShape(Radius.l)).background(shimmerBrush()))
}

@Composable
fun PersonChipSkeleton() {
    Column(Modifier.width(CoverSize.person).padding(vertical = Space.xs), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(CoverSize.avatar).clip(CircleShape).background(shimmerBrush()))
        Box(Modifier.padding(top = Space.s).size(40.dp, 12.dp).clip(RoundedCornerShape(Radius.xs)).background(shimmerBrush()))
    }
}

/**
 * 内容分区标题：标题（titleMedium，读屏「标题」）+ 次要色计数；右侧最多两个文字按钮（[extra] 次要色，[action] 强调色），
 * 每个按钮 ≥ 40dp 高。
 */
@Composable
fun ContentHeader(title: String, count: String = "", action: String? = null, extra: Pair<String, () -> Unit>? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.xs, top = Space.l, bottom = Space.xs).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.Bottom) {
            Text(title, color = C.Text, style = MaterialTheme.typography.titleMedium)
            if (count.isNotEmpty()) Text(count, color = C.Sub, style = MaterialTheme.typography.bodySmall.merge(Type.num), modifier = Modifier.padding(start = Space.s, bottom = 1.dp))
        }
        if (extra != null) HeaderTextButton(extra.first, C.Sub, extra.second)
        if (action != null) HeaderTextButton(action, C.Accent, onAction)
    }
}

@Composable
fun HeaderTextButton(text: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(Radius.s)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = Space.m),
        contentAlignment = Alignment.Center) {
        Text(text, color = color, style = MaterialTheme.typography.labelLarge)
    }
}
