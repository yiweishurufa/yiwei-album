package com.hark.shiguang.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
    // 2026-10-22: a native crash / ANR is written to last.txt by a startup background thread — pick it up shortly after
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(1500); if (crash == null) crash = CrashLog.pending(ctx); kotlinx.coroutines.delay(1000); AppUpdate.autoCheck() }
    crash?.let { log ->
        AppAlertDialog(onDismissRequest = {}, containerColor = C.Surface,
            title = { Text("上次异常退出", color = C.Text) },
            text = { Text("上次异常退出，要把日志发给开发者吗？日志只含版本、机型和出错位置，不含账号、地址和照片。", color = C.Sub, fontSize = 14.sp) },
            confirmButton = { TextButton({ crash = null; CrashLog.send(ctx, log) }) { Text("发送日志", color = C.Accent) } },
            dismissButton = { TextButton({ crash = null; CrashLog.clear(ctx) }) { Text("不用了", color = C.Sub) } })
    }
    if (crash == null) AppUpdate.offer?.let { r ->
        val p = AppUpdate.progress
        // 1.7.17：打开更新窗口时去读这次的更新内容（GitHub 发布里的 CHANGELOG.md，走同一条加速路径）；读不到就显示 version.json 的说明
        LaunchedEffect(r.skipKey) { AppUpdate.offerChanges = null; AppUpdate.offerChanges = AppUpdate.loadChanges(r) }
        AppAlertDialog(onDismissRequest = { if (p < 0f) AppUpdate.offer = null }, containerColor = C.Surface,
            title = { Text("发现新版本 ${r.version}", color = C.Text) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("当前 ${BuildInfo.label}" + (if (r.build > 0) " · 新版 CI #${r.build}" else "") + if (r.size > 0) " · 安装包 ${"%.1f".format(r.size / 1048576.0)} MB" else "", color = C.Sub, fontSize = 13.sp)
                    val changes = AppUpdate.offerChanges
                    if (changes != null) {
                        Text("更新内容", color = C.Text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
                        changes.forEach { v ->
                            // 跳过了几个版本时，每个版本一个小标题
                            if (changes.size > 1) Text(v.version + if (v.date.isNotEmpty()) "（${v.date}）" else "", color = C.Sub, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 10.dp))
                            ChangelogGroups(v, compact = true)
                        }
                    } else if (!Changelog.isLegacyNotes(r.notes)) Text(r.notes.trim(), color = C.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                    Text(AppUpdate.FALLBACK_TEXT, color = C.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                    if (p >= 0f) {
                        LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, color = C.Accent, trackColor = C.Surface2, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
                        Text("正在下载 ${(p * 100).toInt()}%", color = C.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            },
            confirmButton = { TextButton(enabled = p < 0f, onClick = { scope.launch { if (AppUpdate.downloadAndInstall(ctx.applicationContext, r)) AppUpdate.offer = null else AppUpdate.offer = null } }) { Text("更新", color = C.Accent) } },
            dismissButton = { Row {
                TextButton(enabled = p < 0f, onClick = { AppUpdate.openInBrowser(ctx); AppUpdate.offer = null }) { Text("浏览器下载", color = C.Sub) }
                TextButton(enabled = p < 0f, onClick = { Store.putStr("upd.skip", r.skipKey); AppUpdate.offer = null }) { Text("以后再说", color = C.Sub) }
            } })
    }
    // B5: the downloaded APK was signed by a different key — the installer was never launched.
    AppUpdate.signatureMismatch?.let {
        AppAlertDialog(onDismissRequest = { AppUpdate.signatureMismatch = null }, containerColor = C.Surface,
            title = { Text("签名不一致", color = C.Text) },
            text = { Text("下载的安装包签名与当前应用不一致，需先卸载旧版才能安装。本次安装已取消，安装包已删除。", color = C.Sub, fontSize = 14.sp) },
            confirmButton = { TextButton({ AppUpdate.signatureMismatch = null }) { Text("知道了", color = C.Accent) } },
            dismissButton = { TextButton({ AppUpdate.signatureMismatch = null; AppUpdate.openInBrowser(ctx) }) { Text("浏览器下载", color = C.Sub) } })
    }
}
