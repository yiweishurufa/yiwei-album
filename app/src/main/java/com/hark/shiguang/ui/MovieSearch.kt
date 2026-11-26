package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.animation.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
        // 1.6.6 阶段 4：二级页同款 44dp 返回圆钮 + 内凹的搜索槽（高 48、圆角 14）；占位提示用次要色（DESIGN §2.2）
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = Space.s + 2.dp, end = Space.page, top = Space.s, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(KI.back(), size = 44.dp, desc = "返回") { Nav.pop() }
            Row(Modifier.padding(start = Space.m).weight(1f).height(48.dp).skeuoInset(RoundedCornerShape(Radius.m)).padding(start = Space.m), verticalAlignment = Alignment.CenterVertically) {
                Icon(KI.search(), null, tint = C.Sub, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f).padding(start = Space.s), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text("输入影片名称搜索", color = C.Sub, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    BasicTextField(text, { text = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = C.Text), cursorBrush = SolidColor(C.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { saveQuery(text); focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "搜索影片" })
                }
                AnimatedVisibility(text.isNotEmpty(), enter = fadeIn(Motion.state()) + scaleIn(Motion.move(), 0.6f), exit = fadeOut(Motion.exit()) + scaleOut(Motion.exit(), 0.6f)) {
                    IconButton(onClick = { text = "" }) { Icon(KI.close(), "清除搜索", tint = C.Sub, modifier = Modifier.size(18.dp)) }
                }
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

        // 1.7.8：环境光晕跟着结果里领头的海报
        val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        AmbientFromGrid(grid, remember(shown) { shown.associate { (it.id as Any) to it.poster } })
        LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), state = grid,
            contentPadding = PaddingValues(start = Space.page, end = Space.page, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(PosterSize.gap), verticalArrangement = Arrangement.spacedBy(Space.l + Space.xs)) {
            if (text.isEmpty() && history.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.bleed(Space.page)) {
                    ContentHeader("最近搜索", extra = "清空" to { MovieSearchHistory.clear(); history = emptyList() })
                    LazyRow(contentPadding = PaddingValues(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        items(history) { h -> ChoiceChip(h, false, role = androidx.compose.ui.semantics.Role.Button) { text = h; saveQuery(h) } }
                    }
                }
            }
            if (all.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.bleed(Space.page).padding(top = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                    if (cats.size > 1) FilterRow(listOf(MovieFilter.ALL) + cats, category, MovieFilter.ALL) { category = it }
                    FilterRow(MovieFilter.SORTS, MovieFilter.SORTS[sort], MovieFilter.SORTS[0]) { sort = MovieFilter.SORTS.indexOf(it).coerceAtLeast(0) }
                    if (genres.isNotEmpty()) FilterRow(listOf("类型") + genres, if (genre == MovieFilter.ALL) "类型" else genre, "类型") { genre = if (it == "类型") MovieFilter.ALL else it }
                    if (years.isNotEmpty()) FilterRow(listOf("年份") + years, if (year == MovieFilter.ALL) "年份" else year, "年份") { year = if (it == "年份") MovieFilter.ALL else it }
                }
            }
            if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(vertical = Space.xxxl, horizontal = Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(KI.search(), null, tint = C.Faint, modifier = Modifier.size(44.dp).padding(bottom = Space.l))
                    Text(when {
                        all.isEmpty() && lib.scanning -> "正在扫描影视库…"
                        all.isEmpty() -> "影视库还是空的，先在影视页选好影视文件夹"
                        text.isNotBlank() -> "没有找到「${text.trim()}」"
                        else -> "没有符合筛选条件的影片"
                    }, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    if (filtered) Box(Modifier.padding(top = Space.m)) { HeaderTextButton("清除筛选", C.Accent) { category = MovieFilter.ALL; genre = MovieFilter.ALL; year = MovieFilter.ALL } }
                }
            } else itemsIndexed(shown, key = { _, m -> m.id }) { i, m ->
                SearchPoster(m, i) { saveQuery(text); Nav.push(Route.MovieDetail(key, m.id)) }
            }
        }
    }
}

