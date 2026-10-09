package com.hark.shiguang

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.*
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.hark.shiguang.data.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * 1.0.1 #13 回忆短片: a portrait 720×1280 H.264/AAC MP4 slideshow (Ken Burns pan/zoom, cross-fades, a title card)
 * with the bundled royalty-free track res/raw/memory_ambient.m4a (tools/gen_memory_music.py).
 *
 * Pure platform APIs (MediaCodec + MediaMuxer), no extra dependencies. Frames are drawn with Canvas into a Bitmap and
 * converted to YUV through the codec's flexible input Image, so explicit presentation times work on every vendor
 * encoder (no EGL surface timing quirks). The audio track is copied (not re-encoded) and cut to the video length.
 */
object MemoryVideo {
    const val W = 720
    const val H = 1280
    private const val FPS = 24
    private const val SEC_PER_PHOTO = 3.0
    private const val FADE_SEC = 0.6
    private const val TITLE_SEC = 2.5
    const val MAX_PHOTOS = 30

    /** Stills only, oldest first, evenly sampled down to [MAX_PHOTOS]. */
    fun pick(photos: List<Photo>, max: Int = MAX_PHOTOS): List<Photo> {
        val l = photos.filter { !it.isVideo && it.thumbM.isNotEmpty() }.sortedBy { it.time }
        if (l.size <= max) return l
        return List(max) { i -> l[(i.toDouble() * (l.size - 1) / (max - 1)).toInt()] }.distinct()
    }

    fun durationSec(n: Int) = TITLE_SEC + n * SEC_PER_PHOTO

    /** Output folder in cacheDir (shared through the existing FileProvider cache-path). */
    fun dir(c: Context) = File(c.cacheDir, "memory").apply { mkdirs() }

