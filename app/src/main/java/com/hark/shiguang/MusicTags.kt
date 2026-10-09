package com.hark.shiguang

import android.util.Base64
import java.nio.charset.Charset

/**
 * 1.0.3 #4: embedded tag reader for the music library, working on byte RANGES of a remote file (no full download).
 *
 * Supported: ID3v2.2/2.3/2.4 (mp3, also inside wav / aiff / dsf), ID3v1, FLAC (Vorbis comment + PICTURE + STREAMINFO),
 * Ogg Vorbis / Opus (comment packet, METADATA_BLOCK_PICTURE, duration from the last granule), MP4 / M4A (ilst atoms,
 * mvhd duration, mp4a / alac codec; moov at the end is followed), APEv2 (ape, wv, mpc, mp3), Monkey's Audio header
 * duration, WavPack header duration, RIFF WAVE (fmt / data / LIST INFO / id3 chunk), AIFF (COMM / ID3 chunk),
 * DSF (fmt + ID3 at the metadata pointer), ASF / WMA (Content Description, Extended Content Description, WM/Picture,
 * File Properties duration).
 * Pure Kotlin on purpose: jaudiotagger needs java.nio.file / ImageIO, which Android lacks or only partly has.
 *
 * Chinese files often carry GBK bytes in ID3 "ISO-8859-1" fields; [latin] detects that and decodes as GBK.
 */
object MusicTags {
    class Tags {
        var title = ""; var artist = ""; var album = ""; var albumArtist = ""
        var year = 0; var track = 0; var disc = 0; var durationMs = 0L
        var codec = ""; var lyrics = ""; var picture: ByteArray? = null
        val hasAny get() = title.isNotBlank() || artist.isNotBlank() || album.isNotBlank()
        fun mergeFrom(o: Tags) {
            if (title.isBlank()) title = o.title; if (artist.isBlank()) artist = o.artist; if (album.isBlank()) album = o.album
            if (albumArtist.isBlank()) albumArtist = o.albumArtist; if (year == 0) year = o.year; if (track == 0) track = o.track
            if (disc == 0) disc = o.disc; if (durationMs == 0L) durationMs = o.durationMs; if (codec.isBlank()) codec = o.codec
            if (lyrics.isBlank()) lyrics = o.lyrics; if (picture == null) picture = o.picture
        }
    }

    /** Random access to the remote file. [at] returns up to [len] bytes from [off] (fewer at EOF), null when unreadable. */
    interface Src {
        val size: Long
        fun at(off: Long, len: Int): ByteArray?
    }

    private const val MAX_TAG = 6 * 1024 * 1024   // biggest ID3 / moov / APE tag we fetch (covers big cover art)

    fun read(src: Src, ext: String): Tags {
        val t = Tags()
        val head = src.at(0, 256 * 1024) ?: return t
        runCatching {
            when (ext) {
                "flac" -> flac(src, head, t)
                "ogg", "oga", "opus" -> ogg(src, head, t)
                "m4a", "mp4", "aac", "alac", "m4b" -> if (isMp4(head)) mp4(src, head, t) else mp3(src, head, t)
                "wav" -> riff(src, head, t)
                "aiff", "aif", "aifc" -> aiff(src, head, t)
                "dsf" -> dsf(src, head, t)
                "dff" -> dff(src, head, t)
                "wma", "asf" -> asf(head, t)
                "ape" -> { ape(src, head, t) }
                "wv" -> { wavpack(head, t) }
                else -> mp3(src, head, t)
            }
        }.onFailure { Diag.log("MUSIC", "tag $ext: ${it.javaClass.simpleName} ${it.message}") }
        // APEv2 / ID3v1 live at the end of the file (ape, wv, mpc and some mp3)
        if (!t.hasAny || ext in setOf("ape", "wv", "mpc") || t.picture == null && ext == "mp3") runCatching { tail(src, t) }
        if (t.codec.isEmpty()) t.codec = ext
        return t
    }

    // ------------------------------------------------------------------ helpers

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun be24(b: ByteArray, i: Int) = (u8(b, i) shl 16) or (u8(b, i + 1) shl 8) or u8(b, i + 2)
    private fun be32(b: ByteArray, i: Int) = (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()
    private fun be64(b: ByteArray, i: Int) = (be32(b, i) shl 32) or be32(b, i + 4)
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun le32(b: ByteArray, i: Int) = u8(b, i).toLong() or (u8(b, i + 1).toLong() shl 8) or (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)
    private fun le64(b: ByteArray, i: Int) = le32(b, i) or (le32(b, i + 4) shl 32)
    private fun syncsafe(b: ByteArray, i: Int) = ((u8(b, i) and 0x7f) shl 21) or ((u8(b, i + 1) and 0x7f) shl 14) or ((u8(b, i + 2) and 0x7f) shl 7) or (u8(b, i + 3) and 0x7f)
    private fun ascii(b: ByteArray, i: Int, n: Int) = if (i + n > b.size) "" else String(b, i, n, Charsets.ISO_8859_1)
    private val GBK: Charset = runCatching { Charset.forName("GBK") }.getOrDefault(Charsets.ISO_8859_1)

    /** "ISO-8859-1" bytes that are really GBK (very common in Chinese mp3s) or UTF-8. */
    fun latin(b: ByteArray, off: Int = 0, len: Int = b.size - off): String {
        if (len <= 0) return ""
        val s = b.copyOfRange(off, off + len)
        if (s.none { it < 0 }) return String(s, Charsets.ISO_8859_1)
        utf8Strict(s)?.let { return it }
        val g = runCatching { GBK.newDecoder().decode(java.nio.ByteBuffer.wrap(s)).toString() }.getOrNull()
        return g ?: String(s, Charsets.ISO_8859_1)
    }
    fun utf8Strict(s: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(s)).toString()
    }.getOrNull()

