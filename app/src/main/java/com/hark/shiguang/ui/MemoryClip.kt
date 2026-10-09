package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.Hidden
import com.hark.shiguang.MemoryVideo
import com.hark.shiguang.Nav
import com.hark.shiguang.Route
import com.hark.shiguang.data.Photo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/** 1.0.1 #13 entry point used by 人物 / 地点 / 相册 / 那年今天. */
fun openMemoryClip(title: String, subtitle: String, photos: List<Photo>) =
    Nav.push(Route.X(ExtScreen.MemoryClip(title, subtitle, photos)))

@Composable
fun MemoryClipScreen(s: ExtScreen.MemoryClip) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked = remember(s) { MemoryVideo.pick(Hidden.visible(s.photos)) }
    var title by remember { mutableStateOf(s.title) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val running = job?.isActive == true
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    fun start() {
        error = null; file = null; progress = 0f
        job = scope.launch {
            try { file = MemoryVideo.render(ctx, title.ifBlank { "回忆" }, s.subtitle, picked) { p -> progress = p } }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { error = e.message ?: "生成失败" }
            finally { job = null }
        }
    }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp, bottom = 40.dp)) {
            // cover
            Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(C.Card)).background(C.Surface)) {
                picked.firstOrNull()?.let { NetImage(it.thumbM, Modifier.fillMaxSize()) }
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xCC000000))))
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(title.ifBlank { "回忆" }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(listOf(s.subtitle, "${picked.size} 张 · 约 ${MemoryVideo.durationSec(picked.size).toInt()} 秒").filter { it.isNotEmpty() }.joinToString(" · "),
                        color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (!running && file == null) InputRow("标题", title, "片头显示的文字") { title = it.take(24) }
            Text(
                if (picked.isEmpty()) "这里没有可用的照片（视频不会放进短片）。"
                else "竖屏 720×1280，每张约 3 秒，带片头和背景音乐。照片多于 ${MemoryVideo.MAX_PHOTOS} 张时按时间均匀挑选。生成在手机上完成，不上传任何照片。",
                style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            when {
                running -> {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).fillMaxWidth(), color = C.Accent, trackColor = C.Surface2)
                    Text(if (progress < 0.3f) "正在读取照片… ${(progress * 100).toInt()}%" else "正在生成视频… ${(progress * 100).toInt()}%",
                        color = C.Sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
                    Row(Modifier.padding(horizontal = 12.dp)) { TextButton(onClick = { job?.cancel(); job = null }) { Text("取消", color = C.Sub) } }
                }
                file != null -> {
                    val f = file!!
                    Text("已生成 · ${"%.1f".format(f.length() / 1048576.0)} MB", color = C.Text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GradientButton("分享到微信等", Modifier.weight(1f)) { runCatching { MemoryVideo.share(ctx, f) }.onFailure { toast(ctx, "没有可分享的应用") } }
                    }
                    Card {
                        NavRow(LI.play(), "播放", "用系统播放器预览") { runCatching { MemoryVideo.play(ctx, f) }.onFailure { toast(ctx, "没有可用的播放器") } }
                        Div()
                        NavRow(LI.photos(), "保存到相册", "手机相册 / Movies/一维相册") {
                            scope.launch { toast(ctx, if (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MemoryVideo.saveToGallery(ctx, f) }) "已保存到相册" else "保存失败") }
                        }
                        Div()
                        NavRow(LI.refresh(), "重新生成", "改标题后再生成一次") { file = null }
                    }
                }
                else -> {
                    error?.let { Text(it, color = C.Danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
                    Row(Modifier.padding(16.dp)) {
                        GradientButton("生成回忆短片", Modifier.fillMaxWidth()) { if (picked.isNotEmpty()) start() else toast(ctx, "没有可用的照片") }
                    }
                }
            }
        }
        ExtTop("回忆短片", s.title)
    }
}
