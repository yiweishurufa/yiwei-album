package com.hark.shiguang

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 1.0.3 #4: the music player behind [MusicService]'s MediaSession.
 *
 * One media3 [Player] (a [SimpleBasePlayer]) that owns the play queue, repeat / shuffle and position, and hands the
 * CURRENT track to one of two engines:
 *  - ExoPlayer (own instance, never shared with the 影视 player) for mp3 / flac / wav / m4a(aac, alac) / ogg / opus,
 *    with the Jellyfin FFmpeg audio decoders preferred (EXTENSION_RENDERER_MODE_PREFER);
 *  - 1.1.0: libVLC removed. ape / wma / dsf / dff / wv / aiff (no media3 1.3.1 extractor) are skipped with a note;
 *    the song menu offers 「用其他播放器打开」 ([ExternalPlayer]).
 * Shuffle reorders the queue (current track first) and turning it off restores the original order, so "next" in the
 * notification, on the lock screen and on Bluetooth headsets always matches what the app shows.
 *
 * mediaId of every item = "<sourceKey>|<track id>" ([Music.lib] resolves it). The stream url is looked up when a track
 * starts (飞牛 download tokens and 123 云盘 signed CDN urls expire), on an IO thread.
 */
@UnstableApi
class MusicPlayer(private val ctx: Context) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ---- queue state
    private var queue = ArrayList<MediaItem>()
    private var original: List<MediaItem>? = null   // order before shuffle
    private var index = 0
    private var pwr = false
    private var state = Player.STATE_IDLE
    private var repeat = Player.REPEAT_MODE_ALL
    private var shuffle = false
    private var error: PlaybackException? = null
    private var gen = 0                       // bumps on every track load; stale async results are dropped
    private var engine = 0                    // 0 none, 1 exo
    private var pendingSeek = 0L
    private var retried = false
    private val uids = java.util.IdentityHashMap<MediaItem, Any>()
    /** Human readable note for the UI (e.g. "这首用兼容模式播放"/"这个格式放不了"). */
    var note: String = ""; private set

    // ---- engines
    private val hdr = ConcurrentHashMap<String, Map<String, String>>()
    // H20: kept as a Lazy so release() can skip building a player (with the FFmpeg renderers) that never played
    private val exoLazy = lazy {
        val up = OkHttpDataSource.Factory(PlayHttp.client)
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(ctx, up)) { spec ->
            val h = hdr[spec.uri.toString()]; if (h.isNullOrEmpty()) spec else spec.withAdditionalHeaders(h)
        }
        val rf = ffmpegRenderersFactory(ctx)
        ExoPlayer.Builder(ctx, rf).setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK).build().apply { addListener(exoListener) }
    }
    private val exo: ExoPlayer by exoLazy

    // ------------------------------------------------------------------ state for media3

    override fun getState(): State {
        val items = queue.map { mi ->
            val d = durationOf(mi)
            MediaItemData.Builder(uids.getOrPut(mi) { Any() }).setMediaItem(mi).setMediaMetadata(mi.mediaMetadata)
                .setDurationUs(if (d > 0) d * 1000 else C.TIME_UNSET).setIsSeekable(true).build()
        }
        val cmds = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_RELEASE, Player.COMMAND_GET_AUDIO_ATTRIBUTES,
        ).build()
        val b = State.Builder().setAvailableCommands(cmds).setPlayWhenReady(pwr, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (queue.isEmpty()) Player.STATE_IDLE else state).setRepeatMode(repeat).setShuffleModeEnabled(shuffle)
            .setPlayerError(error).setPlaylist(items)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build())
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
        if (items.isNotEmpty()) b.setCurrentMediaItemIndex(index.coerceIn(0, items.size - 1)).setContentPositionMs { position() }
            .setContentBufferedPositionMs { if (engine == 1) exo.bufferedPosition else position() }
        return b.build()
    }

    private fun durationOf(mi: MediaItem): Long {
        if (queue.getOrNull(index) === mi) {
            if (engine == 1) exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { return it }
        }
        return mi.mediaMetadata.extras?.getLong("dur") ?: 0L
    }

    private fun position(): Long = when (engine) { 1 -> exo.currentPosition; else -> pendingSeek }

    private fun idOf(mi: MediaItem) = mi.mediaId

    // ------------------------------------------------------------------ commands

    override fun handleSetMediaItems(items: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val start = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        original = null; uids.clear()
        queue = ArrayList(items); index = start
        if (shuffle && queue.size > 1) shuffleKeepingCurrent()
        load(index, if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(at: Int, items: MutableList<MediaItem>): ListenableFuture<*> {
        val i = at.coerceIn(0, queue.size)
        queue.addAll(i, items); original = original?.let { it + items }
        if (i <= index && queue.size > items.size) index += items.size
        if (queue.size == items.size) load(0, 0)
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(from: Int, to: Int): ListenableFuture<*> {
        val removed = queue.subList(from, to).toList()
        val wasCurrent = index in from until to
        queue.subList(from, to).clear(); original = original?.filterNot { o -> removed.any { it === o } }
        when {
            queue.isEmpty() -> { stopEngines(); index = 0; state = Player.STATE_IDLE; pwr = false }
            wasCurrent -> { index = from.coerceAtMost(queue.size - 1); load(index, 0) }
            index >= to -> index -= (to - from)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(from: Int, to: Int, newIndex: Int): ListenableFuture<*> {
        val cur = queue.getOrNull(index)
        val moving = queue.subList(from, to).toList(); queue.subList(from, to).clear()
        queue.addAll(newIndex.coerceIn(0, queue.size), moving)
        index = queue.indexOfFirst { it === cur }.coerceAtLeast(0)
        return Futures.immediateVoidFuture()
    }

    override fun handleReplaceMediaItems(from: Int, to: Int, items: MutableList<MediaItem>): ListenableFuture<*> {
        handleRemoveMediaItems(from, to); return handleAddMediaItems(from, items)
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        pwr = playWhenReady
        if (pwr && state == Player.STATE_ENDED) { load(if (index >= queue.size - 1) 0 else index, 0); return Futures.immediateVoidFuture() }
        when (engine) {
            1 -> exo.playWhenReady = pwr
            else -> if (pwr && queue.isNotEmpty() && state == Player.STATE_IDLE) load(index, pendingSeek)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        error = null
        if (queue.isNotEmpty() && (engine == 0 || state == Player.STATE_IDLE)) load(index, pendingSeek)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        pendingSeek = position(); stopEngines(); state = Player.STATE_IDLE; return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        stopEngines(); if (exoLazy.isInitialized()) runCatching { exo.release() }
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> { repeat = repeatMode; MusicState.saveModes(repeat, shuffle); return Futures.immediateVoidFuture() }

    override fun handleSetShuffleModeEnabled(enabled: Boolean): ListenableFuture<*> {
        if (enabled == shuffle) return Futures.immediateVoidFuture()
        shuffle = enabled
        if (enabled) shuffleKeepingCurrent() else original?.let { o ->
            val cur = queue.getOrNull(index); queue = ArrayList(o); original = null
            index = queue.indexOfFirst { it === cur }.coerceAtLeast(0)
        }
        MusicState.saveModes(repeat, shuffle)
        return Futures.immediateVoidFuture()
    }

    private fun shuffleKeepingCurrent() {
        val cur = queue.getOrNull(index) ?: return
        original = original ?: queue.toList()
        val rest = queue.filterIndexed { i, _ -> i != index }.shuffled()
        queue = ArrayList(listOf(cur) + rest); index = 0
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (queue.isEmpty()) return Futures.immediateVoidFuture()
        val target = if (mediaItemIndex == C.INDEX_UNSET) index else mediaItemIndex
        val pos = if (positionMs == C.TIME_UNSET) 0 else positionMs
        if (target != index || engine == 0 || state == Player.STATE_ENDED) load(target.coerceIn(0, queue.size - 1), pos)
        else if (engine == 1) exo.seekTo(pos)
        return Futures.immediateVoidFuture()
    }

    /** Initial modes (restored from the last session). */
    fun setModes(r: Int, s: Boolean) { repeat = r; shuffle = s; invalidateState() }

    // ------------------------------------------------------------------ loading

    private fun load(i: Int, startMs: Long) {
        if (queue.isEmpty()) return
        stopEngines()
        index = i.coerceIn(0, queue.size - 1); pendingSeek = startMs; error = null; retried = false; note = ""
        state = Player.STATE_BUFFERING
        invalidateState()
        val mi = queue[index]; val my = ++gen
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { resolve(mi) }.getOrNull() }
            if (my != gen) return@launch
            if (r == null) { fail("读不到这首歌的播放地址"); return@launch }
            val (url, h, ext) = r
            if (ext in Music.UNSUPPORTED) fail("内置播放器不支持 ${ext.uppercase()}，可在歌曲菜单里「用其他播放器打开」") else startExo(url, h, startMs)
        }
    }

    /** (url, headers, ext) for the queue item; null when the track or source is gone. */
    private suspend fun resolve(mi: MediaItem): Triple<String, Map<String, String>, String>? {
        val key = idOf(mi).substringBefore('|'); val id = idOf(mi).substringAfter('|')
        val lib = Music.lib(key)
        lib.awaitReady()   // H5: cold start from the notification — wait for the library instead of reading an empty one
        val t = withContext(Dispatchers.Main) { lib.track(id) } ?: return null
        val fs = lib.fs
        val raw = fs.playUrl(MediaFile(t.path, t.name, t.size)) ?: return null
        val h = fs.headers(raw)
        val (url, fh) = PlayHttp.resolve(raw, h, followRedirect = lib.kind == "dav")
        return Triple(url, fh, t.ext)
    }

    private fun startExo(url: String, h: Map<String, String>, startMs: Long) {
        hdr[url] = h
        engine = 1
        exo.setMediaItem(MediaItem.Builder().setUri(url).setMediaId(idOf(queue[index])).build(), startMs)
        exo.prepare(); exo.playWhenReady = pwr
        invalidateState()
    }

    private val exoListener = object : Player.Listener {
        override fun onPlaybackStateChanged(s: Int) {
            if (engine != 1) return
            when (s) {
                Player.STATE_ENDED -> ended()
                Player.STATE_READY -> { state = Player.STATE_READY; exo.duration.takeIf { it > 0 && it != C.TIME_UNSET }?.let { MusicState.durationLearnt(idOf(queue[index]), it) }; invalidateState() }
                Player.STATE_BUFFERING -> { state = Player.STATE_BUFFERING; invalidateState() }
                else -> {}
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (engine == 1) invalidateState() }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // audio focus loss / headphones unplugged pause ExoPlayer itself: mirror it
            if (engine == 1 && playWhenReady != pwr && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) { pwr = playWhenReady; invalidateState() }
        }
        override fun onPlayerError(e: PlaybackException) {
            if (engine != 1) return
            val pos = exo.currentPosition
            val unsupported = e.errorCode in setOf(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_DECODING_FAILED)
            Diag.log("MUSIC", "exo error ${e.errorCodeName}: ${e.message}")
            val mi = queue.getOrNull(index) ?: return
            if (!retried) {
                retried = true
                val my = gen
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        // an expired signed CDN url / download token: look the url up again
                        PlayHttp.resolved.clear(); runCatching { resolve(mi) }.getOrNull()
                    }
                    if (my != gen) return@launch
                    if (r == null) { fail("读不到这首歌的播放地址"); return@launch }
                    exo.stop()
                    if (unsupported) fail("内置播放器放不了这个文件（${r.third.uppercase()}），可在歌曲菜单里「用其他播放器打开」") else startExo(r.first, r.second, pos)
                }
                return
            }
            fail(if (unsupported) "这个格式放不了" else "网络中断，播放失败")
        }
    }

    private fun ended() {
        if (repeat == Player.REPEAT_MODE_ONE) { load(index, 0); return }
        if (index < queue.size - 1) { load(index + 1, 0); return }
        if (repeat == Player.REPEAT_MODE_ALL && queue.isNotEmpty()) { load(0, 0); return }
        stopEngines(); state = Player.STATE_ENDED; pwr = false; pendingSeek = 0; invalidateState()
    }

    /** A track that can't be played: note it, skip to the next one after a moment (never stop the whole queue). */
    private fun fail(msg: String) {
        note = msg
        MusicState.toast(msg)
        val my = gen
        stopEngines()
        state = Player.STATE_BUFFERING; invalidateState()
        main.postDelayed({
            if (my != gen) return@postDelayed
            val last = index >= queue.size - 1
            if (queue.size > 1 && (!last || repeat == Player.REPEAT_MODE_ALL)) load(if (last) 0 else index + 1, 0)
            else { state = Player.STATE_IDLE; pwr = false; error = PlaybackException(msg, null, PlaybackException.ERROR_CODE_UNSPECIFIED); invalidateState() }
        }, 1500)
    }

    private fun stopEngines() {
        if (engine == 1) runCatching { exo.stop(); exo.clearMediaItems() }
        engine = 0
    }

    /** Headphones unplugged / Bluetooth gone: ExoPlayer pauses itself (setHandleAudioBecomingNoisy); kept for MusicService. */
    fun onBecomingNoisy() {}

    companion object {
        /** MediaItem for a track (metadata shows in the notification / lock screen). */
        fun item(sourceKey: String, t: Track): MediaItem {
            val md = MediaMetadata.Builder().setTitle(t.title.ifBlank { NameParser.stem(t.name) }).setArtist(t.displayArtist).setAlbumTitle(t.album.ifBlank { null })
                .setAlbumArtist(t.albumArtist.ifBlank { null }).setTrackNumber(t.trackNo.takeIf { it > 0 }).setIsPlayable(true).setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(android.os.Bundle().apply { putLong("dur", t.durationMs) })
            t.artPath?.let { md.setArtworkUri(Uri.fromFile(java.io.File(it))) }
            return MediaItem.Builder().setMediaId("$sourceKey|${t.id}").setMediaMetadata(md.build()).build()
        }
    }
}
