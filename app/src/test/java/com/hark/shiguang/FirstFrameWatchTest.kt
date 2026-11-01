package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Test

/** 用户 2026-11-02 反馈「播放仍会黑屏」：首帧看门狗的步骤。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FirstFrameWatchTest {
    private fun run(w: FirstFrameWatch, id: String, from: Long, to: Long, playing: Boolean = true): List<FirstFrameWatch.Action> {
        val out = ArrayList<FirstFrameWatch.Action>()
        var t = from
        while (t <= to) { w.tick(id, t, playing).takeIf { it != FirstFrameWatch.Action.NONE }?.let { out += it }; t += 500 }
        return out
    }

    @Test fun `一直没有画面：重挂画面、换解码器、提示，各一次`() {
        val w = FirstFrameWatch()
        assertEquals(listOf(FirstFrameWatch.Action.REATTACH, FirstFrameWatch.Action.NEXT_DECODER, FirstFrameWatch.Action.GIVE_UP), run(w, "a", 0, 30_000))
    }

    @Test fun `出了画面就不再动作`() {
        val w = FirstFrameWatch()
        run(w, "a", 0, 2_000); w.onFirstFrame()
        assertEquals(emptyList<FirstFrameWatch.Action>(), run(w, "a", 2_500, 30_000))
    }

    @Test fun `暂停和缓冲不计时`() {
        val w = FirstFrameWatch()
        assertEquals(emptyList<FirstFrameWatch.Action>(), run(w, "a", 0, 60_000, playing = false))
        assertEquals(emptyList<FirstFrameWatch.Action>(), run(w, "a", 60_500, 63_000))
    }

    @Test fun `换集重新计时`() {
        val w = FirstFrameWatch()
        run(w, "a", 0, 5_000)
        assertEquals(listOf(FirstFrameWatch.Action.REATTACH), run(w, "b", 5_500, 10_000))
    }

    @Test fun `编码描述`() {
        assertEquals("HEVC 10bit 4K", VideoDecoders.describe("video/hevc", "hvc1.2.4.L153.B0", null, 3840, 2160))
        assertEquals("杜比视界 P5 1080p", VideoDecoders.describe("video/dolby-vision", "dvhe.05.06", 5, 1920, 1080))
        assertEquals("AVC", VideoDecoders.describe("video/avc", "avc1.64001f", null, 1280, 720))
    }
}
