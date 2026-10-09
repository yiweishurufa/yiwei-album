package com.hark.shiguang.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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

/** Horizontal row of 那年今天 cards; nothing when there are no memories. Tap opens the viewer for that year. */
@Composable
fun MemoriesRow(groups: List<MemoryGroup>) {
    val visible = remember(groups, Hidden.version) { groups.mapNotNull { g -> Hidden.visible(g.photos).takeIf { it.isNotEmpty() }?.let { g.copy(photos = it) } } }
    AnimatedVisibility(visible.isNotEmpty(), enter = fadeIn() + expandVertically()) {
        Column(Modifier.padding(bottom = 6.dp)) {
            Row(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                Text("那年今天", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(LocalDate.now().let { "${it.monthValue}月${it.dayOfMonth}日" }, color = C.Sub, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, bottom = 1.dp))
                Spacer(Modifier.weight(1f))
                Text("生成短片", color = C.Accent, fontSize = 13.sp, modifier = Modifier.clickableNoRipple {
                    val d = LocalDate.now()
                    openMemoryClip("那年今天", "${d.monthValue}月${d.dayOfMonth}日 · ${visible.joinToString("、") { it.year.toString() }}", visible.flatMap { it.photos })
                })
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(visible, key = { it.year }) { g ->
                    Box(Modifier.size(132.dp, 168.dp).pressScale().clip(RoundedCornerShape(C.Card)).background(C.Surface)
                        .clickableNoRipple { Nav.push(Route.Viewer(g.photos, 0)) }) {
                        NetImage(g.photos.first().thumbM, Modifier.fillMaxSize())
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xB3000000))))
                        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                            Text(if (g.yearsAgo == 1) "一年前" else "${g.yearsAgo} 年前", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("${g.year} · ${g.photos.size} 张", color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
