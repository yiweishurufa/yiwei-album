package com.hark.shiguang.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.hark.shiguang.App
import com.hark.shiguang.Diag
import com.hark.shiguang.ImageAuth
import com.hark.shiguang.Nav
import com.hark.shiguang.Route
import com.hark.shiguang.Transfers
import com.hark.shiguang.Store
import com.hark.shiguang.cloud.CloudAccounts
import com.hark.shiguang.cloud.CloudEntry
import com.hark.shiguang.cloud.CloudSource
import com.hark.shiguang.cloud.CloudSources
import com.hark.shiguang.data.Album
import com.hark.shiguang.data.FnClient
import com.hark.shiguang.data.NasX
import com.hark.shiguang.data.Photo
import com.hark.shiguang.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_SHORT).show()

/** Bridge between cloud drive entries and the app's Photo model. */
object CloudPhotos {
    private val sources = ConcurrentHashMap<String, CloudSource>()
    private val entries = ConcurrentHashMap<String, CloudEntry>()   // photo.key -> entry
    private val urlOwner = ConcurrentHashMap<String, String>()      // url -> account id
    @Volatile private var registered = false

    fun source(accountId: String): CloudSource? {
        sources[accountId]?.let { return it }
        val a = CloudAccounts.get(accountId) ?: return null
        return CloudSources.create(a).also { sources[accountId] = it }
    }
    fun forget(accountId: String) { sources.remove(accountId) }

    private fun ensureAuth() {
        if (registered) return
        registered = true
        ImageAuth.register { url ->
            val id = urlOwner[url] ?: return@register null
            sources[id]?.headers(url)
        }
    }

    fun entryOf(p: Photo): CloudEntry? = entries[p.key]

    private val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

    /** Turns media entries into Photos; resolves raw urls (bounded concurrency) where no thumbnail exists. */
    suspend fun toPhotos(accountId: String, list: List<CloudEntry>): List<Photo> = coroutineScope {
        ensureAuth()
        val src = source(accountId) ?: return@coroutineScope emptyList()
        val gate = Semaphore(6)
        list.filter { it.isImage || it.isVideo }.map { e ->
            async(Dispatchers.IO) {
                val raw = if (e.thumbUrl == null || e.isVideo) gate.withPermit { runCatching { src.rawUrl(e) }.getOrNull() } else null
                val thumb = e.thumbUrl ?: raw?.let { com.hark.shiguang.DavThumb.mark(it) } ?: return@async null
                listOfNotNull(thumb, raw).forEach { urlOwner[it] = accountId }
                val time = if (e.modified > 0) synchronized(fmt) { fmt.format(Date(e.modified)) } else "1970:01:01 00:00:00"
                val p = Photo(
                    id = e.id.hashCode(), uuid = e.id, isVideo = e.isVideo, isLive = false, fileName = e.name, time = time,
                    width = 0, height = 0, duration = 0, collected = false,
                    thumbS = thumb, thumbM = thumb, original = if (e.isImage) (raw ?: thumb) else thumb,
                    video = if (e.isVideo) raw else null,
                    make = "", model = "", fNumber = "", exposure = "", iso = "", focal = "", size = e.size, geo = "",
                    path = e.path, source = accountId, cloudPath = e.path,
                )
                entries[p.key] = e
                p
            }
        }.awaitAll().filterNotNull()
    }
}

/** One place for every action on photos, NAS or cloud. */
object Ops {
    private val app = MainScope()

    fun launch(block: suspend () -> Unit) { app.launch { block() } }

    private fun nas(photos: List<Photo>) = photos.filter { !it.isCloud }
    private fun cloud(photos: List<Photo>) = photos.filter { it.isCloud }