    /** Text bytes of unknown encoding (.lrc files, cue): BOM → UTF-16 / UTF-8, then strict UTF-8, then GBK. */
    fun decodeText(b: ByteArray): String = when {
        b.size >= 3 && u8(b, 0) == 0xef && u8(b, 1) == 0xbb && u8(b, 2) == 0xbf -> String(b, 3, b.size - 3, Charsets.UTF_8)
        b.size >= 2 && u8(b, 0) == 0xff && u8(b, 1) == 0xfe -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
        b.size >= 2 && u8(b, 0) == 0xfe && u8(b, 1) == 0xff -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
        else -> utf8Strict(b) ?: runCatching { String(b, GBK) }.getOrDefault(String(b, Charsets.ISO_8859_1))
    }

    private fun num(s: String): Int = Regex("\\d+").find(s)?.value?.take(9)?.toIntOrNull() ?: 0
    private fun yearOf(s: String): Int = Regex("(19|20)\\d{2}").find(s)?.value?.toIntOrNull() ?: 0

    /** Sets a field from a Vorbis-comment / APE / MP4-style key. */
    private fun put(t: Tags, key0: String, v0: String) {
        val v = v0.trim().trim('\u0000'); if (v.isEmpty()) return
        when (key0.trim().uppercase()) {
            "TITLE" -> if (t.title.isEmpty()) t.title = v
            "ARTIST" -> if (t.artist.isEmpty()) t.artist = v
            "ALBUM" -> if (t.album.isEmpty()) t.album = v
            "ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST" -> if (t.albumArtist.isEmpty()) t.albumArtist = v
            "DATE", "YEAR", "ORIGINALDATE" -> if (t.year == 0) t.year = yearOf(v)
            "TRACKNUMBER", "TRACK" -> if (t.track == 0) t.track = num(v)
            "DISCNUMBER", "DISC" -> if (t.disc == 0) t.disc = num(v)
            "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "LYRIC" -> if (t.lyrics.isEmpty()) t.lyrics = v
        }
    }

    // ------------------------------------------------------------------ ID3v2

    private fun id3Size(b: ByteArray, at: Int = 0): Int =
        if (b.size >= at + 10 && ascii(b, at, 3) == "ID3") 10 + syncsafe(b, at + 6) + (if (u8(b, at + 5) and 0x10 != 0) 10 else 0) else 0

    /** Parses an ID3v2 tag starting at [b][0]. */
    private fun id3v2(b0: ByteArray, t: Tags) {
        if (b0.size < 10 || ascii(b0, 0, 3) != "ID3") return
        val ver = u8(b0, 3); val flags = u8(b0, 5)
        val size = minOf(syncsafe(b0, 6), b0.size - 10)
        var b = b0.copyOfRange(10, 10 + size)
        if (ver < 4 && flags and 0x80 != 0) b = unsync(b)
        var i = 0
        if (flags and 0x40 != 0 && b.size > 4) i += if (ver == 4) syncsafe(b, 0) else (be32(b, 0).toInt() + 4)
        val idLen = if (ver == 2) 3 else 4; val hdr = if (ver == 2) 6 else 10
        while (i + hdr <= b.size) {
            val id = ascii(b, i, idLen)
            if (id.isBlank() || id[0] == '\u0000') break
            val fs = when (ver) { 2 -> be24(b, i + 3); 4 -> syncsafe(b, i + 4); else -> be32(b, i + 4).toInt() }
            val fflags = if (ver == 2) 0 else be16(b, i + 8)
            val start = i + hdr
            if (fs <= 0 || start + fs > b.size) break
            var data = b.copyOfRange(start, start + fs)
            if (ver == 4 && fflags and 0x0002 != 0) data = unsync(data)
            if (ver == 4 && fflags and 0x0001 != 0 && data.size > 4) data = data.copyOfRange(4, data.size) // data length indicator
            runCatching { frame(id, data, t) }
            i = start + fs
        }
    }

