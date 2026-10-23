package com.hark.shiguang.data

/**
 * 用户 2026-10-23 反馈 #1 的纯逻辑部分（不依赖 Android，单元测试见 LanLogicTest）：
 * 要扫的 IP 段、mDNS 查询包的构造与解析、「是不是飞牛」的判定、搜不到时给用户看的原因。
 */
object LanLogic {
    /** 一个网段最多扫这么多个地址（/24 是 254 个；更大的网段以手机所在地址为中心截取）。 */
    const val MAX_HOSTS = 1022

    fun ipToInt(ip: String): Int? {
        val p = ip.trim().split('.')
        if (p.size != 4) return null
        var v = 0
        for (s in p) { val n = s.toIntOrNull() ?: return null; if (n !in 0..255) return null; v = (v shl 8) or n }
        return v
    }

    fun intToIp(v: Int): String = "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"

    fun isPrivate(ip: String): Boolean {
        val v = ipToInt(ip) ?: return false
        val a = (v ushr 24) and 255; val b = (v ushr 16) and 255
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    /**
     * 手机地址 [ip]/[prefix] 所在网段里要探测的主机（不含网络号、广播地址和手机自己）。
     * prefix 比 /24 小（如 /23、/16）时，以手机地址为中心最多取 [cap] 个，NAS 通常和手机挨得近；
     * prefix 大于 /30 或不是私有地址时返回空（不扫公网、不扫点对点链路）。
     */
    fun hosts(ip: String, prefix: Int, cap: Int = MAX_HOSTS): List<String> {
        val me = ipToInt(ip) ?: return emptyList()
        if (!isPrivate(ip) || prefix !in 8..30) return emptyList()
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val net = me and mask
        val size = 1L shl (32 - prefix)                    // 含网络号和广播
        val first = net.toLong() and 0xffffffffL
        val lastUsable = first + size - 2
        val firstUsable = first + 1
        val meL = me.toLong() and 0xffffffffL
        val usable = lastUsable - firstUsable + 1
        val (lo, hi) = if (usable <= cap) firstUsable to lastUsable else {
            var l = meL - cap / 2
            if (l < firstUsable) l = firstUsable
            var h = l + cap - 1
            if (h > lastUsable) { h = lastUsable; l = h - cap + 1 }
            l to h
        }
        val out = ArrayList<String>((hi - lo + 1).toInt())
        var x = lo
        while (x <= hi) { if (x != meL) out.add(intToIp(x.toInt())); x++ }
        return out
    }

    // ------------------------------------------------------------------ 是不是飞牛

    /**
     * 飞牛网页的特征（小写比较）。依据：fnOS 登录页 title 形如「xxx - 飞牛 fnOS」、未开 JS 提示里也写明「飞牛」，
     * 自带的自签名证书里有「fnOS」（飞牛论坛 tid=55617，2026-02）；服务由 trim_nginx 提供（论坛 tid=15792、63284）。
     */
    private val MARKS = listOf("fnos", "飞牛", "fnnas", "trim_", "trim-")

    fun looksLikeFnos(text: String): Boolean { val t = text.lowercase(); return MARKS.any { t.contains(it) } }

    private val TITLE = Regex("<title[^>]*>([^<]{1,80})</title>", RegexOption.IGNORE_CASE)

    fun titleOf(html: String): String = TITLE.find(html)?.groupValues?.get(1)?.trim().orEmpty()

    /** 设备名：页面标题去掉「 - 飞牛 fnOS」之类的尾巴；剩下的就是用户给 NAS 起的名字，没有就空。 */
    fun nameFromTitle(title: String): String {
        val t = title.trim()
        val cut = Regex("\\s*[-–—|·]\\s*(飞牛\\s*)?fnos.*$", RegexOption.IGNORE_CASE).replace(t, "").trim()
        return if (cut.isEmpty() || cut.equals("fnos", true) || cut == "飞牛" || cut.equals("飞牛 fnos", true) || cut.equals("飞牛私有云", true)) "" else cut
    }

    /** http 端口回了 3xx 跳到 https（开了「强制 HTTPS」）：返回要跟着去探测的 https 基址，否则 null。 */
    fun httpsTarget(host: String, location: String?): String? {
        val loc = location?.trim().orEmpty()
        if (!loc.startsWith("https://", true)) return null
        val hp = loc.substringAfter("://").substringBefore('/')
        val h = if (hp.startsWith("[")) hp.substringBefore("]") + "]" else hp.substringBefore(':')
        val port = if (hp.startsWith("[")) hp.substringAfter("]:", "") else hp.substringAfter(':', "")
        // 只跟到同一台机器上，防止被引去别处
        if (!h.equals(host, true) && h.isNotEmpty() && ipToInt(h) != null) return null
        return "https://$host" + if (port.isNotEmpty() && port != "443") ":$port" else ""
    }

    // ------------------------------------------------------------------ mDNS

    /** fnOS 用 avahi 广播 SMB（%h(SMB)）和 _device-info；也问 http/https/afp，谁回应就去 HTTP 探测确认。 */
    val MDNS_TYPES = listOf("_smb._tcp.local", "_device-info._tcp.local", "_http._tcp.local", "_https._tcp.local", "_afpovertcp._tcp.local")

    /** 一个 mDNS 查询包：每个类型一个 PTR 问题，QU 位置 1（要求单播回到我们的端口，不依赖收组播）。 */
    fun mdnsQuery(types: List<String> = MDNS_TYPES): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write((v ushr 8) and 255); out.write(v and 255) }
        u16(0); u16(0); u16(types.size); u16(0); u16(0); u16(0)
        for (t in types) {
            for (label in t.trimEnd('.').split('.')) { val b = label.toByteArray(Charsets.UTF_8); out.write(b.size); out.write(b) }
            out.write(0); u16(12); u16(0x8001)
        }
        return out.toByteArray()
    }

    data class MdnsHit(val ip: String, val name: String)

    /**
     * 从 mDNS 响应里取 A 记录（主机名 + IPv4）。只做 fnOS 发现需要的最小解析：支持名字压缩指针，
     * 坏包返回已解析出的部分，不抛异常。
     */
    fun parseMdnsA(p: ByteArray, len: Int = p.size): List<MdnsHit> {
        val res = ArrayList<MdnsHit>()
        runCatching {
            fun u8(i: Int) = p[i].toInt() and 255
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            fun name(start: Int): Pair<String, Int> {
                val parts = ArrayList<String>(); var i = start; var end = -1; var jumps = 0
                while (true) {
                    if (i >= len) throw IndexOutOfBoundsException()
                    val l = u8(i)
                    if (l == 0) { i++; break }
                    if (l and 0xC0 == 0xC0) { if (end < 0) end = i + 2; i = ((l and 0x3F) shl 8) or u8(i + 1); if (++jumps > 20) throw IllegalStateException(); continue }
                    parts.add(String(p, i + 1, l, Charsets.UTF_8)); i += l + 1
                }
                return parts.joinToString(".") to (if (end >= 0) end else i)
            }
            if (len < 12 || (u8(2) and 0x80) == 0) return res        // 不是响应
            val qd = u16(4); val rrs = u16(6) + u16(8) + u16(10)
            var i = 12
            repeat(qd) { i = name(i).second + 4 }
            repeat(rrs) {
                val (n, after) = name(i)
                val type = u16(after); val rdLen = u16(after + 8); val rd = after + 10
                if (type == 1 && rdLen == 4 && rd + 4 <= len) res.add(MdnsHit("${u8(rd)}.${u8(rd + 1)}.${u8(rd + 2)}.${u8(rd + 3)}", n.removeSuffix(".local")))
                i = rd + rdLen
            }
        }
        return res
    }

    // ------------------------------------------------------------------ 搜不到的原因

    data class Stats(
        val lanIfaces: Int,          // 找到的 Wi-Fi / 以太网网段数
        val subnet: String,          // 例如 "192.168.1.x"
        val vpn: Boolean,
        val bindFailed: Boolean,     // VPN 不允许绕过，只能经 VPN 扫
        val openHosts: Int,          // 5666/5667 端口有应答的主机数
        val mdnsHosts: Int,          // mDNS 有回应的主机数
        val probed: Int,             // 做了 HTTP 校验的主机数
    )

    fun reason(s: Stats): String = when {
        s.lanIfaces == 0 -> "手机没连 Wi-Fi，局域网搜索只能在和 NAS 同一个 Wi-Fi 下用。可以直接手动输入地址。"
        s.vpn && s.openHosts > 40 -> "开着 VPN，局域网连接被它接管了（${s.subnet} 几乎全部「有应答」）。先关掉 VPN 再搜，或手动输入地址。"
        s.openHosts == 0 && s.mdnsHosts == 0 && s.vpn && s.bindFailed -> "开着 VPN 且它不允许绕过，搜索被挡住了。先关掉 VPN（或在 VPN 里放行局域网）再搜，或手动输入地址。"
        s.openHosts == 0 && s.mdnsHosts == 0 -> "${s.subnet} 里没有设备开着飞牛端口（5666/5667）。确认 NAS 已开机、和手机连的是同一个 Wi-Fi，路由器没开「AP 隔离 / 访客网络」；也可以手动输入地址。"
        else -> "找到 ${s.probed} 台设备，但都不是飞牛网页。如果改过飞牛的端口，请手动输入地址和端口。"
    }
}
