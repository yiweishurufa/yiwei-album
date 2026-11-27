package com.hark.shiguang

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 1.7.21（用户 2026-11-27 反馈「一直持续在后台扫描，太耗电，而且每次都在重新扫描」）：读失败的条目的退避规则。
 *
 * 以前本地分析 / 人脸 / 图中文字 / AI 整理 / 音乐标签对「这次读不到」的条目都不做记录，只是不标记完成，于是
 * 每次被叫醒（网络回调、进页面、扫描结束接力）都把同一批读不到的文件再请求一遍，队列永远清不空，后台一直在跑。
 *
 * 现在：第 1 次失败后 1 小时内不再试，第 2 次 6 小时，第 3 次 24 小时，第 4 次失败后放弃，直到用户手动刷新
 * （下拉刷新 / 立即扫描 / 通知里的「继续」）才重新给一次机会。成功一次即清除记录。
 */
object RetryBackoff {
    /** 第 1、2、3 次失败之后要等的时间。 */
    val WAITS_MS = longArrayOf(3_600_000L, 6 * 3_600_000L, 24 * 3_600_000L)
    /** 失败这么多次后放弃，等手动刷新。 */
    const val MAX_ATTEMPTS = 4

    data class Entry(val attempts: Int, val lastAt: Long)

    /** 现在能不能再试。时钟被调回过去（now < lastAt）时允许重试，避免永远卡住。 */
    fun eligible(e: Entry?, now: Long): Boolean {
        if (e == null || e.attempts <= 0) return true
        if (e.attempts >= MAX_ATTEMPTS) return false
        if (now < e.lastAt) return true
        return now - e.lastAt >= WAITS_MS[(e.attempts - 1).coerceIn(0, WAITS_MS.size - 1)]
    }

    fun failed(e: Entry?, now: Long): Entry = Entry(((e?.attempts ?: 0) + 1).coerceAtMost(MAX_ATTEMPTS), now)

    fun gaveUp(e: Entry?): Boolean = e != null && e.attempts >= MAX_ATTEMPTS
}

/**
 * 一组条目（按路径或曲目 id）的失败记录，存成一个小 JSON 文件：{"key":[次数, 最后失败时间]}。
 * 独立文件，不动原有的 meta.json / faces.json / library.json 格式；文件丢了只是退回到「都可以试」。
 * 线程安全；[save] 只在有改动时写盘。[file] 为 null 时只在内存里（单测）。
 */
class RetryBook(private val file: File?) {
    private val map = ConcurrentHashMap<String, RetryBackoff.Entry>()
    @Volatile private var loaded = file == null
    @Volatile private var dirty = false

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        if (!f.exists()) return
        runCatching { fromJson(f.readText()) }.onFailure { Diag.log("SCAN", "retry book unreadable, starting fresh: ${f.name}") }
    }

    /** [manual] = 用户手动触发：不看退避。 */
    fun eligible(key: String, manual: Boolean = false, now: Long = System.currentTimeMillis()): Boolean {
        if (manual) return true
        load()
        return RetryBackoff.eligible(map[key], now)
    }

    fun fail(key: String, now: Long = System.currentTimeMillis()) {
        load()
        map.compute(key) { _, old -> RetryBackoff.failed(old, now) }
        dirty = true
    }

    fun ok(key: String) {
        load()
        if (map.remove(key) != null) dirty = true
    }

    fun attempts(key: String): Int { load(); return map[key]?.attempts ?: 0 }

    /** 现在不能试（退避中或已放弃）的条目数。 */
    fun waiting(now: Long = System.currentTimeMillis()): Int { load(); return map.values.count { !RetryBackoff.eligible(it, now) } }

    fun gaveUp(): Int { load(); return map.values.count { RetryBackoff.gaveUp(it) } }

    val size: Int get() { load(); return map.size }

    /** 手动刷新：所有失败条目重新获得机会。 */
    fun reset() {
        load()
        if (map.isNotEmpty()) { map.clear(); dirty = true }
    }

    /** 只保留 [live] 里还存在的条目（文件已删除的不再记着）。 */
    fun retain(live: Set<String>) {
        load()
        if (map.keys.removeAll { it !in live }) dirty = true
    }

    @Synchronized fun save() {
        if (!dirty) return
        dirty = false
        val f = file ?: return
        runCatching {
            if (map.isEmpty()) { f.delete(); return@runCatching }
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(toJson())
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        }.onFailure { dirty = true; Diag.log("SCAN", "retry book save failed: ${it.javaClass.simpleName}") }
    }

    fun toJson(): String {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, JSONArray().put(v.attempts).put(v.lastAt)) }
        return o.toString()
    }

    fun fromJson(s: String) {
        val o = JSONObject(s)
        o.keys().forEach { k ->
            val a = o.optJSONArray(k) ?: return@forEach
            val n = a.optInt(0); val at = a.optLong(1)
            if (n > 0) map[k] = RetryBackoff.Entry(n.coerceAtMost(RetryBackoff.MAX_ATTEMPTS), at)
        }
    }
}

