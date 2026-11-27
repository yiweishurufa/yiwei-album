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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
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
    LaunchedEffect(StorageUsage.version) { cacheSize = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { StorageUsage.clearableCacheSize(ctx.cacheDir) } }
    val nas = NasAccounts.current
    val dav = HomeState.source == "dav"
    val davAcc = Dav.current
    var bgOpen by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    var davFolderSel by remember { mutableStateOf(Dav.lib()?.getSelectedDirs() ?: emptyList()) }
    var showDavFolders by remember { mutableStateOf(false) }
    // 第 4 批：平板/折叠屏——内容区最大宽度 720dp 居中，宽屏不再通栏拉伸。
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxHeight().appBackground().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 110.dp)) {
        if (Nav.stack.size > 1) Row(Modifier.statusBarsPadding().padding(start = 6.dp, top = 8.dp)) { IconButton(onClick = { Nav.pop() }) { Icon(LI.back(), "返回", tint = C.Text) } }
        else TopSwitch(search = SearchScope.None, showDate = false)   // 审查 6：设置页不放照片搜索和日期筛选
        // 1.7.9 v2：页面大标题 displaySmall（40sp 粗体、负字距）
        Text("设置", style = MaterialTheme.typography.displaySmall, color = C.Text, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 6.dp).semantics { heading() })

        // ---- 当前来源的账号（1.7.9 v2：玻璃账号卡——带强调色光晕的大头像、20sp 粗体名字、来源胶囊、右侧「管理」玻璃胶囊）
        AccountCard(
            name = if (dav) davAcc?.title ?: "未添加 WebDAV" else nas?.title ?: "未登录飞牛",
            avatar = if (dav) davAcc?.title ?: "W" else nas?.user ?: "我",
            kind = if (dav) "WebDAV" else "飞牛",
            user = if (dav) davAcc?.user.orEmpty() else nas?.user.orEmpty(),
        ) { if (dav) Nav.push(Route.DavAccounts) else Nav.push(Route.ConnectionSettings) }

        // ---- 外观
        Section("外观")
        Card {
            FillSegmented(ThemeMode.values().map { it.title }, ThemeMode.values().indexOf(ThemeState.mode)) { ThemeState.pickMode(ThemeMode.values()[it]) }
            Div()
            // 1.7.0：界面风格 玻璃（默认，半透明 + 模糊）/ 简洁（不模糊、纯色，低端机 / 省电）。存储键不变
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("界面风格", color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(if (ThemeState.skeuo) "半透明与模糊" else "不模糊，更省电", color = C.Sub, style = MaterialTheme.typography.bodySmall)
            }
            FillSegmented(listOf("玻璃", "简洁"), if (ThemeState.skeuo) 0 else 1) { ThemeState.pickStyle(it == 0) }
            Div()
            // 1.7.9 v2：强调色 = 一排玻璃色球（球面高光 + 选中时外圈光环、放大、中间一个勾），标题单独一行
            Text("强调色", color = C.Text, fontSize = 15.sp, modifier = Modifier.padding(start = 18.dp, top = 14.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Accents.forEachIndexed { i, a -> AccentOrb(a, ThemeState.accent == i) { ThemeState.pickAccent(i) } }
            }
            Div()
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("照片每行", color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { val n = (HomeState.columns - 1).coerceAtLeast(3); HomeState.columns = n; Store.columns = n }) { Text("−", color = C.Text, fontSize = 20.sp) }
                Text("${HomeState.columns} 张", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                IconButton(onClick = { val n = (HomeState.columns + 1).coerceAtMost(6); HomeState.columns = n; Store.columns = n }) { Text("+", color = C.Text, fontSize = 20.sp) }
            }
        }

        // ---- 照片与影音（1.7.17 功能审计：「照片与整理」「影音」两组合并成一组，少一个分组标题；一件事只有一个家，副标题只放状态值）
        Section("照片与影音")
        Card {
            if (dav) NavRow(LI.folder(), "照片文件夹", value = if (davFolderSel.isEmpty()) "全部" else "已选 ${davFolderSel.size} 个") { showDavFolders = true }
            else NavRow(LI.backup(), "手机备份", value = if (Store.backupOn) "已开启" else "未开启") { Nav.push(Route.Backup) }
            Div()
            // 飞牛的「智能整理」只有 AI 一项，直接进 AI 页，不多走一层
            NavRow(LI.sparkle(), "智能整理", value = if (AiConfig.configured) "AI 已配置" else "AI 未配置") { Nav.push(if (dav) Route.SmartSettings else Route.AiSettings) }
            Div()
            NavRow(LI.clock(), "后台整理", value = ScanPolicy.label()) { bgOpen = true }
            Div()
            // 1.1.0：TMDB、音乐刮削、影视 / 音乐文件夹合并为一个入口
            val ml = Movies.lib(); val mu = Music.lib()
            val folders = (ml?.dirs?.size ?: 0) + (mu?.dirs?.size ?: 0)
            NavRow(LI.film(), "影音设置", value = if (!Tmdb.configured) "TMDB 未配置" else if (folders > 0) "$folders 个文件夹" else null) { Nav.push(Route.MediaSettings) }
        }

        // ---- 隐私与存储
        Section("隐私与存储")
        Card {
            // H18: tell the user when passwords could not be stored encrypted on this phone（只留一行危险提示，细节放 ⓘ）
            if (Store.insecure || com.hark.shiguang.cloud.CloudAccounts.insecure) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("本机无法加密保存密码", color = C.Danger, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    InfoDot("这台手机的加密存储不可用，飞牛 / WebDAV 的密码和登录凭据只能以普通方式保存在本机。介意的话请不要在这台手机上保存密码，或在「设置 → 关于 → 反馈与帮助」导出诊断日志发给开发者。")
                }
            }
            val lockOk = android.os.Build.VERSION.SDK_INT >= 28
            ToggleRow(LI.lock(), "应用锁", if (lockOk) null else "需要 Android 9+", lockOn, enabled = lockOk) {
                if (lockOk) { lockOn = it; Store.lockOn = it }
            }
            Div()
            // 1.7.17 功能审计：「存储空间」「清理缓存」「缩略图缓存上限」三行做的是同一件事 → 只留「存储空间」一个入口，
            // 清理和缓存上限都在页里（存储键 thumbCacheMb 不变）。右侧直接显示可清理的大小。
            NavRow(KI.drive(), "存储空间", value = when { cacheSize == 0L -> "无缓存"; cacheSize > 0 -> "可清理 ${sizeLabel(cacheSize)}"; else -> null },
                valueLoading = cacheSize < 0) { Nav.push(Route.X(ExtScreen.Storage)) }
            Div()
            NavRow(LI.settings(), "高级") { Nav.push(Route.AdvancedSettings) }
        }

        // ---- 关于（1.7.17：原「通用」组；「导出诊断日志」只留在「反馈与帮助」里，不再单独一行）
        Section("关于")
        Card {
            NavRow(LI.refresh(), "检查更新", value = if (AppUpdate.checking) "检查中…" else AppUpdate.offer?.let { "有新版本 ${it.version}" } ?: BuildInfo.version) {
                if (!AppUpdate.checking) scope.launch { AppUpdate.manualCheck()?.let { toast(ctx, it) } }
            }
            Div()
            // 1.7.17：每个版本改了什么（随包附带的 CHANGELOG.md）
            NavRow(KI.feed(), "更新内容") { Nav.push(Route.X(ExtScreen.Changelog)) }
            Div()
            NavRow(LI.sparkle(), "使用引导") { Onboarding.open(0) }
            Div()
            NavRow(KI.mail(), "反馈与帮助", value = "邮件 · QQ 群 · 诊断日志") { helpOpen = true }
            Div()
            // 1.6.0：第三方图标 / 字体的许可（Kubai Icons MIT 要求附版权声明；Allo 是 SIL OFL 1.1）
            var licOpen by remember { mutableStateOf(false) }
            NavRow(LI.info(), "开源许可") { licOpen = true }
            if (licOpen) AppAlertDialog(onDismissRequest = { licOpen = false }, containerColor = C.Surface,
                title = { Text("开源许可", style = MaterialTheme.typography.titleLarge) },
                text = { Text(OPEN_SOURCE_NOTICE, color = C.Sub, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { licOpen = false }) { Text("知道了", color = C.Accent) } })
        }
        // 1.1.0: 账号入口只保留顶部卡片（切换与管理 → 退出 / 移除都在账号页里）
    }
    }
    if (bgOpen) BackgroundPolicyDialog { bgOpen = false }
    if (helpOpen) ActionListDialog("反馈与帮助", onDismiss = { helpOpen = false }, actions = listOf(
        "发邮件给开发者" to { helpOpen = false; Feedback.email(ctx) },
        "加入 QQ 群（${Feedback.QQ_GROUP}）" to { helpOpen = false; Feedback.joinQq(ctx) },
        // 「导出诊断日志」原来在设置首页另有一行，1.7.17 合并到这里（同一个导出，IO 线程写文件后调起分享）
        "导出诊断日志（遇到问题时发给开发者）" to {
            helpOpen = false
            scope.launch {
                val f = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Diag.writeExport() } }.getOrNull()
                if (f == null) toast(ctx, "导出失败") else Diag.share(ctx, f)
            }
        },
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
                    scope.launch { lib.scan(reason = "更改照片文件夹", user = true) }
                }
                showDavFolders = false
                toast(ctx, "已保存，将只显示选中文件夹的照片")
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
    AppAlertDialog(
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
    // 1.7.0 v2：分组标题大号粗体左对齐（titleLarge 20sp Bold、负字距、正文色），与卡片左沿对齐（20），上 32 下 10
    Row(Modifier.semantics { heading() }.padding(start = 20.dp, end = 12.dp, top = 32.dp, bottom = if (info == null) 10.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t, color = C.Text, style = MaterialTheme.typography.titleLarge)
        if (info != null) InfoDot(info, t)
    }
}

