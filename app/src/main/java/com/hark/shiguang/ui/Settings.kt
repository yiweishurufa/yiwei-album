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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.hark.shiguang.*
import com.hark.shiguang.data.Endpoint
import com.hark.shiguang.data.FnClient
import com.hark.shiguang.cloud.*
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheSize by remember { mutableLongStateOf(-1L) }
    var lockOn by remember { mutableStateOf(Store.lockOn) }
    LaunchedEffect(Unit) { cacheSize = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ctx.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } } }
    val nas = NasAccounts.current
    val dav = HomeState.source == "dav"
    val davAcc = Dav.current
    var bgOpen by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    var limitOpen by remember { mutableStateOf(false) }
    var limitMb by remember { mutableIntStateOf(Store.thumbCacheMb) }
    var davFolderSel by remember { mutableStateOf(Dav.lib()?.getSelectedDirs() ?: emptyList()) }
    var showDavFolders by remember { mutableStateOf(false) }
    // 第 4 批：平板/折叠屏——内容区最大宽度 720dp 居中，宽屏不再通栏拉伸。
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxHeight().appBackground().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 110.dp)) {
        if (Nav.stack.size > 1) Row(Modifier.statusBarsPadding().padding(start = 6.dp, top = 8.dp)) { IconButton(onClick = { Nav.pop() }) { Icon(LI.back(), null, tint = C.Text) } }
        else TopSwitch(search = SearchScope.None, showDate = false)   // 审查 6：设置页不放照片搜索和日期筛选
        Text("设置", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Text, letterSpacing = (-0.4).sp, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))

        // ---- 当前来源的账号
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().skeuoCard()
            .clickable { if (dav) Nav.push(Route.DavAccounts) else Nav.push(Route.ConnectionSettings) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
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
            FillSegmented(ThemeMode.values().map { it.title }, ThemeMode.values().indexOf(ThemeState.mode)) { ThemeState.pickMode(ThemeMode.values()[it]) }
            Div()
            // 用户 2026-10-23：界面风格 拟物（锤子风格，默认）/ 简洁
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("界面风格", color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            }
            FillSegmented(listOf("拟物", "简洁"), if (ThemeState.skeuo) 0 else 1) { ThemeState.pickStyle(it == 0) }
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

        // ---- 照片与整理（用户 2026-10-23：一件事只有一个家；副标题只放状态值）
        Section("照片与整理")
        Card {
            if (dav) NavRow(LI.folder(), "照片文件夹", value = if (davFolderSel.isEmpty()) "全部" else "已选 ${davFolderSel.size} 个") { showDavFolders = true }
            else NavRow(LI.backup(), "手机备份", value = if (Store.backupOn) "已开启" else "未开启") { Nav.push(Route.Backup) }
            Div()
            // 飞牛的「智能整理」只有 AI 一项，直接进 AI 页，不多走一层
            NavRow(LI.sparkle(), "智能整理", value = if (AiConfig.configured) "AI 已配置" else "AI 未配置") { Nav.push(if (dav) Route.SmartSettings else Route.AiSettings) }
            Div()
            NavRow(LI.clock(), "后台整理", value = ScanPolicy.label()) { bgOpen = true }
        }

        // ---- 影音（1.1.0：TMDB、音乐刮削、影视/音乐文件夹合并为一个入口）
        Section("影音")
        Card {
            val ml = Movies.lib(); val mu = Music.lib()
            val folders = (ml?.dirs?.size ?: 0) + (mu?.dirs?.size ?: 0)
            NavRow(LI.film(), "影音设置", value = if (!Tmdb.configured) "TMDB 未配置" else if (folders > 0) "$folders 个文件夹" else null) { Nav.push(Route.MediaSettings) }
        }

        // ---- 安全与存储
        Section("安全与存储")
        Card {
            // H18: tell the user when passwords could not be stored encrypted on this phone（只留一行危险提示，细节放 ⓘ）
            if (Store.insecure || com.hark.shiguang.cloud.CloudAccounts.insecure) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("本机无法加密保存密码", color = C.Danger, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    InfoDot("这台手机的加密存储不可用，飞牛 / WebDAV 的密码和登录凭据只能以普通方式保存在本机。介意的话请不要在这台手机上保存密码，或在「设置 → 反馈与帮助 → 导出诊断日志」发给开发者。")
                }
            }
            val lockOk = android.os.Build.VERSION.SDK_INT >= 28
            ToggleRow(LI.lock(), "应用锁", if (lockOk) null else "需要 Android 9+", lockOn, enabled = lockOk) {
                if (lockOk) { lockOn = it; Store.lockOn = it }
            }
            Div()
            NavRow(LI.layers(), "清理缓存", value = if (cacheSize >= 0) "${"%.0f".format(cacheSize / 1048576.0)} MB" else null) {
                scope.launch {
                    // B38: never delete the diagnostic log, downloaded update packages, or crash reports —
                    // they are the evidence when something goes wrong, and the update would need re-downloading.
                    val left = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        ctx.cacheDir.listFiles()?.filter { it.name != "diag" && it.name != "update" && !it.name.startsWith("crash") }?.forEach { it.deleteRecursively() }
                        // H13: the in-memory url tables go too (redirect targets + entries of photos no longer in any library)
                        DavThumb.clearUrlCache(); PlayHttp.resolved.clear()
                        runCatching { CloudPhotos.trimTo(Dav.loadedLibs().flatMap { it.photos.toList() }) }.onFailure { Diag.e("CACHE", it) }
                        ctx.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    }
                    cacheSize = left
                    Toast.makeText(ctx, "缓存已清理", Toast.LENGTH_SHORT).show()
                }
            }
            Div()
            NavRow(LI.layers(), "缩略图缓存上限", value = CacheLimit.label(limitMb)) { limitOpen = true }
        }

        // ---- 通用
        Section("通用")
        Card {
            NavRow(LI.refresh(), "检查更新", value = if (AppUpdate.checking) "检查中…" else AppUpdate.offer?.let { "有新版本 ${it.version}" }) {
                if (!AppUpdate.checking) scope.launch { AppUpdate.manualCheck()?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() } }
            }
            Div()
            NavRow(LI.sparkle(), "使用引导") { Onboarding.open(0) }
            Div()
            NavRow(Icons.Rounded.MailOutline, "反馈与帮助") { helpOpen = true }
            Div()
            NavRow(LI.settings(), "高级") { Nav.push(Route.AdvancedSettings) }
        }
        // 1.1.0: 账号入口只保留顶部卡片（切换与管理 → 退出 / 移除都在账号页里）
    }
    }
    if (limitOpen) ActionListDialog("缩略图缓存上限", sub = "超过上限会自动删掉最久没看的缩略图，重启 App 后生效", onDismiss = { limitOpen = false },
        actions = CacheLimit.OPTIONS_MB.map { mb -> (CacheLimit.label(mb) + if (mb == limitMb) "  ✓" else "") to { Store.thumbCacheMb = mb; limitMb = mb; limitOpen = false } })
    if (bgOpen) BackgroundPolicyDialog { bgOpen = false }
    if (helpOpen) ActionListDialog("反馈与帮助", onDismiss = { helpOpen = false }, actions = listOf(
        "发邮件给开发者" to { helpOpen = false; Feedback.email(ctx) },
        "加入 QQ 群（${Feedback.QQ_GROUP}）" to { helpOpen = false; Feedback.joinQq(ctx) },
        "导出诊断日志" to { helpOpen = false; Diag.export(ctx) },
    ))
    if (showDavFolders) {
        DavFolderSelectDialog(
            initial = davFolderSel,
            onDismiss = { showDavFolders = false },
            onSave = { sel ->
                val lib = Dav.lib()
                if (lib != null) {
                    lib.setSelectedDirs(sel)
                    davFolderSel = sel
                    scope.launch { lib.scan() }
                }
                showDavFolders = false
                Toast.makeText(ctx, "已保存，将只显示选中文件夹的照片", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun DavFolderSelectDialog(initial: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val lib = Dav.lib()
    var selected by remember { mutableStateOf(initial.toSet()) }
    val folders = lib?.folders
    LaunchedEffect(Unit) {
        val l = lib ?: return@LaunchedEffect
        if (l.folders == null) l.refreshFolders()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text("WebDAV 照片文件夹", color = C.Text) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("不选 = 全部文件夹；勾选后只显示选中文件夹里的照片。", style = MaterialTheme.typography.bodySmall, color = C.Sub)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { selected = emptySet() }.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = selected.isEmpty(), onCheckedChange = { if (it) selected = emptySet() })
                    Spacer(Modifier.width(8.dp))
                    Text("全部文件夹", color = C.Text, fontSize = 15.sp)
                }
                HorizontalDivider(color = C.Line, thickness = 0.6.dp, modifier = Modifier.padding(vertical = 6.dp))
                when {
                    lib == null -> Text("还没有 WebDAV", color = C.Sub, fontSize = 14.sp)
                    folders == null -> Text("正在加载文件夹…", color = C.Sub, fontSize = 14.sp)
                    folders.isEmpty() -> Text("根目录下没有文件夹", color = C.Sub, fontSize = 14.sp)
                    else -> folders.sortedBy { it.name }.forEach { f ->
                        val checked = f.path in selected
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                            selected = if (checked) selected - f.path else selected + f.path
                        }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = { on ->
                                selected = if (on) selected + f.path else selected - f.path
                            })
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, color = C.Text, fontSize = 15.sp, maxLines = 1)
                                Text(f.path, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { selected = (folders ?: emptyList()).map { it.path }.toSet() }) { Text("全选", color = C.Accent) }
                    TextButton(onClick = { selected = emptySet() }) { Text("清空", color = C.Accent) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected.toList()) }) { Text("保存", color = C.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = C.Sub) } }
    )
}

