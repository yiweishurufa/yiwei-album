package com.hark.shiguang

import android.content.Context
import android.net.Uri
import com.hark.shiguang.cloud.WebDavSource
import com.hark.shiguang.data.NasX
import com.hark.shiguang.data.Photo
import com.hark.shiguang.ui.CloudPhotos
import com.hark.shiguang.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 1.0.1 WebDAV live photos: iPhone HEIC/JPG + same-name .MOV pairing, and Android Motion Photo detection from the
 * 128 KB header that analysis already reads (XMP GCamera:MicroVideo / MicroVideoOffset, or Camera:MotionPhoto +
 * Container:Item Semantic="MotionPhoto" Length).
 */
object LivePairing {
    private val IMG = setOf("jpg", "jpeg", "heic", "heif")
    private fun ext(n: String) = n.substringAfterLast('.', "").lowercase()
    private fun base(n: String) = n.substringBeforeLast('.').lowercase()
    private fun parent(path: String) = path.substringBeforeLast('/', "").lowercase()

    fun motionCandidateExt(name: String) = ext(name) in IMG

    private val NAME_HINT = Regex("""(?i)^(mvimg_|pxl_).*\.jpe?g$|.*[._]mp\.jpe?g$|.*\.mp$""")
    fun motionNameHint(name: String) = NAME_HINT.matches(name)

    /** Hides a .MOV that has a same-name photo in the same folder and marks the photo live (unless the MOV is known to be longer than 6 s). */
    fun pair(list: List<Photo>, meta: Map<String, Meta>): List<Photo> {
        val movs = HashMap<String, Photo>()
        for (p in list) if (p.isVideo && p.isCloud && ext(p.fileName) == "mov" && p.video != null) movs[parent(p.cloudPath) + "/" + base(p.fileName)] = p
        if (movs.isEmpty()) return list
        val used = HashSet<String>()
        val out = ArrayList<Photo>(list.size)
        for (p in list) {
            if (!p.isVideo && p.isCloud && ext(p.fileName) in IMG) {
                val mov = movs[parent(p.cloudPath) + "/" + base(p.fileName)]
                if (mov != null && (meta[mov.cloudPath]?.dur ?: 0) <= 6) {
                    out.add(p.copy(isLive = true, liveType = 1, liveVideo = mov.video)); used.add(mov.cloudPath); continue
                }
            }
            out.add(p)
        }
        return if (used.isEmpty()) out else out.filter { !(it.isVideo && it.cloudPath in used) }
    }

    private val OFFSET = Regex("""MicroVideoOffset\s*(?:=\s*"|>)\s*(\d+)""")
    private val FLAG_ON = Regex("""(?:GCamera:MicroVideo|MotionPhoto)\s*(?:=\s*"|>)\s*1\b""")
    private val LENGTH = Regex("""Length\s*(?:=\s*"|>)\s*(\d+)""")

