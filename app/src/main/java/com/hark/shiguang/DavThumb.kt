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
    private val gate = Semaphore(6)
    private val videoGate = Semaphore(3)
    /** H3: at most 3 originals downloading for thumbnails at once. */
    private val fullGate = Semaphore(3)
    /** H3: originals above this are not downloaded for a thumbnail (placeholder instead). */
    private const val FULL_MAX = 40L * 1024 * 1024
    // derived from FnClient.http: shares its connection pool and inherits NetEnv dns/socketFactory
    private val http: OkHttpClient by lazy {
        com.hark.shiguang.data.FnClient.http.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
    }
    private val dir: File by lazy { File(App.ctx.cacheDir, "davthumb").apply { mkdirs() } }

    fun mark(raw: String) = raw + MARK

    /** B20: drop the cached thumbnail for [url] (called when the photo is added / removed / replaced). */
    fun invalidate(url: String) { runCatching { File(dir, HashUtil.md5(url) + ".jpg").delete() } }
    fun invalidateUrls(urls: Iterable<String>) { urls.forEach { invalidate(it) } }
    suspend fun file(url: String): File = withContext(Dispatchers.IO) {
        val f = File(dir, HashUtil.md5(url) + ".jpg")
        // B20: thumbnails never invalidated before — a file older than 7 days is re-fetched, so a replaced
        // photo on the server cannot keep showing its old thumbnail forever.
        if (f.length() > 0 && System.currentTimeMillis() - f.lastModified() > 7 * 86_400_000L) f.delete()
        if (f.length() > 0) { if (System.currentTimeMillis() - f.lastModified() > 86_400_000L) f.setLastModified(System.currentTimeMillis()); return@withContext f }
        gate.withPermit {
            if (f.length() > 0) return@withPermit f
            val bmp = (if (isVideo(url)) videoGate.withPermit { fromVideo(url) } else fromHead(url) ?: fullGate.withPermit { fromFull(url) }) ?: error("缩略图加载失败")
            val tmp = File(dir, f.name + ".tmp")
            val big = maxOf(bmp.width, bmp.height)
            val out = if (big > 480) Bitmap.createScaledBitmap(bmp, bmp.width * 480 / big, bmp.height * 480 / big, true) else bmp
            try { tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 78, it) } }
            finally { if (out !== bmp) out.recycle(); bmp.recycle() }   // H3: free the pixels now, not at the next GC
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
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

    /** S4: resolved redirect targets are cached 5 min — Analyzer, Viewer and the thumbnail loader all
     * resolve the same url, and each resolution is a network round trip. */
    private val finalUrlCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    fun invalidateFinalUrl(url: String) { finalUrlCache.remove(url) }
    /** H13: dropped by 「清理缓存」 (signed CDN urls expire anyway). */
    fun clearUrlCache() { finalUrlCache.clear() }

    /** Follow redirects ourselves (123pan → signed CDN url) so the retriever streams with Range requests. */
    fun finalUrl(url: String): String {
        finalUrlCache[url]?.takeIf { System.currentTimeMillis() - it.second < 300_000 }?.let { return it.first }
        val out = runCatching {
            http.newCall(req(url, false).newBuilder().header("Range", "bytes=0-0").build()).execute().use { it.request.url.toString() }
        }.getOrDefault(url)
        finalUrlCache[url] = out to System.currentTimeMillis()
        return out
    }

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

    /**
     * H3: the original is streamed to a temp file (never a whole ByteArray in memory), refused above [FULL_MAX],
     * then decoded with inJustDecodeBounds + inSampleSize to ~400 px straight from the file.
     */
    private fun fromFull(url: String): Bitmap? {
        val tmp = File(dir, "full-" + HashUtil.md5(url) + ".part")
        return try {
            http.newCall(req(url, false)).execute().use { r ->
                if (!r.isSuccessful) return null
                val body = r.body ?: return null
                val len = body.contentLength()
                if (len > FULL_MAX) { Diag.log("THUMB", "skip full download: ${len / 1048576} MB"); return null }
                body.byteStream().use { ins -> tmp.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024); var got = 0L
                    while (true) { val n = ins.read(buf); if (n < 0) break; got += n; if (got > FULL_MAX) return null; os.write(buf, 0, n) }
                } }
            }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var s = 1; while (o.outWidth / (s * 2) >= 400 && o.outHeight / (s * 2) >= 400) s *= 2
            val b = BitmapFactory.decodeFile(tmp.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
            val orient = runCatching { ExifInterface(tmp.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            rotate(b, orient)
        } catch (e: Throwable) {
            Diag.log("THUMB", "full decode failed: ${e.javaClass.simpleName}")
            null
        } finally { tmp.delete() }
    }

    private fun rotate(b: Bitmap, o: Int): Bitmap {
        val deg = when (o) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
        if (deg == 0f) return b
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
        if (r !== b) b.recycle()   // H3
        return r
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
