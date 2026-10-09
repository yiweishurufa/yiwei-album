package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What to cast: resolved lazily (off the main thread) because play urls may need a network call. */
class CastMedia(val title: String, val name: String, val startMs: Long, val resolve: () -> Pair<String, Map<String, String>>?)

/** 1.0.9 DLNA: pick a TV, then a simple remote (play/pause, ±30 s, seek bar, stop). */
@Composable
fun CastDialog(media: CastMedia?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DlnaDevice>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var scanKey by remember { mutableIntStateOf(0) }
    val dev = CastState.device
    LaunchedEffect(scanKey, dev) { if (dev == null) { devices = null; devices = runCatching { Dlna.discover(ctx) }.getOrDefault(emptyList()) } }
    // remote: poll the TV position
    LaunchedEffect(dev) {
        while (dev != null) {
            runCatching { withContext(Dispatchers.IO) { Dlna.position(dev) } }.onSuccess { (p, d) -> CastState.pos = p; if (d > 0) CastState.dur = d }
            delay(1500)
        }
    }
    fun act(f: (DlnaDevice) -> Unit) { val d = CastState.device ?: return; scope.launch { runCatching { withContext(Dispatchers.IO) { f(d) } }.onFailure { CastState.error = it.message } } }

    AlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface,
        title = { Text(if (dev == null) "投屏到电视" else "正在投屏", color = C.Text) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (dev == null) {
                    val list = devices
                    when {
                        busy -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), color = C.Accent, strokeWidth = 2.dp); Text("  正在连接…", color = C.Sub) }
                        list == null -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), color = C.Accent, strokeWidth = 2.dp); Text("  正在查找同一 Wi-Fi 下的电视…", color = C.Sub) }
                        list.isEmpty() -> Text("没找到可投屏的设备。确认电视开着、和手机连在同一个 Wi-Fi，并已打开 DLNA / 投屏接收。", color = C.Sub, fontSize = 14.sp)
                        else -> list.forEach { d ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = media != null) {
                                val m = media ?: return@clickable
                                busy = true; CastState.error = null
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            val (url, h) = m.resolve() ?: throw Exception("拿不到播放地址")
                                            val lan = CastProxy.serve(url, h, m.name)
                                            Dlna.play(d, lan, m.title, Dlna.mimeOf(m.name))
                                            if (m.startMs > 10_000) { delay(2500); runCatching { Dlna.seek(d, m.startMs) } }
                                        }
                                    }.onSuccess { CastState.device = d; CastState.title = m.title; CastState.playing = true; CastState.dur = 0; CastState.pos = m.startMs }
                                        .onFailure { CastState.error = it.message ?: "投屏失败" }
                                    busy = false
                                }
                            }.padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Tv, null, tint = C.Text, modifier = Modifier.size(22.dp))
                                Text(d.name, color = C.Text, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else {
                    Text("${dev.name} · ${CastState.title}", color = C.Sub, fontSize = 13.sp, maxLines = 2)
                    val d = CastState.dur
                    Slider(value = if (d > 0) (CastState.pos.toFloat() / d).coerceIn(0f, 1f) else 0f, onValueChange = { if (d > 0) CastState.pos = (it * d).toLong() },
                        onValueChangeFinished = { val p = CastState.pos; act { Dlna.seek(it, p) } }, enabled = d > 0,
                        colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent), modifier = Modifier.padding(top = 8.dp))
                    Text("${fmt(CastState.pos)} / ${if (d > 0) fmt(d) else "--:--"}", color = C.Sub, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = { val p = (CastState.pos - 30_000).coerceAtLeast(0); CastState.pos = p; act { Dlna.seek(it, p) } }) { Icon(Icons.Rounded.Replay30, null, tint = C.Text) }
                        IconButton(onClick = { val pl = CastState.playing; CastState.playing = !pl; act { if (pl) Dlna.pause(it) else Dlna.resume(it) } }) {
                            Icon(if (CastState.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = C.Accent, modifier = Modifier.size(34.dp))
                        }
                        IconButton(onClick = { val p = CastState.pos + 30_000; CastState.pos = p; act { Dlna.seek(it, p) } }) { Icon(Icons.Rounded.Forward30, null, tint = C.Text) }
                    }
                }
                CastState.error?.let { Text(it, color = C.Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (dev != null) TextButton({ act { Dlna.stop(it) }; CastState.device = null; CastProxy.stop(); onDismiss() }) { Text("停止投屏", color = C.Danger) }
            else TextButton({ scanKey++ }) { Text("重新查找", color = C.Accent) }
        },
        dismissButton = { TextButton(onDismiss) { Text(if (dev != null) "收起" else "取消", color = C.Sub) } })
}

private fun fmt(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

/** Small badge chips (4K / 杜比视界 / HDR10 / TrueHD …). */
@Composable
fun BadgeRow(badges: List<String>, modifier: Modifier = Modifier) {
    if (badges.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        badges.take(5).forEach { b ->
            val strong = b.startsWith("杜比") || b.startsWith("HDR") || b == "HLG" || b == "4K"
            Text(b, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (strong) C.Accent else C.Sub, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(if (strong) C.Accent.copy(alpha = 0.14f) else C.Surface2).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}
