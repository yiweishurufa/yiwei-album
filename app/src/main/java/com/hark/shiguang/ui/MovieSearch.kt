package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.*

/**
 * 用户口述 1 / 审查 1（2026-10-30）：影音页的搜索是搜影片，不再跳到照片搜索。
 * 版式参照用户给的飞牛影视截图：顶部返回 + 搜索框，下面几行横滑筛选（分类 / 排序 / 类型 / 年份），结果三列海报。
 * 筛选项只列库里实际有的；没有数据的行不显示（地区暂无数据，整行不显示）。
 */
@Composable
fun MovieSearchScreen() {
    val key = Movies.keyOf()
    val focus = LocalFocusManager.current
    var text by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(MovieFilter.ALL) }
    var genre by rememberSaveable { mutableStateOf(MovieFilter.ALL) }
    var year by rememberSaveable { mutableStateOf(MovieFilter.ALL) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var history by remember { mutableStateOf(MovieSearchHistory.load()) }
    fun saveQuery(q: String) { if (q.isNotBlank()) { MovieSearchHistory.add(q); history = MovieSearchHistory.load() } }

    Column(Modifier.fillMaxSize().appBackground()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Nav.pop() }) { Icon(LI.back(), "返回", tint = C.Text, modifier = Modifier.size(22.dp)) }
            Row(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(C.Surface).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(LI.search(), null, tint = C.Faint, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text("输入影片名称搜索", color = C.Faint, fontSize = 15.sp)
                    BasicTextField(text, { text = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = C.Text, fontSize = 15.sp), cursorBrush = SolidColor(C.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { saveQuery(text); focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth())
                }
                if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(LI.close(), "清除搜索", tint = C.Sub, modifier = Modifier.size(18.dp)) }
            }
        }
        if (key == null) { NoSourceState(LI.film(), "影视库跟着左上角的来源走"); return@Column }
        val lib = remember(key) { Movies.lib(key) }
        if (!lib.ready) { LibLoading(); return@Column }
        val all by remember(lib) { derivedStateOf { lib.version; lib.items.toList() } }
        val cats = remember(all) { MovieFilter.categories(all) }
        val genres = remember(all) { MovieFilter.genres(all) }
        val years = remember(all) { MovieFilter.years(all) }
        val q = MovieFilter.Query(text, category, genre, year, sort)
        val shown = remember(all, q) { MovieFilter.apply(all, q) }
        val filtered = category != MovieFilter.ALL || genre != MovieFilter.ALL || year != MovieFilter.ALL

        LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (text.isEmpty() && history.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.bleed(14.dp)) {
                    Row(Modifier.padding(start = 18.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("最近搜索", style = MaterialTheme.typography.labelLarge, color = C.Sub, modifier = Modifier.weight(1f))
                        TextButton(onClick = { MovieSearchHistory.clear(); history = emptyList() }) { Text("清空", color = C.Sub, fontSize = 13.sp) }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(history) { h -> FilterChipText(h, false) { text = h } }
                    }
                }
            }
            if (all.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.bleed(14.dp).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (cats.size > 1) FilterRow(listOf(MovieFilter.ALL) + cats, category, MovieFilter.ALL) { category = it }
                    FilterRow(MovieFilter.SORTS, MovieFilter.SORTS[sort], MovieFilter.SORTS[0]) { sort = MovieFilter.SORTS.indexOf(it).coerceAtLeast(0) }
                    if (genres.isNotEmpty()) FilterRow(listOf("类型") + genres, if (genre == MovieFilter.ALL) "类型" else genre, "类型") { genre = if (it == "类型") MovieFilter.ALL else it }
                    if (years.isNotEmpty()) FilterRow(listOf("年份") + years, if (year == MovieFilter.ALL) "年份" else year, "年份") { year = if (it == "年份") MovieFilter.ALL else it }
                }
            }
            if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 60.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(when {
                        all.isEmpty() && lib.scanning -> "正在扫描影视库…"
                        all.isEmpty() -> "影视库还是空的，先在影音页选好影视文件夹"
                        text.isNotBlank() -> "没有找到「${text.trim()}」"
                        else -> "没有符合筛选条件的影片"
                    }, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    if (filtered) TextButton(onClick = { category = MovieFilter.ALL; genre = MovieFilter.ALL; year = MovieFilter.ALL }) { Text("清除筛选", color = C.Accent) }
                }
            } else items(shown, key = { it.id }) { m ->
                SearchPoster(m) { saveQuery(text); Nav.push(Route.MovieDetail(key, m.id)) }
            }
        }
    }
}

/** 一行横滑筛选：[options] 第一个是这一行的「全部」（高亮时表示这一行没筛）。 */
@Composable
private fun FilterRow(options: List<String>, selected: String, allLabel: String, onPick: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(options, key = { it }) { o -> FilterChipText(o, o == selected, bold = o == allLabel) { onPick(o) } }
    }
}

@Composable
private fun FilterChipText(t: String, on: Boolean, bold: Boolean = false, onClick: () -> Unit) {
    Text(t, color = if (on) C.Accent else C.Text, fontSize = 15.sp, fontWeight = if (on || bold) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
        modifier = Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(if (on) C.Accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 14.dp).wrapContentHeight(Alignment.CenterVertically))
}

/** 搜索结果海报：左上角类型角标，右下角评分，下面片名 + 「共 N 季」/ 年份。 */
@Composable
private fun SearchPoster(m: MovieItem, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(C.Surface2)) {
            if (m.poster != null) NetImage(posterModel(m.poster), Modifier.fillMaxSize())
            else Text(m.title, color = C.Sub, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(10.dp))
            Text(MovieFilter.categoryOf(m), color = Color.White, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(topStart = 10.dp, bottomEnd = 8.dp)).background(C.Accent.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp))
            m.rating?.let { r ->
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(36.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))))
                Text("%.1f".format(r), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
        }
        Text(m.title, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
        val sub = MovieFilter.subtitle(m)
        if (sub.isNotEmpty()) Text(sub, color = C.Sub, fontSize = 12.sp, maxLines = 1)
    }
}
