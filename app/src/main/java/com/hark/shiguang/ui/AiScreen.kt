package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.*
import kotlinx.coroutines.launch

/** AI 大模型（用户 2026-10-23 统一叫法；以前叫「智能整理 · AI」「大模型」「AI 设置」）: provider presets, key/baseUrl/model, privacy & power limits, monthly budget. */
@Composable
fun AiSettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickProvider by remember { mutableStateOf(false) }
    var freeList by remember { mutableStateOf<List<AiClient.FreeModel>?>(null) }
    var freeBusy by remember { mutableStateOf(false) }
    var freeErr by remember { mutableStateOf<String?>(null) }
    val inputsKey = AiConfig.provider + "|" + AiConfig.baseUrl + "|" + AiConfig.key + "|" + AiConfig.model
    val prov = AiProviders.of(AiConfig.provider)
    Column(Modifier.fillMaxSize().background(C.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Nav.pop() }) { Icon(LI.back(), null, tint = C.Text) }
            Text("AI 大模型", style = MaterialTheme.typography.headlineSmall)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Section("接口", info = "地点、重复、相似照片在手机本地完成，不需要 AI、不上传。大模型只用于场景分类和智能搜索。")
            Card {
                Row(Modifier.fillMaxWidth().clickable { pickProvider = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("提供商", style = MaterialTheme.typography.labelSmall)
                        Text(prov.name, color = C.Text, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
                        if (prov.note.isNotEmpty()) Text(prov.note, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    Text(AiProviders.GROUPS.firstOrNull { it.first == prov.group }?.second?.substringBefore("（").orEmpty(), fontSize = 11.sp,
                        color = if (prov.group == "free") C.OnAccent else C.Sub,
                        modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(8.dp)).background(if (prov.group == "free") C.Accent else C.Chip).padding(horizontal = 8.dp, vertical = 3.dp))
                    Icon(LI.down(), null, tint = C.Sub, modifier = Modifier.size(18.dp))
                }
            }
            if (prov.keyUrl.isNotEmpty()) KeyLink(prov.keyUrl)
            InputRow("接口地址（Base URL）", AiConfig.baseUrl, "https://…/v1") { AiConfig.baseUrl = it }
            InputRow("API Key", AiConfig.key, if (prov.needsKey) "sk-…" else "本地模型可留空") { AiConfig.key = it }
            InputRow("模型", AiConfig.model, "模型名") { AiConfig.model = it }
            val sees = remember(AiConfig.model, freeList) { AiClient.modelSeesImages() }
            if (sees == false) Text("这个模型不能看图，分类会失败",
                color = C.Danger, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
            if (prov.id == "openrouter" || AiConfig.baseUrl.contains("openrouter.ai")) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (freeBusy) "正在获取…" else "获取免费模型", color = C.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(C.Accent.copy(alpha = 0.12f)).clickable(enabled = !freeBusy) {
                            freeBusy = true; freeErr = null
                            scope.launch {
                                runCatching { AiClient.freeModels() }.onSuccess { freeList = it; if (it.isEmpty()) freeErr = "今天没有免费模型" }
                                    .onFailure { freeErr = it.message ?: "获取失败" }
                                freeBusy = false
                            }
                        }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
                freeErr?.let { Text(it, color = C.Danger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp)) }
            }
            if (prov.models.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp).horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                prov.models.forEach { m ->
                    val on = AiConfig.model == m
                    Text(m + if (m in prov.free) " · 免费" else "", fontSize = 12.sp, color = if (on) C.OnAccent else C.Text, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) C.Accent else C.Chip)
                        .clickable { AiConfig.model = m }.padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
            // 用户 2026-10-23：单独的「测速」和底部「测试连接」重复，只留「测试连接」（结果里带延迟）
            // 1.1.0: 「免费模型自动切换」并入模型列表：列表顺序就是切换优先级
            Section("出错时")
            Card {
                ToggleRow(LI.swap(), "按顺序换下一个模型", on = AiPool.autoSwitch,
                    info = "额度用完、限流、超时或下线时，同一张照片换列表里的下一个模型继续；填过 Key 的免费平台会自动加入列表。用 ↑ ↓ 调整顺序，上面的优先。") { AiPool.setAuto(it) }
                Div()
                ToggleRow(LI.sparkle(), "本地识别兜底", on = LocalAi.enabled,
                    info = "没配大模型、或所有模型额度用完时，用手机上的识别模型继续分类：不联网、不耗额度，结果标「本地识别」，大模型恢复后自动细分。") { LocalAi.set(it) }
            }
            PoolList()
            Section("费用", info = "只发送 512px 缩略图，不传原图和位置。什么时候运行跟随「${ScanPolicy.SETTINGS_REF}」（移动网络 / 不充电时是否继续），暂停后到「发现」页点「立即继续」。到达每月预算后自动暂停。")
            InputRow("每月预算（元）", AiConfig.budget, "10") { AiConfig.budget = it.filter { c -> c.isDigit() || c == '.' } }
            // 1.1.0: 单价不再手填，按内置价目表估算；不认识的模型按 0 计并标注「无法估算」
            val curEp = AiEndpoint.current()
            val known = AiConfig.priceOf(curEp.model, curEp.free) != null
            Text(AiPrices.describe(curEp.model, curEp.free).let { if (!known || AiPrices.of(curEp.model, curEp.free) != null) it else "使用你以前填写的单价估算" },
                color = if (known) C.Sub else C.Gold, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp))
            Text("本月已用约 ¥${"%.2f".format(AiConfig.spent)}" + (if (!known) "（当前模型无法估算）" else "") + (AiRunner.note.ifEmpty { AiRunner.status }).let { if (it.isNotEmpty()) " · $it" else "" }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
            // 1.1.0: 低频选项收进「高级」
            var adv by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable { adv = !adv }.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("高级", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (adv) "收起" else "展开", color = C.Accent, fontSize = 13.sp)
            }
            if (adv) Card {
                ToggleRow(LI.nas(), "飞牛照片也用 AI 整理", on = AiConfig.nasToo,
                    info = "默认关闭。飞牛自带智能分类，打开后再用你的模型整理一遍（会消耗额度）。") { AiConfig.nasToo = it }
            }
            Spacer(Modifier.height(20.dp))
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            TestButton("测试连接", Modifier.weight(1f), resetKey = inputsKey) {
                AiConfig.enabled = true; AiConfig.save()
                val t0 = System.currentTimeMillis()
                val r = AiClient.test()
                "连接成功 · ${System.currentTimeMillis() - t0} ms：" + r
            }
            GradientButton("保存并开始", Modifier.weight(1f)) {
                AiConfig.enabled = true; AiConfig.save()
                ScanPolicy.paused = false
                if (HomeState.source == "dav") Dav.lib()?.let { AiRunner.startDav(ctx, it) }
                else if (AiConfig.nasToo && NasAccounts.current != null) AiRunner.startNas(ctx, HomeState.timeline.photos.toList())
                toast(ctx, if (!Analyzer.canScan(ctx)) "已保存，" + ScanPolicy.waitingText() else "已保存，开始整理")
            }
        }
    }
    freeList?.takeIf { it.isNotEmpty() }?.let { list ->
        AlertDialog(onDismissRequest = { freeList = null }, containerColor = C.Surface, title = { Text("今日免费模型", color = C.Text) }, text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(list, key = { it.id }) { m ->
                    Row(Modifier.fillMaxWidth().clickable {
                        AiConfig.model = m.id; AiConfig.save(); freeList = null
                        if (!m.vision) toast(ctx, "注意：这个模型不能看图，照片分类会失败")
                    }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, color = if (m.id == AiConfig.model) C.Accent else C.Text, fontSize = 14.sp)
                            Text(m.id, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (m.vision) "看图" else "仅文字", fontSize = 11.sp, color = if (m.vision) C.OnAccent else C.Sub,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (m.vision) C.Accent else C.Chip).padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { freeList = null }) { Text("关闭", color = C.Sub) } })
    }
    if (pickProvider) AlertDialog(onDismissRequest = { pickProvider = false }, containerColor = C.Surface, title = { Text("选择提供商", color = C.Text) }, text = {
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            AiProviders.GROUPS.forEach { (g, title) ->
                item(key = "h_$g") {
                    Text(title, color = if (g == "free") C.Accent else C.Sub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                }
                items(AiProviders.all.filter { it.group == g }, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { AiConfig.choose(p); pickProvider = false }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = if (p.id == AiConfig.provider) C.Accent else C.Text, fontSize = 15.sp)
                            Text(if (p.free.isNotEmpty()) "免费：" + p.free.joinToString("、") { it.substringAfterLast('/').removeSuffix(":free") } else p.baseUrl.ifEmpty { "自己填写接口地址" },
                                style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        if (AiKeys.has(p.id)) Text("已填 Key", fontSize = 11.sp, color = C.Sub,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(C.Chip).padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { pickProvider = false }) { Text("关闭", color = C.Sub) } })
}

/** The failover pool in order, with what each model is doing (可用 / 冷却到 HH:mm · 原因). */
@Composable
private fun PoolList() {
    val v = AiPool.version
    val cands = remember(v, AiConfig.provider, AiConfig.model, AiConfig.key, AiPool.autoSwitch, AiConfig.enabled) { AiPool.candidates() }
    Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
        // 第 4 批：冷却按平台/错误类型拆分后的卡片提示（余额不足 / 模型下线），与 AI 卡片上的 AiPool.topHint 同源。
        if (AiPool.balanceInsufficient) Text("余额不足，请充值或换免费模型",
            color = C.Danger, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 4.dp))
        if (AiPool.modelOffline) Text("有模型已下线，请换模型",
            color = C.Danger, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 4.dp))
        // 用户 2026-10-23：只留危险提示（余额不足、模型下线），其余说明删掉；列表本身就说明了顺序
        if (cands.isNotEmpty() && AiPool.autoSwitch) {
            cands.forEachIndexed { i, ep ->
                val cool = AiPool.coolUntil(ep).takeIf { it > System.currentTimeMillis() }
                val act = AiPool.active?.id == ep.id
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${i + 1}. ${ep.label}", color = if (act) C.Accent else C.Text, fontSize = 13.sp, maxLines = 1)
                        Text(ep.providerName + (if (ep.free) " · 免费" else AiConfig.priceOf(ep.model).let { if (it == null) " · 无法估算费用" else "" }) +
                            (if (cool != null) " · 冷却到 ${AiPool.hhmm(cool)}" else if (act) " · 当前" else ""),
                            fontSize = 12.sp, color = if (cool != null) C.Danger else if (act) C.Accent else C.Sub)
                    }
                    IconButton(onClick = { AiPool.move(ep.id, -1) }, enabled = i > 0) { Text("↑", color = if (i > 0) C.Text else C.Faint, fontSize = 16.sp) }
                    IconButton(onClick = { AiPool.move(ep.id, 1) }, enabled = i < cands.lastIndex) { Text("↓", color = if (i < cands.lastIndex) C.Text else C.Faint, fontSize = 16.sp) }
                }
                if (cool != null) AiPool.reason(ep).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp)) }
            }
            if (AiPool.customOrder) Text("恢复默认顺序", color = C.Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp).clickable { AiPool.resetOrder() })
            if (cands.any { AiPool.cooled(it) }) Text("清除冷却，立即重试", color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp).clickable { AiPool.clear() })
        }
    }
}

/** 「获取 Key：https://…」 — the address as text (copyable) and a tap opens the browser. */
@Composable
fun KeyLink(url: String, modifier: Modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
    val ctx = LocalContext.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("获取 Key：", style = MaterialTheme.typography.bodySmall)
        Text(url, color = C.Accent, fontSize = 12.sp, modifier = Modifier.clickable {
            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { toast(ctx, "打不开浏览器，请手动访问 $url") }
        })
    }
}

private fun Modifier.horizontalScrollCompat(): Modifier = composed { this.then(Modifier.horizontalScroll(rememberScrollState())) }

