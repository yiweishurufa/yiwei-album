package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.*
import com.hark.shiguang.data.Photo
import java.text.NumberFormat

private fun n(v: Int) = NumberFormat.getIntegerInstance().format(v)

/** Picks the photo to keep: highest resolution, then largest file (less compression), then a clean original name, then the oldest. */
object Quality {
    private val copyName = Regex("""(\(\d+\)|副本|copy|_\d{1,2})(\.\w+)?$""", RegexOption.IGNORE_CASE)
    fun score(p: Photo): Double {
        val px = p.width.toLong() * p.height
        val nameBonus = if (copyName.containsMatchIn(p.fileName.substringBeforeLast('.') + "." )) 0.0 else 1.0
        return px * 1e3 + p.size / 1024.0 + nameBonus * 0.5
    }
    fun best(g: List<Photo>): Photo = g.maxWith(compareBy<Photo>({ score(it) }).thenByDescending { -it.fileName.length })
    fun why(best: Photo, g: List<Photo>): String = when {
        best.width > 0 && g.any { it !== best && it.width.toLong() * it.height < best.width.toLong() * best.height } -> "分辨率最高"
        g.any { it !== best && it.size < best.size } -> "文件最大 · 压缩最少"
        else -> "原始文件名"
    }
}

/**
 * 用户 2026-10-23：发现页「重复照片」「相似照片」合并成一个入口「重复与相似」，进来后用分段切换。
 * 两种列表各自计算、各自保存勾选（key(kind) 让切换时重新建状态，不会把重复的勾选带到相似里）。
 */
@Composable
fun DavGroupsScreen(r: Route.DavGroups) {
    if (r.kind != "dup" && r.kind != "sim") { DavGroupsBody(r) {}; return }
    var kind by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(r.kind) }
    key(kind) {
        DavGroupsBody(r.copy(title = "重复与相似", kind = kind)) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Segmented(listOf("重复", "相似"), if (kind == "dup") 0 else 1) { kind = if (it == 0) "dup" else "sim" }
            }
        }
    }
}

