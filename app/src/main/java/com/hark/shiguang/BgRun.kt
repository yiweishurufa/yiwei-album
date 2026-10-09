package com.hark.shiguang

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 1.0.1 「后台运行设置」 helpers: notification permission (Android 13+), ignore battery optimisations, and the vendor
 * pages (MIUI/HyperOS, ColorOS, 鸿蒙/EMUI/MagicOS, vivo, 三星, 魅族) where 自启动 / 后台运行 is allowed. Vendor activities
 * change between ROM versions, so every page is tried in order and the app-details page is the fallback.
 */
object BgRun {
    enum class Vendor(val label: String) { XIAOMI("小米 / Redmi（MIUI · HyperOS）"), HUAWEI("华为（鸿蒙 · EMUI）"), HONOR("荣耀（MagicOS）"),
        OPPO("OPPO / 一加 / realme（ColorOS）"), VIVO("vivo / iQOO（OriginOS）"), SAMSUNG("三星（One UI）"), MEIZU("魅族（Flyme）"), OTHER("其他手机") }

    val vendor: Vendor get() {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> Vendor.XIAOMI
            m.contains("honor") -> Vendor.HONOR
            m.contains("huawei") -> Vendor.HUAWEI
            m.contains("oppo") || m.contains("oneplus") || m.contains("realme") -> Vendor.OPPO
            m.contains("vivo") || m.contains("iqoo") -> Vendor.VIVO
            m.contains("samsung") -> Vendor.SAMSUNG
            m.contains("meizu") -> Vendor.MEIZU
            else -> Vendor.OTHER
        }
    }

    /** Step-by-step tips per vendor (menu names differ a little between versions). */
    fun tips(v: Vendor): String = when (v) {
        Vendor.XIAOMI -> "1. 设置 → 应用设置 → 应用管理 → 一维相册 → 自启动：打开\n2. 同一页「省电策略」选「无限制」\n3. 最近任务里下拉一维相册的卡片加锁，防止一键清理"
        Vendor.HUAWEI -> "1. 设置 → 应用和服务 → 应用启动管理 → 一维相册：关闭「自动管理」，在弹窗里打开「允许自启动 / 关联启动 / 后台活动」\n2. 设置 → 电池 → 更多电池设置：打开「休眠时始终保持网络连接」"
        Vendor.HONOR -> "1. 设置 → 应用 → 应用启动管理 → 一维相册：关闭「自动管理」，打开三个开关\n2. 设置 → 电池：关闭「智能省电」对一维相册的限制"
        Vendor.OPPO -> "1. 设置 → 应用 → 应用管理 → 一维相册 → 耗电管理：打开「允许后台运行」「允许自启动」\n2. 设置 → 电池 → 更多设置：关闭「睡眠待机优化」或把一维相册加入例外"
        Vendor.VIVO -> "1. i 管家 → 应用管理 → 权限管理 → 自启动：打开一维相册\n2. 设置 → 电池 → 后台耗电管理 → 一维相册：选「允许后台高耗电」"
        Vendor.SAMSUNG -> "设置 → 电池 → 后台使用限制 → 从不休眠的应用：添加一维相册；并确认一维相册不在「休眠应用」里"
        Vendor.MEIZU -> "手机管家 → 权限管理 → 后台管理 → 一维相册：选「允许后台运行」"
        Vendor.OTHER -> "在系统设置里找到一维相册，允许自启动 / 后台运行，并把电池优化设为「不优化」或「无限制」"
    }

    private fun cn(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Candidate pages for 自启动 / 后台运行, most specific first. */
    private fun vendorIntents(c: Context, v: Vendor): List<Intent> = when (v) {
        Vendor.XIAOMI -> listOf(
            cn("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY").setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                .putExtra("package_name", c.packageName).putExtra("package_label", "一维相册").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                .putExtra("extra_pkgname", c.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Vendor.HUAWEI -> listOf(
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        Vendor.HONOR -> listOf(
            cn("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        Vendor.OPPO -> listOf(
            cn("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            cn("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            cn("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            cn("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            cn("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
        )
        Vendor.VIVO -> listOf(
            cn("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            cn("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            cn("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        )
        Vendor.SAMSUNG -> listOf(
            cn("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            cn("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        Vendor.MEIZU -> listOf(
            Intent("com.meizu.safe.security.SHOW_APPSEC").addCategory(Intent.CATEGORY_DEFAULT).putExtra("packageName", c.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            cn("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
        )
        Vendor.OTHER -> emptyList()
    }

    fun appDetails(c: Context) {
        runCatching { c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Opens the vendor's 自启动 / 后台 page; false when none exists on this ROM (then app details were opened). */
    fun openVendorPage(c: Context, v: Vendor = vendor): Boolean {
        for (i in vendorIntents(c, v)) {
            // no resolveActivity(): Android 11+ package visibility would hide other apps' pages without <queries>
            val ok = runCatching { c.startActivity(i) }.isSuccess
            if (ok) return true
        }
        appDetails(c)
        return false
    }

    fun ignoringBattery(c: Context): Boolean = c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) ?: true

    @SuppressLint("BatteryLife")
    fun requestIgnoreBattery(c: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { c.startActivity(direct) }.isSuccess) return
        if (runCatching { c.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        appDetails(c)
    }

    fun notificationsAllowed(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else c.getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() ?: true

    fun openNotificationSettings(c: Context) {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { c.startActivity(i) }.isFailure) appDetails(c)
    }

    /**
     * Android 13+: asks once for POST_NOTIFICATIONS (so the scan / AI progress notification can show), only after the
     * user has a library (not on the very first login screen). Later the 「后台运行设置」 page offers it again.
     */
    fun maybeAskNotifications(a: Activity) {
        if (Build.VERSION.SDK_INT < 33 || notificationsAllowed(a)) return
        if (Store.getStr("perm.notif.asked") == "1") return
        if (Store.token.isEmpty() && Dav.accounts.isEmpty()) return
        Store.putStr("perm.notif.asked", "1")
        runCatching { a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4109) }
    }
}
