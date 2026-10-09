package com.hark.shiguang.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

class ApiException(val code: Int, msg: String) : Exception(msg)

/** Signing used by the fnOS Photos web front-end for every /p/api request. */
object AuthX {
    private const val KEY = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh"
    private const val SALT = "EAECCF25-80A6-4666-A7C2-A76904A74AB6"
    private val rnd = SecureRandom()

    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** [payload] is the sorted raw (unencoded) query for GET, or the JSON body for POST. */
    fun header(path: String, payload: String): String {
        val nonce = (100000 + rnd.nextInt(900000)).toString()
        val ts = System.currentTimeMillis().toString()
        val sign = md5(listOf(KEY, path, nonce, ts, md5(payload), SALT).joinToString("_"))
        return "nonce=$nonce&timestamp=$ts&sign=$sign"
    }
}

object FnClient {
    const val NEED_2FA = -2002
    @Volatile var baseUrl: String = ""
    @Volatile var token: String = ""
    private var user: String = ""
    private var pass: String = ""

    val http: OkHttpClient = OkHttpClient.Builder()
        .dns(com.hark.shiguang.NetEnv.dns).socketFactory(com.hark.shiguang.NetEnv.socketFactory)   // 1.0.2: bypass VPN for domestic hosts
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    fun normalize(input: String): String {
        var s = input.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        val scheme = s.substringBefore("://")
        val host = s.substringAfter("://").substringBefore("/")
        val hasPort = if (host.startsWith("[")) host.contains("]:") else host.contains(":")
        return "$scheme://" + if (hasPort) host else "$host:5666"
    }

    fun setCredentials(url: String, u: String, p: String) { baseUrl = url; user = u; pass = p; com.hark.shiguang.NetEnv.prime(url) }

    // ---------------------------------------------------------------- login (WebSocket)

    private var reqIndex = 1
    private fun reqId(): String = "%08x".format(System.currentTimeMillis() / 1000) + "0000000000000000" + "%04x".format(reqIndex++)

    private fun randomStr(n: Int): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val r = SecureRandom(); return (1..n).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    @Volatile private var lastAes = ""
    @Volatile private var lastIv = ByteArray(16)

