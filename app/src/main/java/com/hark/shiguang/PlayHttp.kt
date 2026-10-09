package com.hark.shiguang

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 1.0.3: shared by the 影视 player (ui/MoviePlayer.kt) and the music player (MusicPlayer.kt). Was private in MoviePlayer.
 * The client derives from FnClient.http, so NetEnv (VPN bypass DNS / socket binding) applies.
 */
internal object PlayHttp {
    val client: OkHttpClient = com.hark.shiguang.data.FnClient.http.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true).build()
    /** original url -> (final url, resolved at). WebDAV hosts like 123pan answer with a 302 to a signed CDN url. */
    val resolved = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * Follows the WebDAV 302 once (cached 15 min) and returns the url to stream plus the headers to send to it.
     * A redirect to another host (the signed CDN url) must NOT carry the WebDAV Authorization header.
     * Blocking: call from a loader / IO thread.
     */
    fun resolve(url: String, h: Map<String, String>, followRedirect: Boolean): Pair<String, Map<String, String>> {
        if (!url.startsWith("http")) return url to emptyMap()
        if (!followRedirect || h.isEmpty()) return url to h
        val cached = resolved[url]?.takeIf { System.currentTimeMillis() - it.second < 15 * 60_000 }?.first
        val final = cached ?: runCatching {
            val rb = Request.Builder().url(url).header("Range", "bytes=0-0"); h.forEach { (k, v) -> rb.header(k, v) }
            client.newCall(rb.build()).execute().use { it.request.url.toString() }
        }.getOrDefault(url).also { resolved[url] = it to System.currentTimeMillis() }
        val sameHost = final.toHttpUrlOrNull()?.host == url.toHttpUrlOrNull()?.host
        return final to (if (sameHost) h else emptyMap())
    }

    /** Bytes [from, from+len) of [url] with [h] (OkHttp drops Authorization itself on a cross-host redirect). Null on failure. */
    fun range(url: String, h: Map<String, String>, from: Long, len: Int): ByteArray? = runCatching {
        val rb = Request.Builder().url(url).header("Range", "bytes=$from-${from + len - 1}"); h.forEach { (k, v) -> rb.header(k, v) }
        client.newCall(rb.build()).execute().use { r ->
            if (!r.isSuccessful) return null
            // a server that ignores Range answers 200 with the whole file: only accept that for the head
            if (r.code == 200 && from > 0) return null
            val ins = r.body?.byteStream() ?: return null
            val out = java.io.ByteArrayOutputStream(minOf(len, 1 shl 20)); val buf = ByteArray(32768); var t = 0
            while (t < len) { val k = ins.read(buf); if (k < 0) break; out.write(buf, 0, minOf(k, len - t)); t += k }
            out.toByteArray()
        }
    }.getOrNull()
}
