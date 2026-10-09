package com.hark.shiguang

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Path.Companion.toOkioPath
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Small, disk-cached thumbnails for WebDAV images (url ending in "#ywthumb").
 * Reads only the first 128 KB with a Range request and uses the JPEG's embedded EXIF thumbnail;
 * falls back to one full download, downsampled to ~400 px. Either way the result is kept as a
 * ~20 KB JPEG, so the grid never downloads originals twice.
 */
object DavThumb {
    const val MARK = "#ywthumb"
    private const val HEAD = 128 * 1024
    private val gate = Semaphore(8)
    private val videoGate = Semaphore(3)
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().dns(NetEnv.dns).socketFactory(NetEnv.socketFactory).connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
    }
    private val dir: File by lazy { File(App.ctx.cacheDir, "davthumb").apply { mkdirs() } }

    fun mark(raw: String) = raw + MARK

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun file(url: String): File = withContext(Dispatchers.IO) {
        val f = File(dir, md5(url) + ".jpg")
        if (f.length() > 0) { if (System.currentTimeMillis() - f.lastModified() > 86_400_000L) f.setLastModified(System.currentTimeMillis()); return@withContext f }
        gate.withPermit {
            if (f.length() > 0) return@withPermit f
            val bmp = (if (isVideo(url)) videoGate.withPermit { fromVideo(url) } else fromHead(url) ?: fromFull(url)) ?: error("缩略图加载失败")
            val tmp = File(dir, f.name + ".tmp")
            tmp.outputStream().use { (if (maxOf(bmp.width, bmp.height) > 480) Bitmap.createScaledBitmap(bmp, bmp.width * 480 / maxOf(bmp.width, bmp.height), bmp.height * 480 / maxOf(bmp.width, bmp.height), true) else bmp).compress(Bitmap.CompressFormat.JPEG, 78, it) }
            tmp.renameTo(f)
            f
        }
    }

    private fun req(url: String, range: Boolean): Request {
        val b = Request.Builder().url(url)
        ImageAuth.headersFor(url)?.forEach { (k, v) -> b.header(k, v) }
        if (range) b.header("Range", "bytes=0-${HEAD - 1}")
        return b.build()
    }

    private fun isJpeg(url: String) = url.substringBefore('?').lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }

    private fun isVideo(url: String) = com.hark.shiguang.cloud.CloudMedia.isVideo(Uri.decode(url.substringBefore('?')))

    /** Follow redirects ourselves (123pan → signed CDN url) so the retriever streams with Range requests. */
    fun finalUrl(url: String): String = runCatching {
        http.newCall(req(url, false).newBuilder().header("Range", "bytes=0-0").build()).execute().use { it.request.url.toString() }
    }.getOrDefault(url)

    /** First frame of a remote video without downloading the file (MediaMetadataRetriever reads only what it needs). */
    private fun fromVideo(url: String): Bitmap? {
        val real = finalUrl(url)
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            val h = if (real == url) (ImageAuth.headersFor(url) ?: emptyMap()) else emptyMap()
            mmr.setDataSource(real, h)
            val t = 1_000_000L
            val b = if (android.os.Build.VERSION.SDK_INT >= 27) mmr.getScaledFrameAtTime(t, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 400, 400)
                    else mmr.getFrameAtTime(t, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            b ?: mmr.frameAtTime
        } catch (_: Throwable) { null } finally { runCatching { mmr.release() } }
    }

    private fun fromHead(url: String): Bitmap? = runCatching {
        if (!isJpeg(url)) return null
        val bytes = http.newCall(req(url, true)).execute().use { r -> if (!r.isSuccessful) return null; r.body?.source()?.let { s -> s.request(HEAD.toLong()); s.buffer.readByteArray(minOf(s.buffer.size, HEAD.toLong())) } } ?: return null
        val ex = ExifInterface(ByteArrayInputStream(bytes))
        val t = ex.thumbnailBytes ?: return null
        val b = BitmapFactory.decodeByteArray(t, 0, t.size) ?: return null
        if (b.width < 120 && b.height < 120) return null
        rotate(b, ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1))
    }.getOrNull()

    private fun fromFull(url: String): Bitmap? = runCatching {
        val bytes = http.newCall(req(url, false)).execute().use { r -> if (!r.isSuccessful) return null; r.body?.bytes() } ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1; while (o.outWidth / (s * 2) >= 400 && o.outHeight / (s * 2) >= 400) s *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val orient = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        rotate(b, orient)
    }.getOrNull()

    private fun rotate(b: Bitmap, o: Int): Bitmap {
        val deg = when (o) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
        if (deg == 0f) return b
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.fragment != "ywthumb") return null
            val url = data.toString().removeSuffix(MARK)
            return Fetcher {
                val f = file(url)
                SourceResult(ImageSource(f.toOkioPath()), "image/jpeg", DataSource.DISK)
            }
        }
    }
}
