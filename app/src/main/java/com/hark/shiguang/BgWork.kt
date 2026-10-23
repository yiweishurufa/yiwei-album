package com.hark.shiguang

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * 1.1.0 (3c): one scheduler for background work — incremental WebDAV scan, local analysis, faces, AI labelling and
 * phone backup — on top of WorkManager, so the system restarts it after reboot / process death and only runs it when
 * [ScanPolicy]'s network and charging rules hold (the same two switches in 设置 → 后台整理).
 *
 * The runners themselves (Analyzer / Faces / AiRunner / BackupRunner) are unchanged: a worker only starts them and
 * keeps the process alive while they make progress. Switch: [Features.workManager] (off = old behaviour: backup via
 * JobScheduler, background analysis only while the app process lives).
 */
object BgWork {
    const val LIBRARY = "bg.library"
    const val BACKUP = "bg.backup"
    const val LIBRARY_NOW = "bg.library.now"
    /** A worker may run ~10 minutes; stop waiting a little earlier so WorkManager never has to kill it. */
    private const val MAX_WAIT_MS = 9 * 60_000L

    /** Network + charging from [ScanPolicy] (scan, faces, AI). */
    fun libraryConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (ScanPolicy.allowCellular) NetworkType.CONNECTED else NetworkType.UNMETERED)
        .setRequiresCharging(!ScanPolicy.allowNoCharge)
        .build()

    /** Backup only ever followed the network rule (1.0.9), never charging — kept that way. */
    fun backupConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (ScanPolicy.allowCellular) NetworkType.CONNECTED else NetworkType.UNMETERED)
        .build()

    private fun wm(c: Context): WorkManager? = runCatching { WorkManager.getInstance(c.applicationContext) }
        .onFailure { Diag.log("WORK", "WorkManager unavailable: ${it.javaClass.simpleName}") }.getOrNull()

    /** (Re)schedules everything from the current settings. Cheap and idempotent: call after any related setting changes. */
    fun schedule(c: Context) {
        val wm = wm(c) ?: return
        if (!Features.workManager) {
            wm.cancelUniqueWork(LIBRARY); wm.cancelUniqueWork(BACKUP); wm.cancelUniqueWork(LIBRARY_NOW)
            return
        }
        if (Dav.accounts.isNotEmpty()) {
            val req = PeriodicWorkRequestBuilder<LibraryWorker>(6, TimeUnit.HOURS)
                .setConstraints(libraryConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(LIBRARY, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else wm.cancelUniqueWork(LIBRARY)

        if (Store.backupOn && Store.token.isNotEmpty()) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.HOURS)
                .setConstraints(backupConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(BACKUP, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else wm.cancelUniqueWork(BACKUP)
    }

    /** One run as soon as the conditions hold (e.g. Wi-Fi / charger just came back). Replaces a pending one. */
    fun kick(c: Context) {
        if (!Features.workManager || ScanPolicy.paused) return
        val wm = wm(c) ?: return
        val req = OneTimeWorkRequestBuilder<LibraryWorker>().setConstraints(libraryConstraints()).build()
        wm.enqueueUniqueWork(LIBRARY_NOW, ExistingWorkPolicy.KEEP, req)
    }

    /** Waits while [busy] is true, at most [MAX_WAIT_MS] in total; returns early when the worker is stopped. */
    internal suspend fun waitWhile(started: Long, busy: () -> Boolean) {
        while (busy() && System.currentTimeMillis() - started < MAX_WAIT_MS) delay(5_000)
    }
}

/** Incremental scan of the current WebDAV library, then local analysis + faces + AI labelling (same rules as the app). */
class LibraryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!Features.workManager || ScanPolicy.paused) return Result.success()
        val lib = Dav.lib() ?: return Result.success()
        val started = System.currentTimeMillis()
        return try {
            lib.ensure()
            // the first full scan belongs to the foreground (it shows progress); here only the cheap incremental pass
            if (lib.photos.isNotEmpty() && !lib.scanning) runCatching { lib.scan(full = false) }
                .onFailure { if (it is CancellationException) throw it; Diag.log("WORK", "scan failed: ${it.javaClass.simpleName}") }
            if (lib.photos.isNotEmpty() && !ScanPolicy.paused) {
                // constraints already hold; manual=false keeps every runner's own pause / switch checks
                ScanStarter.startAll(applicationContext, lib, manual = false)
                delay(3_000)
                BgWork.waitWhile(started) { !ScanPolicy.paused && (Analyzer.running || Faces.running || AiRunner.running || OcrRunner.running) }
            }
            Diag.log("WORK", "library run done in ${(System.currentTimeMillis() - started) / 1000}s")
            Result.success()
        } catch (e: CancellationException) {
            // constraints lost (left Wi-Fi / unplugged) or the system stopped us: the runners notice the same
            // conditions themselves; WorkManager reschedules the periodic work.
            throw e
        } catch (e: Exception) {
            Diag.log("WORK", "library run failed: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }
}

/** Phone-gallery backup (replaces the 1.0.x JobScheduler job when [Features.workManager] is on). */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!Features.workManager || !Store.backupOn) return Result.success()
        val r = try { BackupRunner.run(applicationContext, false) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Diag.log("WORK", "backup failed: ${e.javaClass.simpleName}"); null
        }
        // B23 rule kept: retry only on a real failure
        return if (r == null || r.failures.isNotEmpty()) Result.retry() else Result.success()
    }
}
