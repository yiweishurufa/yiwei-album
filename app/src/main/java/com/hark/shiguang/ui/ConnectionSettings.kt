package com.hark.shiguang.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.*
import com.hark.shiguang.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat

// 1.1.0 拆分自 Home.kt（只移动代码，行为不变）：连接设置页。
// ============================================================ connection settings page
// Was ConnectionSheet (bottom sheet): same account / LAN-WAN / speed-test / mode content, now a
// standalone page entered only from the Settings header card (TopSwitch no longer links here).

@Composable
fun ConnectionSettingsScreen() {
    Page("连接设置", NasAccounts.current?.let { "飞牛 · ${it.title}" } ?: "") {
        ConnectionSettingsContent(onClose = { Nav.pop() })
    }
}

@Composable
fun ConnectionSettingsContent(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var editLan by remember { mutableStateOf(false) }
    var editWan by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var speedLan by remember { mutableStateOf<Endpoint.Speed?>(null) }
    var speedWan by remember { mutableStateOf<Endpoint.Speed?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 30.dp)) {
            Text("账号", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 20.dp, bottom = 10.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(NasAccounts.list, key = { it.id }) { a ->
                    val cur = a.id == NasAccounts.currentId
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp).clickable {
                        if (!cur || HomeState.source != "fn") { switchSource(ctx, Src("fn", a.id, a.title, "")); onClose() }
                    }) {
                        Avatar(a.user, 52.dp, ring = cur)
                        Text(a.title, fontSize = 12.sp, color = if (cur) C.Text else C.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp).clickable { onClose(); Nav.push(Route.Login) }) {
                        Box(Modifier.size(52.dp).clip(CircleShape).border(1.dp, C.Line, CircleShape), contentAlignment = Alignment.Center) { Icon(LI.plus(), null, tint = C.Sub) }
                        Text("添加", fontSize = 12.sp, color = C.Sub, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            val a = NasAccounts.current
            if (a != null) {
                Section("连接 · ${a.title}")
                Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Bg.copy(alpha = 0.6f)).padding(4.dp)) {
                    listOf("auto" to "自动", "lan" to "仅内网", "wan" to "仅外网").forEach { (k, t) ->
                        val on = a.mode == k
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (on) C.Surface2 else Color.Transparent).clickable {
                            NasAccounts.update(a.copy(mode = k)); scope.launch { runCatching { Endpoint.autoSelect() }; HomeState.clear() }
                        }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) { Text(t, color = if (on) C.Text else C.Sub, fontSize = 14.sp) }
                    }
                }
                NavRow(LI.edit(), "重命名", a.title) { renaming = true }
                NavRow(LI.nas(), "内网地址", a.lanUrl.ifEmpty { "未设置 · 点按填写" } + if (Endpoint.active == "内网") "  · 使用中" else "") { editLan = true }
                NavRow(LI.swap(), "外网地址", a.wanUrl.ifEmpty { "未设置 · 点按填写" } + if (Endpoint.active == "外网") "  · 使用中" else "") { editWan = true }
                // 1.0.0: 内网 / 外网 speed test, result inline on each button
                val samples = remember(HomeState.source) { if (HomeState.source == "fn") HomeState.timeline.photos.asSequence().filter { !it.isVideo }.map { it.thumbM }.filter { it.isNotEmpty() }.take(24).toList() else emptyList() }
                listOf(true, false).forEach { isLan ->
                    val addr = if (isLan) a.lanUrl else a.wanUrl
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f).padding(top = 10.dp)) {
                            Text(if (isLan) "内网测速" else "外网测速", color = C.Text, fontSize = 15.sp)
                            Text(addr.ifEmpty { "未设置地址" }.substringAfter("://"), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(12.dp))
                        TestButton("测速", Modifier.width(170.dp), resetKey = addr, height = 42.dp, holdMs = 6000, enabled = addr.isNotEmpty(),
                            okLabel = { it.substringAfter("下载 ", "").substringBefore("（").ifEmpty { it.substringBefore(" ·") } }) { prog ->
                            val r = Endpoint.speedTest(addr, samples, prog)
                            if (isLan) speedLan = r else speedWan = r
                            Endpoint.rememberFaster(speedLan, speedWan)
                            r.line()
                        }
                    }
                }
                NavRow(LI.clock(), "延迟", if (testing) "测速中…" else if (Endpoint.latency >= 0) "${Endpoint.latency} ms · ${Endpoint.active.ifEmpty { "当前地址" }} · 点按重测" else "连不上 · 点按重测") {
                    testing = true; scope.launch { runCatching { Endpoint.autoSelect() }; testing = false }
                }
                NavRow(LI.close(), "退出这个账号", a.user, danger = true) {
                    onClose(); Ops.logoutNas(a.id)
                }
            }
    }
    val a = NasAccounts.current
    if (a != null && renaming) InputDialog("重命名账号", a.title, "比如：家里的飞牛", "保存", onDismiss = { renaming = false }) { v ->
        renaming = false; NasAccounts.update(a.copy(name = v.trim()))
    }
    if (a != null && (editLan || editWan)) {
        val lan = editLan
        InputDialog(if (lan) "内网地址" else "外网地址", if (lan) a.lanUrl else a.wanUrl, if (lan) "192.168.1.10:5666" else "nas.example.com 或 IP:端口", "保存", onDismiss = { editLan = false; editWan = false }) { v ->
            editLan = false; editWan = false
            scope.launch {
                val u = if (v.isBlank()) "" else runCatching { Endpoint.resolve(v) }.getOrDefault(v)
                NasAccounts.update(if (lan) a.copy(lanUrl = u) else a.copy(wanUrl = u))
                runCatching { Endpoint.autoSelect() }
                toast(ctx, "已保存")
            }
        }
    }
}
