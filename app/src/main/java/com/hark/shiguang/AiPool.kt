package com.hark.shiguang

import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * 1.0.1 #4: automatic free-model failover.
 *
 * Order: the model the user configured → free vision models of every 「免费（国内推荐）」 provider with a key
 * (智谱 → 硅基流动 → 魔搭, in [AiProviders.all] order) → OpenRouter `:free` vision models last (only with an
 * OpenRouter key; it needs a VPN in mainland China, so it is the advanced tail of the pool).
 *
 * A model that answers 429/402/quota, times out, 5xx, 404/model-not-found or comes back empty is cooled down
 * (Retry-After / X-RateLimit-Reset honoured; daily quota → next reset; otherwise a default) and the same photo is
 * retried on the next model. Cooldowns survive restarts (Store "ai.cool").
 */
object AiPool {
    /** 「免费模型自动切换」 (default on). Off = only the configured model, with the old backoff. */
    var autoSwitch by mutableStateOf(Store.getStr("ai.auto", "1") == "1")
        private set
    fun setAuto(on: Boolean) { autoSwitch = on; Store.putStr("ai.auto", if (on) "1" else "0") }

    /** Model that answered last (shown on the AI card). */
    var active by mutableStateOf<AiEndpoint?>(null)
    /** 「已自动切换到 xxx」 while a fallback model is in use; empty when the configured model works. */
    var switched by mutableStateOf("")
    /** Bumps whenever cooldowns change (UI). */
    var version by mutableIntStateOf(0)

    private val until = HashMap<String, Long>()
    private val why = HashMap<String, String>()

    init {
        runCatching {
            val o = JSONObject(Store.getStr("ai.cool", "{}"))
            o.keys().forEach { k -> val v = o.optJSONObject(k); if (v != null) { until[k] = v.optLong("u"); why[k] = v.optString("w") } else until[k] = o.optLong(k) }
        }
    }

    private fun persist() {
        val now = System.currentTimeMillis()
        val o = JSONObject()
        synchronized(until) { until.forEach { (k, v) -> if (v > now) o.put(k, JSONObject().put("u", v).put("w", why[k].orEmpty())) } }
        Store.putStr("ai.cool", o.toString())
        version++
    }

    fun coolUntil(ep: AiEndpoint): Long = synchronized(until) { maxOf(until[ep.id] ?: 0L, until[ep.provider + "|*"] ?: 0L) }
    fun cooled(ep: AiEndpoint) = coolUntil(ep) > System.currentTimeMillis()
    fun reason(ep: AiEndpoint): String = synchronized(until) { why[ep.id].takeIf { (until[ep.id] ?: 0) > System.currentTimeMillis() } ?: why[ep.provider + "|*"].orEmpty() }
    fun clear() { synchronized(until) { until.clear(); why.clear() }; switched = ""; persist() }

    /**
     * 第 4 批：按冷却原因派生的卡片提示（从已持久化的 ai.cool 里读，重启后仍在）。
     * 余额不足 = 某个付费模型因 402/欠费被长冷；模型下线 = 某个模型因 404 被冷 24h。
     */
    private fun activeReasons(): List<String> = synchronized(until) {
        val now = System.currentTimeMillis()
        until.filter { it.value > now }.keys.mapNotNull { why[it] }
    }
    /** 有付费模型因余额不足被长冷 → 卡片显示「余额不足，请充值」。 */
    val balanceInsufficient: Boolean get() = activeReasons().any { it.startsWith("余额不足") }
    /** 有模型因 404（不存在/已下线）被冷 → 卡片提示换模型。 */
    val modelOffline: Boolean get() = activeReasons().any { it.contains("已下线") }
    /** AI 卡片的冷却提示行（余额 / 下线），空 = 无需提示。 */
    val topHint: String get() = listOf(
        if (balanceInsufficient) "余额不足，请充值" else "",
        if (modelOffline) "有模型已下线，请换模型" else "",
    ).filter { it.isNotEmpty() }.joinToString(" · ")

    private fun cool(key: String, ms: Long, reason: String) {
        synchronized(until) { until[key] = System.currentTimeMillis() + ms; why[key] = reason }
        persist()
    }

    /** OpenRouter `:free` vision models from the last 「获取免费模型」 (falls back to the preset list). */
    fun orFreeVision(): List<String> {
        val saved = runCatching { JSONArray(Store.getStr("ai.or.free", "[]")).let { a -> (0 until a.length()).map { a.optString(it) } } }.getOrDefault(emptyList())
        return saved.filter { it.isNotBlank() }.ifEmpty { AiProviders.of("openrouter").free }.take(6)
    }
    fun saveOrFreeVision(ids: List<String>) = Store.putStr("ai.or.free", JSONArray(ids).toString())

