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
        .replace(Regex("(?i)(token|key|password|pass|authorization|cookie|secret)[=:\\s]+[^\\s,;\"]+"), "$1=<hidden>")
        .replace(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+"), "<email>")

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
