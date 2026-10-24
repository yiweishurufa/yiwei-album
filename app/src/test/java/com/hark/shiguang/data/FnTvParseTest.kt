package com.hark.shiguang.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户 2026-10-23 反馈（清晰度）：飞牛影视返回 JSON → 档位 / 文件匹配。样例取自 fntv_danmu_all《飞牛影视API文档》8.1。 */
class FnTvParseTest {
    private val streamResp = JSONObject(
        """
        {"code":0,"data":{
          "file_stream":{"guid":"6a36","path":"/vol1/1000/video/xxx.mkv","file_name":"xxx.mkv","size":2332695484},
          "video_stream":{"media_guid":"6a36","codec_name":"h264","width":3840,"height":1608},
          "qualities":[
            {"bitrate":6255343,"resolution":"4k","progressive":true,"is_m3u8":false},
            {"bitrate":5000000,"resolution":"1080","progressive":true,"is_m3u8":false},
            {"bitrate":4000000,"resolution":"1080","progressive":true,"is_m3u8":false},
            {"bitrate":5000000,"resolution":"720","progressive":true,"is_m3u8":false},
            {"bitrate":4000000,"resolution":"720","progressive":true,"is_m3u8":false},
            {"bitrate":3000000,"resolution":"720","progressive":true,"is_m3u8":false}
          ]}}
        """.trimIndent()
    )

    @Test fun documentedSampleGivesFiveTiersWithoutTheOriginal() {
        val t = FnTvParse.tiersOf(streamResp)
        assertEquals(listOf("1080P 高码率", "1080P", "720P 高码率", "720P", "720P"), t.map { it.label })
        assertEquals(listOf("5 Mbps", "4 Mbps", "5 Mbps", "4 Mbps", "3 Mbps"), t.map { it.detail })
        assertEquals("1080", t[0].resolution)
        assertEquals(5_000_000L, t[0].bitrate)
    }

    @Test fun transcoded4kIsKeptWhenItIsNotTheFirstEntry() {
        val r = JSONObject("""{"code":0,"data":{"qualities":[
            {"bitrate":40000000,"resolution":"4k"},{"bitrate":20000000,"resolution":"4k"},{"bitrate":8000000,"resolution":"1080"}]}}""")
        assertEquals(listOf("4K", "1080P"), FnTvParse.tiersOf(r).map { it.label })
    }

    @Test fun m3u8AndDuplicateEntriesAreDropped() {
        val r = JSONObject("""{"data":{"qualities":[
            {"bitrate":9000000,"resolution":"1080"},
            {"bitrate":4000000,"resolution":"720","is_m3u8":true},
            {"bitrate":3000000,"resolution":"720"},{"bitrate":3000000,"resolution":"720"}]}}""")
        val t = FnTvParse.tiersOf(r)
        assertEquals(1, t.size)
        assertEquals("720P", t[0].label)
    }

    @Test fun playQualityBareListShapeIsRead() {
        val r = JSONObject("""{"code":0,"data":[{"bitrate":8000000,"resolution":"1080"},{"bitrate":2000000,"resolution":"480"}]}""")
        assertEquals(listOf("480P"), FnTvParse.tiersOf(r).map { it.label })
    }

    @Test fun missingOrOnlyOriginalGivesNoTiers() {
        assertTrue(FnTvParse.tiersOf(JSONObject("""{"code":0,"data":{}}""")).isEmpty())
        assertTrue(FnTvParse.tiersOf(JSONObject("""{"code":0,"data":{"qualities":[{"bitrate":1,"resolution":"1080"}]}}""")).isEmpty())
        assertTrue(FnTvParse.tiersOf(null).isEmpty())
    }

    @Test fun names() {
        assertEquals("4K", FnTvParse.resolutionName("4k"))
        assertEquals("1080P", FnTvParse.resolutionName("1080"))
        assertEquals("720P", FnTvParse.resolutionName("720p"))
        assertEquals("原画", FnTvParse.resolutionName("原画"))
        assertEquals("6.3 Mbps", FnTvParse.bitrateName(6_255_343))
        assertEquals("800 kbps", FnTvParse.bitrateName(800_000))
        assertEquals("", FnTvParse.bitrateName(0))
    }

