package com.hark.shiguang.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C as MC
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.hark.shiguang.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One entry of the play queue. */
private data class QItem(val path: String, val title: String, val file: MediaFile)


@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MoviePlayerScreen(r: Route.MoviePlay) {
    val ctx = LocalContext.current
    val act = ctx as? Activity
    val lib = remember(r.sourceKey) { Movies.lib(r.sourceKey) }
    val item = lib.item(r.itemId)
    val fs = remember(r.sourceKey) { lib.fs }

    // queue: a movie plays the chosen version; a show plays from the chosen episode onward (auto next).
    val queue = remember(item, r.path) {
        if (item == null) emptyList() else if (item.kind == "movie") {
            val f = item.files.firstOrNull { it.path == r.path } ?: item.files.first()
            listOf(QItem(f.path, item.title, f))
        } else item.episodes.mapNotNull { e -> e.file?.let { QItem(e.path, "${item.title} · 第${e.season}季 第${e.episode}集" + (e.title?.let { " $it" } ?: ""), it) } }
    }
    val start = queue.indexOfFirst { it.path == r.path }.coerceAtLeast(0)

    // landscape + immersive while playing
    DisposableEffect(Unit) {
        MusicState.pause()   // 1.0.3 #4: a video stops the music (the music player is its own ExoPlayer)
        val old = act?.requestedOrientation
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val w = act?.window
        val ctl = w?.let { WindowCompat.getInsetsController(it, it.decorView) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            act?.requestedOrientation = old ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ctl?.show(WindowInsetsCompat.Type.systemBars())
            w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    if (item == null || queue.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { Text("找不到这个视频", color = Color.White) }
        return
    }
    val bad = NameParser.ext(queue[start].file.name) in NameParser.UNPLAYABLE
    if (bad) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("这个格式暂时放不了", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("${NameParser.ext(queue[start].file.name).uppercase()} 光盘镜像需要转码，换个版本试试", color = Color(0xB3FFFFFF), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                TextButton(onClick = { Nav.pop() }, modifier = Modifier.padding(top = 12.dp)) { Text("返回", color = C.Accent) }
            }
        }
        return
    }

    // external subtitles: fetch the small files once into cache (they may sit on an authenticated host)
    val subs by produceState<Map<String, List<File>>?>(null, queue) {
        value = withContext(Dispatchers.IO) {
            val gate = Semaphore(4)
            val dir = File(ctx.cacheDir, "subs").apply { mkdirs() }
            kotlinx.coroutines.coroutineScope {
                queue.take(start + 40).drop((start - 1).coerceAtLeast(0)).map { q ->
                    async {
                        q.path to q.file.subs.mapNotNull { sp ->
                            gate.withPermit {
                                val f = File(dir, MessageDigest.getInstance("MD5").digest("${r.sourceKey}|$sp".toByteArray()).joinToString("") { "%02x".format(it) }.take(20) + "." + NameParser.ext(sp))
                                if (f.exists() && f.length() > 0) f else fs.bytes(sp)?.let { b -> f.writeBytes(b); f }
                            }
                        }
                    }
                }.awaitAll().toMap()
            }
        }
    }
    // 1.0.10: play urls can need a network call (fnOS /multiple-download token) — resolve them off the main thread
    val playUrls by produceState<Map<String, String>?>(null, queue) {
        value = withContext(Dispatchers.IO) { queue.associate { q -> q.path to (runCatching { fs.playUrl(q.file) }.getOrNull() ?: "") } }
    }
    val subMap = subs
    val pre = playUrls
    if (subMap == null || pre == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(32.dp)) }
        return
    }

    var title by remember { mutableStateOf(queue[start].title) }
    var error by remember { mutableStateOf<String?>(null) }
    var controls by remember { mutableStateOf(true) }
    // 1.0.9: per-show choices (audio/subtitle language, intro/outro skip, subtitle delay) + subtitle look
    val showId = item.id
    var subOffset by remember { mutableLongStateOf(PlayPrefs.subOffset(showId)) }
    var hint by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var casting by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    // 1.0.1 #11 弹幕 (弹弹play) — media3 only, not in the libVLC fallback
    var curPath by remember { mutableStateOf(queue[start].path) }
    var dmOn by remember { mutableStateOf(DanmakuPrefs.enabled) }
    var dmView by remember { mutableStateOf<DanmakuView?>(null) }
    var dmMatch by remember { mutableStateOf<DmMatch?>(null) }
    var dmStatus by remember { mutableStateOf("") }
    var dmMenu by remember { mutableStateOf(false) }
    var dmSettings by remember { mutableStateOf(false) }
    var dmSearch by remember { mutableStateOf(false) }
    var dmReload by remember { mutableIntStateOf(0) }
    val skipped = remember { HashSet<String>() }
    // 1.0.10 libVLC fallback: rmvb/rm/wmv/asf always, any other file after media3 says the container/codec is unsupported
    fun needsVlc(q: QItem) = NameParser.ext(q.file.name) in VlcSupport.EXT
    val startVlc = needsVlc(queue[start])
    var vlcIdx by remember { mutableIntStateOf(if (startVlc) start else -1) }
    var vlcStart by remember { mutableLongStateOf(if (startVlc) lib.progressOf(queue[start].path)?.takeIf { !it.watched && it.pos > 5_000 }?.pos ?: 0L else 0L) }
    var canVlc by remember { mutableStateOf(false) }

    /** External subtitles of [q], shifted by the show's subtitle delay when it is not 0. */
    fun itemFor(q: QItem, url: String, offset: Long): MediaItem {
        val sc = subMap[q.path].orEmpty().mapIndexed { i, f0 ->
            val ext = f0.extension.lowercase()
            val f = if (offset == 0L) f0 else File(f0.parentFile, f0.nameWithoutExtension + ".off$offset.$ext").also { o ->
                if (!o.exists()) runCatching { o.writeText(SubShift.shift(readSub(f0), ext, offset)) }
            }.takeIf { it.exists() } ?: f0
            val name = q.file.subs.getOrNull(i)?.substringAfterLast('/') ?: f0.name
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                .setMimeType(when (ext) { "ass", "ssa" -> MimeTypes.TEXT_SSA; "vtt" -> MimeTypes.TEXT_VTT; else -> MimeTypes.APPLICATION_SUBRIP })
                .setLanguage(subLang(name)).setLabel(subLabel(name, q.file.name))
                .setSelectionFlags(if (i == 0) MC.SELECTION_FLAG_DEFAULT else 0).build()
        }
        return MediaItem.Builder().setUri(url).setMediaId(q.path).setSubtitleConfigurations(sc)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(q.title).build()).build()
    }
    val urls = remember(queue) { HashMap<String, String>(pre) }

    val player = remember(queue) {
        val upstream = OkHttpDataSource.Factory(PlayHttp.client)
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(ctx, upstream)) { spec ->
            val url = spec.uri.toString()
            if (!url.startsWith("http")) return@Factory spec
            val h = fs.headers(url)
            if (lib.kind != "dav" || h.isEmpty()) return@Factory if (h.isEmpty()) spec else spec.withAdditionalHeaders(h)
            // 1.0.3: same logic as before, now in PlayHttp.resolve (shared with the music player)
            val (final, fh) = PlayHttp.resolve(url, h, followRedirect = true)
            if (fh.isNotEmpty()) spec.withUri(Uri.parse(final)).withAdditionalHeaders(fh) else spec.withUri(Uri.parse(final))
        }
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 60_000, 1_200, 2_500).build()
        // 1.0.9: FFmpeg audio decoders first (DTS / TrueHD / AC3 / EAC3 play on every phone), fall back to other decoders
        // when one fails to start (Dolby Vision → HEVC on non-DV devices).
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(ctx, renderers).setLoadControl(load).setMediaSourceFactory(DefaultMediaSourceFactory(resolving)).build().apply {
            val items = queue.map { q -> itemFor(q, urls[q.path].orEmpty(), subOffset) }
            setMediaItems(items, start, lib.progressOf(queue[start].path)?.takeIf { !it.watched && it.pos > 5_000 }?.pos ?: 0L)
            val a = PlayPrefs.audioLang(showId); val sl = PlayPrefs.subLang(showId)
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguages(*(listOf(a).filter { it.isNotEmpty() } + listOf("zh", "chi")).toTypedArray())
                .setPreferredTextLanguages(*(listOf(sl).filter { it.isNotEmpty() && it != "off" } + listOf("zh", "chi", "zho")).toTypedArray())
                .setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, sl == "off")
                .build()
            if (startVlc) playWhenReady = false else { prepare(); playWhenReady = true }
        }
    }
    fun toVlc(i: Int, at: Long) { player.pause(); vlcStart = at; vlcIdx = i; error = null; canVlc = false }

    fun save() {
        if (vlcIdx >= 0) return // VlcPlayer saves its own progress
        val id = player.currentMediaItem?.mediaId ?: return
        val d = player.duration
        if (d > 0 && d != MC.TIME_UNSET) lib.setProgress(id, player.currentPosition, d)
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaMetadata?.title?.let { title = it.toString() }
                mediaItem?.mediaId?.let { curPath = it }
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    // previous episode ran to the end
                    val prev = queue.getOrNull(player.currentMediaItemIndex - 1)
                    prev?.let { p -> lib.progressOf(p.path)?.let { lib.setProgress(p.path, it.dur, it.dur) } }
                }
                error = null
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val id = player.currentMediaItem?.mediaId ?: return
                // badges from the real streams, shown on the detail page next time
                val badges = MediaBadges.fromTracks(tracks)
                MediaBadges.remember(id, badges)
                val dv = tracks.groups.firstNotNullOfOrNull { g -> if (g.type == MC.TRACK_TYPE_VIDEO) MediaBadges.dvProfile(g.getTrackFormat(0)) else null }
                if (hint == null) hint = DeviceCaps.hint(ctx, badges, dv)
                // remember the language choice for this show (applies to the next episodes / next time)
                tracks.groups.firstOrNull { it.type == MC.TRACK_TYPE_AUDIO && it.isSelected }?.let { g ->
                    (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language }?.let { PlayPrefs.setAudioLang(showId, it) }
                }
                val texts = tracks.groups.filter { it.type == MC.TRACK_TYPE_TEXT }
                if (texts.isNotEmpty()) {
                    val sel = texts.firstOrNull { it.isSelected }
                    val lang = sel?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language } }
                    if (sel == null && player.trackSelectionParameters.disabledTrackTypes.contains(MC.TRACK_TYPE_TEXT)) PlayPrefs.setSubLang(showId, "off")
                    else if (lang != null) PlayPrefs.setSubLang(showId, lang)
                }
            }
            override fun onPlayerError(e: PlaybackException) {
                if (vlcIdx >= 0) return
                canVlc = e.errorCode in setOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED)
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "服务器拒绝了请求，检查账号权限"
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接失败"
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "手机解不了这个编码"
                    else -> if (urls[player.currentMediaItem?.mediaId].isNullOrEmpty() && com.hark.shiguang.data.FnFiles.lastError == com.hark.shiguang.data.FnFiles.RELOGIN)
                        com.hark.shiguang.data.FnFiles.RELOGIN else "播放失败（${e.errorCodeName}）"
                }
            }
        }
        player.addListener(l)
        onDispose { save(); player.removeListener(l); player.release() }
    }
    LaunchedEffect(player) { var n = 0; while (true) { delay(1_000); if (player.isPlaying && ++n % 5 == 0) save()
        // skip intro / outro (per show, set from the ⋯ menu); each only once per episode so seeking back works
        val id = player.currentMediaItem?.mediaId ?: continue
        val intro = PlayPrefs.intro(showId) * 1000L; val outro = PlayPrefs.outro(showId) * 1000L
        val pos = player.currentPosition; val dur = player.duration
        if (intro > 0 && pos in 0 until intro - 1500 && skipped.add("i$id")) { player.seekTo(intro); hint = "已跳过片头" }
        if (outro > 0 && dur > 0 && dur != MC.TIME_UNSET && dur - pos in 1..outro && player.hasNextMediaItem() && skipped.add("o$id")) {
            lib.setProgress(id, dur, dur); player.seekToNextMediaItem(); hint = "已跳过片尾"
        }
    } }
    LaunchedEffect(hint) { if (hint != null) { delay(6_000); hint = null } }
    // load the comments of the current file whenever it changes (or after a manual match)
    LaunchedEffect(curPath, dmOn, dmView, dmReload) {
        val dv = dmView ?: return@LaunchedEffect
        dv.visibility = if (dmOn) android.view.View.VISIBLE else android.view.View.GONE
        dv.clear(); dmMatch = null
        if (!dmOn) { dmStatus = "弹幕已关闭"; return@LaunchedEffect }
        if (!DanmakuPrefs.configured) { dmStatus = DanmakuPrefs.HINT; return@LaunchedEffect }
        val q = queue.firstOrNull { it.path == curPath } ?: return@LaunchedEffect
        dmStatus = "正在匹配弹幕…"
        var dur = player.duration; var waited = 0
        while ((dur <= 0 || dur == MC.TIME_UNSET) && waited < 6) { delay(500); dur = player.duration; waited++ }
        val durMs = if (dur > 0 && dur != MC.TIME_UNSET) dur else q.file.duration * 1000L
        runCatching {
            withContext(Dispatchers.IO) {
                val m = DanDan.auto(lib, item, q.file, q.path, durMs) ?: return@withContext null
                m to DanDan.comments(m.episodeId).let { l -> if (m.shift != 0.0) l.map { it.copy(t = it.t + (m.shift * 1000).toLong()) } else l }
            }
        }.onSuccess { r ->
            if (r == null) { dmStatus = "没有匹配到弹幕，可以手动匹配"; hint = "没有匹配到弹幕 · 点「弹幕」手动匹配" }
            else { dmMatch = r.first; dv.setData(r.second); dmStatus = "${r.first.label} · ${r.second.size} 条"; if (r.second.isNotEmpty()) hint = "弹幕 ${r.second.size} 条" }
        }.onFailure { dmStatus = it.message ?: "弹幕加载失败"; if (it !is DanmakuNotConfigured) hint = "弹幕加载失败" }
    }
    fun applySubStyle() { playerView?.subtitleView?.let { sv -> sv.setApplyEmbeddedFontSizes(false); sv.setFractionalTextSize(PlayPrefs.subSize); sv.setBottomPaddingFraction(PlayPrefs.subBottom) } }
    fun setOffset(ms: Long) {
        subOffset = ms; PlayPrefs.setSubOffset(showId, ms)
        val i = player.currentMediaItemIndex; val q = queue.getOrNull(i) ?: return
        if (subMap[q.path].isNullOrEmpty()) { hint = "字幕延迟仅外挂字幕可用"; return }
        val pos = player.currentPosition
        player.replaceMediaItem(i, itemFor(q, urls[q.path].orEmpty(), ms)); player.seekTo(i, pos)
    }
    BackHandler { if (vlcIdx < 0) save(); Nav.pop() }

    if (vlcIdx >= 0) {
        val q = queue[vlcIdx]
        val u = urls[q.path].orEmpty()
        VlcPlayer(u, if (u.startsWith("http")) fs.headers(u) else emptyMap(), q.file.name, q.title, vlcStart,
            onProgress = { p, d -> if (d > 0) lib.setProgress(q.path, p, d) },
            onEnded = {
                val n = vlcIdx + 1
                if (n < queue.size) { if (needsVlc(queue[n])) { vlcStart = 0L; vlcIdx = n } else { vlcIdx = -1; player.seekTo(n, 0L); player.prepare(); player.play() } }
            },
            onBack = { Nav.pop() })
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = {
            PlayerView(it).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                this.player = player
                useController = true
                setShowSubtitleButton(true)
                setShowNextButton(queue.size > 1); setShowPreviousButton(queue.size > 1)
                setShowFastForwardButton(true); setShowRewindButton(true)
                controllerShowTimeoutMs = 3500
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controls = v == android.view.View.VISIBLE })
                keepScreenOn = true
                playerView = this
                subtitleView?.let { sv -> sv.setApplyEmbeddedFontSizes(false); sv.setFractionalTextSize(PlayPrefs.subSize); sv.setBottomPaddingFraction(PlayPrefs.subBottom) }
                // danmaku layer: inside the overlay frame (above video + subtitles, below the controller; touches fall through)
                overlayFrameLayout?.let { ov ->
                    val dv = DanmakuView(it).apply { position = { player.currentPosition }; playing = { player.isPlaying }; visibility = if (dmOn) android.view.View.VISIBLE else android.view.View.GONE }
                    ov.addView(dv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    dmView = dv
                }
            }
        }, modifier = Modifier.fillMaxSize())
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))).padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { save(); Nav.pop() }) { Icon(LI.back(true), null, tint = Color.White) }
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                SpeedButton(player)
                Box {
                    TextButton(onClick = { dmMenu = true }) { Text(if (dmOn) "弹幕" else "弹幕关", color = if (dmOn) Color.White else Color(0x99FFFFFF), fontSize = 14.sp) }
                    DropdownMenu(dmMenu, onDismissRequest = { dmMenu = false }) {
                        Text(dmStatus.ifEmpty { "弹弹play 弹幕" }, fontSize = 12.sp, color = C.Sub, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = 280.dp))
                        DropdownMenuItem(text = { Text(if (dmOn) "关闭弹幕" else "打开弹幕") }, onClick = { dmMenu = false; dmOn = !dmOn; DanmakuPrefs.enabled = dmOn })
                        DropdownMenuItem(text = { Text("手动匹配…") }, onClick = { dmMenu = false; dmSearch = true })
                        DropdownMenuItem(text = { Text("弹幕设置…") }, onClick = { dmMenu = false; dmSettings = true })
                    }
                }
                IconButton(onClick = { player.pause(); casting = true }) { Icon(Icons.Rounded.Cast, null, tint = Color.White) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, null, tint = Color.White) }
            }
        }
        hint?.let { h ->
            Text(h, color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 24.dp, end = 24.dp)
                .background(Color(0xB3000000), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        error?.let { e ->
            Column(Modifier.align(Alignment.Center).background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(e, color = Color.White, fontSize = 15.sp)
                Row(Modifier.padding(top = 10.dp)) {
                    TextButton(onClick = { error = null; player.prepare(); player.play() }) { Text("重试", color = C.Accent) }
                    if (canVlc) TextButton(onClick = { toVlc(player.currentMediaItemIndex, player.currentPosition) }) { Text("用兼容模式播放", color = C.Accent) }
                    if (player.hasNextMediaItem()) TextButton(onClick = { error = null; player.seekToNextMediaItem(); player.prepare(); player.play() }) { Text("下一集", color = Color.White) }
                }
            }
        }
    }
    if (menu) {
        // 1.0.10: delay works only for sideloaded files (we rewrite their timestamps). media3 1.3.1 has no cue-offset hook for
        // embedded tracks: a TextOutput wrapper could only delay (not advance) cues and breaks on seek/pause, so embedded = disabled.
        val q = queue.getOrNull(player.currentMediaItemIndex)
        val extLabels = q?.let { qq -> subMap[qq.path].orEmpty().mapIndexed { i, f -> subLabel(qq.file.subs.getOrNull(i)?.substringAfterLast('/') ?: f.name, qq.file.name) } }.orEmpty().toSet()
        val selText = player.currentTracks.groups.firstOrNull { it.type == MC.TRACK_TYPE_TEXT && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
        val extSelected = extLabels.isNotEmpty() && (selText == null || selText.label in extLabels)
        PlayerMenu(player, showId, subOffset, extSelected, { setOffset(it) }, { applySubStyle() }) { menu = false }
    }
    if (dmSettings) DanmakuSettingsDialog(onChanged = { dmView?.applyPrefs() }) { dmSettings = false }
    if (dmSearch) {
        val ep = item.episodes.firstOrNull { it.path == curPath }
        DanmakuSearchDialog(dmMatch?.anime?.takeIf { it.isNotBlank() } ?: item.title, ep?.episode, onPick = { m ->
            DanDan.remember(lib.sourceKey, curPath, m); if (!dmOn) { dmOn = true; DanmakuPrefs.enabled = true }; dmReload++
        }) { dmSearch = false }
    }
    if (casting) {
        val q = queue.getOrNull(player.currentMediaItemIndex) ?: queue[start]
        val media = CastMedia(q.title, q.file.name, player.currentPosition) { (urls[q.path] ?: fs.playUrl(q.file))?.let { u -> u to fs.headers(u) } }
        CastDialog(media) { casting = false }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlayerMenu(player: ExoPlayer, showId: String, offset: Long, offsetEnabled: Boolean, onOffset: (Long) -> Unit, onStyle: () -> Unit, onDismiss: () -> Unit) {
    var intro by remember { mutableIntStateOf(PlayPrefs.intro(showId)) }
    var outro by remember { mutableIntStateOf(PlayPrefs.outro(showId)) }
    var size by remember { mutableFloatStateOf(PlayPrefs.subSize) }
    var bottom by remember { mutableFloatStateOf(PlayPrefs.subBottom) }
    fun secs(s: Int) = if (s <= 0) "未设置" else "%d:%02d".format(s / 60, s % 60)
    AlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface, title = { Text("播放设置", color = C.Text) }, text = {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text("本剧通用", color = C.Sub, fontSize = 12.sp)
            MenuLine("跳过片头 · ${secs(intro)}", "设为当前位置", if (intro > 0) "清除" else null,
                { intro = (player.currentPosition / 1000).toInt(); PlayPrefs.setIntro(showId, intro) }, { intro = 0; PlayPrefs.setIntro(showId, 0) })
            MenuLine("跳过片尾 · ${secs(outro)}", "从当前位置起", if (outro > 0) "清除" else null, {
                val d = player.duration; if (d > 0 && d != MC.TIME_UNSET) { outro = ((d - player.currentPosition) / 1000).toInt().coerceAtLeast(1); PlayPrefs.setOutro(showId, outro) }
            }, { outro = 0; PlayPrefs.setOutro(showId, 0) })
            Stepper("字幕延迟（仅外挂字幕）", "%+.1f 秒".format(offset / 1000f), { onOffset(offset - 500) }, { onOffset(offset + 500) }, enabled = offsetEnabled)
            if (!offsetEnabled) Text("当前是内嵌字幕或没有外挂字幕，无法调整延迟。", color = C.Faint, fontSize = 11.sp)
            Text("字幕外观", color = C.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            Stepper("字幕大小", "${(size / 0.0533f * 100).toInt()}%", { size = (size - 0.005f).coerceAtLeast(0.03f); PlayPrefs.subSize = size; onStyle() },
                { size = (size + 0.005f).coerceAtMost(0.1f); PlayPrefs.subSize = size; onStyle() })
            Stepper("字幕位置", if (bottom <= 0.03f) "最低" else "抬高 ${(bottom * 100).toInt()}%", { bottom = (bottom - 0.02f).coerceAtLeast(0f); PlayPrefs.subBottom = bottom; onStyle() },
                { bottom = (bottom + 0.02f).coerceAtMost(0.4f); PlayPrefs.subBottom = bottom; onStyle() })
            Text("音轨和字幕轨在播放器底栏的设置按钮里选，选过的语言本剧会记住。", color = C.Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }, confirmButton = { TextButton(onDismiss) { Text("完成", color = C.Accent) } })
}

@Composable
private fun MenuLine(label: String, action: String, clear: String?, onAction: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (clear != null) TextButton(onClear) { Text(clear, color = C.Sub, fontSize = 13.sp) }
        TextButton(onAction) { Text(action, color = C.Accent, fontSize = 13.sp) }
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, enabled: Boolean = true) {
    val col = if (enabled) C.Text else C.Faint
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = col, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onMinus, enabled = enabled) { Text("−", color = col, fontSize = 18.sp) }
        Text(value, color = col, fontSize = 13.sp, modifier = Modifier.widthIn(min = 64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        TextButton(onPlus, enabled = enabled) { Text("+", color = col, fontSize = 18.sp) }
    }
}

/** Reads a subtitle file as UTF-8, falling back to GBK (common for Chinese .srt/.ass). */
private fun readSub(f: File): String {
    val b = f.readBytes()
    val utf = String(b, Charsets.UTF_8)
    return if (utf.contains('\uFFFD')) runCatching { String(b, charset("GBK")) }.getOrDefault(utf) else utf
}

@Composable
private fun SpeedButton(player: ExoPlayer) {
    var open by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    Box {
        TextButton(onClick = { open = true }) { Text(if (speed == 1f) "倍速" else "${fmtSpeed(speed)}×", color = Color.White, fontSize = 14.sp) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { s ->
                DropdownMenuItem(text = { Text("${fmtSpeed(s)}×", fontWeight = if (s == speed) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { open = false; speed = s; player.setPlaybackSpeed(s) })
            }
        }
    }
}

private fun fmtSpeed(s: Float) = if (s == s.toInt().toFloat()) "${s.toInt()}" else "$s".trimEnd('0')

private fun subLang(name: String): String? {
    val n = name.lowercase()
    return when {
        Regex("(chs|chi|zho|zh|sc|gb|简|中|cht|tc|big5|繁)").containsMatchIn(n) -> "zh"
        Regex("(eng|\\ben\\b|英)").containsMatchIn(n) -> "en"
        Regex("(jpn|\\bja\\b|日)").containsMatchIn(n) -> "ja"
        else -> null
    }
}

private fun subLabel(sub: String, video: String): String {
    val rest = NameParser.stem(sub).removePrefix(NameParser.stem(video)).trim('.', ' ', '-', '_')
    val n = rest.lowercase()
    return when {
        n.contains("chs") && n.contains("eng") || n.contains("简英") -> "简体 + 英文"
        n.contains("cht") && n.contains("eng") || n.contains("繁英") -> "繁体 + 英文"
        n.contains("chs") || n.contains("sc") || n.contains("简") || n.contains("gb") -> "简体中文"
        n.contains("cht") || n.contains("tc") || n.contains("繁") || n.contains("big5") -> "繁体中文"
        n.contains("eng") || n == "en" -> "英文"
        rest.isEmpty() -> "外挂字幕"
        else -> rest
    }
}
