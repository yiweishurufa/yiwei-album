package com.hark.shiguang.ui

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
import com.hark.shiguang.CastProxy
import com.hark.shiguang.Diag
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * 1.0.10 fallback player (libVLC 3.x) for containers media3 can't open: .rmvb / .rm / .wmv / .asf, and any file where
 * ExoPlayer reports an unsupported container/codec (the user taps 「用兼容模式播放」). media3 stays the main player.
 * Auth: VLC can't send our headers, so non-empty [headers] go through [CastProxy.serveLocal] on 127.0.0.1, which forwards
 * Range requests with them (WebDAV Basic, fnOS accesstoken/authx/cookie). UNVERIFIED on a device.
 */
object VlcSupport {
    val EXT = setOf("rmvb", "rm", "wmv", "asf")
}

@Composable
fun VlcPlayer(
    url: String,
    headers: Map<String, String>,
    name: String,
    title: String,
    startMs: Long,
    onProgress: (pos: Long, dur: Long) -> Unit,
    onEnded: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var error by remember(url) { mutableStateOf<String?>(null) }
    var playing by remember(url) { mutableStateOf(false) }
    var pos by remember(url) { mutableLongStateOf(startMs) }
    var len by remember(url) { mutableLongStateOf(0L) }
    var controls by remember(url) { mutableStateOf(true) }
    var dragging by remember(url) { mutableStateOf<Float?>(null) }

    val vlc = remember { LibVLC(ctx.applicationContext, arrayListOf("--network-caching=2000", "--http-reconnect", "--no-stats")) }
    val mp = remember(vlc) { MediaPlayer(vlc) }
    var layout by remember { mutableStateOf<VLCVideoLayout?>(null) }

    DisposableEffect(vlc) {
        onDispose { runCatching { mp.stop(); mp.detachViews(); mp.release(); vlc.release() } }
    }

    // (re)load when the url changes (next episode)
    DisposableEffect(url, layout) {
        val l = layout
        if (l == null) return@DisposableEffect onDispose { }
        runCatching {
            val src = if (headers.isEmpty()) url else CastProxy.serveLocal(url, headers, name)
            val m = Media(vlc, Uri.parse(src))
            m.setHWDecoderEnabled(true, false)
            if (startMs > 0) m.addOption(":start-time=${startMs / 1000}")
            mp.media = m
            m.release()
            mp.setEventListener { e ->
                when (e.type) {
                    MediaPlayer.Event.Playing -> { playing = true; error = null }
                    MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                    MediaPlayer.Event.TimeChanged -> if (dragging == null) pos = e.timeChanged
                    MediaPlayer.Event.LengthChanged -> len = e.lengthChanged
                    MediaPlayer.Event.EndReached -> { playing = false; onProgress(len, len); onEnded() }
                    MediaPlayer.Event.EncounteredError -> { playing = false; error = "兼容模式也放不了这个文件" }
                }
            }
            mp.play()
        }.onFailure { Diag.log("VLC", "open failed: ${it.message}"); error = "兼容播放器启动失败：${it.message}" }
        onDispose { onProgress(runCatching { mp.time }.getOrDefault(pos), runCatching { mp.length }.getOrDefault(len)); runCatching { mp.setEventListener(null); mp.stop() } }
    }
    LaunchedEffect(url) { var n = 0; while (true) { delay(1_000); if (playing && ++n % 5 == 0 && len > 0) onProgress(pos, len) } }
    LaunchedEffect(controls, playing) { if (controls && playing) { delay(3_500); controls = false } }

    Box(Modifier.fillMaxSize().background(Color.Black)
        .clickable(remember { MutableInteractionSource() }, null) { controls = !controls }) {
        AndroidView(factory = {
            VLCVideoLayout(it).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                keepScreenOn = true
                mp.attachViews(this, null, true, false)
                layout = this
            }
        }, modifier = Modifier.fillMaxSize())

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))).padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(LI.back(true), null, tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("兼容模式（VLC）", color = Color(0xB3FFFFFF), fontSize = 11.sp)
                }
            }
        }
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (mp.isPlaying) mp.pause() else mp.play() }) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White)
                }
                Text(fmtMs(dragging?.let { (it * len).toLong() } ?: pos), color = Color.White, fontSize = 12.sp)
                Slider(
                    value = dragging ?: if (len > 0) (pos.toFloat() / len).coerceIn(0f, 1f) else 0f,
                    onValueChange = { dragging = it; controls = true },
                    onValueChangeFinished = { dragging?.let { f -> if (len > 0) { val t = (f * len).toLong(); mp.setTime(t); pos = t } }; dragging = null },
                    enabled = len > 0,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = C.Accent, inactiveTrackColor = Color(0x55FFFFFF)),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(fmtMs(len), color = Color.White, fontSize = 12.sp)
            }
        }
        error?.let { e ->
            Column(Modifier.align(Alignment.Center).background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(e, color = Color.White, fontSize = 15.sp)
                Row(Modifier.padding(top = 10.dp)) {
                    TextButton(onClick = { error = null; mp.stop(); mp.play() }) { Text("重试", color = C.Accent) }
                    TextButton(onClick = onBack) { Text("返回", color = Color.White) }
                }
            }
        }
    }
}

private fun fmtMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
