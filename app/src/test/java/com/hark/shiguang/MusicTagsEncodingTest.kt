package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** 用户 2026-11-02 反馈：123云盘 WAV 的中文歌名 / 歌手 / 专辑显示成 ??。 */
class MusicTagsEncodingTest {
    private class Mem(val b: ByteArray) : MusicTags.Src {
        override val size: Long = b.size.toLong()
        override fun at(off: Long, len: Int): ByteArray? =
            if (off < 0 || off > b.size) null else b.copyOfRange(off.toInt(), minOf(b.size.toLong(), off + len).toInt())
    }

    private fun le32(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xff); o.write(v shr 8 and 0xff); o.write(v shr 16 and 0xff); o.write(v shr 24 and 0xff) }
    private fun chunk(o: ByteArrayOutputStream, id: String, body: ByteArray) {
        o.write(id.toByteArray(Charsets.ISO_8859_1)); le32(o, body.size); o.write(body); if (body.size % 2 == 1) o.write(0)
    }
    private fun info(fields: List<Pair<String, ByteArray>>): ByteArray {
        val o = ByteArrayOutputStream(); o.write("INFO".toByteArray())
        fields.forEach { (id, v) -> chunk(o, id, v + byteArrayOf(0)) }
        return o.toByteArray()
    }
    private fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 0x7f).toByte(), (n shr 14 and 0x7f).toByte(), (n shr 7 and 0x7f).toByte(), (n and 0x7f).toByte())
    /** ID3v2.3 with UTF-16 (BOM) text frames. */
    private fun id3(frames: List<Pair<String, String>>): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { (id, v) ->
            val data = byteArrayOf(1) + byteArrayOf(0xff.toByte(), 0xfe.toByte()) + v.toByteArray(Charsets.UTF_16LE)
            body.write(id.toByteArray()); body.write(byteArrayOf((data.size shr 24).toByte(), (data.size shr 16).toByte(), (data.size shr 8).toByte(), data.size.toByte()))
            body.write(byteArrayOf(0, 0)); body.write(data)
        }
        val b = body.toByteArray()
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0) + syncsafe(b.size) + b
    }
    private fun wav(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        val fmt = ByteArray(16).also { it[0] = 1; it[2] = 2; it[4] = 0x44; it[5] = 0xac.toByte(); it[8] = 0x10; it[9] = 0xb1.toByte(); it[10] = 2; it[12] = 4; it[14] = 16 }
        chunk(o, "fmt ", fmt)
        chunk(o, "data", ByteArray(1000))
        chunks.forEach { (id, b) -> chunk(o, id, b) }
        val body = o.toByteArray()
        val r = ByteArrayOutputStream(); r.write("RIFF".toByteArray()); le32(r, body.size + 4); r.write("WAVE".toByteArray()); r.write(body)
        return r.toByteArray()
    }

    @Test fun `INFO 里是问号、id3 块是正确中文时用 id3`() {
        val f = wav("LIST" to info(listOf("INAM" to "????".toByteArray(), "IART" to "??&???".toByteArray())),
            "id3 " to id3(listOf("TIT2" to "晴天", "TPE1" to "周杰伦", "TALB" to "叶惠美")))
        val t = MusicTags.read(Mem(f), "wav")
        assertEquals("晴天", t.title); assertEquals("周杰伦", t.artist); assertEquals("叶惠美", t.album)
    }

    @Test fun `INFO 是 GBK 字节时按 GBK 解出中文`() {
        val gbk = charset("GBK")
        val f = wav("LIST" to info(listOf("INAM" to "稻香".toByteArray(gbk), "IART" to "周杰伦".toByteArray(gbk))))
        val t = MusicTags.read(Mem(f), "wav")
        assertEquals("稻香", t.title); assertEquals("周杰伦", t.artist)
    }

    @Test fun `INFO 是 UTF-8 时照常读`() {
        val f = wav("LIST" to info(listOf("INAM" to "七里香".toByteArray(Charsets.UTF_8))))
        assertEquals("七里香", MusicTags.read(Mem(f), "wav").title)
    }

    @Test fun `只有问号标签时当作没有标签（回退文件名）`() {
        val f = wav("LIST" to info(listOf("INAM" to "??".toByteArray(), "IART" to "???".toByteArray(), "IPRD" to "?? ????".toByteArray())))
        val t = MusicTags.read(Mem(f), "wav")
        assertEquals("", t.title); assertEquals("", t.artist); assertEquals("", t.album); assertFalse(t.hasAny)
    }

    @Test fun `乱码判定`() {
        assertTrue(MusicTags.isGarbled("??"))
        assertTrue(MusicTags.isGarbled("??&???&???"))
        assertTrue(MusicTags.isGarbled("????????? ??????"))
        assertFalse(MusicTags.isGarbled("Who?"))
        assertFalse(MusicTags.isGarbled("为什么?"))
        assertFalse(MusicTags.isGarbled(""))
        assertFalse(MusicTags.isGarbled("音乐(1)"))
    }

    @Test fun `旧缓存里的乱码歌要重读，新版读过的不再重读`() {
        val old = Track("1", "/a.wav", "a.wav", 1, 1, "??", "??", "", scanned = true)
        assertTrue(old.staleTags)
        assertFalse(old.copy(tagVer = Music.TAG_VER).staleTags)
        assertFalse(old.copy(title = "晴天", artist = "周杰伦", tagged = true).staleTags)
    }

    @Test fun `1_7_16 旧版没读到标签也没封面的歌重读一次，读过的和有封面的不重读`() {
        val poisoned = Track("2", "/b.mp3", "b.mp3", 1000, 1, "b", "", "", scanned = true, tagVer = 2)
        assertTrue(poisoned.staleTags)
        assertFalse(poisoned.copy(tagVer = Music.TAG_VER).staleTags)          // 新版读过（真没标签）不再反复重读
        assertFalse(poisoned.copy(tagged = true, artist = "周杰伦").staleTags)  // 读到过标签
        assertFalse(poisoned.copy(artPath = "/x/art.img").staleTags)          // 有封面（文件夹封面 / 联网补全）
        assertFalse(poisoned.copy(scanned = false).staleTags)                 // 还没读过的本来就会读
    }

    @Test fun `1_7_16 读不到文件算读失败要重试，不算没有标签`() {
        assertTrue(Music.readFailed(hasTags = false, size = 1000, reachable = false, gotBytes = false))   // 播放地址没查到
        assertTrue(Music.readFailed(hasTags = false, size = 1000, reachable = true, gotBytes = false))    // 请求全失败
        assertFalse(Music.readFailed(hasTags = false, size = 1000, reachable = true, gotBytes = true))    // 读到了，真没标签
        assertFalse(Music.readFailed(hasTags = true, size = 1000, reachable = true, gotBytes = true))
        assertFalse(Music.readFailed(hasTags = false, size = 0, reachable = true, gotBytes = false))      // 空文件
    }

    @Test fun `歌词文本编码识别`() {
        assertEquals("[00:01.00]你好", MusicTags.decodeText("[00:01.00]你好".toByteArray(charset("GBK"))))
        assertEquals("[00:01.00]你好", MusicTags.decodeText("[00:01.00]你好".toByteArray(Charsets.UTF_8)))
    }
}
