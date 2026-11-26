package com.hark.shiguang

import java.net.URI

/**
 * 1.7.17 缩略图缓存命中：飞牛照片的缩略图 / 大图地址带着当时用的 NAS 地址（内网 http://192.168.x.x:5666 或外网域名），
 * 自动切换内外网（Endpoint.autoSelect）后同一张图的地址变了，Coil 的磁盘缓存按地址存，于是整库缩略图重新下载一遍
 * （出门走外网时既慢又费流量，回家再切回内网又下一遍）。
 *
 * 这里把「属于某个飞牛账号（url / 内网 / 外网任一地址）的 /p/ 接口地址」换成与主机无关的稳定键
 * `fnimg://<账号 id>/p/...`，只用作磁盘缓存键（[StableImageKeys] 拦截器设置 diskCacheKey），真正请求的地址不变。
 * 其他地址（WebDAV、TMDB、封面等）原样返回。纯函数，单元测试 ImageKeysTest。
 */
object ImageKeys {
    /** "scheme://host:port"（小写，补默认端口）；解析不了返回 null。 */
    internal fun origin(u: String): String? = runCatching {
        val x = URI(u.trim())
        val scheme = x.scheme?.lowercase() ?: return null
        val host = x.host?.lowercase() ?: return null
        val port = if (x.port > 0) x.port else when (scheme) { "http" -> 80; "https" -> 443; else -> return null }
        "$scheme://$host:$port"
    }.getOrNull()

    /** [accounts]：账号 id → 它的各个地址。 */
    fun stable(url: String, accounts: List<Pair<String, List<String>>>): String {
        if (!url.startsWith("http")) return url
        val o = origin(url) ?: return url
        val id = accounts.firstOrNull { (_, urls) -> urls.any { it.isNotBlank() && origin(it) == o } }?.first ?: return url
        val x = runCatching { URI(url) }.getOrNull() ?: return url
        val path = x.rawPath ?: return url
        if (!path.startsWith("/p/")) return url
        return "fnimg://$id$path" + (x.rawQuery?.let { "?$it" } ?: "")
    }

    @Volatile private var cache: Pair<List<NasAccount>, List<Pair<String, List<String>>>>? = null

    /** 用当前保存的飞牛账号算稳定键（账号表没变时复用上次整理好的地址表）。 */
    fun stable(url: String): String {
        val list = NasAccounts.list
        val c = cache
        val acc = if (c != null && c.first === list) c.second else list.map { it.id to listOf(it.url, it.lanUrl, it.wanUrl) }.also { cache = list to it }
        return stable(url, acc)
    }
}