    /**
     * Renders the clip. [progress] gets 0..1 (first 30 % = loading photos, rest = encoding).
     * Throws when no photo could be loaded. Cancellable (coroutine cancellation is checked per frame).
     */
    suspend fun render(c: Context, title: String, subtitle: String, photos: List<Photo>, progress: (Float) -> Unit): File = withContext(Dispatchers.Default) {
        val app = c.applicationContext
        // ---- 1. load bitmaps (scaled so the short side covers the frame with a little zoom headroom)
        val bmps = ArrayList<Bitmap>()
        photos.forEachIndexed { i, p ->
            coroutineContext.ensureActive()
            val r = runCatching {
                app.imageLoader.execute(ImageRequest.Builder(app).data(p.thumbM).size(1280).allowHardware(false).build())
            }.getOrNull()
            ((r as? SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let { bmps.add(it) }
            progress(0.3f * (i + 1) / photos.size)
        }
        if (bmps.isEmpty()) throw IllegalStateException("照片都没能加载，检查网络后再试")

        val out = File(dir(app), "回忆-${System.currentTimeMillis()}.mp4")
        val totalSec = durationSec(bmps.size)
        val totalFrames = (totalSec * FPS).toInt()

        // ---- 2. video encoder
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // audio source (copied as-is)
        val ex = MediaExtractor()
        var audioTrackSrc = -1
        var audioFmt: MediaFormat? = null
        runCatching {
            app.resources.openRawResourceFd(R.raw.memory_ambient).use { afd -> ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length) }
            for (t in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(t)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { audioTrackSrc = t; audioFmt = f; break }
            }
        }
        var vTrack = -1
        var aTrack = -1
        var muxStarted = false
        val info = MediaCodec.BufferInfo()
        val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val argb = IntArray(W * H)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        fun drain(eos: Boolean) {
            while (true) {
                val idx = enc.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        vTrack = mux.addTrack(enc.outputFormat)
                        audioFmt?.let { aTrack = mux.addTrack(it) }
                        mux.start(); muxStarted = true
                    }
                    idx >= 0 -> {
                        val buf = enc.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxStarted) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            mux.writeSampleData(vTrack, buf, info)
                        }
                        enc.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            for (f in 0 until totalFrames) {
                coroutineContext.ensureActive()
                drawFrame(canvas, paint, bmps, f.toDouble() / FPS, title, subtitle)
                frame.getPixels(argb, 0, W, 0, 0, W, H)
                var inIdx: Int
                do { inIdx = enc.dequeueInputBuffer(10_000); if (inIdx < 0) drain(false) } while (inIdx < 0)
                val img = enc.getInputImage(inIdx)!!
                fillYuv(img, argb)
                enc.queueInputBuffer(inIdx, 0, W * H * 3 / 2, f * 1_000_000L / FPS, 0)
                drain(false)
                if (f % 6 == 0) progress(0.3f + 0.65f * f / totalFrames)
            }
            var inIdx: Int
            do { inIdx = enc.dequeueInputBuffer(10_000); if (inIdx < 0) drain(false) } while (inIdx < 0)
            enc.queueInputBuffer(inIdx, 0, 0, totalFrames * 1_000_000L / FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)

            // ---- 3. audio: copy AAC samples up to the video length
            if (muxStarted && aTrack >= 0 && audioTrackSrc >= 0) {
                ex.selectTrack(audioTrackSrc)
                val limitUs = (totalSec * 1_000_000).toLong()
                val ab = ByteBuffer.allocate(256 * 1024)
                val ai = MediaCodec.BufferInfo()
                while (true) {
                    val n = ex.readSampleData(ab, 0)
                    if (n < 0 || ex.sampleTime > limitUs) break
                    ai.set(0, n, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mux.writeSampleData(aTrack, ab, ai)
                    ex.advance()
                }
            }
            progress(1f)
        } catch (t: Throwable) {
            runCatching { enc.stop() }; runCatching { enc.release() }
            runCatching { if (muxStarted) mux.stop() }; runCatching { mux.release() }
            runCatching { ex.release() }
            out.delete()
            throw t
        }
        enc.stop(); enc.release()
        if (muxStarted) mux.stop()
        mux.release(); ex.release()
        frame.recycle()
        out
    }

    // ------------------------------------------------------------------ drawing
    private fun drawFrame(cv: Canvas, paint: Paint, bmps: List<Bitmap>, t: Double, title: String, subtitle: String) {
        cv.drawColor(Color.BLACK)
        val pt = t - TITLE_SEC
        if (pt < 0) {
            // title card over the first photo, dimmed
            drawKenBurns(cv, paint, bmps[0], 0, 0.0, 255)
            cv.drawColor(Color.argb(140, 0, 0, 0))
            val a = (min(1.0, t / 0.6) * 255).toInt()
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = a; textSize = 64f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
            drawWrapped(cv, title, tp, H / 2f - 20f)
            if (subtitle.isNotEmpty()) {
                tp.textSize = 34f; tp.typeface = Typeface.DEFAULT; tp.alpha = (a * 0.8).toInt()
                cv.drawText(subtitle, W / 2f, H / 2f + 60f, tp)
            }
            // fade from the title card into the first photo
            if (t > TITLE_SEC - FADE_SEC) drawKenBurns(cv, paint, bmps[0], 0, 0.0, (((t - (TITLE_SEC - FADE_SEC)) / FADE_SEC) * 255).toInt())
            return
        }
        val i = min(bmps.size - 1, (pt / SEC_PER_PHOTO).toInt())
        val local = pt - i * SEC_PER_PHOTO
        drawKenBurns(cv, paint, bmps[i], i, local / SEC_PER_PHOTO, 255)
        if (i + 1 < bmps.size && local > SEC_PER_PHOTO - FADE_SEC) {
            val k = (local - (SEC_PER_PHOTO - FADE_SEC)) / FADE_SEC
            drawKenBurns(cv, paint, bmps[i + 1], i + 1, 0.0, (k * 255).toInt())
        }
        // fade to black over the last second
        val endT = bmps.size * SEC_PER_PHOTO
        if (pt > endT - 1.0) cv.drawColor(Color.argb(((pt - (endT - 1.0)) * 255).toInt().coerceIn(0, 255), 0, 0, 0))
    }

    /** Cover-crop with a slow zoom (alternating in/out) and a small pan; [k] = 0..1 through this photo's slot. */
    private fun drawKenBurns(cv: Canvas, paint: Paint, b: Bitmap, index: Int, k: Double, alpha: Int) {
        val cover = maxOf(W.toFloat() / b.width, H.toFloat() / b.height)
        val kk = k.coerceIn(0.0, 1.0).toFloat()
        val zoom = if (index % 2 == 0) 1.0f + 0.10f * kk else 1.10f - 0.10f * kk
        val s = cover * zoom
        val dw = b.width * s; val dh = b.height * s
        val panX = (dw - W) * (if (index % 3 == 0) kk else if (index % 3 == 1) 1 - kk else 0.5f)
        val panY = (dh - H) * 0.5f
        paint.alpha = alpha.coerceIn(0, 255)
        cv.drawBitmap(b, null, RectF(-panX, -panY, -panX + dw, -panY + dh), paint)
        paint.alpha = 255
    }

    private fun drawWrapped(cv: Canvas, text: String, p: Paint, y: Float) {
        val maxW = W - 120f
        if (p.measureText(text) <= maxW) { cv.drawText(text, W / 2f, y, p); return }
        val n = p.breakText(text, true, maxW, null)
        cv.drawText(text.take(n), W / 2f, y - 40f, p)
        cv.drawText(text.drop(n).let { if (p.measureText(it) > maxW) it.take(p.breakText(it, true, maxW - p.measureText("…"), null)) + "…" else it }, W / 2f, y + 40f, p)
    }

    /** ARGB → YUV420 (BT.601 limited range) into the codec's flexible Image, honouring row/pixel strides. */
    private fun fillYuv(img: Image, argb: IntArray) {
        val pl = img.planes
        val yB = pl[0].buffer; val yRs = pl[0].rowStride; val yPs = pl[0].pixelStride
        val uB = pl[1].buffer; val uRs = pl[1].rowStride; val uPs = pl[1].pixelStride
        val vB = pl[2].buffer; val vRs = pl[2].rowStride; val vPs = pl[2].pixelStride
        for (y in 0 until H) {
            val row = y * W
            for (x in 0 until W) {
                val c = argb[row + x]
                val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
                yB.put(y * yRs + x * yPs, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                if (y and 1 == 0 && x and 1 == 0) {
                    val cy = y shr 1; val cx = x shr 1
                    uB.put(cy * uRs + cx * uPs, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                    vB.put(cy * vRs + cx * vPs, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
                }
            }
        }
    }

    // ------------------------------------------------------------------ share / save
    fun share(c: Context, f: File) {
        val uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        c.startActivity(Intent.createChooser(send, "分享回忆短片（微信等）").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun play(c: Context, f: File) {
        val uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copies the clip into the system gallery (Movies/一维相册). Android 10+ needs no permission; 8–9 uses the legacy path. */
    fun saveToGallery(c: Context, f: File): Boolean = runCatching {
        val cr = c.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/一维相册"); put(MediaStore.Video.Media.IS_PENDING, 1) }
        }
        val col = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        if (Build.VERSION.SDK_INT < 29) {
            val d = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "一维相册").apply { mkdirs() }
            val dst = File(d, f.name); f.copyTo(dst, true)
            v.put(MediaStore.Video.Media.DATA, dst.absolutePath)
            cr.insert(col, v); return@runCatching true
        }
        val uri = cr.insert(col, v) ?: return@runCatching false
        cr.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        v.clear(); v.put(MediaStore.Video.Media.IS_PENDING, 0); cr.update(uri, v, null, null)
        true
    }.getOrDefault(false)
}