    fun download(ctx: Context, photos: List<Photo>) {
        val c = ctx.applicationContext
        nas(photos).forEach { p ->
            val url = if (p.isVideo) p.video ?: p.original else p.original
            Transfers.download(c, url, p.fileName.ifEmpty { "${p.id}.jpg" }, NasX.streamHeaders(url), p.isVideo)
        }
        cloud(photos).forEach { p ->
            launch {
                runCatching {
                    val src = CloudPhotos.source(p.source) ?: error("网盘账户已移除")
                    val e = CloudPhotos.entryOf(p) ?: error("文件信息丢失，请刷新后再试")
                    val url = withContext(Dispatchers.IO) { src.rawUrl(e) }
                    Transfers.download(c, url, p.fileName, src.headers(url), p.isVideo)
                }.onFailure { toast(c, it.message ?: "下载失败") }
            }
        }
        if (photos.isNotEmpty()) toast(c, "已加入传输队列（${photos.size}）")
    }

    fun downloadLiveVideo(ctx: Context, p: Photo) {
        val c = ctx.applicationContext
        // 1.0.1: every live kind (飞牛 1/2/3, WebDAV MOV pair / Motion Photo) through LiveMotion
        launch { com.hark.shiguang.LiveMotion.save(c, p) }
    }

    /** Fetches files into cache and opens the system share sheet. */
    fun share(ctx: Context, photos: List<Photo>) {
        if (photos.isEmpty()) return
        toast(ctx, "正在准备分享…")
        launch {
            runCatching {
                val files = withContext(Dispatchers.IO) {
                    val dir = File(ctx.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
                    photos.take(30).map { p -> fetchTo(p, dir) }
                }
                val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) })
                val mime = if (photos.all { it.isVideo }) "video/*" else if (photos.none { it.isVideo }) "image/*" else "*/*"
                val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                i.type = mime; i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(Intent.createChooser(i, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Diag.e("SHARE", it); toast(ctx, it.message ?: "分享失败") }
        }
    }

    private suspend fun fetchTo(p: Photo, dir: File): File {
        val (url, headers) = if (p.isCloud) {
            val src = CloudPhotos.source(p.source) ?: error("网盘账户已移除")
            val e = CloudPhotos.entryOf(p) ?: error("文件信息丢失")
            val u = src.rawUrl(e); u to src.headers(u)
        } else {
            val u = if (p.isVideo) p.video ?: p.original else p.original
            u to NasX.streamHeaders(u)
        }
        val name = p.fileName.ifEmpty { "${p.id}" }.replace('/', '_')
        val f = File(dir, name)
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        FnClient.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("下载失败（HTTP ${r.code}）")
            f.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
        }
        return f
    }

    /** NAS: to recycle bin. Cloud: deleted on the drive. */
    /**
     * Deletes on the server, then drops the items from every local cache so they do not come back
     * from the index before the next re-scan. 飞牛: into its recycle bin (or for good when the user chose so).
     */
    suspend fun delete(photos: List<Photo>) {
        val n = nas(photos)
        if (n.isNotEmpty()) {
            NasX.delete(n.map { it.id })
            if (Store.getStr("nas.deleteForever") == "1") runCatching { NasX.recycleDeleteForever(n.map { it.id }) }
            val gone = n.map { it.id }.toSet()
            runCatching { HomeState.timeline.photos.removeAll { it.id in gone && !it.isCloud } }
            HomeState.dirty = true
        }
        val c = cloud(photos)
        val failed = ArrayList<String>()
        c.groupBy { it.source }.forEach { (acc, ps) ->
            val src = CloudPhotos.source(acc) ?: error("网盘账户已移除")
            val ok = ArrayList<String>()
            ps.forEach { p ->
                val e = CloudPhotos.entryOf(p) ?: com.hark.shiguang.cloud.CloudEntry(p.fileName, p.cloudPath, false, p.size, 0, null, !p.isVideo, p.isVideo)
                runCatching { src.delete(e) }.onSuccess { ok.add(p.cloudPath) }.onFailure { failed.add(it.message ?: p.fileName) }
            }
            if (ok.isNotEmpty()) com.hark.shiguang.Dav.lib(acc).removeLocal(ok)
        }
        if (failed.isNotEmpty()) error("有 ${failed.size} 项没删掉：${failed.first()}")
    }

    suspend fun addToAlbum(album: Album, photos: List<Photo>) {
        val n = nas(photos)
        if (n.isEmpty()) error("网盘照片不能加入 NAS 相册")
        NasX.addToAlbum(album.id, n.map { it.id }, album.name)
        HomeState.albums = null
    }

    suspend fun collect(photos: List<Photo>, on: Boolean) {
        val n = nas(photos)
        NasX.collect(n.map { it.id }, on)
        n.forEach { it.collected = on }
    }

    fun openSimilar(p: Photo) {
        Nav.push(Route.Collection("相似照片", p.fileName, PhotoSource(grouped = false) { o, _ ->
            if (o > 0) emptyList<Photo>() to false else NasX.similarSearch(p.id) to false
        }))
    }
}