/** Settings group label (small grey text). Deliberately NOT [SectionTitle]: that one is a titleMedium row with a trailing count for content lists — merging them would restyle every Settings section. */
@Composable
fun Section(t: String, info: String? = null) {
    Row(Modifier.padding(start = 22.dp, end = 12.dp, top = 24.dp, bottom = if (info == null) 8.dp else 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t, color = C.Sub, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (info != null) InfoDot(info, t)
    }
}

/** 卡片里的细分隔线。拟物时是「刻线」：一道暗线下面跟一道高光线，像压在纸上的折痕。 */
@Composable
fun Div() {
    if (!Skeuo.on) HorizontalDivider(color = C.Line, thickness = 0.6.dp, modifier = Modifier.padding(start = 64.dp))
    else Column(Modifier.padding(start = 64.dp)) {
        HorizontalDivider(color = C.Line, thickness = 0.6.dp)
        HorizontalDivider(color = Skeuo.highlight.copy(alpha = Skeuo.highlight.alpha * 0.7f), thickness = 0.6.dp)
    }
}

/** 撑满一行的分段控件（主题、界面风格）：拟物时是内凹的槽 + 凸起的选中块。 */
@Composable
fun FillSegmented(items: List<String>, sel: Int, onPick: (Int) -> Unit) {
    Row(Modifier.padding(12.dp).fillMaxWidth().skeuoInset(RoundedCornerShape(12.dp)).padding(3.dp)) {
        items.forEachIndexed { i, t ->
            val on = i == sel
            Box(Modifier.weight(1f).then(if (on) Modifier.skeuoPressed(false, RoundedCornerShape(10.dp), C.Surface, Elevation.pressed + 1.dp) else Modifier.clip(RoundedCornerShape(10.dp)))
                .clickable { onPick(i) }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                Text(t, color = if (on) C.Text else C.Sub, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/**
 * 用户 2026-10-23：功能说明不再常驻成小字；确实需要解释的，放一个 ⓘ，点开用对话框看。
 */
@Composable
fun InfoDot(text: String, title: String = "说明") {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.size(32.dp).clip(CircleShape).clickable { open = true }, contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Info, "说明", tint = C.Faint, modifier = Modifier.size(16.dp))
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, containerColor = C.Surface,
        title = { Text(title, color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = { Text(text, color = C.Sub, fontSize = 14.sp, lineHeight = 21.sp) },
        confirmButton = { TextButton(onClick = { open = false }) { Text("知道了", color = C.Accent) } })
}

/**
 * 设置行。用户 2026-10-23：副标题可选，只用来显示状态值或危险提示（一行）；功能说明改用 [info]（ⓘ）。
 * [value] 是右侧灰色状态值（「已开启」「320 MB」）；[alert] 让副标题变红；[danger] 是整行危险操作（退出、清空）。
 */
@Composable
fun NavRow(icon: ImageVector, title: String, sub: String? = null, danger: Boolean = false, value: String? = null, alert: Boolean = false,
           info: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        RowIcon(icon, danger)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = if (danger) C.Danger else C.Text, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (info != null) InfoDot(info, title)
            }
            if (!sub.isNullOrEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = if (alert) C.Danger else C.Sub, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (!value.isNullOrEmpty()) Text(value, color = C.Sub, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 10.dp).widthIn(max = 150.dp), overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (!danger) Icon(LI.chevron(), null, tint = C.Faint, modifier = Modifier.padding(start = 4.dp).size(18.dp))
    }
}

/** 开关行，规则同 [NavRow]：[sub] 只放状态或危险提示（[alert] 变红），说明放 [info]；[enabled] = false 时整行置灰。 */
@Composable
fun ToggleRow(icon: ImageVector, title: String, sub: String? = null, on: Boolean, enabled: Boolean = true, alert: Boolean = false, info: String? = null,
              onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled) { onChange(!on) }.padding(horizontal = 16.dp, vertical = 9.dp)
        .then(if (enabled) Modifier else Modifier.alpha(0.5f)), verticalAlignment = Alignment.CenterVertically) {
        RowIcon(icon, false)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = C.Text, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (info != null) InfoDot(info, title)
            }
            if (!sub.isNullOrEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = if (alert) C.Danger else C.Sub, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
        }
        Spacer(Modifier.width(10.dp))
        SkeuoSwitch(on, enabled, onChange)
    }
}

/** 设置行左侧的小图标底。 */
@Composable
internal fun RowIcon(icon: ImageVector, danger: Boolean) {
    // 拟物：小图标底是微凹的槽，图标用次要色、线条细，颜色克制
    Box(Modifier.size(32.dp).then(if (danger) Modifier.clip(RoundedCornerShape(9.dp)).background(C.Danger.copy(alpha = 0.12f)) else Modifier.skeuoInset(RoundedCornerShape(9.dp))),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (danger) C.Danger else if (Skeuo.on) C.Sub else C.Text, modifier = Modifier.size(18.dp))
    }
}

/** 全局开关：拟物风格是凹槽 + 凸起圆钮（[SkeuoToggle]），简洁风格是 Material 开关。 */
@Composable
fun SkeuoSwitch(on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    if (Skeuo.on) SkeuoToggle(on, enabled, onChange)
    else Switch(on, onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedThumbColor = C.OnAccent, checkedTrackColor = C.Accent, uncheckedTrackColor = C.Surface2, uncheckedBorderColor = C.Line))
}

