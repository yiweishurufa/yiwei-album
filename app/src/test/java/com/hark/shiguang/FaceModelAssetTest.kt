package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用户 2026-10-23 反馈 #7「人脸识别好像不工作」的根因：Faces.model() 读 assets/mobilefacenet.tflite，
 * 但这个文件从来没有提交进仓库（HANDOFF 里写着「未提交依赖」），CI 打出的 APK 里没有它 → 每次都「人脸模型加载失败」，
 * 一张脸都不会产出。这个测试守住它：文件必须在、必须是 TFLite 模型、输入输出形状和 Faces.embed 的假设一致。
 */
class FaceModelAssetTest {
    private val f = listOf("src/main/assets/mobilefacenet.tflite", "app/src/main/assets/mobilefacenet.tflite").map(::File).firstOrNull { it.exists() }

    @Test fun modelIsBundled() {
        assertTrue("assets/mobilefacenet.tflite 缺失：人脸识别会一直失败", f != null)
        assertTrue("模型文件太小，可能是 LFS 指针或下载失败", f!!.length() > 1_000_000)
    }

    @Test fun isTfliteFlatbuffer() {
        val head = f!!.readBytes().copyOfRange(4, 8).toString(Charsets.US_ASCII)
        assertEquals("TFL3", head)
    }

    @Test fun shapeMatchesEmbedCode() {
        // Faces.embed 写入 batch×112×112×3 float，读出 batch×192；这里在 flatbuffer 里找这两组 int32 形状
        val b = f!!.readBytes()
        fun has(vararg dims: Int): Boolean {
            val pat = java.nio.ByteBuffer.allocate(4 + dims.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { putInt(dims.size); dims.forEach { putInt(it) } }.array()
            outer@ for (i in 0..b.size - pat.size) { for (j in pat.indices) if (b[i + j] != pat[j]) continue@outer; return true }
            return false
        }
        assertTrue("输入形状应为 [2,112,112,3]", has(2, 112, 112, 3))
        assertTrue("输出形状应为 [2,192]", has(2, 192))
    }
}
