package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
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
        scope.launch { busy = true; runCatching { f() }.onSuccess { toast(ctx, ok); HomeState.dirty = true }.onFailure { failToast(ctx, it, "失败") }; busy = false; refresh() }
    }
    // 1.6.6 阶段 3：副标题只放数量，30 天规则收进 ⓘ；列表是一张卡片里的分隔行（不再一行一张卡）；剩余天数等宽数字，≤ 3 天用错误色
    Page("最近删除", if (list.isNotEmpty()) "${list.size} 项" else "", actions = {
        InfoDot("在本 App 里删除的网盘照片和视频会先放到这里，保留 30 天，到期自动清除；期间可以恢复。", "最近删除")
        if (list.isNotEmpty()) HeaderTextButton("清空", if (busy) C.Faint else C.Danger) { if (!busy) clearAll = true }
    }) {
        if (list.isEmpty()) StateMessage(KI.trash(), "最近删除是空的", "删除的网盘照片和视频会在这里保留 30 天。")
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = Space.s, bottom = 40.dp)) {
            item {
                Card {
                    list.forEachIndexed { i, e ->
                        if (i > 0) Div()
                        val left = e.daysLeft()
                        Row(Modifier.fillMaxWidth().heightIn(min = Space.row + Space.s).padding(start = Space.l, end = Space.xs, top = Space.xs, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (e.video) KI.videos() else KI.photos(), null, tint = C.Sub, modifier = Modifier.size(22.dp))
                            Column(Modifier.weight(1f).padding(horizontal = Space.m).semantics(mergeDescendants = true) {}) {
                                Text(e.name, color = C.Text, style = MaterialTheme.typography.bodyLarge, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row {
                                    Text("还剩 $left 天", color = if (left <= 3) C.Danger else C.Sub, style = MaterialTheme.typography.bodySmall.merge(Type.num).copy(color = if (left <= 3) C.Danger else C.Sub))
                                    Text(" · " + e.orig.substringBeforeLast('/').ifEmpty { "/" }, color = C.Sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            HeaderTextButton("恢复", if (busy) C.Faint else C.Accent) { if (!busy) act({ DavRecycle.restore(r.accountId, src!!, e) }, "已恢复") }
                            HeaderTextButton("删除", if (busy) C.Faint else C.Danger) { if (!busy) confirm = e }
                        }
                    }
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
