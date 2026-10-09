package com.hark.shiguang

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.hark.shiguang.data.FnClient
import com.hark.shiguang.data.NasX
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Periodic phone-gallery backup through JobScheduler (no WorkManager dependency). */
object BackupScheduler {
    private const val JOB_ID = 7301

    fun schedule(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java) ?: return
        if (!Store.backupOn || Store.token.isEmpty()) { js.cancel(JOB_ID); return }
        val info = JobInfo.Builder(JOB_ID, ComponentName(c, BackupJob::class.java))
            .setRequiredNetworkType(if (ScanPolicy.allowCellular) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED) // 1.0.9: one network rule for scan/AI/backup
            .setPeriodic(60 * 60 * 1000L)
            .setPersisted(true)
            .build()
        runCatching { js.schedule(info) }.onFailure { Diag.e("BACKUP", it) }
    }

    fun cancel(c: Context) { c.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
}

object BackupRunner {
    private val lock = Mutex()
    var lastResult: String = ""

    private fun ensureClient() {
        if (FnClient.baseUrl.isEmpty() && Store.url.isNotEmpty()) {
            FnClient.setCredentials(Store.url, Store.user, Store.pass); FnClient.token = Store.token
        }
    }

    /** Uploads everything added since the last backup. [viaQueue] shows each file in the transfer queue. */
    suspend fun run(c: Context, viaQueue: Boolean): Int = lock.withLock {
        ensureClient()
        if (FnClient.baseUrl.isEmpty()) return 0
        val since = Store.backupSince
        val items = LocalMediaReader.newSince(c, since)
        Diag.log("BACKUP", "found ${items.size} new since $since")
        var done = 0
        for (m in items) {
            val work: suspend ((Float) -> Unit) -> Unit = { progress ->
                NasX.upload(m.name, m.size, m.mime.ifEmpty { "application/octet-stream" },
                    { c.contentResolver.openInputStream(m.uri) ?: throw Exception("无法读取 ${m.name}") },
                    lastModifiedMs = m.taken * 1000,
                    onProgress = { s, t -> if (t > 0) progress(s.toFloat() / t) })
                Store.backupSince = maxOf(Store.backupSince, m.taken)
            }
            if (viaQueue) {
                Transfers.enqueue(m.name, true, "NAS 备份", work)
            } else {
                runCatching { work {} }.onFailure { Diag.e("BACKUP", it); lastResult = "失败：${it.message}"; return done }
            }
            done++
        }
        lastResult = "备份了 $done 项"
        done
    }
}

class BackupJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        if (!Store.backupOn) return false
        job = scope.launch {
            val ok = runCatching { BackupRunner.run(applicationContext, false) }.isSuccess
            jobFinished(params, !ok)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { job?.cancel(); return true }
}
