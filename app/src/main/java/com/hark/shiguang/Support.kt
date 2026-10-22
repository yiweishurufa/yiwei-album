package com.hark.shiguang

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 1.0.9: 关于 → 反馈 / 加入 QQ 群; also used by the crash dialog. */
object Feedback {
    const val EMAIL = "1@yiwei.cc.cd"
    const val QQ_GROUP = "887416704"

    fun deviceInfo(): String = "版本：${BuildInfo.label}\nAndroid：${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）\n机型：${Build.MANUFACTURER} ${Build.MODEL}"

    /** Opens the mail app with subject/body prefilled. [attachment] (a file in cacheDir) is shared through FileProvider. */
    fun email(c: Context, body: String = "", subjectExtra: String = "", attachment: File? = null) {
        val subject = "一维相册反馈 ${BuildInfo.version}" + subjectExtra
        val text = (if (body.isNotEmpty()) body + "\n\n" else "请描述遇到的问题：\n\n\n") + "——\n" + deviceInfo()
        val intent = if (attachment == null) Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$EMAIL?subject=${Uri.encode(subject)}&body=${Uri.encode(text)}")).apply {
            putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, text)
        } else {
            val uri = FileProvider.getUriForFile(c, c.packageName + ".files", attachment)
            Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL)); putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.let { Intent.createChooser(it, "发送给开发者") }
        }
        val ok = runCatching { c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        if (!ok) { copy(c, EMAIL); Toast.makeText(c, "没找到邮件应用，已复制邮箱 $EMAIL", Toast.LENGTH_LONG).show() }
    }

    fun joinQq(c: Context) {
        val uri = Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$QQ_GROUP&card_type=group&source=qrcode")
        val ok = runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        if (!ok) { copy(c, QQ_GROUP); Toast.makeText(c, "没打开 QQ，群号 $QQ_GROUP 已复制", Toast.LENGTH_LONG).show() }
    }

    fun copy(c: Context, text: String) {
        runCatching { c.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("一维相册", text)) }
    }
}

/**
 * 1.0.9: uncaught exceptions are written to filesDir/crash/last.txt (version, device, stack trace; no URLs/tokens),
 * then the default handler runs. Next launch [pending] returns it and MainActivity offers to email it.
 */
object CrashLog {
    private fun dir(c: Context) = File(c.filesDir, "crash").apply { mkdirs() }
    private fun last(c: Context) = File(dir(c), "last.txt")

    fun install(c: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    append("一维相册 崩溃记录 ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
                    append(Feedback.deviceInfo()).append('\n').append("线程：").append(t.name).append("\n\n")
                    append(scrub(sw.toString()).take(60_000))
                }
                last(c).writeText(text)
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** Removes anything that looks like a URL, token or e-mail from exception messages. */
    fun scrub(s: String): String = s
        .replace(Regex("https?://[^\\s\"')]+"), "<url>")
        // S21: signing material (si / otp / sign / signature) + URL userinfo, like Diag.mask.
        .replace(Regex("(?i)(token|key|password|pass|authorization|cookie|secret|si|otp|sign|signature)[=:\\s]+[^\\s,;\"]+"), "$1=<hidden>")
        .replace(Regex("([a-zA-Z][a-zA-Z0-9+.-]*://)([^/@\\s:]+)@"), "$1***@")
        .replace(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+"), "<email>")

    /**
     * 2026-10-22: a native crash (SIGSEGV / abort in a media library) or an ANR never reaches the Java handler above, so
     * the user got no「上次异常退出」dialog and the diagnostic log just stopped. On Android 11+ the system keeps the exit
     * reason: log it and, for a crash / ANR the user has not seen yet, put it in last.txt so the dialog offers to send it.
     * Background thread; reads at most the 5 latest exits.
     */
    fun recordSystemExit(c: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = c.getSystemService(android.app.ActivityManager::class.java) ?: return
            val seen = Store.getStr("crash.exit.seen").toLongOrNull() ?: 0L
            val exits = am.getHistoricalProcessExitReasons(c.packageName, 0, 5).filter { it.timestamp > seen }
            if (exits.isEmpty()) return
            Store.putStr("crash.exit.seen", exits.maxOf { it.timestamp }.toString())
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            exits.forEach { e -> Diag.log("EXIT", "${fmt.format(Date(e.timestamp))} ${reasonName(e.reason)} status=${e.status} importance=${e.importance} ${e.description.orEmpty().take(200)}") }
            val bad = exits.filter { it.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == android.app.ApplicationExitInfo.REASON_ANR }
                .maxByOrNull { it.timestamp } ?: return
            if (pending(c) != null) return   // a Java crash report is already waiting
            val trace = if (Build.VERSION.SDK_INT >= 31) runCatching { bad.traceInputStream?.use { traceText(it.readBytes(), bad.reason) } }.getOrNull().orEmpty() else ""
            if (trace.isNotEmpty()) Diag.log("EXIT", "trace: ${trace.take(1500)}")
            val text = buildString {
                append("一维相册 崩溃记录 ").append(fmt.format(Date(bad.timestamp))).append('\n')
                append(Feedback.deviceInfo()).append('\n').append("类型：").append(reasonName(bad.reason)).append("（系统记录，非 Java 异常）\n")
                append(bad.description.orEmpty()).append("\n\n").append(scrub(trace).take(30_000))
            }
            last(c).writeText(text)
        }.onFailure { Diag.log("EXIT", "read exit reasons failed: ${it.javaClass.simpleName}") }
    }

    private fun reasonName(r: Int): String = when (r) {
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃(native crash)"
        android.app.ApplicationExitInfo.REASON_CRASH -> "Java 崩溃"
        android.app.ApplicationExitInfo.REASON_ANR -> "无响应(ANR)"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被杀"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "被信号终止"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过高被杀"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "用户/系统强行停止"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "正常退出"
        android.app.ApplicationExitInfo.REASON_OTHER -> "其他"
        else -> "reason=$r"
    }

    /** ANR traces are text; a native tombstone is a protobuf — keep its readable strings (library names, frames, abort message). */
    private fun traceText(b: ByteArray, reason: Int): String {
        if (reason == android.app.ApplicationExitInfo.REASON_ANR) return String(b, Charsets.UTF_8).take(20_000)
        val out = StringBuilder(); val cur = StringBuilder()
        for (x in b) { val ch = x.toInt() and 0xff
            if (ch in 0x20..0x7e) cur.append(ch.toChar()) else { if (cur.length >= 6) out.append(cur).append('\n'); cur.setLength(0) }
            if (out.length > 20_000) break
        }
        if (cur.length >= 6) out.append(cur)
        return out.toString()
    }

    fun pending(c: Context): String? = last(c).takeIf { it.exists() && it.length() > 0 }?.readText()
    fun clear(c: Context) { runCatching { last(c).delete() } }

    /** Mail with the log in the body (truncated) and as an attachment. */
    fun send(c: Context, text: String) {
        val att = runCatching { File(c.cacheDir, "crash-${BuildInfo.version}.txt").apply { writeText(text) } }.getOrNull()
        val body = "上次异常退出的日志（节选，完整日志见附件）：\n\n" + text.take(6000)
        Feedback.email(c, body, " · 异常退出", att)
        clear(c)
    }
}
