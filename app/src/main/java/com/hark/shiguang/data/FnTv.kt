package com.hark.shiguang.data

import com.hark.shiguang.Diag
import com.hark.shiguang.HashUtil
import com.hark.shiguang.MediaFile
import com.hark.shiguang.NasAccounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom

/** Why 飞牛影视 transcoding is not available for a file; [message] is shown to the user as is. */
class FnTvUnavailable(message: String) : Exception(message)

/**
 * 用户 2026-10-23 反馈（清晰度）：飞牛影视（trim.media）服务器转码。相册用的是 /p/api，这里是另一套 /v/api：
 * 自己的登录 token（POST /v/api/v1/login，飞牛系统账号）、自己的 Authx 签名密钥。
 *
 * 流程（依据见 [FnTvParse] 顶部注释；抓包文档 + FlyNarwhal 源码，未在真机抓包核对 —— 推测）：
 *  1. search/list?q=片名 → 电影 / 剧集 → (剧集) season/list → episode/list → 条目 guid
 *  2. stream/list/{条目} → files：按 NAS 路径（或文件名 + 大小）认出是不是同一个文件 → media_guid、视频 / 音轨 guid
 *  3. POST stream → qualities（转码档位）
 *  4. POST play/play（档位、开始秒数、音轨）→ play_link（HLS m3u8，同一 NAS 地址，Authorization 头鉴权）
 *  5. POST media/p {req: media.quit} 结束服务器转码会话
 * 任何一步失败都抛 [FnTvUnavailable]，播放器保持原画。日志只写步骤和档位，不写 token、完整地址。
 */
object FnTv {
    private const val TAG = "FNTV"
    // Authx: same scheme as /p/api ([AuthX]) but the /v/api secret (fntv_danmu_all auth_utils.dart, FlyNarwhal auth_interceptor.dart)
    private const val API_KEY = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh"
    private const val API_SECRET = "16CCEB3D-AB42-077D-36A1-F355324E4237"
    private const val UA = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    /** Search hits opened per keyword (each costs one or three requests). */
    private const val MAX_HITS = 4
    private val rnd = SecureRandom()

    @Volatile private var token = ""
    /** "url|user" the token belongs to. */
    @Volatile private var tokenFor = ""

    fun authx(path: String, payload: String): String {
        val nonce = (100000 + rnd.nextInt(900000)).toString()
        val ts = System.currentTimeMillis().toString()
        val sign = HashUtil.md5(listOf(API_KEY, path, nonce, ts, HashUtil.md5(payload), API_SECRET).joinToString("_"))
        return "nonce=$nonce&timestamp=$ts&sign=$sign"
    }

    private val base: String get() = FnClient.baseUrl

    /** fnOS relay addresses (FN Connect) need this cookie, like FlyNarwhal does. */
    private fun relayCookie(): String? {
        val host = base.substringAfter("://").substringBefore('/').substringBefore(':')
        return if (host.endsWith("fnos.net") || host.endsWith("5ddd.com")) "mode=relay" else null
    }