    private fun encryptLogin(pub: String, si: String, u: String, p: String, otp: String = ""): String {
        val raw = JSONObject().apply {
            put("reqid", reqId()); put("user", u); put("password", p)
            put("deviceType", "Android"); put("deviceName", "一维相册-" + android.os.Build.MODEL)
            put("stay", true); put("req", "user.login"); put("si", si)
            if (otp.isNotEmpty()) put("otp", otp) // two-step code field name // UNVERIFIED
        }.toString()
        val aesKey = randomStr(32)
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        lastAes = aesKey; lastIv = iv
        val aes = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey.toByteArray(), "AES"), IvParameterSpec(iv))
            Base64.encodeToString(doFinal(raw.toByteArray()), Base64.NO_WRAP)
        }
        val clean = pub.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replace(Regex("\\s+"), "")
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(clean, Base64.DEFAULT)))
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, key); Base64.encodeToString(doFinal(aesKey.toByteArray()), Base64.NO_WRAP)
        }
        return JSONObject().apply {
            put("req", "encrypted"); put("iv", Base64.encodeToString(iv, Base64.NO_WRAP)); put("rsa", rsa); put("aes", aes)
        }.toString()
    }

    /** Returns the access token. */
    suspend fun login(url: String, u: String, p: String, otp: String = ""): String = withContext(Dispatchers.IO) {
        val wsUrl = url.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://") + "/websocket?type=main"
        val tk = withTimeout(20_000) {
            suspendCancellableCoroutine<String> { cont ->
                var done = false
                fun finish(r: Result<String>, ws: WebSocket) {
                    if (done) return; done = true
                    ws.close(1000, null)
                    r.onSuccess { cont.resume(it) }.onFailure { cont.resumeWith(Result.failure(it)) }
                }
                val ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(JSONObject().put("req", "util.crypto.getRSAPub").put("reqid", reqId()).toString())
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            val j = JSONObject(text)
                            when {
                                j.has("pub") -> webSocket.send(encryptLogin(j.getString("pub"), j.optString("si"), u, p, otp))
                                j.optString("result") == "succ" && j.has("token") -> {
                                    // upload signing key: login "secret" AES-decrypted with this login's key/iv
                                    j.optString("secret").takeIf { it.isNotEmpty() }?.let { sc ->
                                        runCatching { NasX.decodeLoginSecret(sc, lastAes, lastIv) }.onSuccess { k -> NasX.signSecretB64 = k; com.hark.shiguang.Store.putStr("sign.$u@$url", k) }
                                    }
                                    finish(Result.success(j.getString("token")), webSocket)
                                }
                                j.has("errno") && j.optInt("errno") != 0 -> {
                                    val e = j.optInt("errno")
                                    val msg = when (e) {
                                        4352, 131074 -> "用户名或密码错误"
                                        else -> "登录失败（错误码 $e）"
                                    }
                                    val need2fa = Regex("2fa|otp|totp|two.?factor", RegexOption.IGNORE_CASE).containsMatchIn(text)
                                    finish(Result.failure(if (need2fa) ApiException(NEED_2FA, if (otp.isEmpty()) "请输入二步验证码" else "验证码不正确") else ApiException(e, msg)), webSocket)
                                }
                            }
                        } catch (t: Throwable) { finish(Result.failure(t), webSocket) }
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        finish(Result.failure(ApiException(-1, "无法连接 NAS：${t.message ?: "网络错误"}")), webSocket)
                    }
                })
                cont.invokeOnCancellation { ws.cancel() }
            }
        }
        baseUrl = url; user = u; pass = p; token = tk
        tk
    }

    // ---------------------------------------------------------------- HTTP

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    suspend fun get(path: String, params: Map<String, Any?> = emptyMap()): JSONObject =
        call(path, params.filterValues { it != null }.mapValues { it.value.toString() }, null)

    suspend fun post(path: String, body: JSONObject): JSONObject = call(path, emptyMap(), body.toString())

    private suspend fun call(path: String, params: Map<String, String>, body: String?, retry: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) {
            val full = "/p$path"
            val sorted = params.toSortedMap()
            val raw = sorted.entries.joinToString("&") { "${it.key}=${it.value}" }
            val qs = sorted.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
            val url = baseUrl + full + if (qs.isNotEmpty()) "?$qs" else ""
            val rb = Request.Builder().url(url)
                .header("accesstoken", token)
                .header("authx", AuthX.header(full, body ?: raw))
            if (body != null) rb.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            val resp = http.newCall(rb.build()).execute()
            val text = resp.body?.string().orEmpty()
            val code = resp.code
            resp.close()
            val j = runCatching { JSONObject(text) }.getOrNull()
            val apiCode = j?.optInt("code", 0) ?: 0
            if ((code == 401 || apiCode == 401) && retry && user.isNotEmpty()) {
                login(baseUrl, user, pass).also { com.hark.shiguang.NasAccounts.storeToken(it); com.hark.shiguang.Store.token = it }
                return@withContext call(path, params, body, false)
            }
            if (code !in 200..299 || j == null) throw ApiException(code, "请求失败（HTTP $code）")
            if (apiCode != 0) throw ApiException(apiCode, j.optString("msg").ifEmpty { "服务返回错误 $apiCode" })
            j
        }

    fun abs(u: String?): String? = when {
        u.isNullOrBlank() -> null
        u.startsWith("http") -> u
        u.startsWith("/p/") -> baseUrl + u
        u.startsWith("/") -> "$baseUrl/p$u"
        else -> "$baseUrl/p/$u"
    }
}

// ---------------------------------------------------------------- models

