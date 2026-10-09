package com.hark.shiguang

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*

/**
 * Keeps the process alive while a scan runs (local analysis, faces, 影视), so leaving the page or the app
 * does not stop it. Shows one quiet notification with progress and stops itself when nothing is left.
 */
class ScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1.0.1: pause / resume from the notification
        when (intent?.action) {
            ACTION_PAUSE -> pauseAll()
            ACTION_RESUME -> resumeAll()
        }
        val n = build("正在整理", "准备中…", 0, 0)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        if (!looping) {
            looping = true
            scope.launch {
                var idle = 0
                while (isActive) {
                    delay(1000)
                    val st = status()
                    if (st == null) { if (++idle >= 2) break } else {
                        idle = 0
                        getSystemService(NotificationManager::class.java)?.notify(ID, build(st.first, st.second, st.third.first, st.third.second))
                    }
                }
                looping = false
                if (ScanPolicy.paused && hasWorkLeft()) {
                    // keep a dismissible 「已暂停」 notification with 继续, without holding a foreground service
                    stopForeground(STOP_FOREGROUND_DETACH)
                    getSystemService(NotificationManager::class.java)?.notify(ID, build("整理已暂停", "点「继续」接着扫描和 AI 整理", 0, 0, paused = true))
                } else stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); looping = false; super.onDestroy() }

    private fun pauseAll() {
        ScanPolicy.paused = true
        runCatching { AiRunner.pause() }
        runCatching { Analyzer.stop() }
        runCatching { Faces.stop() }
    }

    private fun resumeAll() {
        ScanPolicy.paused = false
        runCatching { AiRunner.resume() }
        val lib = runCatching { Dav.lib() }.getOrNull()
        if (lib != null && lib.photos.isNotEmpty()) {
            // like 「立即继续」 on the AI card: the user asked for it, so Wi-Fi / charging rules do not hold it back
            runCatching { Analyzer.start(this, lib, true) }
            runCatching { Faces.start(this, lib, true) }
            if (AiConfig.configured) runCatching { AiRunner.startDav(this, lib, manual = true) }
        }
        if (AiConfig.nasToo && AiConfig.configured) runCatching { AiRunner.startNas(this, com.hark.shiguang.ui.HomeState.timeline.photos.toList()) }
    }

    private fun hasWorkLeft(): Boolean {
        val lib = runCatching { Dav.lib() }.getOrNull() ?: return AiConfig.configured
        return lib.photos.any { !it.isVideo && lib.meta[it.cloudPath]?.done != true } || (AiConfig.configured && lib.aiDone() < lib.imagesCount())
    }

    private fun status() = ScanStatus.current()

    private fun build(title: String, text: String, done: Int, total: Int, paused: Boolean = false): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CH) == null)
            nm?.createNotificationChannel(NotificationChannel(CH, "后台整理", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text).setOngoing(!paused).setOnlyAlertOnce(true).setContentIntent(open)
        if (total > 0) b.setProgress(total, done.coerceAtMost(total), false)
        // pause only makes sense for analysis / faces / AI (listing and 影视 finish by themselves)
        val pausable = paused || Analyzer.running || Faces.running || AiRunner.running
        if (pausable) {
            val act = if (paused) ACTION_RESUME else ACTION_PAUSE
            val i = Intent(this, ScanService::class.java).setAction(act)
            val pi = if (paused && Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(this, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                else PendingIntent.getService(this, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            @Suppress("DEPRECATION")
            b.addAction(Notification.Action.Builder(null, if (paused) "继续" else "暂停", pi).build())
        }
        return b.build()
    }

    companion object {
        private const val CH = "scan"
        private const val ID = 4108
        const val ACTION_PAUSE = "com.hark.shiguang.scan.PAUSE"
        const val ACTION_RESUME = "com.hark.shiguang.scan.RESUME"
        @Volatile private var looping = false
        fun ensure(c: Context) {
            if (looping) return
            runCatching {
                val i = Intent(c.applicationContext, ScanService::class.java)
                if (Build.VERSION.SDK_INT >= 26) c.applicationContext.startForegroundService(i) else c.applicationContext.startService(i)
            }
        }
    }
}
