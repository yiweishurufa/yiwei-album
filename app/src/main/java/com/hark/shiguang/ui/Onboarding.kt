package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hark.shiguang.*

/**
 * 1.0.1 #9: first-run wizard — 照片源 → AI（国内免费模型）→ 影视刮削 → 完成.
 * Shown once after install (can be skipped), re-opened from 设置 → 关于 → 使用引导.
 * The 飞牛 login screen resets the stack to Home when it succeeds; [resumeAt] brings the wizard back at the AI step.
 */
object Onboarding {
    var done: Boolean
        get() = Store.getStr("onboard.done") == "1"
        set(v) = Store.putStr("onboard.done", if (v) "1" else "")
    /** Step to reopen at once the 飞牛 login reaches Home (-1 = none). */
    var resumeAt = -1
    private var autoShown = false

    fun open(step: Int = 0) = Nav.push(Route.X(ExtScreen.Onboarding(step)))

    fun hasSource() = Dav.accounts.isNotEmpty() || NasAccounts.list.isNotEmpty() || Store.token.isNotEmpty()

    /** First run: new installs, or upgrades that have no photo source / no AI yet. */
    private fun shouldAutoShow() = !done && (!hasSource() || !AiConfig.enabled)

    /** Called from MainActivity on every navigation change (one line there). */
    @Composable
    fun Hook() {
        val top = Nav.stack.lastOrNull()
        LaunchedEffect(top) {
            when {
                resumeAt >= 0 && top == Route.Home -> { val s = resumeAt; resumeAt = -1; open(s) }
                !autoShown && top != null && top !is Route.X -> { autoShown = true; if (shouldAutoShow()) open(0) }
            }
        }
    }

    /** Leaves the wizard: with a source but the login page underneath, go to Home instead. */
    fun finish() {
        done = true
        Nav.pop()
        if (Nav.stack.lastOrNull() == Route.Login && hasSource()) {
            if (Dav.accounts.isNotEmpty() && NasAccounts.list.isEmpty() && Store.token.isEmpty()) HomeState.useSource("dav")
            Nav.reset(Route.Home)
        }
    }
}

private val STEPS = listOf("照片源", "AI 整理", "影视刮削", "完成")

@Composable
fun OnboardingScreen(start: Int) {
    var step by remember { mutableIntStateOf(start.coerceIn(0, 3)) }
    Column(Modifier.fillMaxSize().background(C.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("欢迎使用一维相册", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (step < 3) TextButton(onClick = { Onboarding.finish() }) { Text("跳过", color = C.Sub) }
        }
        // step dots
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            STEPS.forEachIndexed { i, t ->
                Box(Modifier.size(22.dp).clip(CircleShape).background(if (i <= step) C.Accent else C.Surface2), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", fontSize = 11.sp, color = if (i <= step) C.OnAccent else C.Sub, fontWeight = FontWeight.SemiBold)
                }
                Text(t, fontSize = 12.sp, color = if (i == step) C.Text else C.Sub, modifier = Modifier.padding(start = 4.dp, end = 10.dp))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (step) {
                0 -> SourceStep()
                1 -> AiStep()
                2 -> MovieStep()
                else -> DoneStep()
            }
            Spacer(Modifier.height(24.dp))
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step in 1..2) TextButton(onClick = { step-- }, modifier = Modifier.height(50.dp)) { Text("上一步", color = C.Sub) }
            GradientButton(if (step < 3) "下一步" else "开始使用", Modifier.weight(1f)) { if (step < 3) step++ else Onboarding.finish() }
        }
    }
}

@Composable
private fun Lead(title: String, sub: String) {
    Text(title, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
    Text(sub, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 4.dp))
}

