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
    private val kick = Runnable { resume(App.ctx) }

    fun init(c: Context) {
        if (registered) return
        registered = true
        runCatching {
            c.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = poke()
                override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) = poke()
            })
        }
        runCatching {
            val f = IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED) }
            val r = object : BroadcastReceiver() { override fun onReceive(ctx: Context, i: Intent) = poke() }
            if (android.os.Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else c.registerReceiver(r, f)
        }
    }

    private fun poke() { main.removeCallbacks(kick); main.postDelayed(kick, 4000) }

    /** Starts whatever has work left for the current WebDAV library, if the policy allows and the user did not pause. */
    fun resume(c: Context) {
        if (ScanPolicy.paused) return
        val lib = runCatching { Dav.lib() }.getOrNull() ?: return
        if (lib.photos.isEmpty() || !Analyzer.canScan(c)) return
        runCatching { Analyzer.start(c, lib, false) }
        runCatching { Faces.start(c, lib, false) }
        if (AiRunner.on) runCatching { AiRunner.startDav(c, lib) }
    }
}
