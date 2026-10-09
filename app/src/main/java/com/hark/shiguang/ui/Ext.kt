package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.*
import com.hark.shiguang.data.Photo

/** Screens added in 1.0.9 (one Route.X in MainActivity dispatches here). */
sealed class ExtScreen(val dark: Boolean = false) {
    data class Person(val accountId: String, val key: String, val group: String) : ExtScreen()
    data class HiddenPersons(val accountId: String) : ExtScreen()
    data object FnHiddenPersons : ExtScreen()
    data object HiddenAlbum : ExtScreen()
    data class Map(val dav: Boolean) : ExtScreen()
    data class Edit(val photo: Photo) : ExtScreen(dark = true)
    /** 1.0.1 #9 first-run wizard (ui/Onboarding.kt). */
    data class Onboarding(val step: Int = 0) : ExtScreen()
    /** 1.0.1 */
    data object BgGuide : ExtScreen()
    /** 1.0.1 #13 回忆短片 (ui/MemoryClip.kt). */
    data class MemoryClip(val title: String, val subtitle: String, val photos: List<Photo>) : ExtScreen()
}

@Composable
fun ExtHost(s: ExtScreen) {
    when (s) {
        is ExtScreen.Person -> PersonScreen(s)
        is ExtScreen.HiddenPersons -> HiddenPersonsScreen(s.accountId)
        ExtScreen.FnHiddenPersons -> FnHiddenPersonsScreen()
        ExtScreen.HiddenAlbum -> HiddenAlbumScreen()
        is ExtScreen.Map -> PhotoMapScreen(s.dav)
        is ExtScreen.Edit -> EditScreen(s.photo)
        is ExtScreen.Onboarding -> OnboardingScreen(s.step)
        ExtScreen.BgGuide -> BgGuideScreen()
        is ExtScreen.MemoryClip -> MemoryClipScreen(s)
    }
}

/** Extra actions the current screen adds to the multi-select bar (e.g. 「移出此人物」). */
data class SelExtraAction(val icon: ImageVector, val label: String, val danger: Boolean = false, val run: (List<Photo>) -> Unit)
object SelExtra { var actions by mutableStateOf<List<SelExtraAction>>(emptyList()) }

@Composable
fun ExtTop(title: String, sub: String = "", actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(C.TopScrim).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(Icons.Rounded.ArrowBackIosNew, size = 40.dp) { Nav.pop() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        actions()
    }
}

// ============================================================ 人物（WebDAV）

fun openDavPerson(accountId: String, p: FacePerson) = Nav.push(Route.X(ExtScreen.Person(accountId, p.key, p.group)))

private fun FaceLib.find(key: String, group: String): FacePerson? =
    (persons + hiddenPersons).let { all -> all.firstOrNull { group.isNotEmpty() && it.group == group } ?: all.firstOrNull { it.key == key } }

@Composable
private fun PersonScreen(s: ExtScreen.Person) {
    val ctx = LocalContext.current
    val lib = remember(s.accountId) { Dav.lib(s.accountId) }
    val fl = remember(s.accountId) { Faces.lib(s.accountId) }
    // after the first edit the person gets a group id; follow it so the screen survives re-clustering
    var group by remember { mutableStateOf(s.group) }
    var key by remember { mutableStateOf(s.key) }
    val p = fl.find(key, group)
    LaunchedEffect(p?.key, p?.group) { p?.let { key = it.key; if (it.group.isNotEmpty()) group = it.group } }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    val hidden = p != null && fl.hiddenPersons.any { it.key == p.key }
    fun regroup(done: String) { fl.cluster(lib); toast(ctx, done) }
    DisposableEffect(p?.key) {
        val cur = p
        SelExtra.actions = if (cur == null) emptyList() else listOf(SelExtraAction(Icons.Rounded.PersonRemove, "移出人物") { l ->
            group = fl.edits.ensureGroup(cur)
            fl.edits.removePhotos(cur, l.map { it.cloudPath }.toSet())
            Selection.clear(); regroup("已从「${cur.name.ifEmpty { "该人物" }}」移出 ${l.size} 张")
        })
        onDispose { SelExtra.actions = emptyList() }
    }
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        if (p == null) StateMessage(LI.person(), "人物已不存在", "可能已被合并或重新归类。")
        else {
            val photos = Hidden.visible(p.photos)
            val entries = remember(photos) { buildEntries(photos, true) }
            val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
            PhotoGrid(entries, HomeState.columns.coerceIn(3, 5), false, onEnd = {}, onOpen = { Nav.push(Route.Viewer(photos, it)) }, top = top, bottom = 40.dp,
                header = {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(64.dp).clip(CircleShape).background(C.Surface2)) { NetImage(fl.cropFile(p.cover).absolutePath, Modifier.fillMaxSize()) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name.ifEmpty { "未命名" }, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("${photos.size} 张 · 长按照片可移出" + if (hidden) " · 已隐藏" else "", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                })
        }
        ExtTop(p?.name?.ifEmpty { "未命名" } ?: "人物") {
            if (p != null) {
                RoundIcon(LI.film(), size = 40.dp) { openMemoryClip(p.name.ifEmpty { "TA 的回忆" }, "人物", Hidden.visible(p.photos)) }
                Spacer(Modifier.width(8.dp))
            }
            if (p != null) Box {
                RoundIcon(Icons.Rounded.MoreHoriz, size = 40.dp) { menu = true }
                DropdownMenu(menu, { menu = false }, Modifier.background(C.Surface2)) {
                    DropdownMenuItem({ Text("命名", color = C.Text) }, { menu = false; renaming = true })
                    DropdownMenuItem({ Text("合并到其他人物…", color = C.Text) }, { menu = false; merging = true })
                    DropdownMenuItem({ Text(if (hidden) "取消隐藏" else "隐藏（路人）", color = C.Text) }, {
                        menu = false; group = fl.edits.ensureGroup(p); fl.edits.hide(p, !hidden)
                        regroup(if (hidden) "已取消隐藏" else "已隐藏，可在人物栏「已隐藏」里恢复")
                    })
                }
            }
        }
        SelectionBar(Modifier.align(Alignment.BottomCenter))
    }
    if (renaming && p != null) InputDialog("给人物命名", p.name, "名字", "保存", onDismiss = { renaming = false }) { v -> renaming = false; fl.rename(p, v) }
    if (merging && p != null) PersonPicker(fl, exclude = p.key, title = "把「${p.name.ifEmpty { "该人物" }}」合并到…", onDismiss = { merging = false }) { target ->
        merging = false
        if (target.name.isEmpty() && p.name.isNotEmpty()) fl.rename(target, p.name)
        val t = fl.find(target.key, target.group) ?: target
        group = fl.edits.merge(t, p); key = t.key
        regroup("已合并到「${t.name.ifEmpty { p.name.ifEmpty { "未命名" } }}」")
    }
}

