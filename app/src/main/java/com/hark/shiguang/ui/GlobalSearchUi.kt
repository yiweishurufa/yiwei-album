package com.hark.shiguang.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.*
import com.hark.shiguang.data.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 优化 18（用户 2026-11-02）：全局搜索。发现页的搜索框进来，一次搜当前来源的照片、影视和歌曲，按类型分组。
 * 照片这里只按文件名和地点快速匹配（本机已有的列表，不联网）；「用一句话找照片」（AI / 地点 / 图中文字）仍在原来的照片搜索里，
 * 底部按钮带着同一个关键词跳过去。
 */
private class GlobalResults(val photos: List<Photo>, val movies: List<MovieItem>, val tracks: List<Track>)

@Composable
fun GlobalSearchScreen() {
    val fr = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    var q by rememberSaveable { mutableStateOf("") }
    var res by remember { mutableStateOf<GlobalResults?>(null) }
    // 1.7.18：最近搜索（与「用一句话找照片」共用一份历史）。以前这里按搜索键会记历史，但从来不显示
    var history by remember { mutableStateOf(SearchHistory.load()) }
    fun record(t: String) { if (t.isNotBlank()) { SearchHistory.add(t.trim()); history = SearchHistory.load() } }
    val key = Movies.keyOf()
    LaunchedEffect(Unit) { fr.requestFocus() }
    LaunchedEffect(q, key) {
        val ws = GlobalSearch.words(q)
        if (ws.isEmpty()) { res = null; return@LaunchedEffect }
        delay(250)   // typing: search once the user pauses
        val photosSrc = if (HomeState.source == "dav") Dav.lib()?.photos?.toList().orEmpty() else HomeState.timeline.photos.toList()
        val movies = key?.let { Movies.lib(it).items.toList() }.orEmpty()
        val tracks = key?.let { Music.lib(it).tracks.toList() }.orEmpty()
        res = withContext(Dispatchers.Default) {
            GlobalResults(
                Hidden.visible(photosSrc.filter { GlobalSearch.photo(it.fileName, it.geo, ws) }).take(60),
                GlobalSearch.rank(movies.filter { GlobalSearch.movie(it, ws) }, ws) { it.title }.take(40),
                GlobalSearch.rank(tracks.filter { GlobalSearch.track(it, ws) }, ws) { it.displayTitle }.take(100),
            )
        }
    }
    fun aiSearch() { record(q); SearchState.pending = q.trim(); Nav.push(Route.Search) }
    Column(Modifier.fillMaxSize().appBackground()) {
        // 1.7.18 v2：与影视搜索同款——44dp 返回圆钮 + 内凹搜索槽（高 48）+ 有字时出现的清除圆钮
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = Space.s + 2.dp, end = Space.page, top = Space.s, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(KI.back(), size = 44.dp, desc = "返回") { Nav.pop() }
            Row(Modifier.padding(start = Space.m).weight(1f).height(48.dp).skeuoInset(RoundedCornerShape(Radius.m)).padding(start = Space.m), verticalAlignment = Alignment.CenterVertically) {
                Icon(KI.search(), null, tint = C.Sub, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f).padding(start = Space.s), contentAlignment = Alignment.CenterStart) {
                    if (q.isEmpty()) Text("搜照片、电影剧集、歌曲", color = C.Sub, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = C.Text), cursorBrush = SolidColor(C.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { record(q); focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(fr).semantics { contentDescription = "搜索照片、影视和歌曲" })
                }
                AnimatedVisibility(q.isNotEmpty(), enter = fadeIn(Motion.state()) + scaleIn(Motion.move(), 0.6f), exit = fadeOut(Motion.exit()) + scaleOut(Motion.exit(), 0.6f)) {
                    IconButton(onClick = { q = "" }) { Icon(KI.close(), "清除搜索", tint = C.Sub, modifier = Modifier.size(18.dp)) }
                }
            }
        }
        val r = res
        when {
            q.isBlank() -> Column(Modifier.fillMaxSize()) {
                if (history.isNotEmpty()) {
                    ContentHeader("最近搜索", extra = "清空" to { SearchHistory.clear(); history = emptyList() })
                    LazyRow(contentPadding = PaddingValues(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        items(history) { h -> ChoiceChip(h, false, role = androidx.compose.ui.semantics.Role.Button) { q = h; record(h) } }
                    }
                }
                StateMessage(KI.search(), "一个框搜全部", "片名、歌名、歌手，或照片的文件名和地点。", "用一句话找照片") { Nav.push(Route.Search) }
            }
            // 1.7.18：搜索中用列表骨架（与音乐读库一致），不再是一个转圈
            r == null -> ListRowSkeleton(rows = 6, cover = 44.dp)
            r.movies.isEmpty() && r.tracks.isEmpty() && r.photos.isEmpty() ->
                StateMessage(KI.search(), "没有找到「${q.trim()}」", "影视、歌曲和照片文件名 / 地点里都没有。照片还可以按画面内容、图中文字找。", "用一句话找照片") { aiSearch() }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
                if (r.movies.isNotEmpty() && key != null) {
                    item { ContentHeader("影视", "${r.movies.size} 部") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(PosterSize.gap)) {
                            items(r.movies, key = { it.id }) { m -> Box(Modifier.width(104.dp)) { PosterCard(m, Movies.lib(key)) { record(q); Nav.push(Route.MovieDetail(key, m.id)) } } }
                        }
                    }
                }
                if (r.tracks.isNotEmpty() && key != null) {
                    item { ContentHeader("歌曲", "${r.tracks.size} 首") }
                    itemsIndexed(r.tracks.take(30), key = { _, t -> "t" + t.id }) { i, t ->
                        Row(Modifier.fillMaxWidth().heightIn(min = Space.row).clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "播放") { record(q); MusicState.play(key, r.tracks, i) }
                            .padding(horizontal = Space.page, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                            MusicArt(t.artPath, Modifier.size(44.dp))
                            Column(Modifier.padding(start = Space.m).weight(1f)) {
                                Text(t.displayTitle, color = C.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(t.displayArtist, t.album).filter { it.isNotBlank() }.joinToString(" · "), color = C.Sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(KI.music(), null, tint = C.Faint, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (r.tracks.size > 30) item { Text("还有 ${r.tracks.size - 30} 首，点任意一首会把全部 ${r.tracks.size} 首加入播放队列", color = C.Sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Space.page, vertical = Space.xs)) }
                }
                if (r.photos.isNotEmpty()) {
                    item { ContentHeader("照片", "文件名 / 地点 · ${r.photos.size}${if (r.photos.size >= 60) "+" else ""} 张") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.xs + 2.dp)) {
                            itemsIndexed(r.photos, key = { i, p -> "p${p.key}_$i" }) { i, p -> Box(Modifier.size(96.dp)) { Thumb(p, Radius.s, small = true) { record(q); Nav.push(Route.Viewer(r.photos, i)) } } }
                        }
                    }
                }
                item {
                    Box(Modifier.padding(horizontal = Space.xs, vertical = Space.m)) {
                        HeaderTextButton("用一句话找照片：「${q.trim()}」（AI / 地点 / 图中文字）", C.Accent) { aiSearch() }
                    }
                }
            }
        }
    }
}