/** 卡片里的发丝分隔线，从行标题的起点开始（图标列不画线）。 */
@Composable
fun Div() {
    HorizontalDivider(color = C.Line, thickness = 0.5.dp, modifier = Modifier.padding(start = 60.dp))
}

/** 撑满一行的分段控件（主题、界面风格）：拟物时是内凹的槽 + 凸起的选中块。 */
@Composable
fun FillSegmented(items: List<String>, sel: Int, onPick: (Int) -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    Row(Modifier.padding(12.dp).fillMaxWidth().skeuoInset(RoundedCornerShape(Radius.m)).padding(3.dp)) {
        items.forEachIndexed { i, t ->
            val on = i == sel
            val fg by androidx.compose.animation.animateColorAsState(if (on) C.Text else C.Sub, Motion.state(), label = "segFg")
            Box(Modifier.weight(1f).then(if (on) Modifier.skeuoPressed(false, RoundedCornerShape(Radius.m - 3.dp), if (Skeuo.on) C.Surface else C.Bg, Elevation.pressed) else Modifier.clip(RoundedCornerShape(Radius.m - 3.dp)))
                .clickable(role = androidx.compose.ui.semantics.Role.Tab) { if (!on) Haptics.tick(view); onPick(i) }.semantics { selected = on }
                .heightIn(min = 40.dp).padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                Text(t, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
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
    // 1.6.6：Kubai「info」图标、触控区补到 48dp（视觉仍是 32dp 的小圆）、读屏读出说明的标题
    Box(Modifier.minimumInteractiveComponentSize().size(32.dp).clip(CircleShape).clickable(onClickLabel = "查看说明", role = androidx.compose.ui.semantics.Role.Button) { open = true }
        .semantics { contentDescription = "$title 说明" }, contentAlignment = Alignment.Center) {
        Icon(KI.info(), null, tint = C.Sub, modifier = Modifier.size(18.dp))
    }
    if (open) AppAlertDialog(onDismissRequest = { open = false }, containerColor = C.Surface,
        title = { Text(title, color = C.Text, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text, color = C.Sub, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = { open = false }) { Text("知道了", color = C.Accent) } })
}

/**
 * 设置行。用户 2026-10-23：副标题可选，只用来显示状态值或危险提示（一行）；功能说明改用 [info]（ⓘ）。
 * [value] 是右侧灰色状态值（「已开启」「320 MB」）；[alert] 让副标题变红；[danger] 是整行危险操作（退出、清空）。
 */
@Composable
fun NavRow(icon: ImageVector, title: String, sub: String? = null, danger: Boolean = false, value: String? = null, alert: Boolean = false,
           info: String? = null, valueLoading: Boolean = false, onClick: () -> Unit) {
    // 1.6.2：读屏角色「按钮」（发现页入口行并入 NavRow 后保持原来的按钮角色）
    Row(Modifier.fillMaxWidth().heightIn(min = Space.row).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RowIcon(icon, danger)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = if (danger) C.Danger else C.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (info != null) InfoDot(info, title)
            }
            if (!sub.isNullOrEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = if (alert) C.Danger else C.Sub, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        // 1.7.23：右侧数值还在统计时显示骨架小条（以前是空着、算完突然冒出来）
        if (valueLoading) SkeletonBar(56.dp, 12.dp, modifier = Modifier.padding(start = 10.dp))
        else if (!value.isNullOrEmpty()) Text(value, color = C.Sub, style = MaterialTheme.typography.bodyMedium.merge(Type.num), maxLines = 1, modifier = Modifier.padding(start = 10.dp).widthIn(max = 150.dp), overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (!danger) Icon(KI.chevron(), null, tint = C.Faint, modifier = Modifier.padding(start = 4.dp).size(18.dp))
    }
}

/** 开关行，规则同 [NavRow]：[sub] 只放状态或危险提示（[alert] 变红），说明放 [info]；[enabled] = false 时整行置灰。 */
@Composable
fun ToggleRow(icon: ImageVector, title: String, sub: String? = null, on: Boolean, enabled: Boolean = true, alert: Boolean = false, info: String? = null,
              onChange: (Boolean) -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    // 整行就是一个开关（读屏读「标题，开关，已开启」），点哪里都能切
    Row(Modifier.fillMaxWidth().heightIn(min = Space.row)
        .toggleable(on, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch) { Haptics.toggle(view, it); onChange(it) }
        .padding(horizontal = 16.dp, vertical = 9.dp)
        .then(if (enabled) Modifier else Modifier.alpha(0.5f)), verticalAlignment = Alignment.CenterVertically) {
        RowIcon(icon, false)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = C.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (info != null) InfoDot(info, title)
            }
            if (!sub.isNullOrEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = if (alert) C.Danger else C.Sub, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
        }
        Spacer(Modifier.width(10.dp))
        // 开关本身不再单独接收点击（整行已是 toggleable），避免读屏读两遍
        Box(Modifier.clearAndSetSemantics {}) { SkeuoSwitch(on, enabled) { Haptics.toggle(view, it); onChange(it) } }
    }
}

/** 设置行左侧的小图标底。 */
@Composable
internal fun RowIcon(icon: ImageVector, danger: Boolean) {
    // 1.6.0：图标跟随文字角色的颜色（次要色），不再放进小方块；固定 32dp 宽的一列，标题起点对齐（unslop §3）
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (danger) C.Danger else C.Sub, modifier = Modifier.size(22.dp))
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
        Box(Modifier.fillMaxWidth().height(50.dp).skeuoInset(RoundedCornerShape(Radius.m)).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            // 提示文字用次要色（≥ 4.5:1，craft-floor：占位文字也要达标），不用 faint
            if (value.isEmpty()) Text(hint, color = C.Sub, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(value, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = C.Text), cursorBrush = SolidColor(C.Accent), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().skeuoCard(RoundedCornerShape(Radius.l)), content = content)
}

/** 1.7.9 设置首页的账号卡（玻璃卡片）。整张卡是一个按钮，读屏读「飞牛 · 名字 · 用户，切换与管理」。 */
@Composable
private fun AccountCard(name: String, avatar: String, kind: String, user: String, onClick: () -> Unit) {
    val ix = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val accent = C.Accent
    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().glassPress(ix, 0.3f)
        .glass(RoundedCornerShape(Radius.l), null, tint = C.Surface, shadow = 10.dp).clip(RoundedCornerShape(Radius.l))
        .clickable(ix, androidx.compose.foundation.LocalIndication.current, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "切换与管理", onClick = onClick)
        .semantics(mergeDescendants = true) {}
        .padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.drawBehind {
            if (Glass.on) drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(accent.copy(alpha = 0.45f), Color.Transparent), radius = size.minDimension),
                radius = size.minDimension)
        }) { Avatar(avatar, 56.dp) }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(name, style = MaterialTheme.typography.titleLarge, color = C.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(kind, style = MaterialTheme.typography.labelSmall, color = C.Accent,
                    modifier = Modifier.clip(CircleShape).background(C.Accent.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 2.dp))
                if (user.isNotEmpty()) Text(user, style = MaterialTheme.typography.bodySmall, color = C.Sub, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Row(Modifier.padding(start = 8.dp).clip(CircleShape).background(C.Text.copy(alpha = 0.07f)).padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("管理", style = MaterialTheme.typography.labelLarge, color = C.Text)
            Icon(KI.chevron(), null, tint = C.Sub, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * 1.7.9 强调色「玻璃色球」：48dp 触摸区里一颗 30dp 的球——左上一点白色高光、右下压暗，像一粒彩色玻璃；
 * 选中时球从 28 长到 32dp（液体弹簧）、外圈 2dp 同色光环 + 柔光、中间一个勾（不只靠颜色）。读屏「蓝色，单选，已选中」。
 * 简洁风格：纯色圆片 + 正文色外圈（无高光、无光晕）。色球显示当前主题下真正会用到的那一档。
 */
@Composable
private fun AccentOrb(a: Accent, on: Boolean, onPick: () -> Unit) {
    val c = if (C.P.dark) a.color else a.soft
    val glass = Glass.on
    val k by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, if (on) Motion.liquid() else Motion.state(), label = "orb")
    val view = androidx.compose.ui.platform.LocalView.current
    Box(Modifier.size(48.dp).clip(CircleShape).selectable(on, role = androidx.compose.ui.semantics.Role.RadioButton) { if (!on) Haptics.tick(view); onPick() }
        .semantics { contentDescription = a.name + "色" }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(42.dp).drawBehind {
                // 外圈光环 + 柔光（选中时）
                if (k > 0f) {
                    if (glass) drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(c.copy(alpha = 0.40f * k), Color.Transparent), radius = size.minDimension * 0.62f))
                    drawCircle(c.copy(alpha = k), radius = size.minDimension / 2f - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                }
            }, contentAlignment = Alignment.Center) {
            val orb = 28.dp + 4.dp * k
            Box(Modifier.size(orb).clip(CircleShape).background(c)
                .then(if (glass) Modifier.drawBehind {
                    // 球面：左上白色高光 → 透明，右下压暗
                    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(size.width * 0.32f, size.height * 0.28f), radius = size.minDimension * 0.55f))
                    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f)),
                        center = androidx.compose.ui.geometry.Offset(size.width * 0.4f, size.height * 0.36f), radius = size.minDimension * 0.75f))
                } else Modifier)
                .then(if (!glass && on) Modifier.border(2.dp, C.Text, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                if (on) Icon(KI.check(), null, tint = if (C.P.dark) Color(0xFF070A12) else Color.White, modifier = Modifier.size(16.dp).graphicsLayer { alpha = if (Motion.reduced) 1f else k.coerceIn(0f, 1f) })
            }
        }
    }
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
    val close = { if (changed.value) toast(ctx, "已保存，新建的连接生效"); onDismiss() }
    AppAlertDialog(onDismissRequest = close, containerColor = C.Surface, title = {
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
    AppAlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface, title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("后台整理", color = C.Text)
            InfoDot("本地分析、人脸识别、AI 分类和手机备份共用这套规则。扫完一遍后只处理新加入的照片；条件满足时自动继续，离开页面或切到后台也不停，进度在通知栏。", "后台整理")
        }
    }, text = {
        Column {
            CheckRow("允许使用移动网络", if (cell) "会产生流量" else null, cell) {
                cell = it; ScanPolicy.allowCellular = it; BackupScheduler.schedule(ctx); if (it) ScanWatch.resume(ctx, "后台整理设置放宽")
            }
            CheckRow("不充电时也整理", if (noCharge) "更耗电" else null, noCharge) {
                noCharge = it; ScanPolicy.allowNoCharge = it; BackupScheduler.schedule(ctx); if (it) ScanWatch.resume(ctx, "后台整理设置放宽")
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
    var davRecycle by remember { mutableStateOf(DavRecycle.enabled) }
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
                } else {
                    Div()
                    // 优化 5：WebDAV 删除先移到网盘里的 /.yiwei-recycle（服务器不能移动时延迟删除），30 天内可在「最近删除」恢复
                    ToggleRow(LI.trash(), "网盘删除先进回收站", if (davRecycle) "30 天内可在「最近删除」恢复" else "删除后无法恢复", davRecycle, alert = !davRecycle,
                        info = "打开时删除的网盘照片先移到网盘里的隐藏文件夹 .yiwei-recycle，30 天后自动彻底删除；服务器不支持移动时先在 App 里隐藏，30 天后再删除。入口：发现 → 工具 → 最近删除。") {
                        davRecycle = it; DavRecycle.enabled = it
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

/** 设置 → 通用 → 开源许可。完整许可原文随包附在 assets/licenses/。 */
internal const val OPEN_SOURCE_NOTICE = """界面图标：Kubai Icons（icons-line）
Copyright (c) 2026 Kubai087 and the Kubai Icons contributors
MIT License — https://github.com/kubai087/kubai-icons

数字字体：Allo（仅数字与英文字母子集）
Copyright 2026 Josh Puckett；Copyright (c) 2016 The Inter Project Authors
SIL Open Font License 1.1 — https://github.com/joshpuckett/allo

播放：AndroidX Media3（Apache 2.0）、Jellyfin FFmpeg 解码扩展（LGPL 2.1+）
图片：Coil（Apache 2.0）；网络：OkHttp（Apache 2.0）；地图：osmdroid（Apache 2.0）"""