data class Photo(
    val id: Int,
    val uuid: String,
    val isVideo: Boolean,
    val isLive: Boolean,
    val fileName: String,
    val time: String,        // "YYYY:MM:DD HH:MM:SS"
    val width: Int,
    val height: Int,
    val duration: Int,
    var collected: Boolean,
    val thumbS: String,
    val thumbM: String,
    val original: String,
    val video: String?,
    val make: String,
    val model: String,
    val fNumber: String,
    val exposure: String,
    val iso: String,
    val focal: String,
    val size: Long,
    val geo: String,
    val path: String,
    val source: String = "nas",          // "nas" or a cloud account id
    val cloudPath: String = "",          // provider path / id for cloud items
    /**
     * 1.0.1 live photo kind. 飞牛: the raw isLive value (1 = separate video stream, 2/3 = Motion Photo with an embedded MP4).
     * WebDAV: 1 = iPhone photo paired with a same-name .MOV ([liveVideo]), 2 = Android Motion Photo (MP4 at the end of the file).
     */
    val liveType: Int = 0,
    /** 1.0.1 WebDAV: raw url of the paired .MOV of an iPhone live photo. */
    val liveVideo: String? = null,
) {
    val key: String get() = "$source:$id:$uuid"
    val isCloud: Boolean get() = source != "nas"
    val day: String get() = time.take(10)
    val ratio: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 1f

    companion object {
        fun parse(j: JSONObject): Photo {
            val id = j.optInt("id")
            val uuid = j.optString("photoUUID")
            val th = j.optJSONObject("additional")?.optJSONObject("thumbnail")
            fun s(k: String) = j.optString(k).takeIf { it != "null" }.orEmpty()
            val base = "/p/api/v1/stream/p/t/$id"
            val video = j.optString("category") == "video"
            return Photo(
                id = id, uuid = uuid, isVideo = video, isLive = j.optInt("isLive") > 0, liveType = j.optInt("isLive"),
                fileName = s("fileName"),
                time = s("photoDateTime").ifEmpty { s("dateTime") },
                width = j.optInt("width"), height = j.optInt("height"),
                duration = j.optInt("mediaDuration"),
                collected = j.optInt("isCollect") == 1,
                thumbS = FnClient.abs(th?.optString("sUrl")?.takeIf { it.isNotBlank() && it != "null" } ?: "$base/s/$uuid")!!,
                thumbM = FnClient.abs(th?.optString("mUrl")?.takeIf { it.isNotBlank() && it != "null" } ?: "$base/m/$uuid")!!,
                original = FnClient.abs(th?.optString("originalUrl")?.takeIf { it.isNotBlank() && it != "null" } ?: "$base/o/$uuid")!!,
                video = FnClient.abs(th?.optString("videoUrl")?.takeIf { it.isNotBlank() && it != "null" })
                    ?: if (video) FnClient.abs("/p/api/v1/stream/v/$id") else null,
                make = s("make"), model = s("model"),
                fNumber = s("fNumber").ifEmpty { s("fumber") },
                exposure = s("exposureTime"), iso = s("isoSpeedRatings"), focal = s("focalLength"),
                size = j.optLong("fileSize"), geo = s("geo"), path = s("showFilePath").ifEmpty { s("filePath") },
            )
        }
    }
}

data class Album(val id: Int, val name: String, val photos: Int, val videos: Int, val poster: String?, val range: String)
data class Person(val id: Int, val name: String, val faceId: Int, val count: Int)
data class Place(val country: String, val city: String, val count: Int, val poster: String?)
data class DayCount(val year: Int, val month: Int, val day: Int, val count: Int)

private fun JSONObject.list(): JSONArray = optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
private inline fun <T> JSONArray.map(f: (JSONObject) -> T): List<T> = (0 until length()).mapNotNull { optJSONObject(it) }.map(f)

object Repo {
    const val FAR_START = "1970:01:01 00:00:00"
    const val FAR_END = "2099:12:31 23:59:59"
    fun monthEnd(ym: String): String { val (y, m) = ym.split(":").map { it.toInt() }; return "%s:%02d 23:59:59".format(ym, java.time.YearMonth.of(y, m).lengthOfMonth()) }

    suspend fun timeline(collect: Boolean = false): List<DayCount> =
        FnClient.get("/api/v1/gallery/timeline", if (collect) mapOf("is_collect" to 1) else emptyMap()).list().map {
            DayCount(it.optInt("year"), it.optInt("month"), it.optInt("day"), it.optInt("itemCount"))
        }

    suspend fun photos(start: String, end: String, offset: Int, limit: Int, collect: Boolean = false, mode: String = "datetime"): Pair<List<Photo>, Boolean> {
        val p = mutableMapOf<String, Any?>("start_time" to start, "end_time" to end, "offset" to offset, "limit" to limit, "mode" to mode)
        if (collect) p["is_collect"] = 1
        val j = FnClient.get("/api/v1/gallery/getList", p)
        val list = j.list().map(Photo::parse)
        val hasNext = j.optJSONObject("data")?.optBoolean("hasNext", list.size >= limit) ?: false
        return list to hasNext
    }

    suspend fun albums(): List<Album> = FnClient.get(
        "/api/v1/album/list", mapOf("sort_direction" to "desc", "sort_by" to "date_time", "offset" to 0, "limit" to 1000)
    ).list().map {
        val poster = it.optString("posterImgUrl").takeIf { s -> s.isNotBlank() && s != "null" }
            ?: it.optString("posterUrl").takeIf { s -> s.isNotBlank() && s != "null" }
        Album(
            it.optInt("albumId"), it.optString("albumName"), it.optInt("photoCount"), it.optInt("videoCount"),
            FnClient.abs(poster),
            listOf(it.optString("startDateTime"), it.optString("endDateTime")).filter { s -> s.length >= 7 && s != "null" }
                .map { s -> s.take(7).replace(':', '.') }.distinct().joinToString(" – "),
        )
    }

    suspend fun albumPhotos(albumId: Int, offset: Int, limit: Int): Pair<List<Photo>, Boolean> {
        val j = FnClient.get("/api/v1/album/photos", mapOf("album_id" to albumId, "sort_by" to "date_time", "sort_direction" to "desc", "offset" to offset, "limit" to limit))
        val l = j.list().map(Photo::parse); return l to (l.size >= limit)
    }