@Composable
fun InputRow(label: String, value: String, hint: String, onChange: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
        Box(Modifier.fillMaxWidth().height(48.dp).skeuoInset(RoundedCornerShape(14.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(hint, color = C.Faint, fontSize = 14.sp)
            BasicTextField(value, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = C.Text, fontSize = 15.sp), cursorBrush = SolidColor(C.Accent), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().skeuoCard(), content = content)
}

@Composable
fun Avatar(name: String, size: androidx.compose.ui.unit.Dp, ring: Boolean = false) {
    Box(Modifier.size(size).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(C.Accent, C.Accent2)))
        .border(if (ring) 2.dp else 0.dp, if (ring) C.Text else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase().ifEmpty { "我" }, color = C.OnAccent, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.38f).sp)
    }
}

/** H19: per-host switch for user-installed CA certificates (self-signed NAS / WebDAV). 用户 2026-10-23 改名「自签名证书的 NAS 地址」并移进高级。 */
@Composable
fun UserCaDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val hosts = remember { (TlsTrust.accountHosts() + TlsTrust.allowedHosts()).distinct().sorted() }
    var allowed by remember { mutableStateOf(TlsTrust.allowedHosts()) }
    val changed = remember { mutableStateOf(false) }
    val close = { if (changed.value) Toast.makeText(ctx, "已保存，新建的连接生效", Toast.LENGTH_SHORT).show(); onDismiss() }
    AlertDialog(onDismissRequest = close, containerColor = C.Surface, title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(CA_TITLE, color = C.Text, modifier = Modifier.weight(1f, fill = false))
            InfoDot(CA_INFO, CA_TITLE)
        }
    }, text = {
        Column {
            if (hosts.isEmpty()) Text("还没有使用 https 的账户", color = C.Sub, fontSize = 14.sp)
            hosts.forEach { h ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(h, color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    SkeuoSwitch(h in allowed) { on -> TlsTrust.setAllowed(h, on); allowed = TlsTrust.allowedHosts(); changed.value = true }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("完成", color = C.Accent) } })
}