/** 重复 / 相似: each group on its own, best one marked 「推荐保留」, the rest pre-selected for deletion. */
@Composable
private fun DavGroupsBody(r: Route.DavGroups, header: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val lib = Dav.lib(r.accountId)
    val dup = r.kind == "dup"
    fun compute() = if (dup) lib.duplicates() else lib.similar()
    // 1.0.2: this page never scans by itself. Detection (headMd5 / dHash) is part of the AI tab's 整理 (本地分析);
    // here we only show what that analysis has found so far, and refresh when it adds more.
    var ready by remember { mutableStateOf(false) }
    val groups = remember { mutableStateListOf<List<Photo>>() }
    val del = remember { mutableStateMapOf<String, Boolean>() }
    val v = lib.metaVersion
    LaunchedEffect(r) { lib.ensure() }
    LaunchedEffect(r, v, lib.photos.size) {
        if (ready) kotlinx.coroutines.delay(1500)
        val g = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { compute() }.getOrNull() } ?: return@LaunchedEffect
        val keepSel = del.toMap()
        groups.clear(); groups.addAll(g)
        del.clear(); g.forEach { gg -> val b = Quality.best(gg); gg.forEach { if (it !== b) del[it.key] = keepSel[it.key] ?: true } }
        ready = true
    }
    val imgs = lib.imagesCount()
    val analyzedN = remember(v, lib.photos.size) { runCatching { lib.meta.values.count { it.done } }.getOrDefault(0) }
    val pending = imgs > 0 && analyzedN < imgs
    if (!ready) { Page(r.title, "") { Column(Modifier.fillMaxSize()) { header(); Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Accent) } } }; return }
    var confirmAll by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    fun doDelete(list: List<Photo>, after: () -> Unit) {
        if (list.isEmpty()) return
        busy = true
        Ops.launch {
            // B2: secondary confirmation for duplicates — full-file SHA-256 vs the kept photo of each group.
            // A candidate whose full hash differs is excluded (head-only collision); one that cannot be
            // verified (too large / unreadable) stays selected but is reported. The confirm dialog is unchanged.
            var verified = list
            if (dup) {
                var excluded = 0; var unverified = 0
                val keep = ArrayList<Photo>()
                for (g in groups) {
                    val best = Quality.best(g)
                    val selected = g.filter { p -> p in list && p !== best }
                    if (selected.isEmpty()) continue
                    val bestHash = runCatching { lib.fullSha256(best.cloudPath) }.getOrNull()
                    for (p in selected) {
                        val h = runCatching { lib.fullSha256(p.cloudPath) }.getOrNull()
                        when {
                            h == null || bestHash == null -> { unverified++; keep.add(p) }
                            h == bestHash -> keep.add(p)
                            else -> excluded++
                        }
                    }
                }
                verified = keep
                if (excluded > 0) toast(ctx, "全文校验排除了 $excluded 张（头部相同但全文不同）")
                if (unverified > 0) toast(ctx, "$unverified 张未能全文校验（文件过大或读取失败），请自行确认")
                if (verified.isEmpty()) { busy = false; return@launch }
            }
            runCatching { Ops.delete(verified) }.onSuccess {
                val keys = verified.map { it.key }.toSet()
                lib.photos.removeAll { it.key in keys }
                after(); toast(ctx, "已删除 ${verified.size} 张")
            }.onFailure { toast(ctx, it.message ?: "删除失败") }
            busy = false
        }
    }
    val total = groups.sumOf { g -> g.count { del[it.key] == true } }
    Page(r.title, "${groups.size} 组 · 已选 $total 张待删除", actions = {
        if (dup) InfoDot("重复判定仅比对文件头部（前 128KB）+ 大小；删除前会做全文哈希二次校验，全文不同者自动排除。", "重复照片")
    }) {
        Column(Modifier.fillMaxSize()) {
            header()
            if (pending) Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Accent.copy(alpha = 0.10f))
                .clickable { HomeState.openAiTab() }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (lib.analyzing) "发现页正在分析 ${n(analyzedN)}/${n(imgs)} 张" else "已分析 ${n(analyzedN)}/${n(imgs)} 张", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (lib.analyzing) "结果会自动出现在这里" else "其余照片在「发现」页的整理里继续分析", color = C.Sub, fontSize = 12.sp)
                }
                Text("去发现页", color = C.Accent, fontSize = 13.sp)
            }
            if (groups.isEmpty()) Box(Modifier.weight(1f)) {
                StateMessage(if (dup) LI.copy() else LI.layers(), if (pending) "还没分析完" else "没有${if (dup) "重复" else "相似"}照片",
                    if (pending) "检测随「发现」页的整理一起进行，完成后结果会显示在这里" else "有新照片时会在 AI 整理里自动检测")
            }
            else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(groups, key = { _, g -> g.first().key }) { gi, g ->
                    val best = Quality.best(g)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().skeuoCard().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("第 ${gi + 1} 组 · ${g.size} 张", color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text("推荐保留：" + Quality.why(best, g), color = C.Sub, fontSize = 12.sp)
                        }
                        LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(g, key = { _, p -> p.key }) { i, p ->
                                val sel = del[p.key] == true
                                Column(Modifier.width(120.dp)) {
                                    Box(Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)).border(if (p === best) 2.dp else 0.dp, if (p === best) C.Green else Color.Transparent, RoundedCornerShape(12.dp))
                                        .clickable { Nav.push(Route.Viewer(g, i)) }) {
                                        NetImage(p.thumbM, Modifier.fillMaxSize())
                                        if (p === best) Text("推荐保留", color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomStart).background(C.Green.copy(alpha = 0.9f), RoundedCornerShape(topEnd = 8.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                                        Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(if (sel) C.Danger else Color(0x66000000)).border(1.5.dp, Color.White, CircleShape)
                                            .clickable { del[p.key] = !sel }, contentAlignment = Alignment.Center) {
                                            if (sel) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    val res = if (p.width > 0) "${p.width}×${p.height} · " else ""
                                    Text(res + fmtBytes(p.size), color = C.Sub, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                                    Text(p.fileName, color = C.Faint, fontSize = 10.sp, maxLines = 1)
                                }
                            }
                        }
                        val gd = g.filter { del[it.key] == true }
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("红色勾选的会删除", color = C.Sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(enabled = !busy && gd.isNotEmpty() && gd.size < g.size, onClick = {
                                doDelete(gd) { val left = g - gd.toSet(); val idx = groups.indexOf(g); if (idx >= 0) { if (left.size > 1) groups[idx] = left else groups.removeAt(idx) } }
                            }) { Text(if (gd.size >= g.size) "至少保留一张" else "删除 ${gd.size} 张", color = if (gd.isNotEmpty() && gd.size < g.size) C.Danger else C.Faint) }
                        }
                    }
                }
            }
            if (groups.isNotEmpty()) GradientButton(if (busy) "处理中…" else "一键清理：删除已选 $total 张，每组保留推荐的", Modifier.padding(16.dp).navigationBarsPadding().fillMaxWidth()) {
                if (!busy && total > 0) confirmAll = true
            }
        }
    }
    if (confirmAll) ConfirmDialog("删除 $total 张照片？", "会从 WebDAV 上直接删除文件，不能恢复。每组至少保留一张。", "删除", onDismiss = { confirmAll = false }) {
        confirmAll = false
        val list = groups.flatMap { g -> g.filter { del[it.key] == true }.let { if (it.size >= g.size) it.drop(1) else it } }
        doDelete(list) { groups.clear() }
    }
}
