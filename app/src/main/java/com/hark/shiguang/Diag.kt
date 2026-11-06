package com.hark.shiguang

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local diagnostic log. Never uploads anything; the user exports it by sharing a file. */
object Diag {
    private lateinit var dir: File
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val buf = ArrayDeque<String>()
    // S21: also scrub signing material (si / otp / sign / signature) — the fnOS file API signs with si,
    // and a login OTP must never land in a log the user might forward.
    private val secret = Regex("(?<![A-Za-z0-9_])(accesstoken|token|password|pass|secret|authx|access_token|Authorization|si|otp|sign|signature)([\"'=:\\s]+)([^\"'&\\s,\\}]+)", RegexOption.IGNORE_CASE)
    /** S21: userinfo in a URL ("https://user@host/…") — the user part is masked, the host stays readable. */
    private val userinfo = Regex("([a-zA-Z][a-zA-Z0-9+.-]*://)([^/@\\s]+)@")   // 优化 10：「用户名:密码@」整段打码（以前带冒号的不匹配）
    /** 优化 10：链接的查询参数（签名、令牌、过期时间…）整段打码，只留主机和路径。 */
    private val query = Regex("(https?://[^\\s?#\"']+)\\?[^\\s\"']+", RegexOption.IGNORE_CASE)

    fun init(c: Context) {
        dir = File(c.cacheDir, "diag").apply { mkdirs() }
        val cur = File(dir, "current.log"); val prev = File(dir, "previous.log")
        if (cur.exists()) { prev.delete(); cur.renameTo(prev) }
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { log("CRASH", "${t.name}: ${e.stackTraceToString()}"); flush() }
            old?.uncaughtException(t, e)
        }
        log("APP", "start ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT}) v${BuildInfo.version}")
    }

    fun mask(s: String): String = query.replace(userinfo.replace(secret.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + "***" }) { m -> m.groupValues[1] + "***@" }) { m -> m.groupValues[1] + "?***" }

    /**
     * H14: lines queue up and one background thread appends them in batches (about once a second), instead of an
     * open-write-close of the file for every line — log() is also called on the main thread.
     */
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val writer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "yw-diag").apply { isDaemon = true } }
    @Volatile private var scheduled = false
    private const val MAX_LOG = 2L * 1024 * 1024

    @Synchronized fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] ${mask(msg)}"
        buf.addLast(line); if (buf.size > 400) buf.removeFirst()
        if (!::dir.isInitialized) return
        pending.add(line)
        if (!scheduled) { scheduled = true; runCatching { writer.schedule({ scheduled = false; flush() }, 1, java.util.concurrent.TimeUnit.SECONDS) }.onFailure { scheduled = false } }
    }

    fun e(tag: String, t: Throwable) = log(tag, "ERROR ${t.javaClass.simpleName}: ${t.message}")

    /** Writes every queued line now (crash handler, export, background batch). */
    private fun flush() {
        if (!::dir.isInitialized || pending.isEmpty()) return
        synchronized(pending) {
            val sb = StringBuilder()
            while (true) { sb.append(pending.poll() ?: break).append('\n') }
            if (sb.isEmpty()) return
            runCatching {
                val f = File(dir, "current.log")
                if (f.length() > MAX_LOG) { val keep = f.readText().takeLast((MAX_LOG / 2).toInt()); f.writeText(keep) }
                f.appendText(sb.toString())
            }
        }
    }

    fun export(c: Context) = share(c, writeExport())

    /** 优化 10：写出导出文件（IO 线程调用）。只保留最近 3 份。 */
    fun writeExport(): File {
        flush()
        dir.listFiles { f -> f.name.startsWith("shiguang-diag-") }?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
        val out = File(dir, "shiguang-diag-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.txt")
        val sb = StringBuilder()
        sb.append("一维相册 诊断日志 v${BuildInfo.version}\n设备 ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}\n\n")
        val logs = listOf("previous.log", "current.log").mapNotNull { File(dir, it).takeIf { f -> f.exists() }?.readText() }
        val errCount = logs.sumOf { t -> t.lineSequence().count { it.contains("ERROR ") } }
        val crashCount = logs.sumOf { t -> t.lineSequence().count { it.contains("[CRASH]") } }
        sb.append("摘要：崩溃 $crashCount 次，错误 $errCount 条\n\n")
        File(dir, "previous.log").takeIf { it.exists() }?.let { sb.append("==== 上次运行 ====\n").append(it.readText().takeLast(200_000)).append("\n") }
        File(dir, "current.log").takeIf { it.exists() }?.let { sb.append("==== 本次运行 ====\n").append(it.readText().takeLast(200_000)) }
        out.writeText(mask(sb.toString()))
        log("APP", "diag exported (${out.length() / 1024} KB)")
        return out
    }

    fun share(c: Context, out: File) {
        val uri = FileProvider.getUriForFile(c, c.packageName + ".files", out)
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(Intent.createChooser(i, "导出诊断日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

object BuildInfo { val version: String get() = BuildConfig.VERSION_NAME; val code: Int get() = BuildConfig.VERSION_CODE; /** CI run number of a GitHub nightly (0 = local build); nightlies share a versionCode. */ val build: Int get() = BuildConfig.CI_RUN; val label: String get() = "$version（$code）" }
