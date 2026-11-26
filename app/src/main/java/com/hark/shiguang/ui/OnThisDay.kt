package com.hark.shiguang.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.Hidden
import com.hark.shiguang.Nav
import com.hark.shiguang.Route
import com.hark.shiguang.data.Photo
import com.hark.shiguang.data.Repo
import java.time.LocalDate

/** One year's photos taken on today's month-day. */
data class MemoryGroup(val yearsAgo: Int, val year: Int, val photos: List<Photo>)

/**
 * 那年今天 (1.0.9). Uses the taken time already known: WebDAV = the whole local index (EXIF time after
 * analysis); 飞牛 = the day index the timeline already downloaded, then one small list call per matching
 * day (no thumbnails or EXIF are downloaded for this).
 */
object OnThisDay {
    fun md(d: LocalDate = LocalDate.now()) = "%02d:%02d".format(d.monthValue, d.dayOfMonth)

    /** Groups [photos] (stills only) taken on today's month-day in earlier years, newest year first. */
    fun from(photos: List<Photo>, today: LocalDate = LocalDate.now()): List<MemoryGroup> {
        val key = md(today)
        return photos.asSequence().filter { !it.isVideo && it.time.length >= 10 && it.time.substring(5, 10) == key }
            .groupBy { it.time.take(4).toIntOrNull() ?: 0 }
            .filter { (y, _) -> y in 1971 until today.year }
            .map { (y, l) -> MemoryGroup(today.year - y, y, l.sortedBy { it.time }) }
            .sortedBy { it.yearsAgo }
    }

    fun feedWidget(sourceKey: String, groups: List<MemoryGroup>, pool: () -> List<Photo>) =
        com.hark.shiguang.MemoryWidget.feed(com.hark.shiguang.App.ctx, sourceKey,
            groups.map { g -> (if (g.yearsAgo == 1) "一年前的今天" else "${g.yearsAgo} 年前的今天") to Hidden.visible(g.photos) }.filter { it.second.isNotEmpty() }) { Hidden.visible(pool()) }

    // 飞牛: cached per account + date
    private val fnCache = HashMap<String, List<MemoryGroup>>()
    suspend fun loadFn(tl: TimelineSource, accountId: String): List<MemoryGroup> {
        val today = LocalDate.now()
        val ck = "$accountId@$today"
        fnCache[ck]?.let { return it }
        val days = tl.days.filter { it.month == today.monthValue && it.day == today.dayOfMonth && it.year < today.year && it.count > 0 }
        if (tl.days.isEmpty()) return emptyList()
        val out = days.sortedByDescending { it.year }.take(12).mapNotNull { d ->
            val day = "%04d:%02d:%02d".format(d.year, d.month, d.day)
            val l = runCatching { Repo.photos("$day 00:00:00", "$day 23:59:59", 0, 80).first }.getOrDefault(emptyList()).filter { !it.isVideo }
            if (l.isEmpty()) null else MemoryGroup(today.year - d.year, d.year, l.sortedBy { it.time })
        }
        fnCache[ck] = out
        return out
    }
}

/**
 * 1.7.16：照片页大图下方三行字（kicker / 大标题 / 副标题）。大图是回忆封面时三行都说这组回忆
 * （「一年前的今天」/「11月26日」/「2025年 · 2 张」），以前标题是时间线当前月份（「11月」/「2026年 · 19,412 项」），
 * 和压在上面的「一年前的今天」、和封面那张照片都对不上。没有回忆时照旧显示时间线月份与总数。
 * 滚过大图后顶栏胶囊仍显示时间线位置（那是网格里滚到哪个月，与封面无关）。
 */
object HeroText {
    data class Labels(val kicker: String?, val title: String, val sub: String)

    fun kicker(yearsAgo: Int): String = if (yearsAgo == 1) "一年前的今天" else "$yearsAgo 年前的今天"

    /** [memTime] = 回忆封面那张照片的拍摄时间（"YYYY:MM:DD …"），读不出月日时用 [today]。 */
    fun labels(mem: MemoryGroup?, count: Int, memTime: String?, today: LocalDate, timelineTitle: String, timelineSub: String): Labels {
        if (mem == null) return Labels(null, timelineTitle, timelineSub)
        val m = memTime?.takeIf { it.length >= 10 }?.substring(5, 7)?.toIntOrNull()?.takeIf { it in 1..12 } ?: today.monthValue
        val d = memTime?.takeIf { it.length >= 10 }?.substring(8, 10)?.toIntOrNull()?.takeIf { it in 1..31 } ?: today.dayOfMonth
        return Labels(kicker(mem.yearsAgo), "${m}月${d}日", "${mem.year}年 · ${"%,d".format(java.util.Locale.US, count)} 张")
    }
}

/** Horizontal row of 那年今天 cards; nothing when there are no memories. Tap opens the viewer for that year. */
@Composable
fun MemoriesRow(groups: List<MemoryGroup>) {
    val visible = remember(groups, Hidden.version) { groups.mapNotNull { g -> Hidden.visible(g.photos).takeIf { it.isNotEmpty() }?.let { g.copy(photos = it) } } }
    AnimatedVisibility(visible.isNotEmpty(), enter = fadeIn(Motion.enter()) + expandVertically(Motion.enter())) {
        Column(Modifier.padding(bottom = Space.s)) {
            // 1.6.2：分区标题 titleMedium（DESIGN §3），日期是次要色副标题，同一基线
            Row(Modifier.padding(start = 18.dp, end = Space.l, top = Space.m, bottom = Space.m).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.Bottom) {
                Text("那年今天", color = C.Text, style = MaterialTheme.typography.titleLarge)
                Text(LocalDate.now().let { "${it.monthValue}月${it.dayOfMonth}日" }, color = C.Sub, style = MaterialTheme.typography.bodySmall.merge(Type.num), modifier = Modifier.padding(start = Space.s, bottom = 1.dp))
            }
            LazyRow(contentPadding = PaddingValues(horizontal = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                itemsIndexed(visible, key = { _, g -> g.year }) { i, g ->
                    val title = if (g.yearsAgo == 1) "一年前" else "${g.yearsAgo} 年前"
                    // 封面卡：大卡按压 0.985（DESIGN §7）、圆角 Radius.l，首屏前几张错开上浮进入
                    // 1.7.0 v2：更大的竖卡（150×200、圆角 24），标题压在底部的一枚玻璃标签里
                    Box(Modifier.enterRise(i).size(150.dp, 200.dp).pressScale(Motion.PRESS_SCALE_LARGE).skeuoPhoto(RoundedCornerShape(Radius.l))
                        .clickable(role = Role.Button, onClickLabel = "查看") { Nav.push(Route.Viewer(g.photos, 0)) }
                        .semantics(mergeDescendants = true) { contentDescription = "$title，${g.year} 年，${g.photos.size} 张" }) {
                        NetImage(g.photos.first().thumbM, Modifier.fillMaxSize())
                        Column(Modifier.align(Alignment.BottomStart).padding(Space.s).glass(RoundedCornerShape(Radius.l - Space.s), null, tint = Color.Black.copy(alpha = 0.55f), shadow = 0.dp, highlight = 0.6f, onDark = true)
                            .padding(horizontal = Space.m, vertical = Space.s)) {
                            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("${g.year} · ${g.photos.size} 张", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall.merge(Type.num), modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }
    }
}
