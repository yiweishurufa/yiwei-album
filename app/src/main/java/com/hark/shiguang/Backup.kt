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

    /**
     * 1.1.0 (3c): with [Features.workManager] on, backup and the library run go through [BgWork] (WorkManager) and
     * the old JobScheduler job is cancelled; off = the 1.0.x JobScheduler job. [BackupJob] stays in the manifest so
     * a job scheduled by an older version still finds its service.
     */
    fun schedule(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java)
        if (Features.workManager) {
            js?.cancel(JOB_ID)
            BgWork.schedule(c)
            return
        }
        BgWork.schedule(c) // switch off: cancels the WorkManager jobs
        js ?: return
        if (!Store.backupOn || Store.token.isEmpty()) { js.cancel(JOB_ID); return }
        val info = JobInfo.Builder(JOB_ID, ComponentName(c, BackupJob::class.java))
            .setRequiredNetworkType(if (ScanPolicy.allowCellular) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED) // 1.0.9: one network rule for scan/AI/backup
            .setPeriodic(60 * 60 * 1000L)
            .setPersisted(true)
            .build()
        runCatching { js.schedule(info) }.onFailure { Diag.e("BACKUP", it) }
    }

    fun cancel(c: Context) {
        c.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        runCatching { androidx.work.WorkManager.getInstance(c.applicationContext).cancelUniqueWork(BgWork.BACKUP) }
    }
}

object BackupRunner {
    private val lock = Mutex()
    var lastResult: String = ""

    /** B23: enqueued (viaQueue — still in the transfer queue) vs actually uploaded are reported separately. */
    data class Result(val enqueued: Int, val completed: Int, val failures: List<String>)

    private fun ensureClient() {
        if (FnClient.baseUrl.isEmpty() && Store.url.isNotEmpty()) {
            FnClient.setCredentials(Store.url, Store.user, Store.pass); FnClient.token = Store.token
        }
    }

    /**
     * Uploads everything added since the last backup. [viaQueue] shows each file in the transfer queue.
     * B23: viaQueue used to count an enqueue as "done" at once — now the result separates enqueued from
     * completed, lists failures, and [lastResult] never claims success when something failed.
     */
    suspend fun run(c: Context, viaQueue: Boolean): Result = lock.withLock {
        ensureClient()
        if (FnClient.baseUrl.isEmpty()) { lastResult = "未连接飞牛"; return Result(0, 0, listOf("未连接飞牛")) }
        val since = Store.backupSince
        val items = LocalMediaReader.newSince(c, since)
        Diag.log("BACKUP", "found ${items.size} new since $since")
        var enqueued = 0; var completed = 0; val failures = ArrayList<String>()
        for (m in items) {
            val work: suspend ((Float) -> Unit) -> Unit = { progress ->
                NasX.upload(m.name, m.size, m.mime.ifEmpty { "application/octet-stream" },
                    { c.contentResolver.openInputStream(m.uri) ?: throw Exception("无法读取 ${m.name}") },
                    lastModifiedMs = m.taken * 1000,
                    onProgress = { s, t -> if (t > 0) progress(s.toFloat() / t) })
                Store.backupSince = maxOf(Store.backupSince, m.taken)
            }
            if (viaQueue) {
                runCatching { Transfers.enqueue(m.name, true, "NAS 备份", work) }
                    .onSuccess { enqueued++ }
                    .onFailure { e -> Diag.e("BACKUP", e); failures += "${m.name}：${e.message}" }
            } else {
                runCatching { work {} }
                    .onSuccess { completed++ }
                    .onFailure { e -> Diag.e("BACKUP", e); failures += "${m.name}：${e.message}" }
            }
        }
        lastResult = when {
            failures.isNotEmpty() -> "备份失败 ${failures.size} 项：${failures.take(3).joinToString("；")}"
            viaQueue -> "已加入传输队列 $enqueued 项"
            else -> "备份了 $completed 项"
        }
        Result(enqueued, completed, failures)
    }
}

class BackupJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        if (!Store.backupOn) return false
        job = scope.launch {
            // B23: reschedule only on a real failure (throw, or any file failed) — not on a partial queue.
            val r = runCatching { BackupRunner.run(applicationContext, false) }.getOrNull()
            jobFinished(params, r == null || r.failures.isNotEmpty())
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { job?.cancel(); return true }
}
