package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 1.0.1 WebDAV presets for the services Chinese users actually have (China-first defaults).
 * [url] is filled into the address field; `<IP>` style placeholders are for the user to replace.
 */
data class DavPreset(val key: String, val label: String, val title: String, val url: String, val userHint: String, val help: String)

object DavPresets {
    val all = listOf(
        DavPreset("123", "123 云盘", "123 云盘", "https://webdav.123pan.cn/webdav", "123 账号（手机号/邮箱）",
            "网页端「工具中心 → 第三方挂载」或 App「我的 → 第三方挂载」里添加应用、选授权文件夹，生成应用密码。用户名填 123 账号，密码填应用密码（不是登录密码）。免费账号的 WebDAV 下载流量有限，部分功能需会员。"),
        DavPreset("jgy", "坚果云", "坚果云", "https://dav.jianguoyun.com/dav/", "坚果云登录邮箱",
            "坚果云网页端「账户信息 → 安全选项 → 第三方应用管理」添加应用，生成应用密码。用户名是登录邮箱，密码填应用密码。免费版每月上传/下载流量有限，适合照片不多的情况。"),
        DavPreset("alist", "Alist / OpenList", "Alist", "http://192.168.1.100:5244/dav", "Alist 用户名",
            "在 NAS 或电脑上运行 Alist / OpenList（默认端口 5244），在它的后台挂载阿里云盘、夸克网盘、百度网盘、115 等，这里就能统一看。地址格式 http://<IP>:5244/dav（把 IP 换成运行 Alist 的设备；外网用你的域名或内网穿透地址）。用户名/密码是 Alist 账号，该用户需开启 WebDAV 读取权限。"),
        DavPreset("syno", "群晖", "群晖 NAS", "http://192.168.1.100:5005", "DSM 用户名",
            "套件中心安装并启用「WebDAV Server」（HTTP 5005 / HTTPS 5006）。用户名密码是 DSM 账号；地址可以带共享文件夹，如 http://<IP>:5005/photo。外网建议用 HTTPS 5006 + 域名。"),
        DavPreset("qnap", "威联通", "威联通 NAS", "http://192.168.1.100:8081", "QTS 用户名",
            "控制台「网络和文件服务 → Win/Mac/NFS/WebDAV → WebDAV」启用，并在共享文件夹权限里允许 WebDAV。端口以控制台里显示的为准（常见 8081 / HTTPS 8082）。"),
        DavPreset("nc", "Nextcloud", "Nextcloud", "https://你的域名/remote.php/dav/files/用户名/", "Nextcloud 用户名",
            "地址末尾换成你的用户名。开了二步验证时，在「设置 → 安全 → 设备与会话」生成应用密码来登录。"),
    )
}

@Composable
fun DavPresetRow(selected: String?, onPick: (DavPreset) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DavPresets.all.forEach { p ->
            val on = p.key == selected
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) C.Accent.copy(alpha = 0.16f) else C.Surface2)
                .border(1.dp, if (on) C.Accent else C.Surface2, RoundedCornerShape(16.dp)).clickable { onPick(p) }
                .padding(horizontal = 12.dp, vertical = 7.dp)) {
                Text(p.label, fontSize = 13.sp, color = if (on) C.Accent else C.Text)
            }
        }
    }
}
