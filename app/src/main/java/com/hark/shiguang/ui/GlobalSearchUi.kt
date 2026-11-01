package com.hark.shiguang.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
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
    var q by rememberSaveable { mutableStateOf("") }
    var res by remember { mutableStateOf<GlobalResults?>(null) }
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
                GlobalSearch.rank(tracks.filter { GlobalSearch.track(it, ws) }, ws) { it.title }.take(100),
            )
        }
    }
    Column(Modifier.fillMaxSize().appBackground().statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface2).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = C.Sub, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text("搜照片、电影剧集、歌曲", color = C.Faint, fontSize = 15.sp)
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = C.Text, fontSize = 15.sp), cursorBrush = SolidColor(C.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { if (q.isNotBlank()) SearchHistory.add(q.trim()) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(fr))
                }
                if (q.isNotEmpty()) TextButton(onClick = { q = "" }) { Text("清除", color = C.Sub, fontSize = 13.sp) }
            }
            TextButton(onClick = { Nav.pop() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消", color = C.Accent) }
        }
        val r = res
        when {
            q.isBlank() -> StateMessage(Icons.Rounded.Search, "一个框搜全部", "输入片名、歌名、歌手或照片的文件名 / 地点。想用一句话描述照片，点下面的「用一句话找照片」。",
                "用一句话找照片") { Nav.push(Route.Search) }
            r == null -> Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Accent, modifier = Modifier.size(24.dp)) }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
                if (r.movies.isEmpty() && r.tracks.isEmpty() && r.photos.isEmpty()) item {
                    Text("影视、歌曲和照片文件名里都没有「${q.trim()}」", color = C.Sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 24.dp))
                }
                if (r.movies.isNotEmpty() && key != null) {
                    item { GroupHead("影视", "${r.movies.size} 部") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(r.movies, key = { it.id }) { m -> Box(Modifier.width(104.dp)) { PosterCard(m, Movies.lib(key)) { Nav.push(Route.MovieDetail(key, m.id)) } } }
                        }
                    }
                }
                if (r.tracks.isNotEmpty() && key != null) {
                    item { GroupHead("歌曲", "${r.tracks.size} 首") }
                    itemsIndexed(r.tracks.take(30), key = { _, t -> "t" + t.id }) { i, t ->
                        Row(Modifier.fillMaxWidth().clickable { MusicState.play(key, r.tracks, i) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            MusicArt(t.artPath, Modifier.size(44.dp))
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(t.title.ifBlank { t.name }, color = C.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(t.displayArtist, t.album).filter { it.isNotBlank() }.joinToString(" · "), color = C.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Rounded.MusicNote, null, tint = C.Faint, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (r.tracks.size > 30) item { Text("还有 ${r.tracks.size - 30} 首，点任意一首会把全部 ${r.tracks.size} 首加入播放队列", color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                }
                if (r.photos.isNotEmpty()) {
                    item { GroupHead("照片", "文件名 / 地点 · ${r.photos.size}${if (r.photos.size >= 60) "+" else ""} 张") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            itemsIndexed(r.photos, key = { _, p -> "p" + p.key }) { i, p -> Box(Modifier.size(96.dp)) { Thumb(p, 8.dp, small = true) { Nav.push(Route.Viewer(r.photos, i)) } } }
                        }
                    }
                }
                item {
                    TextButton(onClick = { SearchState.pending = q.trim(); Nav.push(Route.Search) }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp).heightIn(min = 48.dp)) {
                        Text("用一句话找照片：「${q.trim()}」（AI / 地点 / 图中文字）", color = C.Accent, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHead(title: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(sub, color = C.Sub, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, bottom = 2.dp))
    }
}
