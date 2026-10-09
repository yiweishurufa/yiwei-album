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
    private val secret = Regex("(accesstoken|token|password|pass|secret|authx|access_token|Authorization)([\"'=:\\s]+)([^\"'&\\s,\\}]+)", RegexOption.IGNORE_CASE)

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

    fun mask(s: String): String = secret.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + "***" }

    @Synchronized fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] ${mask(msg)}"
        buf.addLast(line); if (buf.size > 400) buf.removeFirst()
        if (::dir.isInitialized) runCatching { File(dir, "current.log").appendText(line + "\n") }
    }

    fun e(tag: String, t: Throwable) = log(tag, "ERROR ${t.javaClass.simpleName}: ${t.message}")

    private fun flush() {}

    fun export(c: Context) {
        val out = File(dir, "shiguang-diag-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.txt")
        val sb = StringBuilder()
        sb.append("一维相册 诊断日志 v${BuildInfo.version}\n设备 ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}\n\n")
        File(dir, "previous.log").takeIf { it.exists() }?.let { sb.append("==== 上次运行 ====\n").append(it.readText().takeLast(200_000)).append("\n") }
        File(dir, "current.log").takeIf { it.exists() }?.let { sb.append("==== 本次运行 ====\n").append(it.readText().takeLast(200_000)) }
        out.writeText(mask(sb.toString()))
        val uri = FileProvider.getUriForFile(c, c.packageName + ".files", out)
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(Intent.createChooser(i, "导出诊断日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

object BuildInfo { val version: String get() = BuildConfig.VERSION_NAME; val code: Int get() = BuildConfig.VERSION_CODE; val label: String get() = "$version（$code）" }