private const val CA_TITLE = "自签名证书的 NAS 地址"
private const val CA_INFO = "手机「设置 → 安全 → 加密与凭据」里自己安装的证书，只对这里打开的地址生效；其他地址只认系统证书，抓包工具装的证书无法冒充它们。只有使用自签名证书的 NAS / WebDAV 才需要打开。改动后新建的连接生效，已打开的页面可下拉刷新。"

/**
 * 用户 2026-10-23：「允许移动网络」「不充电时也整理」合并成一行「后台整理」，点开是这个弹窗。
 * 存储键（scan.cell / scan.nocharge）和默认值都不变，见 [ScanPolicy]。
 */
@Composable
fun BackgroundPolicyDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var cell by remember { mutableStateOf(ScanPolicy.allowCellular) }
    var noCharge by remember { mutableStateOf(ScanPolicy.allowNoCharge) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface, title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("后台整理", color = C.Text)
            InfoDot("本地分析、人脸识别、AI 分类和手机备份共用这套规则。扫完一遍后只处理新加入的照片；条件满足时自动继续，离开页面或切到后台也不停，进度在通知栏。", "后台整理")
        }
    }, text = {
        Column {
            CheckRow("允许使用移动网络", if (cell) "会产生流量" else null, cell) {
                cell = it; ScanPolicy.allowCellular = it; BackupScheduler.schedule(ctx); if (it) ScanWatch.resume(ctx)
            }
            CheckRow("不充电时也整理", if (noCharge) "更耗电" else null, noCharge) {
                noCharge = it; ScanPolicy.allowNoCharge = it; BackupScheduler.schedule(ctx); if (it) ScanWatch.resume(ctx)
            }
            // 用户 2026-10-23：后台运行设置不再常驻，只在通知没开或电池优化没关时出现这一行
            if (remember { BgGuidePrompt.permissionsMissing(ctx) }) Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp))
                .clickable { onDismiss(); Nav.push(Route.X(ExtScreen.BgGuide)) }.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("后台可能被系统停掉", color = C.Danger, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("去设置", color = C.Accent, fontSize = 14.sp)
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成", color = C.Accent) } })
}