    /**
     * 1.1.0: 用户在模型列表里排的顺序（endpoint id 列表，"ai.order"）。列表顺序 = 切换优先级；
     * 不在列表里的模型（新填了 Key 的平台）按默认顺序排在后面。没排过序时就是旧顺序（已配置的模型第一）。
     */
    private fun userOrder(): List<String> = runCatching { JSONArray(Store.getStr("ai.order", "[]")).let { a -> (0 until a.length()).map { a.optString(it) } } }.getOrDefault(emptyList())
    fun move(id: String, delta: Int) {
        val cur = candidates().map { it.id }.toMutableList()
        val i = cur.indexOf(id); val j = i + delta
        if (i < 0 || j !in cur.indices) return
        cur.removeAt(i); cur.add(j, id)
        Store.putStr("ai.order", JSONArray(cur).toString()); version++
    }
    fun resetOrder() { Store.removeKey("ai.order"); version++ }
    val customOrder: Boolean get() = userOrder().isNotEmpty()

    /** The ordered pool: the user's order from the model list, else the configured model first. */
    fun candidates(): List<AiEndpoint> {
        val base = defaultCandidates()
        val order = userOrder()
        if (order.isEmpty()) return base
        val rank = order.withIndex().associate { it.value to it.index }
        return base.withIndex().sortedWith(compareBy({ rank[it.value.id] ?: (order.size + it.index) }, { it.index })).map { it.value }
    }

    private fun defaultCandidates(): List<AiEndpoint> {
        val out = LinkedHashMap<String, AiEndpoint>()
        if (AiConfig.configured && !AiConfig.overBudget) AiEndpoint.current().let { out[it.id] = it }
        if (!autoSwitch || !AiConfig.enabled) return out.values.toList()
        for (p in AiProviders.all) {
            if (p.group != "free" || p.free.isEmpty()) continue
            val key = if (p.id == AiConfig.provider) AiConfig.key.trim().ifEmpty { AiKeys.get(p.id) } else AiKeys.get(p.id)
            if (key.isBlank()) continue
            val base = if (p.id == AiConfig.provider && AiConfig.baseUrl.isNotBlank()) AiConfig.baseUrl.trim().trimEnd('/') else p.baseUrl
            p.free.forEach { m -> AiEndpoint(p.id, base, key, m).let { out.putIfAbsent(it.id, it) } }
        }
        val orKey = if (AiConfig.provider == "openrouter") AiConfig.key.trim().ifEmpty { AiKeys.get("openrouter") } else AiKeys.get("openrouter")
        if (orKey.isNotBlank()) orFreeVision().forEach { m -> AiEndpoint("openrouter", AiProviders.of("openrouter").baseUrl, orKey, m).let { out.putIfAbsent(it.id, it) } }
        return out.values.toList()
    }

    /** A model in the pool can take a request right now. */
    fun usable(): Boolean = candidates().any { !cooled(it) }
    /** Earliest time a cooled model comes back (0 = none cooled / pool empty). */
    fun nextReady(): Long = candidates().map { coolUntil(it) }.filter { it > System.currentTimeMillis() }.minOrNull() ?: 0L

    class AllCooledException(val readyAt: Long, cause: Throwable?) : Exception(
        "所有可用的大模型都在冷却" + if (readyAt > 0) "（${hhmm(readyAt)} 起恢复）" else "", cause)

    /** How a failed request is handled. */
    sealed class Verdict {
        /** This photo only (400/413/415/422, sensitive-content filter): skip it, the model is fine. */
        data object PerImage : Verdict()
        data class Cool(val ms: Long, val reason: String, val wholeProvider: Boolean = false) : Verdict()
    }

