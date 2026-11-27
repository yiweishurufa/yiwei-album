package com.hark.shiguang.ui

import com.hark.shiguang.ui.StorageUsage.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 1.7.13 存储空间页：分类口径，以及「可清理」与清理范围一致（不碰日志 / 更新包 / 崩溃记录）。 */
class StorageUsageTest {
    @Test fun `cacheDir 一级目录分类`() {
        assertEquals(Kind.THUMBS, StorageUsage.cacheKind("thumbs"))
        assertEquals(Kind.THUMBS, StorageUsage.cacheKind("davthumb"))
        assertEquals(Kind.VIDEO, StorageUsage.cacheKind("live"))
        assertEquals(Kind.VIDEO, StorageUsage.cacheKind("subs"))
        assertEquals(Kind.LOGS, StorageUsage.cacheKind("diag"))
        assertEquals(Kind.LOGS, StorageUsage.cacheKind("update"))
        assertEquals(Kind.LOGS, StorageUsage.cacheKind("crash-1.7.13.txt"))
        assertEquals(Kind.OTHER, StorageUsage.cacheKind("osmdroid"))
        assertEquals(Kind.OTHER, StorageUsage.cacheKind("share"))
    }

    @Test fun `filesDir 分类：影视音乐资料、日志、其余算索引`() {
        assertEquals(Kind.MEDIA, StorageUsage.filesKind("dav/abc/movies/img/1.jpg"))
        assertEquals(Kind.MEDIA, StorageUsage.filesKind("fn/u1/music/art/x.jpg"))
        assertEquals(Kind.INDEX, StorageUsage.filesKind("dav/abc/meta.json"))
        assertEquals(Kind.INDEX, StorageUsage.filesKind("dav/abc/faces/f.bin"))
        assertEquals(Kind.INDEX, StorageUsage.filesKind("hidden/list.json"))
        assertEquals(Kind.LOGS, StorageUsage.filesKind("crash/last.txt"))
        assertEquals(Kind.INDEX, StorageUsage.filesKind("movies"))
    }

    @Test fun `只有三类可清理`() {
        assertEquals(setOf(Kind.THUMBS, Kind.VIDEO, Kind.OTHER), Kind.values().filter { it.clearable }.toSet())
    }

    @Test fun `可清理大小不含日志、更新包、崩溃记录`() {
        val dir = Files.createTempDirectory("cache").toFile()
        try {
            dir.resolve("thumbs").apply { mkdirs() }.resolve("a").writeBytes(ByteArray(100))
            dir.resolve("diag").apply { mkdirs() }.resolve("log").writeBytes(ByteArray(1000))
            dir.resolve("update").apply { mkdirs() }.resolve("a.apk").writeBytes(ByteArray(1000))
            dir.resolve("crash-1.txt").writeBytes(ByteArray(1000))
            dir.resolve("edit-src-1.tmp").writeBytes(ByteArray(10))
            assertEquals(110L, StorageUsage.clearableCacheSize(dir))
            assertTrue(StorageUsage.keptInCache("diag"))
            assertFalse(StorageUsage.keptInCache("thumbs"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun percentLabelForScreenReader() {
        org.junit.Assert.assertEquals("占 40%", StorageUsage.percentLabel(40, 100))
        org.junit.Assert.assertEquals("占 33%", StorageUsage.percentLabel(1, 3))
        org.junit.Assert.assertEquals("不到 1%", StorageUsage.percentLabel(1, 1000))
        org.junit.Assert.assertEquals("占 0%", StorageUsage.percentLabel(0, 1000))
        org.junit.Assert.assertEquals("占 0%", StorageUsage.percentLabel(5, 0))
        org.junit.Assert.assertEquals("占 100%", StorageUsage.percentLabel(100, 100))
    }
}
