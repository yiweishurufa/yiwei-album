package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.*
import kotlinx.coroutines.launch

/**
 * 优化 5（用户 2026-11-02）：WebDAV / 网盘的「最近删除」。每行：文件名、删除日期、还剩几天；恢复 / 彻底删除；右上角清空。
 * 入口：发现 → 工具 → 最近删除（WebDAV 来源），设置 → 高级 → 网盘删除先进回收站（开关）旁边。
 */
@Composable
fun DavRecycleScreen(r: Route.DavRecycle) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val src = remember(r.accountId) { CloudPhotos.source(r.accountId) as? com.hark.shiguang.cloud.WebDavSource }
    var list by remember { mutableStateOf(DavRecycle.list(r.accountId).sortedByDescending { it.deletedAt }) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<DavRecycle.Entry?>(null) }
    var clearAll by remember { mutableStateOf(false) }
    fun refresh() { list = DavRecycle.list(r.accountId).sortedByDescending { it.deletedAt } }
    LaunchedEffect(Unit) { if (src != null) { busy = true; runCatching { DavRecycle.purgeExpired(r.accountId, src) }; busy = false; refresh() } }
    fun act(f: suspend () -> Unit, ok: String) {
        if (src == null) { toast(ctx, "网盘账户不可用"); return }
        scope.launch { busy = true; runCatching { f() }.onSuccess { toast(ctx, ok); HomeState.dirty = true }.onFailure { toast(ctx, it.message ?: "失败") }; busy = false; refresh() }
    }
    Page("最近删除", if (list.isNotEmpty()) "${list.size} 项 · 删除 30 天后自动清除" else "", actions = {
        if (list.isNotEmpty()) TextButton(onClick = { clearAll = true }, enabled = !busy) { Text("清空", color = C.Danger) }
    }) {
        if (list.isEmpty()) StateMessage(Icons.Rounded.DeleteOutline, "最近删除是空的", "在本 App 里删除的网盘照片和视频会在这里保留 30 天，可以恢复。")
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.orig + it.deletedAt }) { e ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Surface).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (e.video) Icons.Rounded.Movie else Icons.Rounded.Image, null, tint = C.Sub, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(e.name, color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.orig.substringBeforeLast('/').ifEmpty { "/" } + " · 还剩 ${e.daysLeft()} 天", color = C.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { act({ DavRecycle.restore(r.accountId, src!!, e) }, "已恢复") }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("恢复", color = C.Accent) }
                    TextButton(onClick = { confirm = e }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除", color = C.Danger) }
                }
            }
        }
    }
    confirm?.let { e ->
        ConfirmDialog("彻底删除「${e.name}」？", "网盘里的文件会被删除，无法恢复。", "彻底删除", onDismiss = { confirm = null }) {
            confirm = null; act({ DavRecycle.purge(r.accountId, src!!, e) }, "已彻底删除")
        }
    }
    if (clearAll) ConfirmDialog("清空最近删除？", "${list.size} 个文件会从网盘彻底删除，无法恢复。", "清空", onDismiss = { clearAll = false }) {
        clearAll = false
        val l = list
        act({ l.forEach { DavRecycle.purge(r.accountId, src!!, it) } }, "已清空")
    }
}
