package com.hark.shiguang.ui

import android.graphics.Bitmap
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.hark.shiguang.*
import com.hark.shiguang.data.Endpoint
import com.hark.shiguang.data.FnClient
import com.hark.shiguang.cloud.*
import kotlinx.coroutines.launch
import org.json.JSONObject

object SettingsCode {
    fun export(): String = "SG1:" + Base64.encodeToString(Store.exportSettings().toString().toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
    fun import(code: String): Boolean = runCatching {
        val c = code.trim().removePrefix("SG1:")
        Store.importSettings(JSONObject(String(Base64.decode(c, Base64.NO_WRAP or Base64.URL_SAFE))))
        ThemeState.reload()
        HomeState.columns = Store.columns
        true
    }.getOrDefault(false)

    fun qr(text: String, size: Int = 640): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    fun decode(b: Bitmap): String? = runCatching {
        val w = b.width; val h = b.height; val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, px)))).text
    }.getOrNull()
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showQr by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableLongStateOf(-1L) }
    var lockOn by remember { mutableStateOf(Store.lockOn) }
    var confirmLogout by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { cacheSize = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ctx.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } } }
    val pickQr = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
            }
            val text = bmp?.let { SettingsCode.decode(it) }
            val ok = text != null && SettingsCode.import(text)
            Toast.makeText(ctx, if (ok) "设置已导入" else "没认出二维码", Toast.LENGTH_SHORT).show()
        }
    }
    val nas = NasAccounts.current
    val dav = HomeState.source == "dav"
    val davAcc = Dav.current
    var cell by remember { mutableStateOf(ScanPolicy.allowCellular) }
    var noCharge by remember { mutableStateOf(ScanPolicy.allowNoCharge) }
    var forever by remember { mutableStateOf(Store.getStr("nas.deleteForever") == "1") }
    var syncOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(C.Bg).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 110.dp)) {
        if (Nav.stack.size > 1) Row(Modifier.statusBarsPadding().padding(start = 6.dp, top = 8.dp)) { IconButton(onClick = { Nav.pop() }) { Icon(LI.back(), null, tint = C.Text) } }
        else TopSwitch()
        Text("设置", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Text, letterSpacing = (-0.4).sp, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))

        // ---- 当前来源的账号
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(C.Card)).background(C.Surface)
            .clickable { if (dav) HomeState.showDav = true else HomeState.showConn = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(if (dav) davAcc?.title ?: "W" else nas?.user ?: "我", 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (dav) davAcc?.title ?: "未添加 WebDAV" else nas?.title ?: "未登录飞牛", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                val sub = if (dav) "WebDAV · " + (davAcc?.user ?: "") else "飞牛 · " + (nas?.user ?: "")
                Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            }
            Text("切换与管理", color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }

        // ---- 外观
        Section("外观")
        Card {
            Row(Modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Surface2).padding(3.dp)) {
                ThemeMode.values().forEach { m ->
                    val on = ThemeState.mode == m
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) C.Surface else Color.Transparent).clickable { ThemeState.pickMode(m) }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                        Text(m.title, color = if (on) C.Text else C.Sub, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            Div()
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("强调色", color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Accents.forEachIndexed { i, a ->
                    val on = ThemeState.accent == i
                    Box(Modifier.padding(start = 10.dp).size(26.dp).clip(CircleShape).border(if (on) 2.dp else 0.dp, if (on) C.Text else Color.Transparent, CircleShape)
                        .padding(if (on) 4.dp else 0.dp).clip(CircleShape).background(a.color).clickable { ThemeState.pickAccent(i) })
                }
            }
            Div()
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("照片每行", color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { val n = (HomeState.columns - 1).coerceAtLeast(3); HomeState.columns = n; Store.columns = n }) { Text("−", color = C.Text, fontSize = 20.sp) }
                Text("${HomeState.columns} 张", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                IconButton(onClick = { val n = (HomeState.columns + 1).coerceAtMost(6); HomeState.columns = n; Store.columns = n }) { Text("+", color = C.Text, fontSize = 20.sp) }
            }
        }

        // ---- 照片
        Section("照片")
        Card {
            // 1.0.9: WebDAV 账户 lives only in the header card (「切换与管理」)
            if (!dav) { NavRow(LI.backup(), "手机备份", if (Store.backupOn) "已开启，自动传到飞牛" else "未开启") { Nav.push(Route.Backup) }; Div() }
            NavRow(LI.swap(), "传输队列", if (Transfers.active > 0) "${Transfers.active} 个进行中" else "上传和下载记录") { Nav.push(Route.Transfers) }
            if (!dav) {
                Div()
                ToggleRow(LI.trash(), "彻底删除", if (forever) "删除时跳过飞牛回收站，无法恢复" else "删除的照片先进飞牛回收站，30 天内可恢复", forever) {
                    forever = it; Store.putStr("nas.deleteForever", if (it) "1" else "")
                }
            }
        }

        // ---- 影视
        Section("影音")
        Card {
            val ml = Movies.lib()
            NavRow(LI.film(), "媒体库与刮削", buildString {
                append(ml?.dirs?.size?.let { if (it == 0) "还没选影视文件夹" else "$it 个影视文件夹" } ?: "先添加来源")
                append(if (Tmdb.configured) " · TMDB 已配置" else " · 未填 TMDB 密钥")
            }) { Nav.push(Route.MovieLibrarySettings) }
            // 1.0.3 #4
            Div()
            val mu = Music.lib()
            NavRow(LI.music(), "音乐", (mu?.dirs?.size?.let { if (it == 0) "还没选音乐文件夹" else "$it 个音乐文件夹 · ${mu.tracks.size} 首" } ?: "先添加来源") +
                (if (MusicPrefs.online) " · 联网补全开" else " · 联网补全关")) { Nav.push(Route.MusicSettings) }
        }

        // ---- 整理与 AI
        Section("整理与 AI")
        Card {
            if (dav) {
                NavRow(LI.sparkle(), "大模型", if (AiConfig.configured) AiProviders.of(AiConfig.provider).name else "未配置，配置后自动分类和智能搜索") { Nav.push(Route.AiSettings) }
                Div()
                NavRow(LI.person(), "人脸识别", "置信度 ${"%.2f".format(FaceConfig.conf)} · 至少 ${FaceConfig.minPhotos} 张 · 差异 ${"%.2f".format(FaceConfig.diff)}") { HomeState.showFaceCfg = true }
            } else {
                NavRow(LI.sparkle(), "大模型", if (AiConfig.configured) AiProviders.of(AiConfig.provider).name + if (AiConfig.nasToo) " · 飞牛照片也整理" else "" else "未配置，可用于智能搜索") { Nav.push(Route.AiSettings) }
            }
        }

        // ---- 网络与电量：扫描、人脸、AI 分类、手机备份共用一套规则
        Section("网络与电量")
        Card {
            ToggleRow(LI.swap(), "允许使用移动网络", "本地分析、人脸识别、AI 分类和手机备份都会读取或上传照片，产生流量", cell) {
                cell = it; ScanPolicy.allowCellular = it; BackupScheduler.schedule(ctx); if (it) ScanWatch.resume(ctx)
            }
            Div()
            ToggleRow(LI.clock(), "不充电时也整理", "关掉更省电：本地分析、人脸识别和 AI 分类只在充电时继续", noCharge) {
                noCharge = it; ScanPolicy.allowNoCharge = it; if (it) ScanWatch.resume(ctx)
            }
            Div()
            NavRow(Icons.Rounded.BatteryChargingFull, "后台运行设置", "通知权限、电池优化、自启动（小米/OPPO/vivo/华为…）") { Nav.push(com.hark.shiguang.Route.X(ExtScreen.BgGuide)) }
            Text("扫完一遍后只处理新加入的照片；条件满足时自动继续，离开页面或切到后台也不停，进度在通知栏。",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 14.dp))
        }

        // ---- 隐私与存储
        Section("隐私与存储")
        Card {
            ToggleRow(LI.lock(), "应用锁", if (android.os.Build.VERSION.SDK_INT >= 28) "打开时验证指纹或锁屏密码" else "需要 Android 9 及以上", lockOn) {
                if (android.os.Build.VERSION.SDK_INT >= 28) { lockOn = it; Store.lockOn = it }
            }
            Div()
            NavRow(LI.lock(), "隐藏相册", "需验证指纹或锁屏密码才能查看") { Nav.push(Route.X(ExtScreen.HiddenAlbum)) }
            Div()
            NavRow(LI.layers(), "清理缓存", if (cacheSize >= 0) "已用 ${"%.0f".format(cacheSize / 1048576.0)} MB，会自动控制在 460 MB 内" else "") {
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
                    cacheSize = 0; Toast.makeText(ctx, "缓存已清理", Toast.LENGTH_SHORT).show()
                }
            }
            Div()
            NavRow(LI.copy(), "同步设置到其他手机", "二维码或设置码，不含密码") { syncOpen = true }
        }

        // ---- 关于
        Section("关于")
        Card {
            NavRow(LI.info(), "一维相册 ${BuildInfo.version} 公测版", "版本号 ${BuildInfo.code} · 第三方应用，与飞牛官方无关") {}
            Div()
            NavRow(LI.refresh(), "检查更新", if (AppUpdate.checking) "正在检查…" else "从开发者网盘获取新版本") {
                if (!AppUpdate.checking) scope.launch { AppUpdate.manualCheck()?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() } }
            }
            Div()
            NavRow(LI.sparkle(), "使用引导", "照片源、免费 AI 模型、影视刮削，一步步设置") { Onboarding.open(0) }
            Div()
            NavRow(Icons.Rounded.MailOutline, "反馈", "发邮件给开发者，自动附上版本和机型") { Feedback.email(ctx) }
            Div()
            NavRow(Icons.Rounded.Groups, "加入 QQ 群", "群号 ${Feedback.QQ_GROUP}，打不开 QQ 时自动复制") { Feedback.joinQq(ctx) }
            Div()
            NavRow(LI.upload(), "导出诊断日志", "脱敏后保存在本机，由你决定发给谁") { Diag.export(ctx) }
        }
        if (!dav && nas != null) {
            Spacer(Modifier.height(18.dp))
            Card { NavRow(LI.close(), "退出「${nas.title}」", nas.user, danger = true) { confirmLogout = true } }
        }
    }
    if (syncOpen) AlertDialog(onDismissRequest = { syncOpen = false }, containerColor = C.Surface, title = { Text("同步设置", color = C.Text) }, text = {
        Column {
            listOf("显示设置二维码" to { syncOpen = false; showQr = true }, "从截图识别二维码" to { syncOpen = false; pickQr.launch("image/*") }, "粘贴设置码导入" to { syncOpen = false; importOpen = true })
                .forEach { (t, f) -> Text(t, color = C.Text, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { f() }.padding(vertical = 13.dp, horizontal = 4.dp)) }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { syncOpen = false }) { Text("取消", color = C.Sub) } })
    if (confirmLogout) ConfirmDialog("退出「${nas?.title}」？", "会从本机移除这个账号的登录信息。", "退出", onDismiss = { confirmLogout = false }) {
        confirmLogout = false
        NasAccounts.current?.let { NasAccounts.remove(it.id) }
        HomeState.clear()
        if (NasAccounts.current == null) { Store.clear(); FnClient.token = ""; if (Dav.accounts.isNotEmpty()) { HomeState.useSource("dav"); Nav.reset(Route.Home) } else Nav.reset(Route.Login) }
        else Nav.reset(Route.Home)
    }
    if (showQr) {
        val code = remember { SettingsCode.export() }
        val bmp = remember(code) { SettingsCode.qr(code) }
        val clip = LocalClipboardManager.current
        AlertDialog(onDismissRequest = { showQr = false }, containerColor = C.Surface,
            title = { Text("设置二维码") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(bmp.asImageBitmap(), null, Modifier.size(240.dp).clip(RoundedCornerShape(12.dp)))
                    Text("不含密码。也可以复制设置码发给自己。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                }
            },
            confirmButton = { TextButton({ clip.setText(AnnotatedString(code)); Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show() }) { Text("复制设置码", color = C.Accent) } },
            dismissButton = { TextButton({ showQr = false }) { Text("关闭", color = C.Sub) } })
    }
    if (importOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { importOpen = false }, containerColor = C.Surface,
            title = { Text("粘贴设置码") },
            text = {
                Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp)).background(C.Surface2).padding(12.dp)) {
                    BasicTextField(text, { text = it }, textStyle = MaterialTheme.typography.bodyMedium.copy(color = C.Text), cursorBrush = SolidColor(C.Accent), modifier = Modifier.fillMaxSize())
                }
            },
            confirmButton = { TextButton({
                val ok = SettingsCode.import(text); importOpen = false
                Toast.makeText(ctx, if (ok) "设置已导入" else "设置码不对", Toast.LENGTH_SHORT).show()
            }) { Text("导入", color = C.Accent) } },
            dismissButton = { TextButton({ importOpen = false }) { Text("取消", color = C.Sub) } })
    }
}

