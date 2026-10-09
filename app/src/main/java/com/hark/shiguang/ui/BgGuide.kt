package com.hark.shiguang.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hark.shiguang.BgRun

/** 1.0.1 「后台运行设置」: notifications, battery optimisation, and the phone maker's 自启动 / 省电 pages. */
@Composable
fun BgGuideScreen() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // re-check the states when the user comes back from a system page
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    val notif = remember(tick) { BgRun.notificationsAllowed(ctx) }
    val battery = remember(tick) { BgRun.ignoringBattery(ctx) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> tick++; if (!ok) BgRun.openNotificationSettings(ctx) }
    val mine = BgRun.vendor
    var open by remember { mutableStateOf<BgRun.Vendor?>(mine) }

    Page("后台运行设置", "让扫描和 AI 整理在后台不被系统停掉") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
            Text("国产手机默认会限制后台应用。照片多时扫描和 AI 整理要跑一阵子，按下面三步设置后，锁屏或切到别的应用也能继续，进度显示在通知栏。",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp))

            Section("1. 通知")
            Card {
                StepRow(Icons.Rounded.Notifications, "允许通知", if (notif) "已允许，通知栏会显示扫描和整理进度" else "用来显示进度和暂停/继续按钮", notif) {
                    if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS) else BgRun.openNotificationSettings(ctx)
                }
            }

            Section("2. 电池优化")
            Card {
                StepRow(Icons.Rounded.BatteryChargingFull, "不优化一维相册的电池使用", if (battery) "已设置" else "系统弹窗里选「允许」", battery) { BgRun.requestIgnoreBattery(ctx) }
            }

            Section("3. 自启动与后台运行（${mine.label}）")
            Card {
                StepRow(Icons.Rounded.RocketLaunch, "打开系统设置页", "找到一维相册，按下面的说明打开", null) {
                    if (!BgRun.openVendorPage(ctx, mine)) toast(ctx, "这台手机没有找到对应页面，已打开应用信息")
                }
                Text(BgRun.tips(mine), style = MaterialTheme.typography.bodySmall, lineHeight = 19.sp, modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp))
            }

            Section("其他品牌")
            Card {
                BgRun.Vendor.values().filter { it != mine }.forEachIndexed { i, v ->
                    if (i > 0) Div()
                    Column(Modifier.fillMaxWidth().clickable { open = if (open == v) null else v }.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(v.label, color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Icon(if (open == v) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = C.Faint)
                        }
                        if (open == v) {
                            Text(BgRun.tips(v), style = MaterialTheme.typography.bodySmall, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp))
                            if (v != BgRun.Vendor.OTHER) Text("尝试打开设置页", color = C.Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp).clickable { BgRun.openVendorPage(ctx, v) })
                        }
                    }
                }
            }
            Text("仍然会被停掉时：把一维相册在最近任务里加锁，并在「设置 → 网络与电量」里按需允许移动网络 / 不充电时整理。",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp))
        }
    }
}

@Composable
private fun StepRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, done: Boolean?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(C.Surface2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = C.Text, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 15.sp)
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
        when (done) {
            true -> Text("已完成", color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            false -> Text("去设置", color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            null -> Icon(LI.chevron(), null, tint = C.Faint, modifier = Modifier.size(18.dp))
        }
    }
}