    private fun unsync(b: ByteArray): ByteArray {
        val o = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) { o.write(b[i].toInt()); if (u8(b, i) == 0xff && i + 1 < b.size && b[i + 1].toInt() == 0) i++; i++ }
        return o.toByteArray()
    }

    private fun enc(e: Int): Charset = when (e) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
    private fun text(data: ByteArray, from: Int, e: Int): String {
        if (from >= data.size) return ""
        return if (e == 0) latin(data, from, data.size - from).trimEnd('\u0000') else String(data, from, data.size - from, enc(e)).trimEnd('\u0000')
    }
    /** End (exclusive) of a NUL-terminated string in encoding [e] starting at [from]. */
    private fun strEnd(d: ByteArray, from: Int, e: Int): Int {
        var i = from
        if (e == 1 || e == 2) { while (i + 1 < d.size && !(d[i].toInt() == 0 && d[i + 1].toInt() == 0)) i += 2; return i }
        while (i < d.size && d[i].toInt() != 0) i++
        return i
    }

    private fun frame(id: String, d: ByteArray, t: Tags) {
        if (d.isEmpty()) return
        val e = u8(d, 0)
        fun tx() = text(d, 1, e).split('\u0000').firstOrNull { it.isNotBlank() }.orEmpty().trim()
        when (id) {
            "TIT2", "TT2" -> if (t.title.isEmpty()) t.title = tx()
            "TPE1", "TP1" -> if (t.artist.isEmpty()) t.artist = text(d, 1, e).split('\u0000').filter { it.isNotBlank() }.joinToString("/").trim()
            "TALB", "TAL" -> if (t.album.isEmpty()) t.album = tx()
            "TPE2", "TP2" -> if (t.albumArtist.isEmpty()) t.albumArtist = tx()
            "TYER", "TYE", "TDRC", "TDOR", "TORY" -> if (t.year == 0) t.year = yearOf(tx())
            "TRCK", "TRK" -> if (t.track == 0) t.track = num(tx())
            "TPOS", "TPA" -> if (t.disc == 0) t.disc = num(tx())
            "TLEN", "TLE" -> if (t.durationMs == 0L) t.durationMs = tx().toLongOrNull()?.takeIf { it in 1000..36_000_000 } ?: 0L
            "USLT", "ULT" -> if (t.lyrics.isEmpty() && d.size > 4) { val s = strEnd(d, 4, e); t.lyrics = text(d, s + (if (e == 1 || e == 2) 2 else 1), e).trim() }
            "TXXX" -> {
                val s = strEnd(d, 1, e); val key = text(d.copyOfRange(0, s), 1, e)
                val v = text(d, s + (if (e == 1 || e == 2) 2 else 1), e)
                if (key.equals("LYRICS", true) || key.equals("UNSYNCEDLYRICS", true)) put(t, "LYRICS", v)
                if (key.equals("ALBUM ARTIST", true) || key.equals("ALBUMARTIST", true)) put(t, "ALBUMARTIST", v)
            }
            "APIC" -> if (t.picture == null || u8(d, strEnd(d, 1, 0) + 1) == 3) {
                val mimeEnd = strEnd(d, 1, 0); val type = u8(d, mimeEnd + 1)
                val descEnd = strEnd(d, mimeEnd + 2, e) + (if (e == 1 || e == 2) 2 else 1)
                if (descEnd < d.size && (t.picture == null || type == 3)) t.picture = d.copyOfRange(descEnd, d.size)
            }
            "PIC" -> if (t.picture == null && d.size > 6) { val descEnd = strEnd(d, 5, e) + (if (e == 1 || e == 2) 2 else 1); if (descEnd < d.size) t.picture = d.copyOfRange(descEnd, d.size) }
        }
    }

    /** Reads the whole ID3v2 at [off] (fetching more than the head when the tag holds big art). */
    private fun id3At(src: Src, have: ByteArray, off: Long, t: Tags) {
        val h = if (off == 0L) have else src.at(off, 10) ?: return
        val n = id3Size(h); if (n == 0) return
        val b = if (off == 0L && n <= have.size) have else src.at(off, minOf(n, MAX_TAG)) ?: return
        id3v2(b, t)
    }

    // ------------------------------------------------------------------ mp3

    private val BR = arrayOf(
        intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448), // V1 L1
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384),    // V1 L2
        intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320),     // V1 L3
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256),    // V2 L1
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),         // V2 L2/L3
    )

    private fun mp3(src: Src, head: ByteArray, t: Tags) {
        id3At(src, head, 0, t)
        t.codec = "mp3"
        if (t.durationMs > 0) return
        // first frame after the tag: Xing / Info / VBRI frame count, else CBR estimate
        val start0 = id3Size(head)
        val b = if (start0 + 4096 <= head.size) head else src.at(start0.toLong(), 8192)?.let { x -> ByteArray(start0) + x } ?: return
        var i = start0
        while (i + 4 < b.size && i < start0 + 65536) {
            if (u8(b, i) == 0xff && u8(b, i + 1) and 0xe0 == 0xe0) {
                val verBits = (u8(b, i + 1) shr 3) and 3; val layer = (u8(b, i + 1) shr 1) and 3
                val brIdx = u8(b, i + 2) shr 4; val srIdx = (u8(b, i + 2) shr 2) and 3; val chMode = u8(b, i + 3) shr 6
                if (verBits != 1 && layer != 0 && brIdx in 1..14 && srIdx != 3) {
                    val v1 = verBits == 3
                    val sr = intArrayOf(44100, 48000, 32000)[srIdx] / (if (v1) 1 else if (verBits == 2) 2 else 4)
                    val table = if (v1) 3 - layer else if (layer == 3) 3 else 4
                    val kbps = BR[table][brIdx]
                    val spf = if (layer == 3) 384 else if (layer == 2 || v1) 1152 else 576
                    val side = if (v1) (if (chMode == 3) 17 else 32) else (if (chMode == 3) 9 else 17)
                    val x = i + 4 + side
                    val tag = ascii(b, x, 4)
                    if ((tag == "Xing" || tag == "Info") && x + 12 <= b.size && u8(b, x + 7) and 1 != 0) {
                        val frames = be32(b, x + 8); if (frames > 0) { t.durationMs = frames * spf * 1000L / sr; return }
                    }
                    if (ascii(b, i + 36, 4) == "VBRI" && i + 54 <= b.size) { val frames = be32(b, i + 50); if (frames > 0) { t.durationMs = frames * spf * 1000L / sr; return } }
                    if (kbps > 0 && src.size > 0) t.durationMs = (src.size - start0) * 8L / kbps
                    return
                }
            }
            i++
        }
    }

    // ------------------------------------------------------------------ tail: APEv2 + ID3v1

    private fun tail(src: Src, t: Tags) {
        if (src.size < 128) return
        val tailLen = minOf(src.size, 160L * 1024).toInt()
        val b = src.at(src.size - tailLen, tailLen) ?: return
        var end = b.size
        if (b.size >= 128 && ascii(b, b.size - 128, 3) == "TAG") {
            val v1 = Tags(); val o = b.size - 128
            v1.title = latin(b, o + 3, 30).trim('\u0000', ' '); v1.artist = latin(b, o + 33, 30).trim('\u0000', ' ')
            v1.album = latin(b, o + 63, 30).trim('\u0000', ' '); v1.year = yearOf(ascii(b, o + 93, 4))
            if (b[o + 125].toInt() == 0 && b[o + 126].toInt() != 0) v1.track = u8(b, o + 126)
            end -= 128
            apeFooter(src, b, end, t); t.mergeFrom(v1)
        } else apeFooter(src, b, end, t)
    }

    private fun apeFooter(src: Src, b: ByteArray, end: Int, t: Tags) {
        if (end < 32 || ascii(b, end - 32, 8) != "APETAGEX") return
        val f = end - 32
        val size = le32(b, f + 12).toInt(); val count = le32(b, f + 16).toInt()
        if (size <= 32 || count <= 0) return
        val itemsStart = end - size  // size includes the footer, not the header
        val items = if (itemsStart >= 0) b.copyOfRange(itemsStart, f) else {
            val abs = src.size - (b.size - end) - size
            src.at(abs, size - 32) ?: return
        }
        var i = 0
        repeat(minOf(count, 200)) {
            if (i + 8 > items.size) return
            val vl = le32(items, i).toInt(); val fl = le32(items, i + 4).toInt()
            var k = i + 8; while (k < items.size && items[k].toInt() != 0) k++
            val key = ascii(items, i + 8, k - i - 8)
            val vs = k + 1
            if (vl < 0 || vs + vl > items.size) return
            val binary = (fl shr 1) and 3 == 1
            if (binary && key.startsWith("Cover Art", true)) {
                if (t.picture == null || key.contains("Front", true)) {
                    var z = vs; while (z < vs + vl && items[z].toInt() != 0) z++
                    if (z + 1 < vs + vl) t.picture = items.copyOfRange(z + 1, vs + vl)
                }
            } else if (!binary) put(t, key, String(items, vs, vl, Charsets.UTF_8).split('\u0000').first())
            i = vs + vl
        }
    }

    // ------------------------------------------------------------------ FLAC

    private fun flac(src: Src, head: ByteArray, t: Tags) {
        t.codec = "flac"
        var off = id3Size(head).toLong()
        if (off > 0) id3At(src, head, 0, t)
        var b = head; var base = 0L
        fun ensure(o: Long, n: Int): Boolean {
            if (o >= base && o + n <= base + b.size) return true
            b = src.at(o, maxOf(n, 65536)) ?: return false; base = o; return b.size >= n
        }
        if (!ensure(off, 4) || ascii(b, (off - base).toInt(), 4) != "fLaC") return
        off += 4
        var guard = 0
        while (guard++ < 64) {
            if (!ensure(off, 4)) return
            val h = (off - base).toInt()
            val last = u8(b, h) and 0x80 != 0; val type = u8(b, h) and 0x7f; val len = be24(b, h + 1)
            val body = off + 4
            when (type) {
                0 -> if (ensure(body, 18)) {
                    val p = (body - base).toInt()
                    val sr = (be24(b, p + 10) shr 4)
                    val total = ((u8(b, p + 13) and 0x0f).toLong() shl 32) or be32(b, p + 14)
                    if (sr > 0 && total > 0) t.durationMs = total * 1000 / sr
                }
                4 -> if (len < MAX_TAG && ensure(body, len)) vorbisComments(b.copyOfRange((body - base).toInt(), (body - base).toInt() + len), 0, t)
                6 -> if (len < MAX_TAG && (t.picture == null) && ensure(body, len)) flacPicture(b.copyOfRange((body - base).toInt(), (body - base).toInt() + len), t)
            }
            off = body + len
            if (last) break
        }
    }

    private fun flacPicture(p: ByteArray, t: Tags) {
        var i = 4
        val ml = be32(p, i).toInt(); i += 4 + ml
        val dl = be32(p, i).toInt(); i += 4 + dl + 16
        val n = be32(p, i).toInt(); i += 4
        if (n > 0 && i + n <= p.size) t.picture = p.copyOfRange(i, i + n)
    }

    /** Vorbis comment block (FLAC block 4 / Ogg comment packet after its 7- or 8-byte magic). */
    private fun vorbisComments(b: ByteArray, from: Int, t: Tags) {
        var i = from
        val vl = le32(b, i).toInt(); i += 4 + vl
        val n = le32(b, i).toInt(); i += 4
        repeat(minOf(n, 500)) {
            if (i + 4 > b.size) return
            val l = le32(b, i).toInt(); i += 4
            if (l < 0 || i + l > b.size) return
            val c = String(b, i, l, Charsets.UTF_8); i += l
            val eq = c.indexOf('='); if (eq <= 0) return@repeat
            val k = c.substring(0, eq); val v = c.substring(eq + 1)
            if (k.equals("METADATA_BLOCK_PICTURE", true)) { if (t.picture == null) runCatching { flacPicture(Base64.decode(v, Base64.DEFAULT), t) } }
            else if (k.equals("COVERART", true)) { if (t.picture == null) runCatching { t.picture = Base64.decode(v, Base64.DEFAULT) } }
            else put(t, k, v)
        }
    }

    // ------------------------------------------------------------------ Ogg

    private fun ogg(src: Src, head: ByteArray, t: Tags) {
        // reassemble the first packets from the pages in the head
        val packets = ArrayList<ByteArray>(); val cur = java.io.ByteArrayOutputStream()
        var i = 0; var rate = 0; var opus = false
        while (i + 27 <= head.size && packets.size < 3 && ascii(head, i, 4) == "OggS") {
            val segs = u8(head, i + 26); var p = i + 27 + segs
            for (s in 0 until segs) {
                val l = u8(head, i + 27 + s)
                if (p + l > head.size) break
                cur.write(head, p, l); p += l
                if (l < 255) { packets.add(cur.toByteArray()); cur.reset() }
            }
            i = p
        }
        packets.getOrNull(0)?.let { id ->
            if (ascii(id, 0, 8) == "OpusHead") { opus = true; rate = 48000; t.codec = "opus" }
            else if (id.size > 16 && ascii(id, 1, 6) == "vorbis") { rate = le32(id, 12).toInt(); t.codec = "vorbis" }
            else if (ascii(id, 1, 4) == "FLAC") { t.codec = "flac" }
        }
        packets.getOrNull(1)?.let { c ->
            if (ascii(c, 0, 8) == "OpusTags") vorbisComments(c, 8, t)
            else if (c.size > 7 && ascii(c, 1, 6) == "vorbis") vorbisComments(c, 7, t)
            else if (c.isNotEmpty() && (u8(c, 0) and 0x7f) == 4) vorbisComments(c, 4, t)
        }
        // big comment packets (cover art) span beyond the head: fetch more and retry once
        if (packets.size < 2 && head.size >= 200 * 1024) src.at(0, MAX_TAG)?.let { if (it.size > head.size) return ogg(src, it, t) }
        if (rate > 0 && src.size > 0) {
            val tl = minOf(src.size, 65536L).toInt()
            val tb = src.at(src.size - tl, tl) ?: return
            var k = tb.size - 27
            while (k >= 0) {
                if (ascii(tb, k, 4) == "OggS") { val g = le64(tb, k + 6); if (g > 0) { val pre = if (opus) 312 else 0; t.durationMs = (g - pre) * 1000 / rate }; break }
                k--
            }
        }
    }

    // ------------------------------------------------------------------ MP4

    private fun isMp4(h: ByteArray) = h.size > 12 && ascii(h, 4, 4).let { it == "ftyp" || it == "moov" || it == "mdat" || it == "free" }

    private fun mp4(src: Src, head: ByteArray, t: Tags) {
        // find moov among the top-level boxes (it may follow a huge mdat)
        var off = 0L; var guard = 0
        while (guard++ < 32 && (src.size <= 0 || off + 8 <= src.size)) {
            val h = if (off + 16 <= head.size) head.copyOfRange(off.toInt(), off.toInt() + 16) else src.at(off, 16) ?: return
            if (h.size < 8) return
            var sz = be32(h, 0); val type = ascii(h, 4, 4)
            if (sz == 1L && h.size >= 16) sz = be64(h, 8) else if (sz == 0L) sz = (src.size - off)
            if (sz < 8) return
            if (type == "moov") {
                val n = minOf(sz, MAX_TAG.toLong()).toInt()
                val b = if (off + n <= head.size) head.copyOfRange(off.toInt(), off.toInt() + n) else src.at(off, n) ?: return
                moov(b, 8, b.size, t); return
            }
            off += sz
        }
    }

    private fun boxes(b: ByteArray, from: Int, to: Int, f: (type: String, start: Int, end: Int) -> Unit) {
        var i = from
        while (i + 8 <= to) {
            var sz = be32(b, i).toInt(); val type = ascii(b, i + 4, 4); var hdr = 8
            if (sz == 1 && i + 16 <= to) { sz = be64(b, i + 8).toInt(); hdr = 16 }
            if (sz == 0) sz = to - i
            if (sz < hdr || i + sz > to) { if (sz >= hdr) f(type, i + hdr, to); return }
            f(type, i + hdr, i + sz); i += sz
        }
    }

    private fun moov(b: ByteArray, from: Int, to: Int, t: Tags) {
        boxes(b, from, to) { type, s, e ->
            when (type) {
                "mvhd" -> {
                    val v = u8(b, s)
                    val (scale, dur) = if (v == 1) be32(b, s + 20) to be64(b, s + 24) else be32(b, s + 12) to be32(b, s + 16)
                    if (scale > 0 && dur > 0) t.durationMs = dur * 1000 / scale
                }
                "trak", "mdia", "minf", "stbl" -> moov(b, s, e, t)
                "stsd" -> if (e - s >= 16) { val c = ascii(b, s + 12, 4); t.codec = when (c) { "alac" -> "alac"; "mp4a" -> "aac"; "fLaC" -> "flac"; "Opus" -> "opus"; "ac-3" -> "ac3"; else -> c } }
                "udta" -> moov(b, s, e, t)
                "meta" -> moov(b, s + 4, e, t)
                "ilst" -> ilst(b, s, e, t)
            }
        }
    }

    private fun ilst(b: ByteArray, from: Int, to: Int, t: Tags) {
        boxes(b, from, to) { key, s, e ->
            var dataStart = -1; var dataEnd = -1; var name = ""
            boxes(b, s, e) { ty, ds, de -> if (ty == "data") { dataStart = ds + 8; dataEnd = de } else if (ty == "name" && de - ds > 4) name = String(b, ds + 4, de - ds - 4, Charsets.UTF_8) }
            if (dataStart < 0 || dataStart > dataEnd) return@boxes
            fun str() = String(b, dataStart, dataEnd - dataStart, Charsets.UTF_8)
            when (key) {
                "\u00a9nam" -> put(t, "TITLE", str())
                "\u00a9ART" -> put(t, "ARTIST", str())
                "\u00a9alb" -> put(t, "ALBUM", str())
                "aART" -> put(t, "ALBUMARTIST", str())
                "\u00a9day" -> put(t, "DATE", str())
                "\u00a9lyr" -> put(t, "LYRICS", str())
                "trkn" -> if (dataEnd - dataStart >= 4 && t.track == 0) t.track = be16(b, dataStart + 2)
                "disk" -> if (dataEnd - dataStart >= 4 && t.disc == 0) t.disc = be16(b, dataStart + 2)
                "covr" -> if (t.picture == null) t.picture = b.copyOfRange(dataStart, dataEnd)
                "----" -> if (name.equals("LYRICS", true)) put(t, "LYRICS", str())
            }
        }
    }

    // ------------------------------------------------------------------ RIFF WAVE / AIFF / DSF / DFF

    private fun riff(src: Src, head: ByteArray, t: Tags) {
        if (ascii(head, 0, 4) != "RIFF" || ascii(head, 8, 4) != "WAVE") return
        t.codec = "wav"
        var off = 12L; var byteRate = 0L; var guard = 0
        var b = head; var base = 0L
        while (guard++ < 64) {
            if (!(off >= base && off + 8 <= base + b.size)) { b = src.at(off, 65536) ?: return; base = off; if (b.size < 8) return }
            val i = (off - base).toInt()
            val id = ascii(b, i, 4); val len = le32(b, i + 4)
            when (id) {
                "fmt " -> if (i + 16 <= b.size) { byteRate = le32(b, i + 16); if (le16(b, i + 8) == 3) t.codec = "wav" }
                "data" -> if (byteRate > 0) t.durationMs = len * 1000 / byteRate
                "LIST" -> if (len < MAX_TAG) {
                    val lb = if (i + 8 + len <= b.size) b.copyOfRange(i + 8, i + 8 + len.toInt()) else src.at(off + 8, len.toInt())
                    if (lb != null && ascii(lb, 0, 4) == "INFO") {
                        var k = 4
                        while (k + 8 <= lb.size) {
                            val sid = ascii(lb, k, 4); val sl = le32(lb, k + 4).toInt()
                            if (sl < 0 || k + 8 + sl > lb.size) break
                            val v = latin(lb, k + 8, sl).trim('\u0000', ' ')
                            when (sid) { "INAM" -> put(t, "TITLE", v); "IART" -> put(t, "ARTIST", v); "IPRD" -> put(t, "ALBUM", v); "ICRD" -> put(t, "DATE", v); "ITRK", "IPRT" -> put(t, "TRACK", v) }
                            k += 8 + sl + (sl and 1)
                        }
                    }
                }
                "id3 ", "ID3 " -> if (len < MAX_TAG) id3At(src, ByteArray(0), off + 8, t)
            }
            off += 8 + len + (len and 1)
            if (src.size in 1..off) break
        }
    }

    private fun aiff(src: Src, head: ByteArray, t: Tags) {
        if (ascii(head, 0, 4) != "FORM") return
        t.codec = "aiff"
        var off = 12L; var guard = 0; var b = head; var base = 0L
        while (guard++ < 64) {
            if (!(off >= base && off + 8 <= base + b.size)) { b = src.at(off, 65536) ?: return; base = off; if (b.size < 8) return }
            val i = (off - base).toInt()
            val id = ascii(b, i, 4); val len = be32(b, i + 4)
            when (id) {
                "COMM" -> if (i + 26 <= b.size) {
                    val frames = be32(b, i + 10)
                    // 80-bit IEEE extended sample rate
                    val e = (be16(b, i + 16) and 0x7fff) - 16383; val m = be64(b, i + 18)
                    val rate = if (e in 0..62) (m ushr (63 - e)).toDouble() else 0.0
                    if (rate > 0) t.durationMs = (frames * 1000 / rate).toLong()
                }
                "ID3 ", "id3 " -> if (len < MAX_TAG) id3At(src, ByteArray(0), off + 8, t)
            }
            off += 8 + len + (len and 1)
            if (src.size in 1..off) break
        }
    }

    private fun dsf(src: Src, head: ByteArray, t: Tags) {
        if (ascii(head, 0, 4) != "DSD ") return
        t.codec = "dsd"
        val meta = le64(head, 20)
        if (ascii(head, 28, 4) == "fmt ") {
            val f = 28 + 12
            val rate = le32(head, f + 16); val count = le64(head, f + 24)
            if (rate > 0 && count > 0) t.durationMs = count * 1000 / rate
        }
        if (meta > 0 && (src.size <= 0 || meta < src.size)) id3At(src, ByteArray(0), meta, t)
    }

    private fun dff(src: Src, head: ByteArray, t: Tags) {
        if (ascii(head, 0, 4) != "FRM8") return
        t.codec = "dsd"
        // PROP/SND: FS (rate) + CHNL; DSD chunk size → duration. ID3 chunk (rare) at the end.
        var i = 16; var rate = 0L; var ch = 0
        while (i + 12 <= head.size) {
            val id = ascii(head, i, 4); val len = be64(head, i + 4)
            when (id) {
                "PROP" -> { var k = i + 16; val end = minOf(head.size, (i + 12 + len).toInt())
                    while (k + 12 <= end) { val sid = ascii(head, k, 4); val sl = be64(head, k + 4)
                        if (sid == "FS  ") rate = be32(head, k + 12); if (sid == "CHNL") ch = be16(head, k + 12)
                        k += 12 + sl.toInt() + (sl.toInt() and 1) }
                    i += 12 + len.toInt() + (len.toInt() and 1); continue }
                "DSD " -> { if (rate > 0 && ch > 0) t.durationMs = len * 8 * 1000 / (rate * ch); val after = i + 12 + len
                    if (src.size > after) id3At(src, ByteArray(0), after + 12, t); return }
            }
            if (len <= 0) return
            i += 12 + len.toInt() + (len.toInt() and 1)
        }
    }

    // ------------------------------------------------------------------ Monkey's Audio / WavPack

    private fun ape(src: Src, head: ByteArray, t: Tags) {
        t.codec = "ape"
        val o = id3Size(head)
        if (o > 0) id3At(src, head, 0, t)
        if (ascii(head, o, 4) != "MAC ") return
        val ver = le16(head, o + 4)
        if (ver >= 3980) {
            val descBytes = le32(head, o + 8).toInt()
            val h = o + descBytes
            if (h + 24 > head.size) return
            val bpf = le32(head, h + 4); val finalBlocks = le32(head, h + 8); val frames = le32(head, h + 12); val rate = le32(head, h + 20)
            if (rate > 0 && frames > 0) t.durationMs = ((frames - 1) * bpf + finalBlocks) * 1000 / rate
        } else if (o + 32 <= head.size) {
            val level = le16(head, o + 6); val rate = le32(head, o + 12); val frames = le32(head, o + 24); val finalBlocks = le32(head, o + 28)
            val bpf = if (ver >= 3950) 73728L * 4 else if (ver >= 3900 || (ver >= 3800 && level >= 4000)) 73728L else 9216L
            if (rate > 0 && frames > 0) t.durationMs = ((frames - 1) * bpf + finalBlocks) * 1000 / rate
        }
    }

    private val WV_RATES = intArrayOf(6000, 8000, 9600, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000, 192000)
    private fun wavpack(head: ByteArray, t: Tags) {
        t.codec = "wavpack"
        if (ascii(head, 0, 4) != "wvpk" || head.size < 32) return
        val total = le32(head, 12); val flags = le32(head, 24)
        val ri = ((flags shr 23) and 0xf).toInt()
        if (ri < WV_RATES.size && total > 0 && total != 0xffffffffL) t.durationMs = total * 1000 / WV_RATES[ri]
    }

    // ------------------------------------------------------------------ ASF / WMA

    private fun guid(b: ByteArray, i: Int): String = (0 until 16).joinToString("") { "%02X".format(u8(b, i + it)) }
    private const val G_HEADER = "3026B2758E66CF11A6D900AA0062CE6C"
    private const val G_CONTENT = "3326B2758E66CF11A6D900AA0062CE6C"
    private const val G_EXT = "40A4D0D207E3D21197F000A0C95EA850"
    private const val G_FILE = "A1DCAB8C47A9CF118EE400C00C205365"
    private const val G_STREAM = "9107DCB7B7A9CF118EE600C00C205365"

    private fun asf(b: ByteArray, t: Tags) {
        if (b.size < 30 || guid(b, 0) != G_HEADER) return
        t.codec = "wma"
        val n = le32(b, 24).toInt()
        var i = 30
        repeat(minOf(n, 64)) {
            if (i + 24 > b.size) return
            val g = guid(b, i); val sz = le64(b, i + 16).toInt()
            if (sz < 24) return
            val s = i + 24; val e = minOf(b.size, i + sz)
            when (g) {
                G_FILE -> if (s + 64 <= e) {
                    val play = le64(b, s + 40); val preroll = le64(b, s + 56)
                    if (play > 0) t.durationMs = (play / 10000 - preroll).coerceAtLeast(0)
                }
                G_CONTENT -> if (s + 10 <= e) {
                    val lens = IntArray(5) { le16(b, s + it * 2) }
                    var p = s + 10
                    val vals = lens.map { l -> val v = if (p + l <= e) String(b, p, l, Charsets.UTF_16LE).trimEnd('\u0000') else ""; p += l; v }
                    put(t, "TITLE", vals[0]); put(t, "ARTIST", vals[1])
                }
                G_EXT -> if (s + 2 <= e) {
                    val cnt = le16(b, s); var p = s + 2
                    repeat(cnt) {
                        if (p + 2 > e) return@repeat
                        val nl = le16(b, p); p += 2
                        val name = if (p + nl <= e) String(b, p, nl, Charsets.UTF_16LE).trimEnd('\u0000') else ""; p += nl
                        if (p + 4 > e) return@repeat
                        val type = le16(b, p); val vl = le16(b, p + 2); p += 4
                        if (p + vl > e) return@repeat
                        val str = if (type == 0) String(b, p, vl, Charsets.UTF_16LE).trimEnd('\u0000') else if (type == 3) le32(b, p).toString() else if (type == 2 || type == 5) le16(b, p).toString() else ""
                        when (name) {
                            "WM/AlbumTitle" -> put(t, "ALBUM", str)
                            "WM/AlbumArtist" -> put(t, "ALBUMARTIST", str)
                            "WM/Year" -> put(t, "DATE", str)
                            "WM/TrackNumber", "WM/Track" -> put(t, "TRACK", if (name == "WM/Track" && str.toIntOrNull() != null) (str.toInt() + 1).toString() else str)
                            "WM/PartOfSet" -> put(t, "DISC", str)
                            "WM/Lyrics" -> put(t, "LYRICS", str)
                            "WM/Picture" -> if (t.picture == null && vl > 6) {
                                var q = p + 5 // type(1) + data length(4)
                                while (q + 1 < p + vl && !(b[q].toInt() == 0 && b[q + 1].toInt() == 0)) q += 2; q += 2 // mime
                                while (q + 1 < p + vl && !(b[q].toInt() == 0 && b[q + 1].toInt() == 0)) q += 2; q += 2 // description
                                if (q < p + vl) t.picture = b.copyOfRange(q, p + vl)
                            }
                        }
                        p += vl
                    }
                }
            }
            i += sz
        }
    }
}