    private const val MIN = 60_000L
    /**
     * 第 4 批：冷却按平台/错误类型拆分（旧版把免费额度和付费欠费混成同一个「余额/免费额度用完」）。
     *  - 免费额度（免费模型 402 / 429-daily）：冷到午夜（OpenRouter 按 UTC 午夜即北京时间 8 点，国内平台按本地午夜）。
     *  - 付费欠费（付费模型 402 / insufficient / 余额 / 欠费）：长冷 7 天 + 整提供商冷却（同一账号余额共用）+ 卡片提示「余额不足，请充值」。
     *  - 401：12h，整提供商（Key 整个账号有问题）。
     *  - 404（模型不存在/已下线）：24h + 提示换模型。
     */
    fun verdict(e: Throwable, ep: AiEndpoint): Verdict {
        val http = e as? AiClient.AiHttpException
        val code = http?.code ?: 0
        val msg = (e.message ?: "").lowercase()
        val ra = http?.retryAfterMs ?: 0L
        fun has(vararg w: String) = w.any { msg.contains(it.lowercase()) }
        val daily = has("per-day", "per day", "daily", "每日", "每天", "今日", "free-models-per-day", "quota", "额度", "1308", "1310")
        val noBalance = code == 402 || has("insufficient", "余额", "欠费", "1113", "balance")
        return when {
            code == 401 || code == 403 -> Verdict.Cool(12 * 60 * MIN, "Key 无效或无权限", wholeProvider = true)
            noBalance && ep.free -> Verdict.Cool(if (ra > 0) ra else msUntilReset(ep), "免费额度用完，午夜后恢复")
            noBalance -> Verdict.Cool(7 * 24 * 60 * MIN, "余额不足，请充值", wholeProvider = true)
            code == 404 || has("1211", "模型不存在", "model not found", "does not exist", "not a valid model", "no endpoints found", "model_not_found") ->
                Verdict.Cool(24 * 60 * MIN, "模型不存在或已下线，请换模型")
            code == 429 && daily && ep.free -> Verdict.Cool(if (ra > 0) ra else msUntilReset(ep), "今日免费额度用完，午夜后恢复")
            code == 429 && daily -> Verdict.Cool(if (ra > 0) ra else msUntilReset(ep), "今日额度用完")
            code == 429 -> Verdict.Cool(if (ra > 0) ra.coerceAtLeast(MIN) else 10 * MIN, "被限流")
            code == 1301 || has("1301", "敏感", "sensitive", "content_filter", "content filter", "data_inspection") -> Verdict.PerImage
            // B10: any other 4xx (401/402/403/404/429 matched above and cool the model) is a per-image
            // rejection — skip this photo instead of blocking the queue on it.
            code in 400..499 -> Verdict.PerImage
            code >= 500 -> Verdict.Cool(if (ra > 0) ra else 15 * MIN, "服务暂时不可用（$code）")
            e is java.net.SocketTimeoutException || has("timeout", "timed out") -> Verdict.Cool(15 * MIN, "超时")
            e is java.net.UnknownHostException || e is java.net.ConnectException || e is javax.net.ssl.SSLException || e is java.io.IOException ->
                Verdict.Cool(30 * MIN, if (ep.isOpenRouter) "连不上（OpenRouter 在国内需要翻墙）" else "连不上", wholeProvider = true)
            has("空回复", "空内容", "长度上限", "empty") -> Verdict.Cool(30 * MIN, "返回空内容")
            else -> Verdict.Cool(30 * MIN, (e.message ?: "出错").take(40))
        }
    }

    /** Daily quotas: OpenRouter resets at 00:00 UTC (08:00 Beijing), the domestic platforms at local midnight. */
    fun msUntilReset(ep: AiEndpoint): Long {
        val tz = if (ep.isOpenRouter) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
        val c = Calendar.getInstance(tz).apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 2); set(Calendar.SECOND, 0) }
        return (c.timeInMillis - System.currentTimeMillis()).coerceIn(10 * MIN, 26 * 60 * MIN)
    }

    /**
     * Classifies one photo with the first model that is not cooled down. A per-photo error is rethrown
     * (the runner skips/backs off as before); a model error cools that model and tries the next one.
     * Throws [AllCooledException] when nothing is left. With auto-switch off: the configured model only, errors rethrown.
     */
    suspend fun classify(jpeg: ByteArray): AiLabel {
        val cands = candidates()
        if (cands.isEmpty()) throw AllCooledException(0, null)
        if (!autoSwitch) { val ep = cands.first(); return AiClient.classify(jpeg, ep).also { active = ep; switched = "" } }
        var last: Throwable? = null
        val primary = cands.first()
        for (ep in cands) {
            if (cooled(ep)) continue
            try {
                val l = AiClient.classify(jpeg, ep)
                active = ep
                switched = if (ep.id == primary.id) "" else "已自动切换到 ${ep.label}（${ep.providerName}）" +
                    reason(primary).let { if (it.isNotEmpty() && cooled(primary)) "：${primary.label} $it" else "" }
                return l
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                when (val v = verdict(e, ep)) {
                    Verdict.PerImage -> throw e
                    is Verdict.Cool -> {
                        cool(if (v.wholeProvider) ep.provider + "|*" else ep.id, v.ms, v.reason)
                        Diag.log("AI", "cool ${ep.id} ${v.ms / 60000} min: ${v.reason} · ${e.message?.take(120)}")
                        last = e
                    }
                }
            }
        }
        throw AllCooledException(nextReady(), last)
    }

    fun hhmm(t: Long): String = java.text.SimpleDateFormat(if (t - System.currentTimeMillis() > 20 * 3600_000L) "M月d日 HH:mm" else "HH:mm", java.util.Locale.CHINA).format(java.util.Date(t))
}