    suspend fun persons(): List<Person> = FnClient.get("/api/v1/ai-person/list", mapOf("getAll" to true, "limit" to -1, "orderBy" to 0))
        .list().map { Person(it.optInt("id"), it.optString("name").takeIf { n -> n != "null" }.orEmpty(), it.optInt("faceId"), it.optInt("itemCount")) }
        .filter { it.count > 0 }

    /** Which parameter shape the server accepted last time (the official name is not documented). */
    private var personShape = -1

    suspend fun personPhotos(personId: Int, offset: Int, limit: Int): Pair<List<Photo>, Boolean> {
        val shapes: List<Map<String, Any?>> = listOf(
            mapOf("id" to personId, "offset" to offset, "limit" to limit),
            mapOf("person_id" to personId, "offset" to offset, "limit" to limit),
            mapOf("personId" to personId, "offset" to offset, "limit" to limit),
            mapOf("person_id" to personId, "start_time" to FAR_START, "end_time" to FAR_END, "offset" to offset, "limit" to limit, "mode" to "datetime"),
        )
        val order = if (personShape >= 0) listOf(personShape) + shapes.indices.filter { it != personShape } else shapes.indices.toList()
        for (i in order) {
            val r = runCatching { FnClient.get("/api/v1/ai-person/photoLibrary/list", shapes[i]) }
            val j = r.getOrNull() ?: continue
            val l = j.list().map(Photo::parse)
            if (l.isEmpty() && offset == 0) continue
            personShape = i
            return l to (l.size >= limit)
        }
        // fallback: the search API with a person filter (returns everything at once)
        if (offset > 0) return emptyList<Photo>() to false
        val res = NasX.search("", listOf(SearchFilter.person(personId)))
        return res.photos to false
    }

    /** Rename a person. Endpoint not documented: tries the likely shapes and reports the last error. */
    suspend fun renamePerson(personId: Int, name: String) {
        val tries: List<Pair<String, JSONObject>> = listOf(
            "/api/v1/ai-person/update" to JSONObject().put("id", personId).put("name", name),
            "/api/v1/ai-person/rename" to JSONObject().put("id", personId).put("name", name),
            "/api/v1/ai-person/update" to JSONObject().put("person_id", personId).put("name", name),
            "/api/v1/ai-person/name" to JSONObject().put("id", personId).put("name", name),
        )
        var last: Throwable? = null
        for ((path, body) in tries) {
            val r = runCatching { FnClient.post(path, body) }
            if (r.isSuccess) return
            last = r.exceptionOrNull()
        }
        throw Exception("飞牛暂不接受改名：" + (last?.message ?: "未知错误"))
    }

    suspend fun places(): List<Place> = FnClient.get("/api/v1/explore/geos", mapOf("offset" to 0, "limit" to -1)).list().map {
        Place(it.optString("country"), it.optString("city"), it.optInt("itemCount"), FnClient.abs(it.optString("posterUrl").takeIf { s -> s != "null" }))
    }

    suspend fun placePhotos(p: Place, offset: Int, limit: Int): Pair<List<Photo>, Boolean> {
        val body = JSONObject().apply {
            put("keyword", "")
            put("filters", JSONArray().put(JSONObject().apply {
                put("filterName", "photo_location"); put("filterValue", p.country)
                put("subFilters", JSONArray().put(JSONObject().put("filterName", p.country).put("filterValue", p.city)))
            }))
            put("antiFilters", JSONArray())
            put("offset", offset); put("limit", limit)
        }
        val j = FnClient.post("/api/v1/search/results", body)
        val l = j.list().map(Photo::parse); return l to false
    }

    suspend fun search(keyword: String): List<Photo> {
        val magic = runCatching {
            FnClient.post("/api/v1/magic-search/do", JSONObject().put("keyword", keyword).put("antiFilters", JSONArray())).list().map(Photo::parse)
        }.getOrNull()
        if (!magic.isNullOrEmpty()) return magic
        return FnClient.get("/api/v1/photo/search", mapOf("keyword" to keyword, "limit" to 200, "offset" to 0)).list().map(Photo::parse)
    }

    suspend fun setCollect(id: Int, on: Boolean) {
        FnClient.post(if (on) "/api/v1/preview/collect" else "/api/v1/preview/collect/cancel", JSONObject().put("ids", JSONArray().put(id)))
    }

    suspend fun stat(): Pair<Int, Int> = runCatching {
        val d = FnClient.get("/api/v1/user_photo/stat").optJSONObject("data")
        (d?.optInt("photoCount") ?: 0) to (d?.optInt("videoCount") ?: 0)
    }.getOrDefault(0 to 0)
}