/** 一行横滑筛选：[options] 第一个是这一行的「全部」（高亮时表示这一行没筛）。 */
@Composable
private fun FilterRow(options: List<String>, selected: String, allLabel: String, onPick: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = Space.page - Space.s), horizontalArrangement = Arrangement.spacedBy(Space.xxs)) {
        items(options, key = { it }) { o -> FilterChipText(o, o == selected, bold = o == allLabel) { onPick(o) } }
    }
}

/**
 * 飞牛影视式的文字筛选项（用户给的截图版式）：未选中只有文字，选中 = 强调色 14% 浅底 + 强调字；颜色渐变 180ms，切换轻震。
 * 每行的「全部 / 类型 / 年份」用 Medium 字重标出行首。
 */
@Composable
private fun FilterChipText(t: String, on: Boolean, bold: Boolean = false, onClick: () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    val bg by androidx.compose.animation.animateColorAsState(if (on) C.AccentSoft else Color.Transparent, Motion.state(), label = "fBg")
    val fg by androidx.compose.animation.animateColorAsState(if (on) C.Accent else if (bold) C.Text else C.Sub, Motion.state(), label = "fFg")
    Text(t, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = if (on || bold) FontWeight.Medium else FontWeight.Normal, maxLines = 1,
        modifier = Modifier.heightIn(min = 40.dp).pressScale().clip(RoundedCornerShape(Radius.s + 6.dp)).background(bg)
            .clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { if (!on) Haptics.tick(view); onClick() }
            .semantics { selected = on }.padding(horizontal = Space.m + 2.dp).wrapContentHeight(Alignment.CenterVertically))
}

/** 搜索结果海报：左上角类型角标，右下角评分，下面片名 + 「共 N 季」/ 年份。1.6.6：与影视页海报同一套圆角、角标、按压与进入动效。 */
@Composable
private fun SearchPoster(m: MovieItem, index: Int, onClick: () -> Unit) {
    val sub = MovieFilter.subtitle(m)
    Column(Modifier.enterRise(index).pressScale(Motion.PRESS_SCALE_LARGE).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(m.title, MovieFilter.categoryOf(m), sub.takeIf { it.isNotEmpty() }, m.rating?.let { "评分 %.1f".format(it) }).joinToString("，") }) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).skeuoPhoto(RoundedCornerShape(Radius.s)).background(C.Surface2)) {
            if (m.poster != null) NetImage(posterModel(m.poster), Modifier.fillMaxSize())
            else Text(m.title, color = C.Sub, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(Space.s + 2.dp))
            PosterBadge(MovieFilter.categoryOf(m), Modifier.align(Alignment.TopStart))
            m.rating?.let { r -> PosterBadge("%.1f".format(r), Modifier.align(Alignment.BottomEnd), num = true) }
        }
        Text(m.title, color = C.Text, style = MaterialTheme.typography.titleSmall, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.s))
        if (sub.isNotEmpty()) Text(sub, color = C.Sub, style = MaterialTheme.typography.labelSmall.merge(Type.num), maxLines = 1)
    }
}

/** 全面审查 A9：详情页点演员 → 库里有他 / 她参演的作品（按年份新到旧）。 */
@Composable
fun MovieActorScreen(sourceKey: String, name: String) {
    val lib = remember(sourceKey) { Movies.lib(sourceKey) }
    val works by remember(lib, name) { derivedStateOf { lib.version; lib.items.filter { m -> m.cast.any { it.name == name } }.sortedByDescending { it.year ?: 0 } } }
    Page(name, if (lib.ready) "库里有 ${works.size} 部作品" else "") {
        if (!lib.ready) { LibLoading(); return@Page }
        val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        AmbientFromGrid(grid, remember(works) { works.associate { (it.id as Any) to it.poster } })
        LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), state = grid, contentPadding = PaddingValues(start = Space.page, end = Space.page, top = Space.s, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(PosterSize.gap), verticalArrangement = Arrangement.spacedBy(Space.l + Space.xs)) {
            itemsIndexed(works, key = { _, m -> m.id }) { i, m -> PosterCard(m, lib, index = i) { Nav.push(Route.MovieDetail(sourceKey, m.id)) } }
        }
    }
}