@Composable
private fun CheckRow(title: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(!on) }.heightIn(min = 52.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = on, onCheckedChange = onChange, colors = CheckboxDefaults.colors(checkedColor = C.Accent, checkmarkColor = C.OnAccent, uncheckedColor = C.Faint))
        Column(Modifier.padding(start = 6.dp)) {
            Text(title, color = C.Text, fontSize = 15.sp)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * 用户 2026-10-23 新二级页「智能整理」（仅 WebDAV；飞牛只有 AI 一项，直接进 AI 页）：
 * AI 大模型 / 人脸识别 / 识别图中文字 / 自动旅行相册。存储键都不变。
 */
@Composable
fun SmartSettingsScreen() {
    val ctx = LocalContext.current
    val lib = Dav.lib()
    Page("智能整理") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 40.dp)) {
            Spacer(Modifier.height(8.dp))
            Card {
                NavRow(LI.sparkle(), "AI 大模型", value = if (AiConfig.configured) "已配置 · " + AiProviders.of(AiConfig.provider).name else "未配置") { Nav.push(Route.AiSettings) }
                if (lib != null) {
                    val fl = remember(lib.accountId) { Faces.lib(lib.accountId) }
                    Div()
                    NavRow(LI.person(), "人脸识别", value = if (fl.persons.isNotEmpty()) "已识别 ${fl.persons.size} 人" else null) { HomeState.showFaceCfg = true }
                    Div()
                    LaunchedEffect(lib.accountId) { OcrRunner.refresh(lib) }
                    ToggleRow(LI.search(), "识别图中文字", ocrStatus(lib), OcrRunner.enabled,
                        info = "在手机上识别照片里的字，搜索时能按图中文字找到照片。只在本机识别，用缩略图，不下原图；小字可能认不出。还没有缩略图的照片，在照片页浏览过后会补上。") { OcrRunner.set(ctx, it) }
                    Div()
                    ToggleRow(LI.pin(), "自动旅行相册", on = TravelAlbums.enabled,
                        info = "按拍摄时间和位置，把离常住地 50 公里以上、15 张以上的出行整理成相册，显示在「发现 → 回忆」。") { TravelAlbums.enabled = it }
                }
            }
        }
    }
}