    /** 0 = not a motion photo, >0 = MP4 length from the end of the file, -1 = motion photo without a usable offset. */
    fun motionOffset(head: ByteArray): Long {
        val s = String(head, Charsets.ISO_8859_1)
        if (!s.contains("MicroVideo") && !s.contains("MotionPhoto")) return 0
        OFFSET.find(s)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        val sem = s.indexOf("Semantic=\"MotionPhoto\"").takeIf { it >= 0 } ?: s.indexOf(">MotionPhoto<")
        if (sem >= 0) {
            val from = s.lastIndexOf("<rdf:li", sem).coerceAtLeast(maxOf(0, sem - 600))
            val to = s.indexOf("</rdf:li>", sem).let { if (it < 0) minOf(s.length, sem + 600) else it }
            LENGTH.find(s.substring(from, to))?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        return if (FLAG_ON.containsMatchIn(s)) -1 else 0
    }

    private val BRANDS = setOf("mp42", "isom", "mp41", "avc1", "iso2", "iso4", "iso5", "iso6", "M4V ", "qt  ", "3gp4", "3gp5")

    fun isMp4(b: ByteArray) = b.size > 12 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()

    /** First "ftyp" box with a video brand after the still image's own header (HEIC stills start with ftyp too). */
    fun findMp4(bytes: ByteArray): ByteArray? {
        var i = 16
        while (i < bytes.size - 12) {
            if (bytes[i] == 'f'.code.toByte() && bytes[i + 1] == 't'.code.toByte() && bytes[i + 2] == 'y'.code.toByte() && bytes[i + 3] == 'p'.code.toByte()) {
                val brand = String(bytes, i + 4, 4, Charsets.ISO_8859_1)
                if (brand in BRANDS && i >= 4) return bytes.copyOfRange(i - 4, bytes.size)
            }
            i++
        }
        return null
    }
}

/** 1.0.1: one entry point for "play / save the moving part" of any live photo (飞牛 types 1/2/3, WebDAV MOV pair / Motion Photo). */
object LiveMotion {
    private fun cacheFile(p: Photo): File {
        val h = MessageDigest.getInstance("MD5").digest(p.key.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
        return File(App.ctx.cacheDir, "live").apply { mkdirs() }.let { File(it, "$h.mp4") }
    }

    private suspend fun cached(p: Photo, bytes: suspend () -> ByteArray?): File? {
        val f = cacheFile(p)
        if (f.exists() && f.length() > 0) return f
        val b = bytes() ?: return null
        val t = File(f.path + ".tmp"); t.writeBytes(b); t.renameTo(f)
        // keep the cache small: oldest files beyond 40 go
        f.parentFile?.listFiles()?.filter { it.name.endsWith(".mp4") }?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
        return f
    }

    private fun davMp4(s: WebDavSource, p: Photo, mv: Long): ByteArray? {
        if (mv > 0 && p.size > mv) s.tailBytes(p.cloudPath, p.size - mv)?.let { if (LivePairing.isMp4(it)) return it }
        val all = s.allBytes(p.cloudPath) ?: return null
        return LivePairing.findMp4(all)
    }

    /** Embedded MP4 extracted to a cache file, or null when this live photo plays a separate stream. */
    private suspend fun embeddedFile(p: Photo): File? = withContext(Dispatchers.IO) {
        if (!p.isCloud) {
            val src = NasX.motionVideo(p) ?: return@withContext null
            if (!src.embedded) return@withContext null
            cached(p) { NasX.motionMp4Bytes(p, p.liveType.takeIf { it > 0 }) }
        } else {
            if (p.liveVideo != null) return@withContext null
            val lib = Dav.lib(p.source); val s = lib.src ?: return@withContext null
            cached(p) { davMp4(s, p, lib.meta[p.cloudPath]?.mv ?: -1L) }
        }
    }

    /** A url (http with the app's auth, or file://) the player can open; null when there is no moving part. */
    suspend fun playable(p: Photo): String? = withContext(Dispatchers.IO) {
        if (!p.isLive) return@withContext null
        runCatching {
            if (p.isCloud) {
                p.liveVideo ?: embeddedFile(p)?.let { Uri.fromFile(it).toString() }
            } else {
                val src = NasX.motionVideo(p) ?: return@runCatching null
                if (!src.embedded) src.url else embeddedFile(p)?.let { Uri.fromFile(it).toString() }
            }
        }.onFailure { Diag.e("LIVE", it) }.getOrNull()
    }

    /** 保存实况视频部分 → phone gallery. */
    suspend fun save(ctx: Context, p: Photo) {
        val c = ctx.applicationContext
        val name = p.fileName.substringBeforeLast('.').ifEmpty { "live_${p.id}" }
        runCatching {
            if (p.isCloud && p.liveVideo != null) {
                val src = CloudPhotos.source(p.source) ?: error("网盘账户已移除")
                Transfers.download(c, p.liveVideo, "$name.mov", src.headers(p.liveVideo), true)
            } else if (!p.isCloud && NasX.motionVideo(p)?.embedded == false) {
                val m = NasX.motionVideo(p)!!
                Transfers.download(c, m.url, "$name.mp4", m.headers, true)
            } else {
                val f = embeddedFile(p) ?: error("这张照片没有实况视频")
                Transfers.saveFile(c, f, "$name.mp4", true)
            }
            withContext(Dispatchers.Main) { toast(c, "已加入传输队列") }
        }.onFailure { withContext(Dispatchers.Main) { toast(c, it.message ?: "失败") } }
    }
}