    /**
     * Auth headers for the HLS playlist / segments of a transcode session (they live under <NAS>/v/), null for any
     * other url so the original-file requests keep their own headers.
     */
    fun mediaHeaders(url: String): Map<String, String>? {
        val t = token
        if (t.isEmpty() || base.isEmpty() || !url.startsWith("$base/v/")) return null
        return buildMap { put("Authorization", t); relayCookie()?.let { put("Cookie", it) } }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun call(path: String, query: Map<String, String> = emptyMap(), body: JSONObject? = null, retry: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) {
            if (base.isEmpty()) throw FnTvUnavailable("没有登录飞牛")
            if (token.isEmpty() || tokenFor != key()) login()
            val sorted = query.toSortedMap()
            val raw = sorted.entries.joinToString("&") { "${it.key}=${it.value}" }
            val qs = sorted.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
            val text = body?.toString()
            val rb = Request.Builder().url(base + path + if (qs.isNotEmpty()) "?$qs" else "")
                .header("Authorization", token).header("Authx", authx(path, text ?: raw)).header("User-Agent", UA)
                .header("Accept", "application/json")
            relayCookie()?.let { rb.header("Cookie", it) }
            if (text != null) rb.post(text.toRequestBody("application/json; charset=utf-8".toMediaType()))
            val (code, out) = FnClient.http.newCall(rb.build()).execute().use { it.code to it.body?.string().orEmpty() }
            val j = runCatching { JSONObject(out) }.getOrNull()
            val api = j?.optInt("code", 0) ?: 0
            if ((code == 401 || code == 403 || api == 401 || api == 403) && retry) {
                Diag.log(TAG, "${path.substringAfter("/v1/").substringBefore('/')}: unauthorized, login again")
                token = ""; return@withContext call(path, query, body, false)
            }
            when {
                code == 404 -> throw FnTvUnavailable("这台 NAS 没有安装或没有打开「飞牛影视」")
                code !in 200..299 || j == null -> throw FnTvUnavailable("飞牛影视请求失败（HTTP $code）")
                api != 0 -> throw FnTvUnavailable(j.optString("msg").takeIf { it.isNotBlank() && it != "null" }?.let { "飞牛影视：$it" } ?: "飞牛影视返回错误 $api")
            }
            j ?: throw FnTvUnavailable("飞牛影视请求失败（HTTP $code）")
        }

    private fun key() = base + "|" + (NasAccounts.current?.user ?: "")

    /** POST /v/api/v1/login with the fnOS account the app is signed in with (飞牛影视 uses the system accounts). */
    private suspend fun login() = withContext(Dispatchers.IO) {
        val a = NasAccounts.current ?: throw FnTvUnavailable("没有登录飞牛")
        if (a.user.isBlank() || a.pass.isBlank()) throw FnTvUnavailable("飞牛账号没有保存密码，无法登录飞牛影视")
        val path = "/v/api/v1/login"
        val text = JSONObject().put("username", a.user).put("password", a.pass).put("app_name", "trimemedia-web").toString()
        val rb = Request.Builder().url(base + path).header("Authx", authx(path, text)).header("User-Agent", UA)
            .post(text.toRequestBody("application/json; charset=utf-8".toMediaType()))
        relayCookie()?.let { rb.header("Cookie", it) }
        val (code, out) = runCatching { FnClient.http.newCall(rb.build()).execute().use { it.code to it.body?.string().orEmpty() } }
            .getOrElse { throw FnTvUnavailable("连不上飞牛影视：${it.message ?: "网络错误"}") }
        if (code == 404) throw FnTvUnavailable("这台 NAS 没有安装或没有打开「飞牛影视」")
        val j = runCatching { JSONObject(out) }.getOrNull()
        val t = FnTvParse.token(j)
        if (t == null) {
            Diag.log(TAG, "login failed http=$code code=${j?.optInt("code")}")
            throw FnTvUnavailable("飞牛影视登录失败" + (j?.optString("msg")?.takeIf { it.isNotBlank() && it != "null" }?.let { "：$it" } ?: "（HTTP $code）"))
        }
        token = t; tokenFor = key()
        Diag.log(TAG, "login ok")
    }

    /** What the player needs to start a transcode of one file. */
    data class Info(
        val path: String,
        val mediaGuid: String,
        val video: FnTvStream,
        val audio: List<FnTvStream>,
        val subs: List<FnTvStream>,
        val tiers: List<FnTvTier>,
    )

    /** One finished lookup per file per run (the library does not change while watching). */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Info>()

    /**
     * Finds [file] in 飞牛影视 and reads its tiers. [titles] are search keywords (the library title, the file name);
     * [season] / [episode] pick the episode of a show.
     */
    suspend fun lookup(file: MediaFile, titles: List<String>, season: Int? = null, episode: Int? = null): Info {
        cache[key() + "|" + file.path]?.let { return it }
        val isShow = season != null && episode != null
        val ext = file.name.substringAfterLast('.', "").lowercase()
        Diag.log(TAG, "lookup ${if (isShow) "episode" else "movie"} .$ext")
        val tried = HashSet<String>()
        for (kw in titles.map { it.trim() }.filter { it.length >= 2 }.distinct()) {
            val hits = FnTvParse.candidates(FnTvParse.hits(call("/v/api/v1/search/list", mapOf("q" to kw))), isShow)
            Diag.log(TAG, "search: ${hits.size} hits")
            for (h in hits.take(MAX_HITS)) {
                val itemGuid = when {
                    h.type == "TV" && isShow -> episodeGuid(h.guid, season!!, episode!!) ?: continue
                    h.type == "TV" -> continue
                    else -> h.guid
                }
                if (!tried.add(itemGuid)) continue
                val sl = FnTvParse.dataObj(call("/v/api/v1/stream/list/$itemGuid")) ?: continue
                val f = FnTvParse.matchFile(FnTvParse.files(sl), file.path, file.name, file.size) ?: continue
                val video = FnTvParse.streams(sl, "video_streams").let { v -> v.firstOrNull { it.mediaGuid == f.guid } ?: v.firstOrNull() }
                    ?: throw FnTvUnavailable("飞牛影视没有这个文件的视频信息")
                val audio = FnTvParse.streams(sl, "audio_streams").let { a -> a.filter { it.mediaGuid == f.guid }.ifEmpty { a } }
                val subs = FnTvParse.streams(sl, "subtitle_streams").let { s -> s.filter { it.mediaGuid == f.guid }.ifEmpty { s } }
                val tiers = tiersOf(f.guid)
                Diag.log(TAG, "matched .$ext tiers=${tiers.joinToString(",") { it.resolution + "/" + it.bitrate }} video=${video.codec} audio=${audio.size}")
                return Info(file.path, f.guid, video, audio, subs, tiers).also { cache[key() + "|" + file.path] = it }
            }
        }
        Diag.log(TAG, "lookup: not in library")
        throw FnTvUnavailable("飞牛影视媒体库里没有这个文件，只能播放原画")
    }

    private suspend fun episodeGuid(tvGuid: String, season: Int, episode: Int): String? {
        val s = FnTvParse.hits(call("/v/api/v1/season/list/$tvGuid")).firstOrNull { it.number == season } ?: return null
        return FnTvParse.hits(call("/v/api/v1/episode/list/${s.guid}")).firstOrNull { it.number == episode }?.guid
    }

    /** POST stream (documented); falls back to POST play/quality (fntv-electron) when that has no list. */
    private suspend fun tiersOf(mediaGuid: String): List<FnTvTier> {
        val user = NasAccounts.current?.user.orEmpty()
        val body = JSONObject().put("media_guid", mediaGuid).put("ip", HashUtil.md5(user)).put("level", 1)
            .put("header", JSONObject().put("User-Agent", JSONArray().put(UA)))
        val fromStream = runCatching { FnTvParse.tiersOf(call("/v/api/v1/stream", body = body)) }
            .onFailure { Diag.log(TAG, "stream failed: ${it.message}") }.getOrDefault(emptyList())
        if (fromStream.isNotEmpty()) return fromStream
        return runCatching { FnTvParse.tiersOf(call("/v/api/v1/play/quality", body = JSONObject().put("media_guid", mediaGuid))) }
            .onFailure { Diag.log(TAG, "play/quality failed: ${it.message}") }.getOrDefault(emptyList())
    }

    /**
     * Starts a server transcode of [info] at [tier] from [startSec]; returns (absolute playlist url, raw play_link).
     * [audio] / [subtitle] are the streams to keep (null = first audio / no subtitle).
     */
    suspend fun play(info: Info, tier: FnTvTier, startSec: Long, audio: FnTvStream?, subtitle: FnTvStream?): Pair<String, String> {
        val body = JSONObject()
            .put("media_guid", info.mediaGuid).put("video_guid", info.video.guid)
            .put("video_encoder", FnTvParse.videoEncoder(info.video.codec, info.video.profile))
            .put("resolution", tier.resolution).put("bitrate", tier.bitrate)
            .put("startTimestamp", startSec.coerceAtLeast(0))
            .put("audio_encoder", "aac").put("audio_guid", (audio ?: info.audio.firstOrNull())?.guid.orEmpty())
            .put("subtitle_guid", subtitle?.guid.orEmpty())
            .put("channels", 2).put("forced_sdr", 0)
        val link = FnTvParse.playLink(call("/v/api/v1/play/play", body = body))
            ?: throw FnTvUnavailable("飞牛影视没有返回转码地址")
        Diag.log(TAG, "play ${tier.resolution}/${tier.bitrate} start=${startSec}s ok (${if (link.contains(".m3u8")) "m3u8" else "other"})")
        return FnTvParse.absUrl(base, link) to link
    }

    /** Ends a transcode session (best effort: the server also times idle sessions out). */
    suspend fun quit(playLink: String) {
        runCatching {
            call("/v/api/v1/media/p", body = JSONObject().put("req", "media.quit").put("reqid", "1234567890ABCDEF").put("playLink", playLink))
        }.onSuccess { Diag.log(TAG, "quit ok") }.onFailure { Diag.log(TAG, "quit failed: ${it.message}") }
    }
}