fun openAlbum(a: Album) {
    Nav.push(Route.Collection(a.name, a.range, PhotoSource(pageSize = 100, grouped = false) { o, l -> Repo.albumPhotos(a.id, o, l) }, AlbumRef(a.id.toString(), null)))
}

/** An album being viewed: a NAS album (id) or a WebDAV virtual album (davAccount != null). */
data class AlbumRef(val id: String, val davAccount: String?)

object AlbumCtx { var current by mutableStateOf<AlbumRef?>(null) }

fun openDavAlbum(lib: com.hark.shiguang.DavLib, a: com.hark.shiguang.VAlbum) {
    Nav.push(Route.Collection(a.name, "WebDAV 相册", PhotoSource(pageSize = 100000, grouped = true) { o, _ ->
        if (o > 0) emptyList<Photo>() to false else lib.photosOf(lib.albums?.firstOrNull { it.id == a.id } ?: a) to false
    }, AlbumRef(a.id, lib.accountId)))
}

/** Album writes, NAS (via NasX) or WebDAV manifest. */
object AlbumOps {
    suspend fun rename(a: AlbumRef, name: String) {
        if (a.davAccount != null) com.hark.shiguang.Dav.lib(a.davAccount).renameAlbum(a.id, name) else { NasX.renameAlbum(a.id.toInt(), name); HomeState.albums = null }
    }
    suspend fun delete(a: AlbumRef) {
        if (a.davAccount != null) com.hark.shiguang.Dav.lib(a.davAccount).deleteAlbum(a.id) else { NasX.deleteAlbum(a.id.toInt()); HomeState.albums = null }
    }
    suspend fun setCover(a: AlbumRef, p: Photo) {
        if (a.davAccount != null) com.hark.shiguang.Dav.lib(a.davAccount).setCover(a.id, p.cloudPath) else { NasX.setAlbumCover(a.id.toInt(), p.id); HomeState.albums = null }
    }
    suspend fun remove(a: AlbumRef, l: List<Photo>) {
        if (a.davAccount != null) com.hark.shiguang.Dav.lib(a.davAccount).removeFrom(a.id, l.map { it.cloudPath }) else { NasX.removeFromAlbum(a.id.toInt(), l.filter { !it.isCloud }.map { it.id }); HomeState.albums = null }
    }
}

/** Pick (or create) a WebDAV virtual album of the current WebDAV account. */
@Composable
fun DavAlbumPicker(lib: com.hark.shiguang.DavLib, onDismiss: () -> Unit, onPick: (com.hark.shiguang.VAlbum) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { if (lib.albums == null) lib.refreshAlbums() }
    AlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface, title = { Text("加入 WebDAV 相册", color = C.Text) }, text = {
        LazyColumn(Modifier.heightIn(max = 380.dp)) {
            item { Text("＋ 新建相册", color = C.Accent, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp)) }
            items(lib.albums.orEmpty(), key = { it.id }) { a ->
                Text("${a.name}（${a.items.size}）", color = C.Text, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { onPick(a) }.padding(vertical = 12.dp))
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = C.Sub) } })
    if (creating) InputDialog("新建相册", "", "相册名称", "创建", onDismiss = { creating = false }) { name ->
        creating = false
        Ops.launch { runCatching { lib.createAlbum(name) }.onSuccess { onPick(it) }.onFailure { toast(ctx, it.message ?: "创建失败") } }
    }
}

