package com.hark.shiguang.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.*
import kotlinx.coroutines.launch

/** 1.0.9: dialogs that can appear over any screen: crash report from the last run, update offer. Hosted by MainActivity. */
@Composable
fun AppOverlays() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var crash by remember { mutableStateOf(CrashLog.pending(ctx)) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(2500); AppUpdate.autoCheck() }
    crash?.let { log ->
        AlertDialog(onDismissRequest = {}, containerColor = C.Surface,
            title = { Text("上次异常退出", color = C.Text) },
            text = { Text("上次异常退出，要把日志发给开发者吗？日志只含版本、机型和出错位置，不含账号、地址和照片。", color = C.Sub, fontSize = 14.sp) },
            confirmButton = { TextButton({ crash = null; CrashLog.send(ctx, log) }) { Text("发送日志", color = C.Accent) } },
            dismissButton = { TextButton({ crash = null; CrashLog.clear(ctx) }) { Text("不用了", color = C.Sub) } })
    }
    if (crash == null) AppUpdate.offer?.let { r ->
        val p = AppUpdate.progress
        AlertDialog(onDismissRequest = { if (p < 0f) AppUpdate.offer = null }, containerColor = C.Surface,
            title = { Text("发现新版本 ${r.version}", color = C.Text) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("当前 ${BuildInfo.label}" + if (r.apk.size > 0) " · 安装包 ${"%.1f".format(r.apk.size / 1048576.0)} MB" else "", color = C.Sub, fontSize = 13.sp)
                    if (r.notes.isNotBlank()) Text(r.notes.trim(), color = C.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                    Text(AppUpdate.FALLBACK_TEXT, color = C.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                    if (p >= 0f) {
                        LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, color = C.Accent, trackColor = C.Surface2, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
                        Text("正在下载 ${(p * 100).toInt()}%", color = C.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            },
            confirmButton = { TextButton(enabled = p < 0f, onClick = { scope.launch { if (AppUpdate.downloadAndInstall(ctx.applicationContext, r)) AppUpdate.offer = null else AppUpdate.offer = null } }) { Text("更新", color = C.Accent) } },
            dismissButton = { Row {
                TextButton(enabled = p < 0f, onClick = { AppUpdate.openSharePage(ctx); AppUpdate.offer = null }) { Text("打开分享页", color = C.Sub) }
                TextButton(enabled = p < 0f, onClick = { Store.putStr("upd.skip", r.skipKey); AppUpdate.offer = null }) { Text("以后再说", color = C.Sub) }
            } })
    }
}
