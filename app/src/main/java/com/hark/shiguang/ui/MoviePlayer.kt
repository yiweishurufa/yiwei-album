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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
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
import kotlinx.coroutines.launch
import com.hark.shiguang.data.FnTvParse
import com.hark.shiguang.data.FnTvTier
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

/** 1.1.0 剧集连播：本集结束前多久开始倒数。 */
private const val NEXT_COUNTDOWN_MS = 5_000L

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
        Analyzer.videoPlaying = true   // 2026-10-22: background WebDAV duration reads wait until the player closes
        Diag.log("PLAY", "open ${if (lib.kind == "dav") "WebDAV" else "飞牛"} video")
        val old = act?.requestedOrientation
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val w = act?.window
        val ctl = w?.let { WindowCompat.getInsetsController(it, it.decorView) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            Analyzer.videoPlaying = false
            act?.requestedOrientation = old ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ctl?.show(WindowInsetsCompat.Type.systemBars())
            w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    if (item == null || queue.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { Text(if (lib.ready) "找不到这个视频" else "正在读取影视库…", color = Color.White) }
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
    /** B35: the P7 EL-drop override is applied once per media item (it re-fires onTracksChanged). */
    var dvFixedFor by remember { mutableStateOf("") }
    var casting by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var gestureLayer by remember { mutableStateOf<PlayerGestureLayer?>(null) }
    // 用户 2026-10-23 反馈：右侧面板（音轨 / 字幕 / 清晰度 / 详细信息）和手势浮层
    var panel by remember { mutableStateOf<PlayerPanel?>(null) }
    var moreMenu by remember { mutableStateOf(false) }
    var tracksVer by remember { mutableIntStateOf(0) }
    var seekPreview by remember { mutableStateOf<String?>(null) }
    var seekFrac by remember { mutableFloatStateOf(0f) }
    var levelHud by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var fast by remember { mutableStateOf(false) }
    /** Double tap feedback: true = now playing, false = now paused. */
    var flash by remember { mutableStateOf<Boolean?>(null) }
    val skipped = remember { HashSet<String>() }
    // 1.1.0: libVLC removed. rmvb/rm/wmv/asf (no media3 extractor) and files media3 reports as unsupported → 「用其他播放器打开」
    fun needsExternal(q: QItem) = NameParser.ext(q.file.name) in ExternalPlayer.VIDEO_EXT
    val startExternal = needsExternal(queue[start])
    var extIdx by remember { mutableIntStateOf(if (startExternal) start else -1) }
    var canExternal by remember { mutableStateOf(false) }

    /** External subtitles of [q], shifted by the show's subtitle delay when it is not 0. */
    fun itemFor(q: QItem, url: String, offset: Long, hls: Boolean = false): MediaItem {
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
            .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }   // 飞牛影视转码是 HLS（play_link …/preset.m3u8）
            .setMediaMetadata(MediaMetadata.Builder().setTitle(q.title).build()).build()
    }
    val urls = remember(queue) { HashMap<String, String>(pre) }
    // 用户 2026-10-23 反馈（清晰度）：飞牛来源可切到飞牛影视服务器转码（见 FnTvQuality）；WebDAV 只有原画
    val isFn = lib.kind != "dav"
    val tv = remember(queue) { FnTvQuality() }
    val scope = rememberCoroutineScope()
    var curPath by remember { mutableStateOf(queue[start].path) }
    /** Url the player uses for [q]: its running transcode, else the original file. */
    fun srcOf(q: QItem): String = tv.urlFor(q.path) ?: urls[q.path].orEmpty()
    // 1.1.0 剧集连播：倒数秒数（null = 不显示）、本集已取消连播的文件
    val isShow = item.kind != "movie"
    val autoNext = remember { PlayPrefs.autoNext }
    var nextIn by remember { mutableStateOf<Int?>(null) }
    var nextCancelledFor by remember { mutableStateOf("") }

    val bufferParams = remember { PlayerBuffer.forHeap(Runtime.getRuntime().maxMemory()) }
    val player = remember(queue) {
        val upstream = OkHttpDataSource.Factory(PlayHttp.client)
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(ctx, upstream)) { spec ->
            val url = spec.uri.toString()
            if (!url.startsWith("http")) return@Factory spec
            com.hark.shiguang.data.FnTv.mediaHeaders(url)?.let { return@Factory spec.withAdditionalHeaders(it) }   // 转码 HLS 片段
            val h = fs.headers(url)
            if (lib.kind != "dav" || h.isEmpty()) return@Factory if (h.isEmpty()) spec else spec.withAdditionalHeaders(h)
            // 1.0.3: same logic as before, now in PlayHttp.resolve (shared with the music player)
            val (final, fh) = PlayHttp.resolve(url, h, followRedirect = true)
            if (fh.isNotEmpty()) spec.withUri(Uri.parse(final)).withAdditionalHeaders(fh) else spec.withUri(Uri.parse(final))
        }
        // 用户 2026-10-24 反馈：WebDAV 观看卡顿 —— 卡顿后多攒一些再播、字节上限按手机堆大小（见 PlayerBuffer）
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(bufferParams.minMs, bufferParams.maxMs, bufferParams.playMs, bufferParams.rebufferMs)
            .setTargetBufferBytes(bufferParams.targetBytes).setPrioritizeTimeOverSizeThresholds(false).build()
        // 1.0.9: FFmpeg audio decoders first (DTS / TrueHD / AC3 / EAC3 play on every phone), fall back to other decoders
        // when one fails to start (Dolby Vision → HEVC on non-DV devices).
        val renderers = ffmpegRenderersFactory(ctx)
        // 全面审查 B1：申请音频焦点，来电或别的 App 放声音时自动暂停
        ExoPlayer.Builder(ctx, renderers).setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(MC.USAGE_MEDIA).setContentType(MC.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setLoadControl(load).setMediaSourceFactory(DefaultMediaSourceFactory(resolving)).build().apply {
            val items = queue.map { q -> itemFor(q, urls[q.path].orEmpty(), subOffset) }
            setMediaItems(items, start, if (r.fromStart) 0L else lib.progressOf(queue[start].path)?.takeIf { !it.watched && it.pos > 5_000 }?.pos ?: 0L)
            val a = PlayPrefs.audioLang(showId); val sl = PlayPrefs.subLang(showId)
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguages(*(listOf(a).filter { it.isNotEmpty() } + listOf("zh", "chi")).toTypedArray())
                .setPreferredTextLanguages(*(listOf(sl).filter { it.isNotEmpty() && it != "off" } + listOf("zh", "chi", "zho")).toTypedArray())
                .setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, sl == "off")
                .build()
            // 1.1.0: 「自动播放下一集」关闭时每集播完停在结尾（下一集按钮仍可用）
            pauseAtEndOfMediaItems = queue.size > 1 && !autoNext
            if (startExternal) playWhenReady = false else { prepare(); playWhenReady = true }
        }
    }
    // 迭代 #52 续播提示：从上次位置接着播时顶部提示一次（hint 6 秒后自动消失）；#51 第一次进播放器显示手势教学
    val resumeAt = remember(queue) { if (r.fromStart) 0L else lib.progressOf(queue[start].path)?.takeIf { !it.watched }?.pos ?: 0L }
    var gestureTip by remember { mutableStateOf(!PlayPrefs.gestureTipSeen) }
    LaunchedEffect(Unit) {
        PlayerHints.resume(resumeAt)?.let { hint = it }
    }
    LaunchedEffect(gestureTip) { if (gestureTip) { PlayPrefs.gestureTipSeen = true; delay(6_000); gestureTip = false } }
    // 用户 2026-10-24 反馈：连续快进闪退 / 卡顿 —— 同一集内的跳转（横滑、快进快退按钮、进度条）合并成一次
    val seekGate = remember(player) { PlayerSeekGate(player) }
    /** Hands queue item [i] to another app (auth goes through the loopback proxy, see ExternalPlayer). */
    fun openExternal(i: Int) {
        val q = queue.getOrNull(i) ?: return
        val u = urls[q.path].orEmpty()
        if (u.isEmpty()) { hint = "还没拿到这个文件的播放地址，请稍后再试"; return }
        player.pause()
        ExternalPlayer.open(ctx, u, if (u.startsWith("http")) fs.headers(u) else emptyMap(), q.file.name, audio = false)?.let { toast(ctx, it) }
    }

    fun save() {
        if (extIdx >= 0) return // played elsewhere: no position to save
        val id = player.currentMediaItem?.mediaId ?: return
        val d = player.duration
        // 还没执行的合并跳转也算（拖完进度条 0.35 秒内就退出，以前记的是拖之前的位置）
        if (d > 0 && d != MC.TIME_UNSET) lib.setProgress(id, seekGate.position(), d)
        if (isShow) lib.setLastEpisode(showId, id)   // 1.1.0: 记住这部剧看到第几集（位置在上面的文件进度里）
    }

    /** Language and index (among tracks of [type]) of the selected track. */
    fun selectedTrack(type: Int): Pair<String?, Int?> {
        var n = 0
        player.currentTracks.groups.filter { it.type == type }.forEach { g ->
            for (k in 0 until g.length) { if (g.isTrackSelected(k)) return g.getTrackFormat(k).language to n; n++ }
        }
        return null to null
    }
    /** Language of the selected subtitle when it is embedded in the file (external ones stay attached to the item anyway). */
    fun embeddedSubLang(q: QItem): String? {
        val ext = subMap[q.path].orEmpty().mapIndexed { i, f -> subLabel(q.file.subs.getOrNull(i)?.substringAfterLast('/') ?: f.name, q.file.name) }.toSet()
        val sel = player.currentTracks.groups.firstOrNull { it.type == MC.TRACK_TYPE_TEXT && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } } ?: return null
        return if (sel.label in ext) null else sel.language
    }
    /** Puts queue item [i] on [url] at [pos]; play / pause stays as it was. */
    fun reload(i: Int, q: QItem, url: String, hls: Boolean, pos: Long) {
        seekGate.cancel()   // a merged seek still waiting belongs to the old source; [pos] already includes it
        player.replaceMediaItem(i, itemFor(q, url, subOffset, hls)); player.seekTo(i, pos)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }
    /**
     * 清晰度 for the current item: null = 原画 (the original file), else a 飞牛影视 tier from the current position.
     * A tier that fails to start leaves (or puts back) the original file and says why.
     */
    fun switchQuality(t: FnTvTier?, why: String? = null) {
        val i = player.currentMediaItemIndex; val q = queue.getOrNull(i) ?: return
        val pos = seekGate.position().coerceAtLeast(0)
        val wasHls = tv.transcoding(q.path)
        if (t == null) {
            tv.stop()
            if (wasHls) reload(i, q, urls[q.path].orEmpty(), false, pos)
            why?.let { hint = it }
            return
        }
        if (tv.busy || (wasHls && tv.tier == t)) return
        tv.busy = true
        scope.launch {
            val res = runCatching {
                tv.start(q.path, t, pos / 1000,
                    audioPick = { list -> val (l, n) = selectedTrack(MC.TRACK_TYPE_AUDIO); FnTvParse.pickAudio(list, l, n) },
                    subPick = { list -> FnTvParse.pickSubtitle(list, embeddedSubLang(q)) })
            }
            tv.busy = false
            // 用户 2026-10-24 要求继续优化：切换期间原来的流还在播、用户也可能又拖了进度 —— 接上「现在」的位置，
            // 不再跳回点清晰度那一刻（以前会倒回去重播切换花掉的几秒，期间的快进也丢了）
            res.onSuccess { u ->
                if (player.currentMediaItemIndex != i) { tv.stop(); return@onSuccess }
                error = null; reload(i, q, u, true, seekGate.position().coerceAtLeast(0)); hint = "已切换到 ${t.label}（飞牛影视转码）"
            }.onFailure { e ->
                // 播放器已关 / 换集 / 切回原画时会话已作废（FnTvSessions），什么都不用做
                if (e is kotlinx.coroutines.CancellationException) return@onFailure
                FnTvQuality.log("switch failed: ${e.message}")
                if (wasHls && player.currentMediaItemIndex == i) reload(i, q, urls[q.path].orEmpty(), false, seekGate.position().coerceAtLeast(0))
                hint = "转码没有开始：${e.message ?: "未知错误"}，" + if (wasHls) "已回到原画" else "继续播放原画"
            }
        }
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
                seekGate.cancel()   // a seek still waiting belongs to the previous episode
                if (isShow) mediaItem?.mediaId?.let { lib.setLastEpisode(showId, it) }
                // a new episode: the countdown starts fresh, and a cancel only applied to the previous one
                nextIn = null
                player.pauseAtEndOfMediaItems = queue.size > 1 && !autoNext
                error = null
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                tracksVer++
                val id = player.currentMediaItem?.mediaId ?: return
                // badges from the real streams, shown on the detail page next time
                val badges = MediaBadges.fromTracks(tracks)
                MediaBadges.remember(id, badges)
                val dv = tracks.groups.firstNotNullOfOrNull { g -> if (g.type == MC.TRACK_TYPE_VIDEO) MediaBadges.dvProfile(g.getTrackFormat(0)) else null }
                if (hint == null) hint = DeviceCaps.hint(ctx, badges, dv)
                // B35: P7 dual-layer on a non-DV phone — drop the EL track once per media item.
                val mid = player.currentMediaItem?.mediaId.orEmpty()
                if (dv == 7 && !DeviceCaps.dolbyVision && dvFixedFor != mid) {
                    val overrides = DeviceCaps.elDisabledOverrides(tracks)
                    if (overrides.isNotEmpty()) {
                        dvFixedFor = mid
                        val b = player.trackSelectionParameters.buildUpon()
                        overrides.forEach { b.addOverride(it) }
                        player.trackSelectionParameters = b.build()
                    }
                }
                // B33: only a MANUAL track pick is persisted. Automatic fallback (unsupported codec → next
                // track) applies to this playback only. The settings dialog writes explicit audio/text
                // overrides, so an audio/text override present = the user picked it (a video-only override
                // is the B35 EL-drop, not a user pick).
                // overrides is a Map<TrackGroup, TrackSelectionOverride> in media3 — check the values, not the entries.
                val manual = player.trackSelectionParameters.overrides.values.any { it.mediaTrackGroup.type == MC.TRACK_TYPE_AUDIO || it.mediaTrackGroup.type == MC.TRACK_TYPE_TEXT }
                if (manual) {
                    // remember the language choice for this show (applies to the next episodes / next time)
                    tracks.groups.firstOrNull { it.type == MC.TRACK_TYPE_AUDIO && it.isSelected }?.let { g ->
                        (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language }?.let { PlayPrefs.setAudioLang(showId, it) }
                    }
                }
                val texts = tracks.groups.filter { it.type == MC.TRACK_TYPE_TEXT }
                if (texts.isNotEmpty()) {
                    val sel = texts.firstOrNull { it.isSelected }
                    val lang = sel?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language } }
                    // turning subtitles off via the dialog disables the text track type — that is manual
                    // too, but only when it differs from the stored preference (the initial setup also
                    // disables it when the stored pref is "off").
                    if (sel == null && player.trackSelectionParameters.disabledTrackTypes.contains(MC.TRACK_TYPE_TEXT) && PlayPrefs.subLang(showId) != "off") PlayPrefs.setSubLang(showId, "off")
                    else if (manual && lang != null) PlayPrefs.setSubLang(showId, lang)
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                // FNTV: one line per started transcode, to check on a real NAS that the HLS timeline is the whole film
                if (state == Player.STATE_READY && tv.pendingReadyLog && tv.transcoding(player.currentMediaItem?.mediaId)) {
                    tv.pendingReadyLog = false
                    FnTvQuality.log("hls ready duration=${player.duration / 1000}s pos=${player.currentPosition / 1000}s")
                }
            }
            override fun onPlayerError(e: PlaybackException) {
                Diag.log("PLAY", "error ${e.errorCodeName}: ${e.cause?.javaClass?.simpleName} ${e.cause?.message?.take(120)}")
                if (extIdx >= 0) return
                // 用户 2026-10-23 反馈（清晰度）：转码流放不了就自动回到原画，不弹错误框
                if (tv.transcoding(player.currentMediaItem?.mediaId)) { switchQuality(null, "转码播放失败（${e.errorCodeName}），已回到原画"); return }
                // B32: an IO failure means the cached redirect target is probably dead — drop it so the
                // retry re-resolves instead of replaying the same stale signed url.
                if (e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS || e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT || e.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
                    PlayHttp.invalidate(urls[player.currentMediaItem?.mediaId].orEmpty())
                }
                canExternal = e.errorCode in setOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
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
        // 用户 2026-10-24 要求继续优化：先结束转码会话（发 media.quit），存进度出异常也不影响它和播放器释放
        onDispose {
            tv.stop()
            runCatching { save() }.onFailure { Diag.log("PLAY", "save on close failed: ${it.javaClass.simpleName}") }
            player.removeListener(l); seekGate.release(); player.release()
        }
    }
    // 用户 2026-10-24 反馈：快进闪退 / 卡顿 —— 播放链路分段写 [PLAYER] 诊断日志（解码器、卡顿、下载错误、内存、跳转）
    DisposableEffect(player) {
        val pd = PlayerDiag(ctx, bufferParams)
        val f = queue[start].file
        pd.start(if (lib.kind == "dav") "WebDAV" else "飞牛", NameParser.ext(f.name), f.size)
        player.addAnalyticsListener(pd)
        onDispose { player.removeAnalyticsListener(pd); pd.stop() }
    }
    LaunchedEffect(player) { var n = 0; while (true) { delay(1_000); if (player.isPlaying && ++n % 5 == 0) save()
        // skip intro / outro (per show, set from the ⋯ menu); each only once per episode so seeking back works
        val id = player.currentMediaItem?.mediaId ?: continue
        val intro = PlayPrefs.intro(showId) * 1000L; val outro = PlayPrefs.outro(showId) * 1000L
        val pos = player.currentPosition; val dur = player.duration
        if (intro > 0 && pos in 0 until intro - 1500 && skipped.add("i$id")) { player.seekTo(intro); hint = "已跳过片头" }
        if (outro > 0 && dur > 0 && dur != MC.TIME_UNSET && dur - pos in 1..outro && player.hasNextMediaItem() && nextCancelledFor != id && skipped.add("o$id")) {
            lib.setProgress(id, dur, dur)
            // 1.1.0: 自动连播关闭时只停在片尾，不替用户切到下一集
            if (autoNext) { player.seekToNextMediaItem(); hint = "已跳过片尾" } else { player.pause(); hint = "已到片尾 · 自动播放下一集已关闭" }
        }
    } }
    // 1.1.0 剧集连播：本集结束（设了跳过片尾时是片尾开始处）前 5 秒显示「下一集 N 秒后播放」，可取消
    LaunchedEffect(player) {
        if (!autoNext || queue.size < 2) return@LaunchedEffect
        while (true) {
            delay(250)
            val id = player.currentMediaItem?.mediaId
            val dur = player.duration
            if (id == null || dur <= 0 || dur == MC.TIME_UNSET || !player.hasNextMediaItem() || nextCancelledFor == id || error != null || extIdx >= 0) { nextIn = null; continue }
            val outro = PlayPrefs.outro(showId) * 1000L
            val end = if (outro > 0 && outro < dur) dur - outro else dur
            val speed = player.playbackParameters.speed.takeIf { it > 0f } ?: 1f
            val left = ((end - player.currentPosition) / speed).toLong()
            nextIn = if (left in 1..NEXT_COUNTDOWN_MS) ((left + 999) / 1000).toInt() else null
        }
    }
    fun cancelNext() {
        nextCancelledFor = player.currentMediaItem?.mediaId.orEmpty(); nextIn = null
        player.pauseAtEndOfMediaItems = true   // stay at the end of this episode instead of moving on
    }
    // 迭代 #68 定时关闭：N 分钟后暂停 / 本集播完后停（⋯ → 定时关闭）；只在本次播放里有效，不记住
    var sleepMin by remember { mutableIntStateOf(0) }
    var sleepEndFor by remember { mutableStateOf<String?>(null) }   // 「本集播完后停」对哪一集有效；换集后自动失效
    val sleepEnd = sleepEndFor != null && sleepEndFor == curPath
    var sleepDialog by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }   // 迭代 #64 锁屏防误触，仅本次播放有效
    var lockBtn by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }   // 迭代 #74 播放状态浮窗，默认关，仅本次播放有效
    var statsText by remember { mutableStateOf("") }
    LaunchedEffect(showStats, player) {
        while (showStats) {
            val f = player.videoFormat
            statsText = PlayerHints.stats(f?.width ?: 0, f?.height ?: 0, f?.frameRate ?: 0f, f?.bitrate?.takeIf { it > 0 } ?: f?.averageBitrate ?: 0, (player.bufferedPosition - player.currentPosition))
            delay(1000)
        }
    }
    var fitMode by remember { mutableIntStateOf(0) }   // 迭代 #61 画面比例，仅本次播放有效
    var boostLevel by remember { mutableIntStateOf(0) }   // 迭代 #60 音量增强（0 关 / +3 / +6 / +9 dB），仅本次播放有效
    val boostFx = remember(player) { VolumeBoostFx() }
    DisposableEffect(player) {
        val bl = object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) { if (boostLevel > 0) boostFx.apply(audioSessionId, boostLevel) }
        }
        player.addListener(bl)
        onDispose { player.removeListener(bl); boostFx.release() }
    }
    fun setSleep(minutes: Int, endOfEpisode: Boolean) {
        sleepMin = minutes; sleepEndFor = if (endOfEpisode) curPath else null
        if (endOfEpisode) cancelNext()   // 停在本集结尾，不倒数连播
        else { nextCancelledFor = ""; player.pauseAtEndOfMediaItems = queue.size > 1 && !autoNext }
        hint = if (minutes > 0 || endOfEpisode) "定时关闭：${PlayerHints.sleepLabel(minutes, endOfEpisode)}暂停" else "已取消定时关闭"
    }
    LaunchedEffect(curPath) {   // 换集后「本集播完后停」失效，恢复原来的连播设置
        if (sleepEndFor != null && sleepEndFor != curPath) { sleepEndFor = null; nextCancelledFor = ""; player.pauseAtEndOfMediaItems = queue.size > 1 && !autoNext }
    }
    LaunchedEffect(sleepMin) {
        if (sleepMin <= 0) return@LaunchedEffect
        delay(sleepMin * 60_000L)
        sleepMin = 0; save(); player.pause(); hint = "定时关闭：已暂停"
    }
    fun playNextNow() { nextIn = null; save(); player.seekToNextMediaItem(); player.play() }
    LaunchedEffect(hint) { if (hint != null) { delay(6_000); hint = null } }
    // 用户 2026-10-23 反馈（清晰度）：每集各查一次飞牛影视的转码档位；离开的那集如果在转码，结束会话并换回原文件
    LaunchedEffect(curPath) {
        if (!isFn) return@LaunchedEffect
        tv.activePath?.takeIf { it != curPath }?.let { old ->
            tv.stop()
            val idx = queue.indexOfFirst { it.path == old }
            if (idx >= 0 && idx != player.currentMediaItemIndex) player.replaceMediaItem(idx, itemFor(queue[idx], urls[old].orEmpty(), subOffset))
        }
        val q = queue.firstOrNull { it.path == curPath } ?: return@LaunchedEffect
        val ep = item.episodes.firstOrNull { it.path == q.path }
        tv.lookup(q.path, q.file, listOf(item.title, NameParser.stem(q.file.name)), ep?.season, ep?.episode)
        // 「记住飞牛清晰度」打开时直接用上次选的档位（同分辨率码率变了取最高的；没有就保持原画）
        if (PlayPrefs.fnQualityRemember && player.currentMediaItem?.mediaId == q.path)
            tv.info(q.path)?.let { i -> FnTvParse.pickTier(i.tiers, PlayPrefs.fnQualityLast) }?.let { switchQuality(it) }
    }
    fun applySubStyle() { playerView?.subtitleView?.let { sv -> applySubLook(sv) } }
    fun setOffset(ms: Long) {
        subOffset = ms; PlayPrefs.setSubOffset(showId, ms)
        val i = player.currentMediaItemIndex; val q = queue.getOrNull(i) ?: return
        if (subMap[q.path].isNullOrEmpty()) { hint = "字幕延迟仅外挂字幕可用"; return }
        val pos = player.currentPosition
        player.replaceMediaItem(i, itemFor(q, srcOf(q), ms, tv.transcoding(q.path))); player.seekTo(i, pos)
    }
    BackHandler { if (extIdx < 0) save(); Nav.pop() }
    LaunchedEffect(lockBtn) { if (lockBtn) { delay(3_000); lockBtn = false } }
    BackHandler(enabled = locked) { lockBtn = true }   // 锁定时返回键只提示解锁按钮
    BackHandler(enabled = panel != null || moreMenu) { panel = null; moreMenu = false }   // a panel closes before the player does

    // 用户 2026-10-23 反馈：手势（对标腾讯视频 / 爱奇艺），识别在 PlayerGestureLayer，动作在这里
    val audio = remember { ctx.getSystemService(android.media.AudioManager::class.java) }
    DisposableEffect(Unit) {
        // brightness is only overridden for this window, and handed back to the system when the player closes
        onDispose { act?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } } }
    }
    LaunchedEffect(flash) { if (flash != null) { delay(700); flash = null } }
    val gestures = remember(player) {
        object : PlayerGestureLayer.Callbacks {
            var seekFrom = 0L; var seekTo = 0L; var dur = 0L
            var levelFrom = 0f; var brightness = false; var maxVol = 1
            var speedBefore = 1f
            override fun onTap() {
                val pv = playerView ?: return
                if (pv.isControllerFullyVisible) pv.hideController() else pv.showController()
            }
            override fun onDoubleTap() {
                if (player.isPlaying) { player.pause(); flash = false } else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play(); flash = true
                }
            }
            override fun onLongPressStart(): Boolean {
                if (!player.isPlaying) return false
                speedBefore = player.playbackParameters.speed
                player.setPlaybackSpeed(LONG_PRESS_SPEED); fast = true
                return true
            }
            override fun onLongPressEnd() { player.setPlaybackSpeed(speedBefore); fast = false }
            override fun onSeekStart(): Boolean {
                dur = player.duration
                if (dur <= 0 || dur == MC.TIME_UNSET || !player.isCurrentMediaItemSeekable) return false
                seekFrom = seekGate.position(); seekTo = seekFrom   // continues from a merged target not applied yet
                onSeekMove(0f, 1)
                return true
            }
            override fun onSeekMove(dxPx: Float, widthPx: Int) {
                seekTo = PlayerGestureMath.seekTarget(seekFrom, dxPx, widthPx, dur)
                seekPreview = PlayerGestureMath.seekLabel(seekTo - seekFrom, seekTo)
                seekFrac = (seekTo.toFloat() / dur).coerceIn(0f, 1f)
            }
            override fun onSeekEnd(commit: Boolean) {
                if (commit && seekTo != seekFrom) seekGate.seekTo(seekTo, "swipe")   // only on release, like 腾讯视频
                seekPreview = null
            }
            override fun onVerticalStart(brightness: Boolean) {
                this.brightness = brightness
                levelFrom = if (brightness) {
                    val cur = act?.window?.attributes?.screenBrightness ?: -1f
                    if (cur >= 0f) cur else runCatching {
                        android.provider.Settings.System.getInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
                    }.getOrDefault(0.5f).coerceIn(0f, 1f)
                } else {
                    maxVol = (audio?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 1).coerceAtLeast(1)
                    (audio?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0).toFloat() / maxVol
                }
                levelHud = (if (brightness) "亮度" else "音量") to levelFrom
            }
            override fun onVerticalMove(upPx: Float, heightPx: Int) {
                val v = PlayerGestureMath.level(levelFrom, upPx, heightPx)
                if (brightness) {
                    act?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = v.coerceAtLeast(0.01f) } }
                } else {
                    val idx = Math.round(v * maxVol)
                    if (audio != null && idx != audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
                        runCatching { audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, idx, 0) }   // system volume, no system slider
                }
                levelHud = (if (brightness) "亮度" else "音量") to v
            }
            override fun onVerticalEnd() { levelHud = null }
        }
    }

    // 1.1.0 画中画：把播放状态、视频宽高比和视频在屏幕上的位置告诉系统（离开 App 时进入小窗，见 VideoPip）
    val inPip = VideoPip.inPip
    fun pushPip() {
        if (extIdx >= 0 || casting) { VideoPip.update(act, null); return }
        val vs = player.videoSize
        val w = (vs.width * (if (vs.pixelWidthHeightRatio > 0f) vs.pixelWidthHeightRatio else 1f)).toInt()
        val (aw, ah) = PipMath.aspect(w, vs.height)
        // sourceRectHint = the letterboxed video area inside the full-screen PlayerView (smoother enter animation)
        val rect = playerView?.let { v ->
            val r = android.graphics.Rect()
            if (!v.getGlobalVisibleRect(r) || r.isEmpty) return@let null
            val va = aw.toFloat() / ah; val ra = r.width().toFloat() / r.height()
            if (ra > va) { val cw = (r.height() * va).toInt(); val l = r.left + (r.width() - cw) / 2; android.graphics.Rect(l, r.top, l + cw, r.bottom) }
            else { val ch = (r.width() / va).toInt(); val t = r.top + (r.height() - ch) / 2; android.graphics.Rect(r.left, t, r.right, t + ch) }
        }
        VideoPip.update(act, VideoPip.Session(w, vs.height, player.isPlaying, player.hasNextMediaItem(), rect))
    }
    DisposableEffect(player) {
        val pipListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = pushPip()
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = pushPip()
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = pushPip()
        }
        // the play/pause and next buttons of the PiP window arrive as a package-local broadcast
        val rcv = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                when (i?.getIntExtra(VideoPip.EXTRA, 0)) {
                    VideoPip.CMD_TOGGLE -> if (player.isPlaying) player.pause() else {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                        if (player.playbackState == Player.STATE_IDLE) player.prepare()
                        player.play()
                    }
                    VideoPip.CMD_NEXT -> if (player.hasNextMediaItem()) { save(); player.seekToNextMediaItem(); player.play() }
                }
            }
        }
        player.addListener(pipListener)
        androidx.core.content.ContextCompat.registerReceiver(ctx, rcv, android.content.IntentFilter(VideoPip.ACTION), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            player.removeListener(pipListener)
            runCatching { ctx.unregisterReceiver(rcv) }
            VideoPip.update(act, null)
        }
    }
    LaunchedEffect(player, casting, extIdx, playerView) { pushPip() }
    // the activity is stopped (screen off, PiP window closed, PiP turned off and the user left): save and pause.
    // While shown as a PiP window the activity is only paused, so playback continues there.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) { save(); player.pause() }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // PiP shows only the video: no controller, no gestures (and the Compose overlays below are hidden too)
    LaunchedEffect(inPip, playerView, gestureLayer) {
        playerView?.let { pv -> pv.useController = !inPip; if (inPip) pv.hideController() }
        gestureLayer?.gesturesOn = !inPip
        if (inPip) panel = null
    }

    if (extIdx >= 0) {
        // 1.1.0: a format the built-in player cannot open at all (rmvb / rm / wmv / asf)
        val q = queue[extIdx]
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(q.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("内置播放器不支持 ${NameParser.ext(q.file.name).uppercase()} 格式。可以交给手机上的其他播放器（如 VLC、MX Player）播放。",
                color = Color(0xCCFFFFFF), fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 10.dp, bottom = 18.dp))
            GradientButton("用其他播放器打开", Modifier.fillMaxWidth()) { openExternal(extIdx) }
            Row(Modifier.padding(top = 8.dp)) {
                val n = extIdx + 1
                if (n < queue.size) TextButton(onClick = {
                    if (needsExternal(queue[n])) extIdx = n else { extIdx = -1; player.seekTo(n, 0L); player.prepare(); player.play() }
                }) { Text("下一集", color = Color.White) }
                TextButton(onClick = { Nav.pop() }) { Text("返回", color = Color(0xCCFFFFFF)) }
            }
        }
        return
    }

    val tracksNow = remember(tracksVer) { player.currentTracks }
    val audioRows = remember(tracksNow) { trackRows(tracksNow, MC.TRACK_TYPE_AUDIO) }
    val textRows = remember(tracksNow) { trackRows(tracksNow, MC.TRACK_TYPE_TEXT) }
    val qualityRowsNow = remember(tracksNow) { qualityRows(tracksNow) }
    val qualityLabel = when {
        isFn && tv.transcoding(curPath) -> tv.tier?.label ?: "转码"
        qualityRowsNow.size <= 1 -> "原画"
        hasVideoOverride(player) -> qualityRowsNow.firstOrNull { it.selected }?.option?.title ?: "清晰度"
        else -> "自动"
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = {
            val pv = PlayerView(it).apply {
                this.player = seekGate.forUi   // 快进 / 快退 / 进度条经过合并（PlayerSeekGate）
                useController = true
                // 用户 2026-10-23 反馈：音轨 / 字幕在顶栏自己的面板里选（去重、显示 mkv 标题），不用 media3 自带的 CC 和齿轮
                setShowSubtitleButton(false)
                setShowNextButton(queue.size > 1); setShowPreviousButton(queue.size > 1)
                setShowFastForwardButton(true); setShowRewindButton(true)
                controllerShowTimeoutMs = 3500
                hideBuiltInSettings(this)
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controls = v == android.view.View.VISIBLE; hideBuiltInSettings(this) })
                keepScreenOn = true
                playerView = this
                subtitleView?.let { sv -> applySubLook(sv) }
            }
            PlayerGestureLayer(it, pv).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                callbacks = gestures
                gestureLayer = this
            }
        }, modifier = Modifier.fillMaxSize())
        AnimatedVisibility(controls && !inPip, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))).padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { save(); Nav.pop() }) { Icon(LI.back(true), null, tint = Color.White) }
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                SpeedButton(player, showId)
                TopTextButton(qualityLabel) { panel = PlayerPanel.QUALITY }
                if (audioRows.size > 1) TopTextButton("音轨") { panel = PlayerPanel.AUDIO }
                if (textRows.isNotEmpty()) TopTextButton("字幕") { panel = PlayerPanel.TEXT }
                IconButton(onClick = { player.pause(); casting = true }) { Icon(Icons.Rounded.Cast, "投屏", tint = Color.White) }
                Box {
                    IconButton(onClick = { moreMenu = true }) { Icon(Icons.Rounded.MoreVert, "更多", tint = Color.White) }
                    DropdownMenu(moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(text = { Text("详细信息") }, onClick = { moreMenu = false; panel = PlayerPanel.INFO })
                        DropdownMenuItem(text = { Text("定时关闭 · " + PlayerHints.sleepLabel(sleepMin, sleepEnd)) }, onClick = { moreMenu = false; sleepDialog = true })
                        DropdownMenuItem(text = { Text("画面比例 · " + PlayerHints.fitLabel(fitMode)) }, onClick = {
                            moreMenu = false; fitMode = PlayerHints.nextFit(fitMode)
                            playerView?.resizeMode = when (fitMode) { 1 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM; 2 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL; else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT }
                            hint = "画面比例：" + PlayerHints.fitLabel(fitMode)
                        })
                        DropdownMenuItem(text = { Text("音量增强 · " + VolumeBoost.label(boostLevel)) }, onClick = {
                            moreMenu = false
                            val nl = VolumeBoost.next(boostLevel)
                            if (boostFx.apply(player.audioSessionId, nl)) {
                                boostLevel = nl
                                hint = if (nl == 0) "音量增强：关" else "音量增强：${VolumeBoost.label(nl)}（声音过大失真就调低）"
                            } else hint = "这台手机不支持音量增强"
                        })
                        DropdownMenuItem(text = { Text("锁定屏幕") }, onClick = {
                            moreMenu = false; locked = true; playerView?.hideController(); hint = "已锁定，轻点屏幕可解锁"
                        })
                        DropdownMenuItem(text = { Text("播放设置") }, onClick = { moreMenu = false; menu = true })
                    }
                }
            }
        }
        hint?.takeIf { !inPip }?.let { h ->
            Text(h, color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 24.dp, end = 24.dp)
                .background(Color(0xB3000000), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        if (showStats && !inPip) Text(statsText, color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart).padding(top = 64.dp, start = 12.dp)
            .background(Color(0x99000000), androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
        if (sleepDialog) AlertDialog(
            onDismissRequest = { sleepDialog = false },
            title = { Text("定时关闭") },
            text = {
                Column {
                    PlayerHints.sleepChoices.forEach { m -> TextButton(onClick = { sleepDialog = false; setSleep(m, false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("$m 分钟后暂停") } }
                    if (isShow) TextButton(onClick = { sleepDialog = false; setSleep(0, true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("本集播完后停止") }
                    if (sleepMin > 0 || sleepEnd) TextButton(onClick = { sleepDialog = false; setSleep(0, false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("关闭定时") }
                }
            },
            confirmButton = { TextButton(onClick = { sleepDialog = false }) { Text("返回") } },
        )
        if (gestureTip && !inPip && error == null) {
            Column(Modifier.align(Alignment.Center).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { gestureTip = false }
                .background(Color(0xB3000000), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("手势小提示", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                PlayerHints.gestureLines.forEach { Text(it, color = Color(0xE6FFFFFF), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                Text("点一下关闭，只显示这一次", color = Color(0x99FFFFFF), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        nextIn?.takeIf { !inPip }?.let { n ->
            Row(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 96.dp)
                .background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("下一集 $n 秒后播放", color = Color.White, fontSize = 14.sp)
                TextButton(onClick = { cancelNext() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消", color = Color(0xCCFFFFFF)) }
                TextButton(onClick = { playNextNow() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("立即播放", color = C.Accent) }
            }
        }
        if (locked && !inPip) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { lockBtn = true } }) {
                if (lockBtn) Row(Modifier.align(Alignment.CenterStart).padding(start = 24.dp).clickable { locked = false; lockBtn = false; hint = "已解锁" }
                    .background(Color(0xB3000000), androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = Color.White)
                    Text("  点此解锁", color = Color.White, fontSize = 14.sp)
                }
            }
        }
        if (!inPip) GestureHud(seekPreview, seekFrac, levelHud, fast, flash)
        error?.takeIf { !inPip }?.let { e ->
            Column(Modifier.align(Alignment.Center).background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(e, color = Color.White, fontSize = 15.sp)
                Row(Modifier.padding(top = 10.dp)) {
                    TextButton(onClick = { error = null; PlayHttp.invalidate(urls[player.currentMediaItem?.mediaId].orEmpty()); player.prepare(); player.play() }) { Text("重试", color = C.Accent) }
                    if (canExternal) TextButton(onClick = { openExternal(player.currentMediaItemIndex) }) { Text("用其他播放器打开", color = C.Accent) }
                    if (player.hasNextMediaItem()) TextButton(onClick = { error = null; player.seekToNextMediaItem(); player.prepare(); player.play() }) { Text("下一集", color = Color.White) }
                }
            }
        }
        if (!inPip) when (panel) {
            PlayerPanel.AUDIO -> SidePanel("音轨", { panel = null }) {
                audioRows.forEach { r -> PanelRow(r.option.title, r.option.detail, r.selected) { selectTrack(player, MC.TRACK_TYPE_AUDIO, r.refs.first()); panel = null } }
            }
            PlayerPanel.TEXT -> SidePanel("字幕", { panel = null }) {
                val off = textRows.none { it.selected }
                PanelRow("关闭", null, off) { subtitlesOff(player); panel = null }
                textRows.forEach { r -> PanelRow(r.option.title, r.option.detail, r.selected) { selectTrack(player, MC.TRACK_TYPE_TEXT, r.refs.first()); panel = null } }
            }
            PlayerPanel.QUALITY -> SidePanel("清晰度", { panel = null }) {
                val cur = queue.getOrNull(player.currentMediaItemIndex)
                if (isFn && cur != null) {
                    // 飞牛：原画 + 飞牛影视服务器转码档位
                    FnTvQualityRows(tv, cur.path, qualityRowsNow.firstOrNull()?.option?.detail?.takeIf { !tv.transcoding(cur.path) }) { t -> panel = null; PlayPrefs.fnQualityLast = t?.key.orEmpty(); switchQuality(t) }
                } else if (qualityRowsNow.size > 1) {
                    val auto = !hasVideoOverride(player)
                    PanelRow("自动", "按网速自动切换", auto) { qualityAuto(player); panel = null }
                    qualityRowsNow.forEach { r -> PanelRow(r.option.title, r.option.detail, !auto && r.selected) { selectTrack(player, MC.TRACK_TYPE_VIDEO, r.refs.first()); panel = null } }
                } else {
                    val r = qualityRowsNow.firstOrNull()
                    PanelRow("原画", r?.option?.detail, true) { panel = null }
                    if (lib.kind == "dav") PanelNote("WebDAV 直接播放原文件，只有原画一种清晰度。")
                }
            }
            PlayerPanel.INFO -> SidePanel("详细信息", { panel = null }) {
                PlayerInfo(player, lib, queue.getOrNull(player.currentMediaItemIndex) ?: queue[start], tv.takeIf { isFn })
            }
            null -> Unit
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
    if (casting) {
        val q = queue.getOrNull(player.currentMediaItemIndex) ?: queue[start]
        val media = CastMedia(q.title, q.file.name, player.currentPosition) { (urls[q.path] ?: fs.playUrl(q.file))?.let { u -> u to fs.headers(u) } }
        CastDialog(media) { casting = false }
    }
}

/** 迭代 #55：字号 / 位置 / 样式一起应用到字幕视图。 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun applySubLook(sv: androidx.media3.ui.SubtitleView) {
    sv.setApplyEmbeddedFontSizes(false); sv.setFractionalTextSize(PlayPrefs.subSize); sv.setBottomPaddingFraction(PlayPrefs.subBottom)
    val W = android.graphics.Color.WHITE; val B = android.graphics.Color.BLACK; val T = android.graphics.Color.TRANSPARENT
    when (PlayPrefs.subStyle) {
        1 -> sv.setStyle(androidx.media3.ui.CaptionStyleCompat(W, T, T, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, B, null))
        2 -> sv.setStyle(androidx.media3.ui.CaptionStyleCompat(W, 0xA0000000.toInt(), T, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE, B, null))
        3 -> sv.setStyle(androidx.media3.ui.CaptionStyleCompat(0xFFFFE066.toInt(), T, T, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, B, null))
        else -> sv.setUserDefaultStyle()
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlayerMenu(player: ExoPlayer, showId: String, offset: Long, offsetEnabled: Boolean, onOffset: (Long) -> Unit, onStyle: () -> Unit, onDismiss: () -> Unit) {
    var intro by remember { mutableIntStateOf(PlayPrefs.intro(showId)) }
    var outro by remember { mutableIntStateOf(PlayPrefs.outro(showId)) }
    var size by remember { mutableFloatStateOf(PlayPrefs.subSize) }
    var bottom by remember { mutableFloatStateOf(PlayPrefs.subBottom) }
    var look by remember { mutableIntStateOf(PlayPrefs.subStyle) }
    val looks = listOf("跟随系统", "白字描边", "黑底白字", "黄字描边")
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
            Stepper("字幕样式", looks[look], { look = (look + 3) % 4; PlayPrefs.subStyle = look; onStyle() }, { look = (look + 1) % 4; PlayPrefs.subStyle = look; onStyle() })
            Text("音轨和字幕在顶栏「音轨」「字幕」里选，选过的语言本剧会记住。", color = C.Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
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
private fun SpeedButton(player: ExoPlayer, showId: String) {
    var open by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    // 倍速按剧记忆：进入播放器时恢复上次为这部剧选的倍速
    LaunchedEffect(showId) {
        val saved = PlayPrefs.speed(showId)
        if (saved > 0f && saved != speed) { speed = saved; player.setPlaybackSpeed(saved) }
    }
    Box {
        TextButton(onClick = { open = true }) { Text(if (speed == 1f) "倍速" else "${fmtSpeed(speed)}×", color = Color.White, fontSize = 14.sp) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { s ->
                DropdownMenuItem(text = { Text("${fmtSpeed(s)}×", fontWeight = if (s == speed) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { open = false; speed = s; player.setPlaybackSpeed(s); PlayPrefs.setSpeed(showId, s) })
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

/** 用户 2026-10-23 反馈：长按临时倍速。 */
private const val LONG_PRESS_SPEED = 2f

/** media3's settings gear only offers speed (we have 倍速) and its own un-deduplicated audio list — hide it. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun hideBuiltInSettings(pv: PlayerView) {
    pv.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)?.visibility = android.view.View.GONE
    pv.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_subtitle)?.visibility = android.view.View.GONE
}

@Composable
private fun TopTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(text, color = Color.White, fontSize = 14.sp) }
}

/** Center feedback for gestures: seek preview「+01:23 / 12:00」, volume / brightness level, 2× while long-pressing, play / pause. */
@Composable
private fun BoxScope.GestureHud(seek: String?, seekFrac: Float, level: Pair<String, Float>?, fast: Boolean, flash: Boolean?) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    if (seek != null) {
        Column(Modifier.align(Alignment.Center).background(Color(0xB3000000), shape).padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(seek, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(progress = { seekFrac }, color = C.Accent, trackColor = Color(0x4DFFFFFF),
                modifier = Modifier.padding(top = 10.dp).width(180.dp).height(3.dp))
        }
    } else if (level != null) {
        Column(Modifier.align(Alignment.Center).background(Color(0xB3000000), shape).padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${level.first} ${(level.second * 100).toInt()}%", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(progress = { level.second }, color = C.Accent, trackColor = Color(0x4DFFFFFF),
                modifier = Modifier.padding(top = 10.dp).width(150.dp).height(3.dp))
        }
    } else if (flash != null) {
        Box(Modifier.align(Alignment.Center).size(72.dp).background(Color(0x99000000), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (flash) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (flash) "播放" else "暂停", tint = Color.White, modifier = Modifier.size(40.dp))
        }
    }
    if (fast) {
        Text("${fmtSpeed(LONG_PRESS_SPEED)}× 快进中", color = Color.White, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp).background(Color(0xB3000000), shape).padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

/**
 * 用户 2026-10-23 反馈：「详细信息」面板。来源只写账号类型、账号名和文件路径，不显示播放地址（可能带密码 / token）。
 * 编码、分辨率等从 media3 当前选中的 Format 读；缓冲每秒刷新。
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ColumnScope.PlayerInfo(player: ExoPlayer, lib: MovieLib, q: QItem, tv: FnTvQuality?) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); tick++ } }
    if (tick < 0) return   // reading tick makes the panel recompose every second
    val vf = player.videoFormat
    val af = player.audioFormat
    val account = if (lib.kind == "dav") com.hark.shiguang.cloud.CloudAccounts.all.firstOrNull { it.id == lib.accountId }?.title
        else NasAccounts.list.firstOrNull { it.id == lib.accountId }?.title
    val path = q.file.path.let { p ->
        // never a full url (a WebDAV url can carry user:password); fnOS 文件管理 paths start with "fm:"
        if (p.startsWith("http", ignoreCase = true)) runCatching { Uri.parse(p).path }.getOrNull().orEmpty() else p.removePrefix("fm:")
    }
    val dash = "—"
    InfoSection("来源")
    InfoRow("账号", listOfNotNull(if (lib.kind == "dav") "WebDAV" else "飞牛", account?.takeIf { it.isNotBlank() }).joinToString(" · "))
    InfoRow("路径", path.ifEmpty { q.file.name })
    TrackNames.sizeName(q.file.size)?.let { InfoRow("文件大小", it) }
    InfoSection("视频")
    InfoRow("容器", TrackNames.containerName(vf?.containerMimeType ?: af?.containerMimeType, q.file.name) ?: dash)
    InfoRow("编码", vf?.let { f -> TrackNames.videoCodecName(f.sampleMimeType)?.let { c -> MediaBadges.dvProfile(f)?.let { "$c · 杜比视界 P$it" } ?: c } } ?: dash)
    InfoRow("分辨率", vf?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}×${it.height}" } ?: dash)
    InfoRow("帧率", vf?.let { TrackNames.frameRateName(it.frameRate) } ?: dash)
    // 用户 2026-10-24 要求继续优化：动态范围（PQ / HLG 为 HDR，其余按 SDR；见 MediaBadges.dynamicRange）
    InfoRow("动态范围", vf?.let { f ->
        val r = MediaBadges.dynamicRange(f.colorInfo?.colorTransfer ?: androidx.media3.common.Format.NO_VALUE)
        when (r) { MediaBadges.HDR10 -> "HDR10（PQ）"; MediaBadges.HLG -> "HLG"; else -> "SDR" }
    } ?: dash)
    val vbr = vf?.let { f -> listOf(f.averageBitrate, f.bitrate, f.peakBitrate).firstOrNull { it > 0 } }
    val dur = player.duration.takeIf { it > 0 && it != MC.TIME_UNSET } ?: (q.file.duration * 1000L)
    InfoRow("码率", vbr?.let { TrackNames.bitrateName(it.toLong()) }
        ?: TrackNames.bitrateName(if (q.file.size > 0 && dur > 0) q.file.size * 8_000 / dur else 0)?.let { "约 $it（整体平均）" } ?: dash)
    InfoSection("音频")
    InfoRow("编码", af?.let { TrackNames.codecName(it.sampleMimeType, it.codecs) ?: it.sampleMimeType } ?: dash)
    InfoRow("声道", af?.channelCount?.takeIf { it > 0 }?.let { n -> when (n) { 1 -> "单声道"; 2 -> "立体声 2.0"; else -> "${TrackNames.channelName(n)} 环绕声" } } ?: dash)
    InfoRow("采样率", af?.let { TrackNames.sampleRateName(it.sampleRate) } ?: dash)
    InfoSection("播放")
    // 用户 2026-10-23 反馈（清晰度）：标明原画还是飞牛影视转码（档位、码率）
    val t = tv?.tier?.takeIf { tv.transcoding(q.path) }
    InfoRow("清晰度", if (t == null) "原画 · 直接播放原文件" else listOf("转码", t.label, t.detail).filter { it.isNotEmpty() }.joinToString(" · ") + "（飞牛影视）")
    InfoRow("缓冲", "已缓冲 ${player.totalBufferedDuration / 1000} 秒" + player.bufferedPercentage.takeIf { it > 0 }?.let { " · 全片 $it%" }.orEmpty())
}