fun openPerson(p: com.hark.shiguang.data.Person) {
    Nav.push(Route.Collection(p.name.ifEmpty { "未命名" }, "${p.count} 张照片", PhotoSource(pageSize = 100) { o, l -> Repo.personPhotos(p.id, o, l) }))
}

@Composable
fun ConfirmDialog(title: String, msg: String, okText: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.Surface,
        title = { Text(title, color = C.Text) },
        text = { if (msg.isNotEmpty()) Text(msg, color = C.Sub) },
        confirmButton = { TextButton(onClick = onOk) { Text(okText, color = C.Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = C.Sub) } },
    )
}

@Composable
fun InputDialog(title: String, initial: String, hint: String, okText: String = "确定", onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.Surface,
        title = { Text(title, color = C.Text) },
        text = {
            OutlinedTextField(v, { v = it }, singleLine = true, placeholder = { Text(hint) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = C.Text, unfocusedTextColor = C.Text, focusedBorderColor = C.Accent, cursorColor = C.Accent))
        },
        confirmButton = { TextButton(onClick = { if (v.isNotBlank()) onOk(v.trim()) }) { Text(okText, color = C.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = C.Sub) } },
    )
}

/** Pick an existing NAS album or create a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumPicker(onDismiss: () -> Unit, onPick: (Album) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var albums by remember { mutableStateOf(HomeState.albums) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { Repo.albums() }.onSuccess { albums = it; HomeState.albums = it } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.Surface, dragHandle = { BottomSheetDefaults.DragHandle(color = C.Faint) }) {
        Text("加入相册", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
            item {
                Row(Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(C.Surface2), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = C.Accent) }
                    Spacer(Modifier.width(14.dp)); Text("新建相册", color = C.Text, fontSize = 15.sp)
                }
            }
            val l = albums
            if (l == null) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Accent, modifier = Modifier.size(24.dp)) } }
            else items(l, key = { it.id }) { a ->
                Row(Modifier.fillMaxWidth().clickable { onPick(a) }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(C.Surface2), contentAlignment = Alignment.Center) {
                        if (a.poster != null) NetImage(a.poster, Modifier.fillMaxSize()) else Icon(Icons.Rounded.PhotoAlbum, null, tint = C.Sub)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column { Text(a.name, color = C.Text, fontSize = 15.sp); Text("${a.photos + a.videos} 项", color = C.Sub, fontSize = 12.sp) }
                }
            }
        }
    }
    if (creating) InputDialog("新建相册", "", "相册名称", "创建", onDismiss = { creating = false }) { name ->
        creating = false
        Ops.launch {
            runCatching { NasX.createAlbum(name) }.onSuccess { id ->
                onPick(Album(id, name, 0, 0, null, ""))
            }.onFailure { toast(ctx, it.message ?: "创建失败") }
        }
    }
}

/** Bottom bar shown while multi-selecting: favourite / download / add to album / delete. */
@Composable
fun SelectionBar(modifier: Modifier = Modifier, onChanged: () -> Unit = {}) {
    if (!Selection.active) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var albumPick by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val sel = Selection.list
    val anyNas = sel.any { !it.isCloud }
    val allCloud = sel.isNotEmpty() && sel.all { it.isCloud }
    val inAlbum = AlbumCtx.current
    var davPick by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(C.Surface).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("已选 ${sel.size} 项", color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { Selection.clear() }) { Text("取消", color = C.Sub) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (anyNas) SelAction(Icons.Rounded.FavoriteBorder, "收藏") {
                val l = Selection.list
                Ops.launch { runCatching { Ops.collect(l, true) }.onSuccess { toast(ctx, "已收藏 ${l.size} 项"); Selection.clear() }.onFailure { toast(ctx, it.message ?: "失败") } }
            }
            SelAction(Icons.Rounded.Download, "下载") { Ops.download(ctx, Selection.list); Selection.clear() }
            SelAction(Icons.Rounded.Share, "分享") { Ops.share(ctx, Selection.list) }
            if (anyNas) SelAction(Icons.Rounded.PhotoAlbum, "加入相册") { albumPick = true }
            if (allCloud) SelAction(Icons.Rounded.PhotoAlbum, "加入相册") { davPick = true }
            if (inAlbum != null) {
                if (sel.size == 1) SelAction(Icons.Rounded.Image, "设为封面") {
                    val p = sel.first()
                    Ops.launch { runCatching { AlbumOps.setCover(inAlbum, p) }.onSuccess { toast(ctx, "已设为封面"); Selection.clear() }.onFailure { toast(ctx, it.message ?: "失败") } }
                }
                SelAction(Icons.Rounded.RemoveCircleOutline, "移出相册") {
                    val l = Selection.list
                    Ops.launch { runCatching { AlbumOps.remove(inAlbum, l) }.onSuccess { toast(ctx, "已移出 ${l.size} 项"); Selection.clear(); onChanged() }.onFailure { toast(ctx, it.message ?: "失败") } }
                }
            }
            SelExtra.actions.forEach { a -> SelAction(a.icon, a.label, if (a.danger) C.Danger else C.Text) { a.run(Selection.list) } }
            if (SelExtra.actions.none { it.label.contains("隐藏") }) SelAction(Icons.Rounded.VisibilityOff, "隐藏") {
                val l = Selection.list
                com.hark.shiguang.Hidden.hide(l, true); toast(ctx, "已隐藏 ${l.size} 项，可在「相册 · 隐藏相册」查看"); Selection.clear(); onChanged()
            }
            SelAction(Icons.Rounded.DeleteOutline, "删除", C.Danger) { confirm = true }
        }
    }
    if (albumPick) AlbumPicker(onDismiss = { albumPick = false }) { a ->
        albumPick = false
        val l = Selection.list
        Ops.launch { runCatching { Ops.addToAlbum(a, l) }.onSuccess { toast(ctx, "已加入「${a.name}」"); Selection.clear() }.onFailure { toast(ctx, it.message ?: "失败") } }
    }
    if (davPick) {
        val lib = com.hark.shiguang.Dav.lib(sel.first().source)
        DavAlbumPicker(lib, onDismiss = { davPick = false }) { a ->
            davPick = false
            val l = Selection.list
            Ops.launch { runCatching { lib.addTo(a.id, l.map { it.cloudPath }) }.onSuccess { toast(ctx, "已加入「${a.name}」"); Selection.clear() }.onFailure { toast(ctx, it.message ?: "失败") } }
        }
    }
    if (confirm) {
        val cloudN = sel.count { it.isCloud }
        ConfirmDialog("删除 ${sel.size} 项？", if (cloudN > 0) "其中 $cloudN 项网盘文件会被直接删除，NAS 照片移到回收站。" else "照片会移到回收站，可以恢复。", "删除",
            onDismiss = { confirm = false }) {
            confirm = false
            val l = Selection.list
            Ops.launch { runCatching { Ops.delete(l) }.onSuccess { toast(ctx, "已删除 ${l.size} 项"); Selection.clear(); onChanged() }.onFailure { toast(ctx, it.message ?: "删除失败") } }
        }
    }
}

@Composable
private fun SelAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: androidx.compose.ui.graphics.Color = C.Text, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Text(label, color = C.Sub, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
    }
}
