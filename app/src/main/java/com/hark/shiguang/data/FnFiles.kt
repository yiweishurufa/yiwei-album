package com.hark.shiguang.data

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 1.0.9: fnOS 文件管理 (not the 相册 app) — browse any NAS folder for the 影视 library.
 *
 * Protocol (from the community docs github.com/FNOSP/fnnas-api docs/protocol.md + docs/modules/file.md and
 * github.com/Timandes/pyfnos fnos/client.py; NOT tested against a real NAS — the sandbox can't reach one):
 *  - ws {base}/websocket?type=main, `util.crypto.getRSAPub` → si
 *  - every later frame is `base64(HMAC-SHA256(secret, json)) + json`; secret = login secret AES-decrypted (= [NasX.signSecretB64])
 *  - `user.authToken {main:true, token, si}` re-uses the photo login's token on this new socket
 *  - `file.ls {path}` → {files:[{name,size,dir:1,v,uid,mtim}]}; path null = user root, else "vol{v}/{uid}/sub/dir"
 * File bytes (UNVERIFIED, from a blog's capture of the web UI, nobb.cc/archives/3776.html):
 *  POST {base}/multiple-download (cookie fnos-token=<token>) returns a download token,
 *  GET  {base}/multiple-download?token=<t> (same cookie) streams the file (Range assumed).
 * Every caller falls back to the old 相册-only folder view when this fails.
 */
object FnFiles {
    private val lock = Mutex()
    private var ws: WebSocket? = null
    private var wsBase = ""
    private var ready: CompletableDeferred<Unit>? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private var si = ""
    private var idx = 1
    @Volatile var lastError = ""

    /** 1.0.10: logins from before 1.0.9 have no signing secret stored; file.ls / download cannot be signed without it. */
    const val RELOGIN = "请退出飞牛账号并重新登录一次"
    val missingSecret: Boolean get() = FnClient.baseUrl.isNotEmpty() && FnClient.token.isNotEmpty() && NasX.signSecretB64.isEmpty()
    @Volatile private var reloginShown = false
    /** Sets [lastError] and shows the hint once per app run (a toast, so it surfaces from any caller). */
    private fun relogin() {
        lastError = RELOGIN
        if (reloginShown) return
        reloginShown = true
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { android.widget.Toast.makeText(com.hark.shiguang.App.ctx, "$RELOGIN，才能浏览和播放 NAS 普通文件夹", android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    data class Entry(val name: String, val path: String, val isDir: Boolean, val size: Long)

    private fun reqId(): String = "%08x".format(System.currentTimeMillis() / 1000) + "0000000000000000" + "%04x".format((idx++) and 0xffff)

    private fun sign(json: String): String {
        val key = NasX.signSecretB64
        if (key.isEmpty()) return ""
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.decode(key, Base64.DEFAULT), "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(json.toByteArray()), Base64.NO_WRAP)
    }

    val available: Boolean get() = FnClient.baseUrl.isNotEmpty() && FnClient.token.isNotEmpty() && NasX.signSecretB64.isNotEmpty()

    private suspend fun connect() {
        if (ws != null && wsBase == FnClient.baseUrl && ready?.isCompleted == true && ready?.isCancelled == false) return
        ws?.cancel(); ws = null
        val base = FnClient.baseUrl
        val url = base.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://") + "/websocket?type=main"
        val gate = CompletableDeferred<Unit>(); ready = gate
        val pubId = reqId()
        val sock = FnClient.http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("req", "util.crypto.getRSAPub").put("reqid", pubId).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = runCatching { JSONObject(text) }.getOrNull() ?: return
                val id = j.optString("reqid")
                if (id == pubId) { si = j.optString("si"); gate.complete(Unit); return }
                pending.remove(id)?.complete(j)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lastError = t.message ?: "连接失败"
                gate.completeExceptionally(t); pending.values.forEach { it.completeExceptionally(t) }; pending.clear()
                if (ws === webSocket) ws = null
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (ws === webSocket) ws = null }
        })
        ws = sock; wsBase = base
        withTimeout(12_000) { gate.await() }
        val auth = call("user.authToken", JSONObject().put("main", true).put("token", FnClient.token).put("si", si))
        if (auth.optString("result") == "fail") throw ApiException(auth.optInt("errno"), "文件管理鉴权失败（${auth.optInt("errno")}）")
    }

    private suspend fun call(req: String, payload: JSONObject): JSONObject {
        val id = reqId()
        payload.put("req", req).put("reqid", id)
        val json = payload.toString()
        val d = CompletableDeferred<JSONObject>(); pending[id] = d
        val s = ws ?: throw ApiException(-1, "未连接")
        if (!s.send(sign(json) + json)) { pending.remove(id); throw ApiException(-1, "发送失败") }
        return withTimeout(15_000) { d.await() }
    }

    /** Lists [path] ("" = the user's root). Paths are "vol1/1000/影视" (no leading slash). */
    suspend fun ls(path: String): List<Entry> = withContext(Dispatchers.IO) {
        lock.withLock {
            if (missingSecret) { relogin(); throw ApiException(-1, RELOGIN) }
            if (!available) throw ApiException(-1, "未登录飞牛")
            var lastEx: Exception? = null
            repeat(2) {
                try {
                    connect()
                    val r = call("file.ls", JSONObject().put("path", if (path.isEmpty()) JSONObject.NULL else path))
                    if (r.optString("result") == "fail" || (r.has("errno") && r.optInt("errno") != 0)) throw ApiException(r.optInt("errno"), "读取文件夹失败（${r.optInt("errno")}）")
                    val a = r.optJSONArray("files") ?: r.optJSONObject("data")?.optJSONArray("files") ?: JSONArray()
                    return@withContext (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { f ->
                        val name = f.optString("name")
                        val child = if (path.isEmpty()) (if (f.has("v")) "vol${f.optInt("v")}/${f.optInt("uid")}/$name" else name) else path.trimEnd('/') + "/" + name
                        Entry(name, child, f.optInt("dir", 0) == 1, f.optLong("size"))
                    }
                } catch (e: Exception) { lastEx = e; ws?.cancel(); ws = null; lastError = e.message ?: "" }
            }
            throw lastEx ?: ApiException(-1, "读取文件夹失败")
        }
    }

    fun cookie(): String = "fnos-token=${FnClient.token}"

    private val tokens = ConcurrentHashMap<String, Pair<String, Long>>()

    /** UNVERIFIED: download token for one file via POST /multiple-download; cached 10 min. */
    fun downloadUrl(path: String): String? {
        tokens[path]?.let { (u, t) -> if (System.currentTimeMillis() - t < 600_000) return u }
        if (missingSecret) { relogin(); return null }
        val abs = "/" + path.trimStart('/')
        val bodies = listOf(
            JSONObject().put("files", JSONArray().put(abs)),
            JSONObject().put("files", JSONArray().put(path.trimStart('/'))),
            JSONObject().put("path", abs),
        )
        for (b in bodies) {
            val t = runCatching {
                FnClient.http.newCall(Request.Builder().url(FnClient.baseUrl + "/multiple-download").header("Cookie", cookie()).header("accesstoken", FnClient.token)
                    .post(b.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (!r.isSuccessful) return@use null
                    val j = runCatching { JSONObject(text) }.getOrNull()
                    j?.optJSONObject("data")?.optString("token")?.ifEmpty { null } ?: j?.optString("token")?.ifEmpty { null }
                        ?: text.trim().takeIf { it.isNotEmpty() && it.length < 200 && !it.startsWith("{") && !it.startsWith("<") }
                }
            }.getOrNull()
            if (!t.isNullOrEmpty()) {
                val u = FnClient.baseUrl + "/multiple-download?token=" + java.net.URLEncoder.encode(t, "UTF-8")
                tokens[path] = u to System.currentTimeMillis()
                return u
            }
        }
        com.hark.shiguang.Diag.log("FNFILE", "multiple-download gave no token")
        return null
    }

    fun headers(): Map<String, String> = mapOf("Cookie" to cookie(), "accesstoken" to FnClient.token)

    suspend fun readText(path: String, max: Int = 512 * 1024): String? = withContext(Dispatchers.IO) {
        val u = downloadUrl(path) ?: return@withContext null
        runCatching {
            FnClient.http.newCall(Request.Builder().url(u).apply { headers().forEach { (k, v) -> header(k, v) } }.build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.bytes()?.let { b -> String(b, 0, minOf(b.size, max)) }
            }
        }.getOrNull()
    }

    suspend fun bytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val u = downloadUrl(path) ?: return@withContext null
        runCatching {
            FnClient.http.newCall(Request.Builder().url(u).apply { headers().forEach { (k, v) -> header(k, v) } }.build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        }.getOrNull()
    }
}
