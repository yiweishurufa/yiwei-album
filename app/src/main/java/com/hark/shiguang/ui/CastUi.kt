package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

    AppAlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface,
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
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.m)).clickable(enabled = media != null) {
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
                            }.padding(vertical = 10.dp, horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                // 1.7.6：设备行 = 强调色淡底圆片里的电视图标 + 名字（面板本身已是玻璃，行内不再叠玻璃）
                                Box(Modifier.size(40.dp).clip(CircleShape).background(C.Accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                                    Icon(KI.tv(), null, tint = C.Accent, modifier = Modifier.size(22.dp))
                                }
                                Text(d.name, color = C.Text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(KI.chevron(), null, tint = C.Faint, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                } else {
                    Text(CastState.title, color = C.Text, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(dev.name, color = C.Sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    val d = CastState.dur
                    // 1.7.6：进度条换成和播放器一致的 ScrubBar（按住加粗、点哪跳哪、读屏可调）
                    ScrubBar(if (d > 0) (CastState.pos.toFloat() / d).coerceIn(0f, 1f) else 0f, enabled = d > 0,
                        onChange = { if (d > 0) CastState.pos = (it * d).toLong() },
                        onCommit = { f -> if (d > 0) { val p = (f * d).toLong(); CastState.pos = p; act { Dlna.seek(it, p) } } },
                        active = C.Accent, track = C.Surface2, label = "电视播放进度", valueText = "${fmt(CastState.pos)} / ${if (d > 0) fmt(d) else "--:--"}",
                        modifier = Modifier.padding(top = 12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text(fmt(CastState.pos), color = C.Sub, style = Type.badge.copy(fontSize = 12.sp, lineHeight = 16.sp))
                        Spacer(Modifier.weight(1f))
                        Text(if (d > 0) fmt(d) else "--:--", color = C.Sub, style = Type.badge.copy(fontSize = 12.sp, lineHeight = 16.sp))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        CastKey(KI.rewind(), "后退 30 秒") { val p = (CastState.pos - 30_000).coerceAtLeast(0); CastState.pos = p; act { Dlna.seek(it, p) } }
                        // 主键：实心强调色圆钮（一屏唯一的主操作），按下液体形变
                        val ix = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        Box(Modifier.size(64.dp).glassPress(ix, 1.4f).clip(CircleShape).background(C.Accent)
                            .clickable(ix, null, role = Role.Button) { val pl = CastState.playing; CastState.playing = !pl; act { if (pl) Dlna.pause(it) else Dlna.resume(it) } }
                            .semantics { contentDescription = if (CastState.playing) "暂停" else "播放" }, contentAlignment = Alignment.Center) {
                            PlayPauseIcon(CastState.playing, C.OnAccent, 28.dp)
                        }
                        CastKey(KI.fastFwd(), "前进 30 秒") { val p = CastState.pos + 30_000; CastState.pos = p; act { Dlna.seek(it, p) } }
                    }
                }
                CastState.error?.let { Text(it, color = C.Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (dev != null) TextButton({ act { Dlna.stop(it) }; CastState.device = null; CastProxy.stopCast(); onDismiss() }) { Text("停止投屏", color = C.Danger) }
            else TextButton({ scanKey++ }) { Text("重新查找", color = C.Accent) }
        },
        dismissButton = { TextButton(onDismiss) { Text(if (dev != null) "收起" else "取消", color = C.Sub) } })
}

/** 投屏遥控的次要键：48dp 触控区里一个 44dp 的凹槽色圆片（对话框本身是玻璃，不再叠玻璃）。 */
@Composable
private fun CastKey(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    val ix = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(Modifier.size(48.dp).glassPress(ix, 1.4f).clickable(ix, null, role = Role.Button, onClick = onClick).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(C.Surface2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = C.Text, modifier = Modifier.size(22.dp))
        }
    }
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