    @Test fun tokenAndPlayLink() {
        assertEquals("8563b1d0", FnTvParse.token(JSONObject("""{"msg":"","code":0,"data":{"token":"8563b1d0"}}""")))
        assertNull(FnTvParse.token(JSONObject("""{"code":-1,"data":null}""")))
        val link = FnTvParse.playLink(JSONObject("""{"code":0,"data":{"play_link":"/v/media/abc/preset.m3u8"}}"""))
        assertEquals("/v/media/abc/preset.m3u8", link)
        assertEquals("http://nas:5666/v/media/abc/preset.m3u8", FnTvParse.absUrl("http://nas:5666/", link!!))
        assertEquals("https://x/y.m3u8", FnTvParse.absUrl("http://nas:5666", "https://x/y.m3u8"))
    }

    @Test fun fileMatchByPathThenNameAndSize() {
        val sl = JSONObject("""{"files":[
            {"guid":"a","path":"/vol1/1000/影视/A.mkv","file_name":"A.mkv","size":10},
            {"guid":"b","path":"/vol2/1000/备份/B.mkv","file_name":"B.mkv","size":20}]}""")
        val files = FnTvParse.files(sl)
        assertEquals("a", FnTvParse.matchFile(files, "fm:vol1/1000/影视/A.mkv", "A.mkv", 0)?.guid)
        assertEquals("a", FnTvParse.matchFile(files, "/vol1/1000/影视/A.mkv", "A.mkv", 0)?.guid)
        // same file seen under another mount path: name + size
        assertEquals("b", FnTvParse.matchFile(files, "fm:share/备份/B.mkv", "b.MKV", 20)?.guid)
        // a different file with the same name is not taken
        assertNull(FnTvParse.matchFile(files, "fm:other/B.mkv", "B.mkv", 21))
        assertNull(FnTvParse.matchFile(files, "fm:other/B.mkv", "B.mkv", 0))
    }

    @Test fun hitsAndCandidates() {
        val r = JSONObject("""{"code":0,"data":[
            {"guid":"t1","type":"TV","title":"翘楚"},{"guid":"m1","type":"Movie","title":"翘楚 电影"},{"guid":"","type":"Movie"}]}""")
        val hits = FnTvParse.hits(r)
        assertEquals(2, hits.size)
        assertEquals(listOf("m1", "t1"), FnTvParse.candidates(hits, isShow = false).map { it.guid })
        assertEquals(listOf("t1", "m1"), FnTvParse.candidates(hits, isShow = true).map { it.guid })
        val eps = FnTvParse.hits(JSONObject("""{"data":[{"guid":"e2","season_number":1,"episode_number":2}]}"""))
        assertEquals(2, eps.single().number)
    }

    @Test fun streamsAndPicks() {
        val sl = JSONObject("""{"audio_streams":[
            {"guid":"a0","media_guid":"m","language":"eng","index":1},{"guid":"a1","media_guid":"m","language":"chi","index":2}],
            "subtitle_streams":[{"guid":"s0","media_guid":"m","language":"chi","is_external":1},{"guid":"s1","media_guid":"m","language":"zho"}]}""")
        val audio = FnTvParse.streams(sl, "audio_streams")
        assertEquals("a1", FnTvParse.pickAudio(audio, "zh", null)?.guid)
        assertEquals("a0", FnTvParse.pickAudio(audio, "und", 0)?.guid)
        assertEquals("a1", FnTvParse.pickAudio(audio, null, 1)?.guid)
        assertNull(FnTvParse.pickAudio(emptyList(), "zh", 0))
        val subs = FnTvParse.streams(sl, "subtitle_streams")
        assertEquals("s1", FnTvParse.pickSubtitle(subs, "zh")?.guid)
        assertNull(FnTvParse.pickSubtitle(subs, null))
    }

    @Test fun encoderAndRememberedTier() {
        assertEquals("hevc", FnTvParse.videoEncoder("hevc", "main 10"))
        assertEquals("h264", FnTvParse.videoEncoder("hevc", "Rext"))
        assertEquals("hevc", FnTvParse.videoEncoder("av1", ""))
        assertEquals("h264", FnTvParse.videoEncoder("mpeg4", ""))
        val t = FnTvParse.tiersOf(streamResp)
        assertEquals(t[1], FnTvParse.pickTier(t, "1080|4000000"))
        assertEquals(t[2], FnTvParse.pickTier(t, "720|1234"))   // bitrate gone: highest 720
        assertNull(FnTvParse.pickTier(t, "4k|6255343"))
        assertNull(FnTvParse.pickTier(t, ""))
    }

    @Test fun pathNormalization() {
        assertEquals("/vol1/a/b.mkv", FnTvParse.normPath("fm:vol1//a/b.mkv/"))
        assertEquals("/vol1/a", FnTvParse.normPath("\\vol1\\a"))
    }
}
