package com.hark.shiguang

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hark.shiguang.data.FnClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

enum class TState { QUEUED, RUNNING, DONE, FAILED }

class TransferTask(val id: Long, val name: String, val upload: Boolean, val target: String) {
    var progress by mutableFloatStateOf(0f)
    var state by mutableStateOf(TState.QUEUED)
    var error by mutableStateOf<String?>(null)
    internal var retry: (() -> Unit)? = null
}

/** One queue for every upload and download, NAS or cloud. */
object Transfers {
    val tasks = mutableStateListOf<TransferTask>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(3)
    private var seq = 0L
    val active: Int get() = tasks.count { it.state == TState.QUEUED || it.state == TState.RUNNING }

    fun clearFinished() { tasks.removeAll { it.state == TState.DONE } }

    /** Generic job: [work] reports progress 0..1. */
    fun enqueue(name: String, upload: Boolean, target: String, work: suspend (progress: (Float) -> Unit) -> Unit): TransferTask {
        val t = TransferTask(++seq, name, upload, target)
        fun run() {
            t.state = TState.QUEUED; t.error = null; t.progress = 0f
            scope.launch {
                gate.withPermit {
                    t.state = TState.RUNNING
                    runCatching { work { p -> t.progress = p.coerceIn(0f, 1f) } }
                        .onSuccess { t.progress = 1f; t.state = TState.DONE }
                        .onFailure { e -> t.state = TState.FAILED; t.error = e.message ?: "失败"; Diag.e("XFER", e) }
                }
            }
        }
        t.retry = { run() }
        tasks.add(0, t); run(); return t
    }

    fun retry(t: TransferTask) { t.retry?.invoke() }

    /** Download [url] (with [headers]) into the phone gallery: Pictures/一维相册 or Movies/一维相册. */
    fun download(c: Context, url: String, fileName: String, headers: Map<String, String>, video: Boolean): TransferTask =
        enqueue(fileName, false, "手机相册") { progress ->
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            FnClient.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw Exception("下载失败（HTTP ${r.code}）")
                val body = r.body ?: throw Exception("空响应")
                val total = body.contentLength()
                val (out, finish) = openGalleryOutput(c, fileName, video, r.header("Content-Type"))
                out.use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    body.byteStream().use { ins ->
                        while (true) {
                            val n = ins.read(buf); if (n < 0) break
                            o.write(buf, 0, n); done += n
                            if (total > 0) progress(done.toFloat() / total)
                        }
                    }
                }
                finish()
            }
        }

    /** 1.0.1: copies a local file (extracted live video, 回忆短片) into Pictures|Movies/一维相册. */
    fun saveFile(c: Context, f: File, name: String, video: Boolean): TransferTask =
        enqueue(name, false, "手机相册") { progress ->
            val (out, finish) = openGalleryOutput(c, name, video, null)
            out.use { o -> f.inputStream().use { it.copyTo(o) } }
            progress(1f); finish()
        }

    private fun mimeOf(name: String, video: Boolean, header: String?): String {
        if (!header.isNullOrBlank() && !header.startsWith("application/octet")) return header.substringBefore(";")
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: if (video) "video/mp4" else "image/jpeg"
    }

    private fun openGalleryOutput(c: Context, name: String, video: Boolean, ct: String?): Pair<OutputStream, () -> Unit> {
        val mime = mimeOf(name, video, ct)
        val isVideo = video || mime.startsWith("video")
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/一维相册")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val coll: Uri = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = c.contentResolver.insert(coll, cv) ?: throw Exception("无法写入相册")
            val os = c.contentResolver.openOutputStream(uri) ?: throw Exception("无法写入相册")
            return os to {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                c.contentResolver.update(uri, done, null, null)
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), "一维相册").apply { mkdirs() }
            val f = File(dir, name)
            return FileOutputStream(f) to { android.media.MediaScannerConnection.scanFile(c, arrayOf(f.absolutePath), arrayOf(mime), null) }
        }
    }
}

/** Phone media picked by the user or found by backup, ready to upload. */
data class LocalMedia(val uri: Uri, val name: String, val size: Long, val mime: String, val taken: Long)

object LocalMediaReader {
    suspend fun describe(c: Context, uri: Uri): LocalMedia = withContext(Dispatchers.IO) {
        var name = uri.lastPathSegment ?: "file"; var size = -1L
        runCatching {
            c.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) { name = cur.getString(0) ?: name; size = cur.getLong(1) }
            }
        }
        LocalMedia(uri, name, size, c.contentResolver.getType(uri) ?: "application/octet-stream", System.currentTimeMillis())
    }

    /** Images and videos added to the phone after [since] (epoch seconds). */
    fun newSince(c: Context, since: Long): List<LocalMedia> {
        val out = ArrayList<LocalMedia>()
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED)
        listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).forEach { coll ->
            runCatching {
                c.contentResolver.query(coll, proj, "${MediaStore.MediaColumns.DATE_ADDED} > ?", arrayOf(since.toString()), "${MediaStore.MediaColumns.DATE_ADDED} ASC")?.use { cur ->
                    while (cur.moveToNext()) {
                        val id = cur.getLong(0)
                        out += LocalMedia(android.content.ContentUris.withAppendedId(coll, id), cur.getString(1) ?: "$id", cur.getLong(2), cur.getString(3) ?: "", cur.getLong(4))
                    }
                }
            }
        }
        return out.sortedBy { it.taken }
    }
}
