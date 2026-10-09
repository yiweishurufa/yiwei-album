@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hark.shiguang

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * 1.0.3 #4: background music playback. MediaSessionService = foreground service (type mediaPlayback) with the media
 * notification, lock screen controls and Bluetooth / headset buttons; all of that comes from media3-session.
 * The UI talks to it through [MusicState] (a MediaController), never to the player directly.
 */
@UnstableApi
class MusicService : MediaSessionService() {
    private var session: MediaSession? = null
    private var player: MusicPlayer? = null
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) player?.onBecomingNoisy() }
    }

    override fun onCreate() {
        super.onCreate()
        val p = MusicPlayer(this)
        p.setModes(MusicState.savedRepeat(), MusicState.savedShuffle())
        player = p
        val open = PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("open", "music"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, p).setSessionActivity(open).setCallback(object : MediaSession.Callback {
            // items come from our own MediaController with mediaId + metadata only: the player resolves the stream itself
            override fun onAddMediaItems(s: MediaSession, c: MediaSession.ControllerInfo, items: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
                Futures.immediateFuture(items)
        }).build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).setChannelName(R.string.music_channel).build().apply { setSmallIcon(R.drawable.ic_notify) })
        val f = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(noisy, f)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(noisy) }
        session?.run { player.release(); release() }
        session = null; player = null
        MusicState.serviceGone()
        super.onDestroy()
    }
}

/** UI side of the music player: one MediaController plus Compose state mirrored from it. */
object MusicState {
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var connecting = false
    private val pending = ArrayList<(MediaController) -> Unit>()

    var mediaId by mutableStateOf<String?>(null); private set
    var isPlaying by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    var repeat by mutableIntStateOf(savedRepeat()); private set
    var shuffle by mutableStateOf(savedShuffle()); private set
    var queue by mutableStateOf<List<MediaItem>>(emptyList()); private set
    var index by mutableIntStateOf(0); private set
    var duration by mutableLongStateOf(0L); private set
    /** Bumps on every player event (Compose keys). */
    var tick by mutableIntStateOf(0); private set

    val active: Boolean get() = mediaId != null && queue.isNotEmpty()
    val position: Long get() = controller?.currentPosition ?: 0L

    /** The playing track, looked up in its library. */
    fun current(): Track? = mediaId?.let { trackOf(it) }
    fun trackOf(mediaId: String): Track? = runCatching { Music.lib(mediaId.substringBefore('|')).track(mediaId.substringAfter('|')) }.getOrNull()

    fun savedRepeat(): Int = Store.getStr("music.repeat", "${Player.REPEAT_MODE_ALL}").toIntOrNull() ?: Player.REPEAT_MODE_ALL
    fun savedShuffle(): Boolean = Store.getStr("music.shuffle") == "1"
    fun saveModes(r: Int, s: Boolean) { Store.putStr("music.repeat", "$r"); Store.putStr("music.shuffle", if (s) "1" else "") }

    private fun with(f: (MediaController) -> Unit) {
        controller?.let { if (it.isConnected) { f(it); return } }
        pending.add(f)
        if (connecting) return
        connecting = true
        val ctx = App.ctx
        val fut = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, MusicService::class.java))).buildAsync()
        fut.addListener({
            connecting = false
            val c = runCatching { fut.get() }.getOrNull()
            if (c == null) { pending.clear(); toast("音乐播放服务启动失败"); return@addListener }
            controller = c
            c.addListener(object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) { sync() } })
            sync()
            val l = pending.toList(); pending.clear(); l.forEach { it(c) }
        }, MoreExecutors.directExecutor())
    }

    private fun sync() {
        val c = controller ?: return
        mediaId = c.currentMediaItem?.mediaId
        isPlaying = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)
        buffering = c.playbackState == Player.STATE_BUFFERING
        repeat = c.repeatMode; shuffle = c.shuffleModeEnabled
        queue = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }
        index = c.currentMediaItemIndex
        duration = c.duration.takeIf { it > 0 } ?: (current()?.durationMs ?: 0L)
        tick++
    }

    internal fun serviceGone() { main.post { controller?.release(); controller = null; mediaId = null; queue = emptyList(); isPlaying = false; tick++ } }

    // ------------------------------------------------------------------ commands

    fun play(sourceKey: String, tracks: List<Track>, start: Int, shuffled: Boolean = false) {
        if (tracks.isEmpty()) return
        val items = tracks.map { MusicPlayer.item(sourceKey, it) }
        with { c ->
            if (shuffled != c.shuffleModeEnabled) c.shuffleModeEnabled = shuffled
            val s = if (shuffled && start < 0) (tracks.indices).random() else start.coerceAtLeast(0)
            c.setMediaItems(items, s, 0); c.prepare(); c.play()
        }
    }
    fun playNext(sourceKey: String, t: Track) = with { c -> if (c.mediaItemCount == 0) play(sourceKey, listOf(t), 0) else c.addMediaItem(c.currentMediaItemIndex + 1, MusicPlayer.item(sourceKey, t)) }
    fun enqueue(sourceKey: String, t: Track) = with { c -> if (c.mediaItemCount == 0) play(sourceKey, listOf(t), 0) else c.addMediaItem(MusicPlayer.item(sourceKey, t)) }
    fun toggle() = with { if (it.isPlaying || it.playWhenReady) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
    fun pause() { controller?.takeIf { it.isConnected }?.pause() }
    fun next() = with { it.seekToNextMediaItem() }
    fun prev() = with { if (it.currentPosition > 3000) it.seekTo(0) else it.seekToPreviousMediaItem() }
    fun seek(ms: Long) = with { it.seekTo(ms) }
    fun playAt(i: Int) = with { it.seekTo(i, 0); it.play() }
    fun remove(i: Int) = with { it.removeMediaItem(i) }
    fun cycleRepeat() = with { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF; else -> Player.REPEAT_MODE_ALL } }
    fun toggleShuffle() = with { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun shuffleTo(b: Boolean) = with { it.shuffleModeEnabled = b }
    fun repeatTo(m: Int) = with { it.repeatMode = m }
    fun stop() = with { it.stop(); it.clearMediaItems() }

    // ------------------------------------------------------------------ from the player

    fun durationLearnt(mediaId: String, ms: Long) { main.post { runCatching { Music.lib(mediaId.substringBefore('|')).setDuration(mediaId.substringAfter('|'), ms) } } }
    fun toast(msg: String) { main.post { Toast.makeText(App.ctx, msg, Toast.LENGTH_SHORT).show() } }
}