@Composable
fun Section(t: String) {
    Text(t, color = C.Sub, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 22.dp, top = 24.dp, bottom = 8.dp))
}

@Composable
fun Div() = HorizontalDivider(color = C.Line, thickness = 0.6.dp, modifier = Modifier.padding(start = 64.dp))

@Composable
fun NavRow(icon: ImageVector, title: String, sub: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(if (danger) C.Danger.copy(alpha = 0.12f) else C.Surface2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (danger) C.Danger else C.Text, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (danger) C.Danger else C.Text, fontSize = 15.sp)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (!danger) Icon(LI.chevron(), null, tint = C.Faint, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun ToggleRow(icon: ImageVector, title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(C.Surface2), contentAlignment = Alignment.Center) { Icon(icon, null, tint = C.Text, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 15.sp)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 1.dp))
        }
        Spacer(Modifier.width(10.dp))
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedThumbColor = C.OnAccent, checkedTrackColor = C.Accent, uncheckedTrackColor = C.Surface2, uncheckedBorderColor = C.Line))
    }
}

@Composable
fun InputRow(label: String, value: String, hint: String, onChange: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
        Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface2).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(hint, color = C.Faint, fontSize = 14.sp)
            BasicTextField(value, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = C.Text, fontSize = 15.sp), cursorBrush = SolidColor(C.Accent), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(C.Card)).background(C.Surface), content = content)
}

@Composable
fun Avatar(name: String, size: androidx.compose.ui.unit.Dp, ring: Boolean = false) {
    Box(Modifier.size(size).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(C.Accent, C.Accent2)))
        .border(if (ring) 2.dp else 0.dp, if (ring) C.Text else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase().ifEmpty { "我" }, color = C.OnAccent, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.38f).sp)
    }
}