/**
 * 用户 2026-10-23 新二级页「高级」：低频、有风险或排障用的选项。
 * 存储键都不变（TlsTrust 主机列表、nas.deleteForever、feat.workmanager）。
 */
@Composable
fun AdvancedSettingsScreen() {
    val ctx = LocalContext.current
    val dav = HomeState.source == "dav"
    var caOpen by remember { mutableStateOf(false) }
    var forever by remember { mutableStateOf(Store.getStr("nas.deleteForever") == "1") }
    var wmOn by remember { mutableStateOf(com.hark.shiguang.Features.workManager) }
    Page("高级") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 40.dp)) {
            Spacer(Modifier.height(8.dp))
            Card {
                NavRow(LI.lock(), CA_TITLE, value = TlsTrust.allowedHosts().size.takeIf { it > 0 }?.let { "$it 个" }) { caOpen = true }
                if (!dav) {
                    Div()
                    ToggleRow(LI.trash(), "彻底删除", if (forever) "删除后无法恢复" else null, forever, alert = forever,
                        info = "打开后删除照片时跳过飞牛回收站，无法恢复；关闭时删除的照片先进飞牛回收站，30 天内可恢复。") {
                        forever = it; Store.putStr("nas.deleteForever", if (it) "1" else "")
                    }
                }
                Div()
                ToggleRow(LI.clock(), "系统定时后台整理", on = wmOn,
                    info = "由系统在满足「后台整理」的网络和充电条件时定时扫描新照片、整理和备份，重启手机后也会继续。只在排查问题时才需要关掉。") {
                    wmOn = it; com.hark.shiguang.Features.workManager = it; BackupScheduler.schedule(ctx)
                }
            }
            Text("一维相册是第三方应用，与飞牛官方无关", color = C.Faint, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
    if (caOpen) UserCaDialog { caOpen = false }
}

/** 「识别图中文字」的状态值：一行，只说进度。 */
private fun ocrStatus(lib: DavLib?): String? {
    if (!OcrRunner.enabled) return null
    val cur = lib != null && OcrRunner.countsFor == lib.accountId
    if (!cur || OcrRunner.total == 0) return null
    return "已识别 ${OcrRunner.done.coerceAtMost(OcrRunner.total)} / ${OcrRunner.total}" + if (OcrRunner.running) " · 识别中" else ""
}
