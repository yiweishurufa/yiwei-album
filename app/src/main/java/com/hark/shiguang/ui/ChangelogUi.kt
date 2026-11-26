package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.BuildInfo
import com.hark.shiguang.Changelog
import com.hark.shiguang.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 1.7.17 随包附带的更新日志（构建时从仓库根目录 CHANGELOG.md 拷进 assets）。读不到返回空表。 */
internal fun bundledChangelog(ctx: android.content.Context): List<Changelog.Version> =
    runCatching { ctx.assets.open("CHANGELOG.md").bufferedReader().use { Changelog.parse(it.readText()) } }
        .onFailure { Diag.log("UPD", "bundled changelog: ${it.message}") }.getOrDefault(emptyList())

/**
 * 一个版本里的分组与条目：分组名（新增 / 改进 / 修复 / 移除）是读屏「标题」，每条前一个圆点，
 * 「**标题**：说明」的标题加粗。[compact] 用在更新窗口里（字号小一档）。
 */
@Composable
internal fun ChangelogGroups(v: Changelog.Version, compact: Boolean = false) {
    v.groups.forEach { g ->
        Text(g.title, color = C.Text, style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = if (compact) 10.dp else 14.dp, bottom = 4.dp).semantics { heading() })
        g.items.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text("•", color = C.Sub, fontSize = if (compact) 13.sp else 14.sp, modifier = Modifier.padding(end = 8.dp))
                val lead = Changelog.lead(item)
                val text = buildAnnotatedString {
                    if (lead != null) {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = C.Text)) { append(lead.first) }
                        append(lead.second)
                    } else append(Changelog.plain(item))
                }
                Text(text, color = if (compact) C.Text else C.Sub, style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    lineHeight = if (compact) 18.sp else 21.sp)
            }
        }
    }
}

/** 设置 → 关于 → 更新内容：每个版本一张卡片，当前安装的版本带「当前版本」标记。 */
@Composable
fun ChangelogScreen() {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<Changelog.Version>?>(null) }
    LaunchedEffect(Unit) { list = withContext(Dispatchers.IO) { bundledChangelog(ctx) } }
    Page("更新内容", "当前 ${BuildInfo.label}") {
        val l = list
        when {
            l == null -> Column(Modifier.padding(top = 8.dp)) { Card { ListRowSkeleton(rows = 4) } }
            l.isEmpty() -> StateMessage(KI.info(), "暂时没有更新记录", "新版本的更新内容会显示在这里。")
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                l.forEach { v ->
                    Card {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { heading() }) {
                                Text(v.version, color = C.Text, style = MaterialTheme.typography.titleLarge.merge(Type.num))
                                if (v.date.isNotEmpty()) Text(v.date, color = C.Sub, style = MaterialTheme.typography.bodySmall.merge(Type.num), modifier = Modifier.padding(start = 10.dp))
                                if (v.version == BuildInfo.version) Text("当前版本", color = C.Accent, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(start = 10.dp).clip(CircleShape).background(C.Accent.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 2.dp))
                            }
                            ChangelogGroups(v)
                        }
                    }
                }
            }
        }
    }
}
