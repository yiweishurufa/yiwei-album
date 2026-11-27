package com.hark.shiguang

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper

/**
 * 1.0.9: resumes local analysis, faces and AI labelling by itself when Wi-Fi / charging come back
 * (they used to stay stopped until the AI tab was opened again). Registered once from [App.onCreate];
 * only works while the process is alive, which [ScanService] takes care of during a scan.
 */
object ScanWatch {
    private val main = Handler(Looper.getMainLooper())
    private var registered = false
    private val kick = Runnable { onConditions(App.ctx) }

    fun init(c: Context) {
        if (registered) return
        registered = true
        runCatching {
            c.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = poke()
                override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) = poke()
                // 1.7.21：断网也要记下来，否则重新连上 Wi-Fi 时看不出「条件刚恢复」
                override fun onLost(network: Network) = poke()
            })
        }
        runCatching {
            // 1.7.21：拔电 / 电量低 / 电量恢复也记下来（只用来判断条件变化，不会因此多跑）
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW); addAction(Intent.ACTION_BATTERY_OKAY)
            }
            val r = object : BroadcastReceiver() { override fun onReceive(ctx: Context, i: Intent) = poke() }
            if (android.os.Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else c.registerReceiver(r, f)
        }
    }

    private fun poke() { main.removeCallbacks(kick); main.postDelayed(kick, 4000) }

    /**
     * 1.7.21（用户 2026-11-27「一直持续在后台扫描，太耗电」）：根因之一。onCapabilitiesChanged 在信号强度 / 带宽估计变化时
     * 也会回调，一分钟可能好几次，以前每次都 [resume]，把整理队列（含读不到、永远清不空的文件）一遍遍拉起。
     * 现在只在条件从「不满足」变成「满足」时（连上 Wi-Fi / 插上电）自动续跑，且两次至少隔 [ResumeGate.MIN_GAP_MS]。
     */
    @Volatile private var lastAllowed: Boolean? = null
    @Volatile private var lastResumeAt = 0L

    private fun onConditions(c: Context) {
        val allowed = runCatching { Analyzer.canScan(c) }.getOrDefault(false)
        val was = lastAllowed
        lastAllowed = allowed
        val now = System.currentTimeMillis()
        if (!ResumeGate.shouldResume(was, allowed, lastResumeAt, now)) return
        lastResumeAt = now
        Diag.log("SCANLOG", "auto resume: conditions became ok (wifi=${Net.isWifi(c)} unmetered=${Net.isUnmetered(c)} charging=${Net.isCharging(c)} batteryLow=${Net.batteryLow(c)})")
        resume(c, "网络或充电条件恢复")
    }

    /** Starts whatever has work left for the current WebDAV library, if the policy allows and the user did not pause. */
    fun resume(c: Context, reason: String = "自动") {
        if (ScanPolicy.paused) return
        val lib = runCatching { Dav.lib() }.getOrNull() ?: return
        if (lib.photos.isEmpty()) return
        ScanStarter.startAll(c, lib, manual = false, reason = reason)
    }
}