@Composable
private fun Note(t: String) = Text(t, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

// ------------------------------------------------------------------ 1 照片源
@Composable
private fun SourceStep() {
    val ctx = LocalContext.current
    var addDav by remember { mutableStateOf(false) }
    var davN by remember { mutableIntStateOf(Dav.accounts.size) }
    Lead("先添加照片在哪里", "照片留在你的 NAS 或网盘里，一维相册只读取、不搬家。两种都可以添加，之后在 设置 里还能再加。")
    Section("已添加")
    Card {
        val nas = NasAccounts.list
        if (nas.isEmpty() && Store.token.isEmpty() && davN == 0) NavRow(LI.info(), "还没有照片源", "从下面选一个添加") {}
        nas.forEach { NavRow(LI.nas(), "飞牛 · ${it.title}", "已登录") {} }
        if (nas.isEmpty() && Store.token.isNotEmpty()) NavRow(LI.nas(), "飞牛相册", Store.url) {}
        Dav.accounts.forEach { NavRow(LI.drive(), "WebDAV · ${it.title}", it.url) {} }
    }
    Section("添加")
    Card {
        NavRow(LI.nas(), "飞牛 fnOS 相册", "用飞牛账号登录，内网 / 外网 / FN Connect 都可以") {
            Onboarding.resumeAt = 1
            if (Nav.stack.getOrNull(Nav.stack.lastIndex - 1) == Route.Login) Nav.pop() else Nav.push(Route.Login)
        }
        Div()
        NavRow(LI.drive(), "WebDAV 网盘", "123 云盘、坚果云、Alist / OpenList、群晖、Nextcloud…") { addDav = true }
    }
    Note("飞牛登录成功后会自动回到这里继续下一步。")
    if (addDav) DavDialog(null, onDismiss = { addDav = false }) { saved ->
        addDav = false
        if (Dav.accounts.size == 1 || Dav.current == null) Dav.select(saved.id)
        davN = Dav.accounts.size
        toast(ctx, "已添加 ${saved.title}")
    }
}

// ------------------------------------------------------------------ 2 AI
@Composable
private fun AiStep() {
    val free = remember { AiProviders.all.filter { it.group == "free" } }
    var pid by remember { mutableStateOf(if (AiProviders.of(AiConfig.provider).group == "free") AiConfig.provider else "zhipu") }
    val p = AiProviders.of(pid)
    var key by remember(pid) { mutableStateOf(AiKeys.get(pid).ifEmpty { if (AiConfig.provider == pid) AiConfig.key else "" }) }
    Lead("AI 整理照片（免费）", "场景分类和「海边日落」这样的智能搜索需要一个大模型。推荐国内的免费看图模型：不用翻墙、不用付费，只需注册拿一个 Key。")
    Section("选择平台")
    Card {
        free.forEachIndexed { i, it ->
            if (i > 0) Div()
            Row(Modifier.fillMaxWidth().clickable { pid = it.id }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(if (pid == it.id) C.Accent else C.Surface2))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(it.name + if (it.id == "zhipu") "（推荐）" else "", color = if (pid == it.id) C.Accent else C.Text, fontSize = 15.sp)
                    Text("免费：" + it.free.joinToString("、") { m -> m.substringAfterLast('/') }, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
        }
    }
    if (p.note.isNotEmpty()) Note(p.note)
    if (p.keyUrl.isNotEmpty()) KeyLink(p.keyUrl, Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
    Spacer(Modifier.height(6.dp))
    InputRow("API Key", key, "粘贴 ${p.short} 的 Key") { key = it }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        TestButton("测试连接", Modifier.fillMaxWidth(), resetKey = pid + key, filled = true) {
            if (key.isBlank()) throw Exception("先粘贴 Key")
            if (AiConfig.provider != pid) AiConfig.choose(p)
            AiConfig.key = key.trim(); AiConfig.enabled = true; AiConfig.save()
            "连接成功：" + AiClient.test()
        }
    }
    Note("多填几个平台的 Key，额度用完会自动切换到下一个免费模型。不填也没关系：会先用手机本地识别分类（较粗），以后到 设置 → 大模型 再配置。OpenRouter 等需要翻墙的在 设置 → 大模型 →「进阶」。")
}

// ------------------------------------------------------------------ 3 影视
@Composable
private fun MovieStep() {
    var key by remember { mutableStateOf(Tmdb.key) }
    var saved by remember { mutableStateOf(false) }
    Lead("影视海报和简介（可选）", "影视库用 TMDB 识别电影和剧集，需要一个免费的 TMDB API Key（v3 API Key 或 v4 读访问令牌都可以）。")
    KeyLink("https://www.themoviedb.org/settings/api", Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    InputRow("TMDB 密钥", key, "粘贴 API Key") { key = it; saved = false }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        GradientButton(if (saved) "已保存" else "保存", Modifier.fillMaxWidth()) { Tmdb.key = key; saved = true }
    }
    Note("网络会自动检测：在国内自动走可用的代理访问 TMDB，在国外直接连接，不需要翻墙。影视文件夹在 影视 页或 设置 → 媒体库与刮削 里选择。")
    DisposableEffect(Unit) { onDispose { if (key.isNotBlank() && key.trim() != Tmdb.key) Tmdb.key = key } }
}

// ------------------------------------------------------------------ 完成
@Composable
private fun DoneStep() {
    Lead("准备好了", "下面是现在的状态，随时可以到 设置 里修改，也可以在 设置 → 关于 → 使用引导 重新打开这个向导。")
    Card {
        NavRow(LI.drive(), "照片源", if (Onboarding.hasSource()) "已添加" else "还没有添加") {}
        Div()
        NavRow(LI.sparkle(), "AI 整理", if (AiConfig.configured) AiProviders.of(AiConfig.provider).short + " · " + AiConfig.model else if (LocalAi.enabled) "本地识别（离线）" else "未开启") {}
        Div()
        NavRow(LI.film(), "影视刮削", if (Tmdb.key.isNotBlank()) "TMDB 已配置" else "未填 TMDB 密钥") {}
    }
    Note("照片分析、人脸识别和 AI 整理会按 设置 → 网络与电量 的规则在后台进行。")
}