/** 每个库一份退避记录，按文件路径共享同一个实例（多个 runner 同时用也不会互相覆盖）。 */
object RetryBooks {
    private val books = ConcurrentHashMap<String, RetryBook>()

    fun of(file: File): RetryBook = books.getOrPut(file.absolutePath) { RetryBook(file) }

    /** WebDAV 账户 [accountId] 的某个队列（analyze / faces / ocr / ai）。 */
    fun dav(accountId: String, name: String): RetryBook = of(File(App.ctx.filesDir, "dav/$accountId/retry-$name.json"))

    /** 用户手动刷新照片库：这个账户所有队列的失败条目都重新获得一次机会。 */
    fun resetDav(accountId: String) {
        listOf("analyze", "faces", "ocr", "ai").forEach { n -> dav(accountId, n).let { it.reset(); it.save() } }
        Diag.log("SCAN", "manual refresh: retry backoff cleared for dav account")
    }
}

/**
 * 1.7.21：网络 / 充电变化时要不要自动续跑后台整理。
 *
 * 以前 [ScanWatch] 对 onCapabilitiesChanged 的每一次回调都续跑——Wi-Fi / 移动网络的信号强度、带宽估计一变就会回调，
 * 一分钟能来好几次，于是整理队列（含永远读不到的文件）被一遍遍重新拉起。现在只在「条件从不满足变成满足」时续跑
 * （连上 Wi-Fi、插上电），且两次自动续跑至少隔 [MIN_GAP_MS]。
 */
object ResumeGate {
    const val MIN_GAP_MS = 15 * 60_000L

    /** [wasAllowed] = 上一次看到的条件（null = 进程刚启动，还没看过）。 */
    fun shouldResume(wasAllowed: Boolean?, allowed: Boolean, lastResumeAt: Long, now: Long): Boolean {
        if (!allowed || wasAllowed == true) return false
        return lastResumeAt == 0L || now < lastResumeAt || now - lastResumeAt >= MIN_GAP_MS
    }
}

/** 1.7.21：每次扫描 / 整理一行摘要（原因、范围、耗时、新增、失败、退避中），写进诊断日志，方便导出排查。 */
object ScanLog {
    fun line(kind: String, reason: String, scope: String, ms: Long, added: Int, failed: Int = 0, deferred: Int = 0, extra: String = ""): String =
        "$kind reason=$reason scope=$scope took=${ms / 1000}.${(ms % 1000) / 100}s new=$added failed=$failed backoff=$deferred" + if (extra.isNotEmpty()) " $extra" else ""

    fun log(kind: String, reason: String, scope: String, ms: Long, added: Int, failed: Int = 0, deferred: Int = 0, extra: String = "") =
        Diag.log("SCANLOG", line(kind, reason, scope, ms, added, failed, deferred, extra))
}