/** Picks another person (visible ones first, then hidden). */
@Composable
fun PersonPicker(fl: FaceLib, exclude: String, title: String, onDismiss: () -> Unit, onPick: (FacePerson) -> Unit) {
    val all = (fl.persons + fl.hiddenPersons).filter { it.key != exclude }
    AlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface, title = { Text(title, color = C.Text, fontSize = 17.sp) }, text = {
        if (all.isEmpty()) Text("没有其他人物。", color = C.Sub)
        else LazyColumn(Modifier.heightIn(max = 440.dp)) {
            items(all, key = { it.key }) { q ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(q) }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(C.Surface2)) { NetImage(fl.cropFile(q.cover).absolutePath, Modifier.fillMaxSize()) }
                    Spacer(Modifier.width(12.dp))
                    Text(q.name.ifEmpty { "未命名" }, color = if (q.name.isEmpty()) C.Sub else C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("${q.photos.size} 张", color = C.Faint, fontSize = 12.sp)
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = C.Sub) } })
}

@Composable
private fun HiddenPersonsScreen(accountId: String) {
    val ctx = LocalContext.current
    val fl = remember(accountId) { Faces.lib(accountId) }
    val lib = remember(accountId) { Dav.lib(accountId) }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        ExtTop("已隐藏的人物", "${fl.hiddenPersons.size} 位")
        if (fl.hiddenPersons.isEmpty()) StateMessage(LI.person(), "没有隐藏的人物", "在人物上长按选「隐藏（路人）」，就不会再出现在人物栏。")
        else LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            items(fl.hiddenPersons, key = { it.key }) { p ->
                PersonListRow(fl.cropFile(p.cover).absolutePath, p.name.ifEmpty { "未命名" }, "${p.photos.size} 张", onOpen = { openDavPerson(accountId, p) }) {
                    fl.edits.hide(p, false); fl.cluster(lib); toast(ctx, "已取消隐藏")
                }
            }
        }
    }
}

@Composable
private fun FnHiddenPersonsScreen() {
    FnPersonHide.version
    val hidden = HomeState.persons.orEmpty().filter { FnPersonHide.has(it.id) }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        ExtTop("已隐藏的人物", "${hidden.size} 位 · 只在本机隐藏")
        if (hidden.isEmpty()) StateMessage(LI.person(), "没有隐藏的人物", "在人物上长按选「隐藏（路人）」。")
        else LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            items(hidden, key = { it.id }) { p ->
                PersonListRow(com.hark.shiguang.data.FnClient.abs("/p/api/v1/stream/face/${p.faceId}"), p.name.ifEmpty { "未命名" }, "${p.count} 张", onOpen = { openPerson(p) }) { FnPersonHide.set(p.id, false) }
            }
        }
    }
}

@Composable
private fun PersonListRow(img: String?, title: String, sub: String, onOpen: () -> Unit, onUnhide: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(C.Surface2)) { NetImage(img, Modifier.fillMaxSize()) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
        Text("取消隐藏", color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Accent.copy(alpha = 0.12f)).clickable(onClick = onUnhide).padding(horizontal = 12.dp, vertical = 7.dp))
    }
}
