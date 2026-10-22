package com.hark.shiguang

import com.hark.shiguang.data.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class LivePairingTest {
    private var nextId = 1
    private fun photo(path: String, video: Boolean = false) = Photo(
        id = nextId++, uuid = path, isVideo = video, isLive = false, fileName = path.substringAfterLast('/'),
        time = "2026:10:22 10:00:00", width = 0, height = 0, duration = 0, collected = false,
        thumbS = "", thumbM = "", original = "", video = if (video) "https://dav/$path" else null,
        make = "", model = "", fNumber = "", exposure = "", iso = "", focal = "", size = 1, geo = "", path = path,
        source = "acc1", cloudPath = path,
    )

    @Test fun `照片没有同名 MOV 时不崩溃（回归 c312054 的 null 键 NPE）`() {
        // ConcurrentHashMap.get(null) 会抛 NPE：只有一张有 MOV 时，另一张也不能拿 null 去查
        val meta = ConcurrentHashMap<String, Meta>()
        val a = photo("/DCIM/IMG_0001.HEIC"); val b = photo("/DCIM/IMG_0002.JPG"); val mov = photo("/DCIM/IMG_0001.MOV", video = true)
        meta[mov.cloudPath] = Meta(dur = 3)
        val out = LivePairing.pair(listOf(a, b, mov), meta)
        assertEquals(2, out.size)
        assertTrue(out.first { it.cloudPath == a.cloudPath }.isLive)
        assertFalse(out.first { it.cloudPath == b.cloudPath }.isLive)
    }

    @Test fun `完全没有 MOV 时原样返回`() {
        val list = listOf(photo("/a/1.jpg"), photo("/a/2.heic"))
        assertEquals(list, LivePairing.pair(list, ConcurrentHashMap()))
    }

    @Test fun `长于 6 秒或时长未知的 MOV 不配对也不隐藏`() {
        val meta = ConcurrentHashMap<String, Meta>()
        val img = photo("/x/IMG_1.jpg"); val mov = photo("/x/IMG_1.mov", video = true)
        meta[mov.cloudPath] = Meta(dur = 30)
        assertEquals(2, LivePairing.pair(listOf(img, mov), meta).size)
        meta.clear() // dur 未分析 = 0
        val out = LivePairing.pair(listOf(img, mov), meta)
        assertEquals(2, out.size); assertFalse(out[0].isLive)
    }

    @Test fun `只在同一文件夹内配对，大小写不敏感`() {
        val meta = ConcurrentHashMap<String, Meta>()
        val img = photo("/A/img_9.JPG"); val other = photo("/B/IMG_9.MOV", video = true); val same = photo("/a/IMG_9.mov", video = true)
        meta[other.cloudPath] = Meta(dur = 2); meta[same.cloudPath] = Meta(dur = 2)
        val out = LivePairing.pair(listOf(img, other, same), meta)
        assertEquals(same.video, out.first { it.cloudPath == img.cloudPath }.liveVideo)
        assertTrue(out.any { it.cloudPath == other.cloudPath })
        assertFalse(out.any { it.cloudPath == same.cloudPath })
    }

    @Test fun `motionOffset 读 MicroVideoOffset`() {
        val xmp = """<x:xmpmeta><rdf:Description GCamera:MicroVideo="1" GCamera:MicroVideoOffset="123456"/></x:xmpmeta>"""
        assertEquals(123456L, LivePairing.motionOffset(xmp.toByteArray()))
    }

    @Test fun `motionOffset 读 Container Item Length`() {
        val xmp = """<Camera:MotionPhoto>1</Camera:MotionPhoto><rdf:li><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"/></rdf:li>
            <rdf:li><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="98765"/></rdf:li>"""
        assertEquals(98765L, LivePairing.motionOffset(xmp.toByteArray()))
    }

    @Test fun `motionOffset 普通照片为 0，标记了但没偏移为 -1`() {
        assertEquals(0L, LivePairing.motionOffset("just a jpeg".toByteArray()))
        assertEquals(-1L, LivePairing.motionOffset("""GCamera:MicroVideo="1"""".toByteArray()))
    }

    @Test fun `findMp4 跳过 HEIC 自己的 ftyp，找到后面的视频`() {
        val heic = byteArrayOf(0, 0, 0, 24) + "ftypheic".toByteArray() + ByteArray(40)
        val mp4 = byteArrayOf(0, 0, 0, 20) + "ftypmp42".toByteArray() + ByteArray(30) { 7 }
        val found = LivePairing.findMp4(heic + mp4)
        assertNotNull(found)
        assertTrue(LivePairing.isMp4(found!!))
        assertEquals(mp4.size, found.size)
    }

    @Test fun `findMp4 没有视频时返回 null`() {
        assertNull(LivePairing.findMp4(ByteArray(200)))
        assertNull(LivePairing.findMp4(ByteArray(3)))
    }
}
