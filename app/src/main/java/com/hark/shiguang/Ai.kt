package com.hark.shiguang

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.*
import com.hark.shiguang.data.FnClient
import com.hark.shiguang.data.NasX
import com.hark.shiguang.data.Photo
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * protocol: "openai" (chat/completions) or "anthropic" (messages).
 * 1.0.1: [group] "free" 免费（国内推荐，不用翻墙）/ "paid" 国内付费 / "adv" 进阶（需翻墙）/ "local" 本地·自定义.
 * [free] = models of this provider that cost nothing and see images (the failover pool uses only these).
 * [keyUrl] = where to create the API key (shown as text + opens the browser).
 */
data class AiProvider(val id: String, val name: String, val baseUrl: String, val models: List<String>, val protocol: String = "openai", val needsKey: Boolean = true, val note: String = "",
                      val group: String = "paid", val free: List<String> = emptyList(), val keyUrl: String = "", val short: String = name)

object AiProviders {
    /**
     * Free domestic models checked against the official pages on 2026-10-09:
     * - 智谱 docs.bigmodel.cn/cn/guide/start/pricing: GLM-4.6V-Flash / GLM-4.1V-Thinking-Flash / GLM-4V-Flash 「免费」.
     * - 硅基流动 www.siliconflow.cn/pricing: Qwen/Qwen3.5-4B price 0 + vlm (GLM-4.1V-9B-Thinking is no longer listed).
     * - 魔搭 api-inference.modelscope.cn/v1/models (live list): Qwen3.5-35B-A3B, ERNIE-4.5-VL-28B-A3B, InternVL3.5-241B; daily free quota.
     */
    val all = listOf(
        // ---------------- 免费（国内推荐）
        AiProvider("zhipu", "智谱 BigModel（免费看图模型）", "https://open.bigmodel.cn/api/paas/v4",
            listOf("glm-4.6v-flash", "glm-4.1v-thinking-flash", "glm-4v-flash", "glm-4.6v-flashx", "glm-4.6v"),
            group = "free", free = listOf("glm-4.6v-flash", "glm-4.1v-thinking-flash", "glm-4v-flash"), keyUrl = "https://bigmodel.cn/usercenter/proj-mgmt/apikeys", short = "智谱",
            note = "glm-4.6v-flash、glm-4.1v-thinking-flash、glm-4v-flash 永久免费（有并发和速率限制），手机号注册即可，不用充值、不用翻墙"),
        AiProvider("siliconflow", "硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1",
            listOf("Qwen/Qwen3.5-4B", "Qwen/Qwen3-VL-8B-Instruct", "Qwen/Qwen3-VL-32B-Instruct", "zai-org/GLM-4.5V"),
            group = "free", free = listOf("Qwen/Qwen3.5-4B"), keyUrl = "https://cloud.siliconflow.cn/account/ak", short = "硅基流动",
            note = "Qwen/Qwen3.5-4B 免费看图（可能要先实名认证）；其余模型按量付费，新用户有赠金"),
        AiProvider("modelscope", "魔搭 ModelScope（每日免费额度）", "https://api-inference.modelscope.cn/v1",
            listOf("Qwen/Qwen3.5-35B-A3B", "PaddlePaddle/ERNIE-4.5-VL-28B-A3B-PT", "OpenGVLab/InternVL3_5-241B-A28B"),
            group = "free", free = listOf("Qwen/Qwen3.5-35B-A3B", "PaddlePaddle/ERNIE-4.5-VL-28B-A3B-PT", "OpenGVLab/InternVL3_5-241B-A28B"),
            keyUrl = "https://modelscope.cn/my/myaccesstoken", short = "魔搭",
            note = "Key 填魔搭「访问令牌」（需绑定阿里云账号）；每天有免费调用次数，可用模型会变动"),
        // ---------------- 国内付费
        AiProvider("qwen", "通义千问（阿里云百炼）", "https://dashscope.aliyuncs.com/compatible-mode/v1", listOf("qwen-vl-plus", "qwen-vl-max", "qwen2.5-vl-72b-instruct"),
            keyUrl = "https://bailian.console.aliyun.com/", short = "百炼", note = "新开通的账号各模型有一段时间的免费额度，之后按量付费"),
        AiProvider("doubao", "豆包（火山方舟）", "https://ark.cn-beijing.volces.com/api/v3", listOf("doubao-1-5-vision-pro-32k-250115", "doubao-seed-1-6-250615"),
            keyUrl = "https://console.volcengine.com/ark", short = "豆包", note = "模型可填推理接入点 ID"),
        AiProvider("kimi", "Kimi（月之暗面）", "https://api.moonshot.cn/v1", listOf("moonshot-v1-8k-vision-preview", "kimi-latest"), keyUrl = "https://platform.moonshot.cn/console/api-keys", short = "Kimi"),
        AiProvider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", listOf("deepseek-chat"), keyUrl = "https://platform.deepseek.com/api_keys", short = "DeepSeek",
            note = "仅文本：只能用于智能搜索的关键词扩展"),
        // ---------------- 进阶（需翻墙）
        AiProvider("openrouter", "OpenRouter（每日免费模型）", "https://openrouter.ai/api/v1", listOf("google/gemma-4-31b-it:free", "google/gemma-4-26b-a4b-it:free", "thinkingmachines/inkling-small:free"),
            group = "adv", free = listOf("google/gemma-4-31b-it:free", "google/gemma-4-26b-a4b-it:free", "thinkingmachines/inkling-small:free"),
            keyUrl = "https://openrouter.ai/settings/keys", short = "OpenRouter",
            note = "需要翻墙。带 :free 的模型免费（每天有次数上限）；点「获取免费模型」看今天可用的"),
        AiProvider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.5-pro"), group = "adv", keyUrl = "https://aistudio.google.com/apikey", short = "Gemini"),
        AiProvider("openai", "OpenAI", "https://api.openai.com/v1", listOf("gpt-4o-mini", "gpt-4.1-mini", "gpt-4o", "gpt-4.1"), group = "adv", short = "OpenAI"),
        AiProvider("anthropic", "Anthropic Claude", "https://api.anthropic.com/v1", listOf("claude-3-5-haiku-latest", "claude-sonnet-4-5", "claude-3-7-sonnet-latest"), protocol = "anthropic", group = "adv", short = "Claude"),
        AiProvider("groq", "Groq", "https://api.groq.com/openai/v1", listOf("meta-llama/llama-4-scout-17b-16e-instruct", "meta-llama/llama-4-maverick-17b-128e-instruct"), group = "adv", short = "Groq"),
        AiProvider("together", "Together AI", "https://api.together.xyz/v1", listOf("meta-llama/Llama-4-Maverick-17B-128E-Instruct-FP8", "meta-llama/Llama-4-Scout-17B-16E-Instruct"), group = "adv", short = "Together"),
        AiProvider("llama", "Meta Llama API", "https://api.llama.com/compat/v1", listOf("Llama-4-Maverick-17B-128E-Instruct-FP8", "Llama-4-Scout-17B-16E-Instruct-FP8", "Llama-3.3-70B-Instruct"), note = "OpenAI 兼容接口", group = "adv", short = "Llama"),
        // ---------------- 本地 / 自定义
        AiProvider("ollama", "Ollama（本地）", "http://192.168.1.2:11434/v1", listOf("qwen2.5vl", "llava", "llama3.2-vision"), needsKey = false, note = "填你电脑的局域网地址", group = "local", short = "Ollama"),
        AiProvider("custom", "自定义（OpenAI 兼容）", "", emptyList(), needsKey = false, group = "local", short = "自定义"),
    )
    val GROUPS = listOf("free" to "免费（国内推荐，不用翻墙）", "paid" to "国内付费", "adv" to "进阶（需翻墙）", "local" to "本地 / 自定义")
    fun of(id: String) = all.firstOrNull { it.id == id } ?: all.first { it.id == "custom" }
    fun isFree(provider: String, model: String) = model.endsWith(":free") || of(provider).free.contains(model)
}

object AiConfig {
    /** 1.0.1: default is the free domestic 智谱 preset (works without VPN). */
    var provider by mutableStateOf(Store.getStr("ai.provider", "zhipu"))
    var baseUrl by mutableStateOf(Store.getStr("ai.base", AiProviders.of(Store.getStr("ai.provider", "zhipu")).baseUrl))
    var key by mutableStateOf(Store.getStr("ai.key").ifEmpty { AiKeys.get(Store.getStr("ai.provider", "zhipu")) })
    var model by mutableStateOf(Store.getStr("ai.model", AiProviders.of(Store.getStr("ai.provider", "zhipu")).models.firstOrNull() ?: ""))
    var budget by mutableStateOf(Store.getStr("ai.budget", "10"))
    var priceIn by mutableStateOf(Store.getStr("ai.pin", "2"))   // ¥ per 1M input tokens
    var priceOut by mutableStateOf(Store.getStr("ai.pout", "8"))
    var nasToo by mutableStateOf(Store.getStr("ai.nas", "0") == "1")
    var enabled by mutableStateOf(Store.getStr("ai.on", "0") == "1")

    val configured: Boolean get() = enabled && baseUrl.isNotBlank() && model.isNotBlank() && (key.isNotBlank() || !AiProviders.of(provider).needsKey)
    val protocol: String get() = AiProviders.of(provider).protocol

    /** Switches the preset and brings back the key saved for it (keys are kept per provider). */
    fun choose(p: AiProvider) {
        if (key.isNotBlank()) AiKeys.set(provider, key)
        provider = p.id
        if (p.baseUrl.isNotEmpty() || p.id == "custom") baseUrl = p.baseUrl
        model = p.models.firstOrNull() ?: ""
        key = AiKeys.get(p.id)
    }

    fun save() {
        Store.putStr("ai.provider", provider); Store.putStr("ai.base", baseUrl.trim().trimEnd('/')); Store.putStr("ai.key", key.trim()); Store.putStr("ai.model", model.trim())
        if (key.isNotBlank()) AiKeys.set(provider, key)
        Store.putStr("ai.budget", budget); Store.putStr("ai.pin", priceIn); Store.putStr("ai.pout", priceOut)
        Store.putStr("ai.nas", if (nasToo) "1" else "0"); Store.putStr("ai.on", if (enabled) "1" else "0")
    }

    private fun month() = java.text.SimpleDateFormat("yyyyMM", java.util.Locale.US).format(java.util.Date())
    val spent: Double get() = if (Store.getStr("ai.month") == month()) Store.getStr("ai.spent", "0").toDoubleOrNull() ?: 0.0 else 0.0
    fun addSpend(inTok: Int, outTok: Int) {
        val c = (inTok * (priceIn.toDoubleOrNull() ?: 0.0) + outTok * (priceOut.toDoubleOrNull() ?: 0.0)) / 1_000_000.0
        val now = spent + c
        Store.putStr("ai.month", month()); Store.putStr("ai.spent", "%.5f".format(java.util.Locale.US, now))
    }
    val overBudget: Boolean get() = (budget.toDoubleOrNull() ?: 0.0) in 0.0001..spent
}

/** [local] = labelled on the phone by ML Kit (1.0.1 #8), to be refined by a model later; [model] = who labelled it. */
data class AiLabel(val cat: String, val tags: List<String>, val caption: String, val faces: Int, val local: Boolean = false, val model: String = "")

object AiClient {
    private val http by lazy { FnClient.http.newBuilder().readTimeout(90, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build() }
    private val JSONT = "application/json; charset=utf-8".toMediaType()

    /** HTTP error from the model API; [retryAfterMs] comes from Retry-After / X-RateLimit-Reset (429). */
    class AiHttpException(val code: Int, msg: String, val retryAfterMs: Long = 0) : Exception(msg)

    val isOpenRouter: Boolean get() = AiConfig.baseUrl.contains("openrouter.ai", true)

    /** OpenRouter app attribution headers (optional). X-Title is non-ASCII, so it goes through addUnsafeNonAscii. */
    private fun Request.Builder.orHeaders(or: Boolean): Request.Builder {
        if (!or) return this
        header("HTTP-Referer", "https://yiwei.cc.cd")
        return headers(okhttp3.Headers.Builder().also { b -> build().headers.forEach { (k, v) -> b.add(k, v) } }.addUnsafeNonAscii("X-Title", "一维相册").build())
    }

    private fun authed(b: Request.Builder, ep: AiEndpoint = AiEndpoint.current()): Request.Builder =
        b.apply { if (ep.key.isNotBlank()) header("Authorization", "Bearer ${ep.key}") }.orHeaders(ep.isOpenRouter)

    /**
     * Turns an error body into a readable line. OpenAI-style {error:{message,code,metadata:{raw,provider_name}}},
     * Anthropic {error:{type,message}}, plain {message}/{detail}, or a bare string.
     */
    fun errorText(code: Int, text: String, ep: AiEndpoint = AiEndpoint.current()): String {
        val j = runCatching { JSONObject(text) }.getOrNull()
        val e = j?.opt("error")
        var msg = when (e) {
            is JSONObject -> e.optString("message").let { m -> e.optString("code").takeIf { it.isNotBlank() && it != "null" && !m.contains(it) }?.let { "$m（$it）" } ?: m }
            is String -> e
            else -> j?.optString("message")?.ifBlank { j.optString("detail") } ?: text.take(160)
        }.orEmpty().trim()
        val meta = (e as? JSONObject)?.optJSONObject("metadata")
        val raw = meta?.opt("raw")?.let { if (it is JSONObject) it.optJSONObject("error")?.optString("message") ?: it.toString() else it.toString() }.orEmpty()
        val provider = meta?.optString("provider_name").orEmpty()
        if (raw.isNotBlank() && !msg.contains(raw.take(40))) msg += "（${provider.ifBlank { "上游" }}：${raw.take(120)}）"
        val or = ep.isOpenRouter
        val hint = when {
            code == 401 -> if (or) "API Key 无效或未填（OpenRouter 的 Key 以 sk-or- 开头）" else if (ep.isModelScope) "Key 无效：魔搭要填「访问令牌」，并先绑定阿里云账号" else "API Key 无效或未填"
            code == 402 -> if (or) "余额不足：这个模型要付费。请换成带 :free 的免费模型（点「获取免费模型」），或给 OpenRouter 充值" else "余额不足"
            code == 404 && msg.contains("data policy", true) -> "OpenRouter 隐私设置不允许这个免费模型：到 openrouter.ai/settings/privacy 打开免费模型的数据策略"
            code == 404 && or -> "模型不存在或今天不再免费，请点「获取免费模型」重新选"
            code == 429 -> if (or && ep.model.endsWith(":free")) "免费模型次数用完或请求太快（OpenRouter 免费模型有每分钟和每天上限，每天按 UTC 零点即北京时间 8 点重置）"
                else if (ep.isZhipu) "请求太频繁或额度用完（智谱免费模型有并发上限）" else "请求太频繁，被限流"
            else -> ""
        }
        return listOf(hint, msg).filter { it.isNotBlank() }.distinct().joinToString(" · ").take(240)
    }

    private fun retryAfter(r: okhttp3.Response): Long {
        r.header("Retry-After")?.trim()?.toLongOrNull()?.let { return it * 1000 }
        r.header("X-RateLimit-Reset")?.trim()?.toLongOrNull()?.let { v ->
            val ms = if (v > 10_000_000_000L) v else v * 1000   // epoch ms (OpenRouter) or seconds
            return (ms - System.currentTimeMillis()).coerceAtLeast(0)
        }
        return 0
    }

    /** [reasoning] = reasoning_content of thinking models (used when the answer field is empty). */
    data class Reply(val text: String, val inTok: Int, val outTok: Int, val reasoning: String = "")

    /** Returns the model's text reply. [jpeg] is an optional ≤512px image. Counts tokens against the monthly budget. */
    suspend fun chat(prompt: String, jpeg: ByteArray? = null, maxTokens: Int = 300, ep: AiEndpoint = AiEndpoint.current()): String = chatFull(prompt, jpeg, maxTokens, ep).text

    /** Hybrid Qwen3.x text/VL models think by default; `enable_thinking:false` (SiliconFlow / ModelScope) skips that. */
    private fun qwenHybrid(m: String) = Regex("Qwen3(\\.\\d+)?-", RegexOption.IGNORE_CASE).containsMatchIn(m) && !m.contains("VL", true) && !m.contains("Thinking", true) && !m.contains("Instruct-2507", true)

    suspend fun chatFull(prompt: String, jpeg: ByteArray? = null, maxTokens: Int = 300, ep: AiEndpoint = AiEndpoint.current()): Reply = withContext(Dispatchers.IO) {
        val base = ep.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) throw Exception("还没填接口地址")
        if (ep.model.isBlank()) throw Exception("还没填模型")
        val b64 = jpeg?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        val req: Request
        if (ep.protocol == "anthropic") {
            val content = JSONArray()
            if (b64 != null) content.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64)))
            content.put(JSONObject().put("type", "text").put("text", prompt))
            val body = JSONObject().put("model", ep.model).put("max_tokens", maxTokens)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            req = Request.Builder().url("$base/messages").header("x-api-key", ep.key).header("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody(JSONT)).build()
        } else {
            // 智谱 documents image_url.url as the bare base64 string; everyone else takes a data: URL
            val imgUrl = if (ep.isZhipu) b64 else "data:image/jpeg;base64,$b64"
            val content: Any = if (b64 == null) prompt else JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imgUrl)))
            // Thinking models spend tokens before answering: a small max_tokens is eaten and the answer comes back empty.
            val thinks = ep.isOpenRouter || ep.model.contains("thinking", true) || ep.model.contains("Qwen3.5", true) || ep.model.contains("InternVL3", true)
            val mt = if (thinks) maxOf(maxTokens, 1500) else maxTokens
            val body = JSONObject().put("model", ep.model).put("max_tokens", mt).put("temperature", 0.2)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            if (ep.isOpenRouter) body.put("reasoning", JSONObject().put("effort", "low").put("exclude", true))
            // GLM-4.6V / 4.5V: thinking can be switched off (GLM-4.1V-Thinking always thinks)
            if (ep.isZhipu && Regex("glm-4\\.[56]v", RegexOption.IGNORE_CASE).containsMatchIn(ep.model)) body.put("thinking", JSONObject().put("type", "disabled"))
            if ((ep.isSilicon || ep.isModelScope) && qwenHybrid(ep.model)) body.put("enable_thinking", false)
            req = authed(Request.Builder().url("$base/chat/completions"), ep).post(body.toString().toRequestBody(JSONT)).build()
        }
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AiHttpException(r.code, "AI 接口错误（HTTP ${r.code}）：" + errorText(r.code, text, ep), if (r.code == 429 || r.code == 503) retryAfter(r) else 0)
            val j = runCatching { JSONObject(text) }.getOrElse { throw Exception("AI 接口返回的不是 JSON：" + text.take(80)) }
            // OpenRouter can answer 200 with an error object (provider failed after routing)
            j.optJSONObject("error")?.let { e ->
                val c = e.optInt("code", 502)
                throw AiHttpException(c, "AI 接口错误（$c）：" + errorText(c, text, ep))
            }
            if (ep.protocol == "anthropic") {
                val u = j.optJSONObject("usage")
                if (!ep.free) u?.let { AiConfig.addSpend(it.optInt("input_tokens"), it.optInt("output_tokens")) }
                Reply(j.optJSONArray("content")?.optJSONObject(0)?.optString("text").orEmpty(), u?.optInt("input_tokens") ?: 0, u?.optInt("output_tokens") ?: 0)
            } else {
                val u = j.optJSONObject("usage")
                // free models cost nothing: keep them out of the monthly budget
                if (!ep.free) u?.let { AiConfig.addSpend(it.optInt("prompt_tokens"), it.optInt("completion_tokens")) }
                val ch = j.optJSONArray("choices")?.optJSONObject(0)
                val msg = ch?.optJSONObject("message")
                val out = msg?.opt("content").let { if (it is String) it else if (it is JSONArray) (0 until it.length()).joinToString("") { i -> it.optJSONObject(i)?.optString("text").orEmpty() } else "" }
                val rs = msg?.optString("reasoning_content")?.takeIf { it != "null" }.orEmpty()
                if (out.isBlank() && rs.isBlank() && ch?.optString("finish_reason") == "length") throw Exception("模型还没给出答案就到了长度上限（思考型模型），换一个非思考模型试试")
                // 智谱 GLM-4.1V-Thinking wraps the answer in <|begin_of_box|>…<|end_of_box|>
                Reply(out.replace("<|begin_of_box|>", "").replace("<|end_of_box|>", ""), u?.optInt("prompt_tokens") ?: 0, u?.optInt("completion_tokens") ?: 0, rs)
            }
        }
    }

    // ------------------------------------------------ OpenRouter free models
    data class FreeModel(val id: String, val name: String, val vision: Boolean, val ctx: Int)
    /** model id → accepts images, from the last model list fetch (OpenRouter). */
    val visionOf = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** GET /models and keep the ones whose prompt and completion price are both 0, vision models first. */
    suspend fun freeModels(): List<FreeModel> = withContext(Dispatchers.IO) {
        val base = AiConfig.baseUrl.trim().trimEnd('/').ifBlank { "https://openrouter.ai/api/v1" }
        val r = http.newCall(authed(Request.Builder().url("$base/models")).build()).execute()
        val text = r.use { if (!it.isSuccessful) throw AiHttpException(it.code, "获取模型列表失败（HTTP ${it.code}）：" + errorText(it.code, it.body?.string().orEmpty())); it.body?.string().orEmpty() }
        val a = JSONObject(text).optJSONArray("data") ?: JSONArray()
        val out = ArrayList<FreeModel>()
        for (i in 0 until a.length()) {
            val m = a.optJSONObject(i) ?: continue
            val id = m.optString("id"); if (id.isBlank()) continue
            val arch = m.optJSONObject("architecture")
            val ins = arch?.optJSONArray("input_modalities")?.let { x -> (0 until x.length()).map { x.optString(it) } } ?: emptyList()
            val outs = arch?.optJSONArray("output_modalities")?.let { x -> (0 until x.length()).map { x.optString(it) } } ?: listOf("text")
            val vision = "image" in ins || arch?.optString("modality").orEmpty().substringBefore("->").contains("image")
            visionOf[id] = vision
            val pr = m.optJSONObject("pricing") ?: continue
            fun zero(k: String) = pr.optString(k).let { it == "0" || it.toDoubleOrNull() == 0.0 }
            if (!zero("prompt") || !zero("completion") || "text" !in outs) continue
            if (!id.endsWith(":free") && id != "openrouter/free") continue
            out += FreeModel(id, m.optString("name").ifBlank { id }, vision, m.optInt("context_length"))
        }
        out.sortedWith(compareByDescending<FreeModel> { it.vision }.thenBy { it.id == "openrouter/free" }.thenBy { it.name.lowercase() })
            .also { l -> l.filter { it.vision && it.id.endsWith(":free") }.map { it.id }.takeIf { it.isNotEmpty() }?.let { AiPool.saveOrFreeVision(it) } }
    }

    /** OpenRouter: today's free-model quota of this key, e.g. 「今日免费额度剩 47/50 次」. Empty when unknown. */
    suspend fun freeQuota(): String = withContext(Dispatchers.IO) {
        if (!isOpenRouter || AiConfig.key.isBlank()) return@withContext ""
        runCatching {
            val base = AiConfig.baseUrl.trim().trimEnd('/')
            http.newCall(authed(Request.Builder().url("$base/key")).build()).execute().use { r ->
                if (!r.isSuccessful) return@use ""
                val q = JSONObject(r.body?.string().orEmpty()).optJSONObject("data")?.optJSONObject("free_model_daily_requests") ?: return@use ""
                "今日免费额度剩 ${q.optInt("remaining")}/${q.optInt("limit")} 次"
            }
        }.getOrDefault("")
    }

    /** null = unknown; false = the model is known to be text-only (photo classification will fail). */
    fun modelSeesImages(): Boolean? = visionOf[AiConfig.model.trim()]

    suspend fun test(): String {
        val t = chat("只回复两个字：你好", maxTokens = 10).trim().ifEmpty { "（空回复）" }
        if (isOpenRouter && visionOf.isEmpty()) runCatching { freeModels() }
        val warn = if (modelSeesImages() == false) " · ⚠ 这个模型不能看图，照片分类会失败，只能用于智能搜索" else ""
        val q = freeQuota().let { if (it.isNotEmpty()) " · $it" else "" }
        return t.take(30) + q + warn
    }

    /** 「测速」: one tiny request; latency and output speed. */
    data class Speed(val ms: Long, val outTok: Int) {
        val tps: Double get() = if (outTok > 0 && ms > 0) outTok * 1000.0 / ms else 0.0
        override fun toString() = "$ms ms" + if (tps > 0) " · %.1f tokens/s".format(java.util.Locale.US, tps) else ""
    }
    suspend fun speed(): Speed {
        val t0 = System.nanoTime()
        val r = chatFull("用一句话（约 30 字）介绍一下你自己。", maxTokens = 80)
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (r.text.isBlank()) throw Exception("模型返回空内容")
        return Speed(ms, r.outTok)
    }

    val CATS = listOf("人像", "合照", "美食", "宠物", "风景", "建筑", "城市", "夜景", "植物", "交通", "运动", "文档", "截图", "其他")

    /** Classifies one image. Only a ≤512px thumbnail is sent, never location. */
    suspend fun classify(jpeg: ByteArray, ep: AiEndpoint = AiEndpoint.current()): AiLabel {
        val prompt = "你是相册整理助手。看这张照片，只输出一行 JSON，不要其他文字：" +
            "{\"cat\":\"从 [${CATS.joinToString(",")}] 中选一个\",\"tags\":[\"3到6个中文关键词\"],\"caption\":\"一句20字以内的中文描述\",\"faces\":画面里能看清的真人脸数量（整数）}"
        val rep = chatFull(prompt, jpeg, 200, ep)
        // thinking models sometimes leave the JSON only in reasoning_content
        val t = rep.text.takeIf { it.contains('{') } ?: rep.reasoning.substringAfterLast("{", "").let { if (it.isNotEmpty()) "{$it" else rep.text }
        if (t.isBlank()) throw Exception("模型返回空内容")
        val js = t.substring(t.indexOf('{').coerceAtLeast(0), (t.lastIndexOf('}') + 1).coerceAtLeast(0).coerceAtMost(t.length))
        val j = runCatching { JSONObject(js) }.getOrElse { JSONObject().put("caption", t.take(40)) }
        val cat = j.optString("cat").takeIf { it in CATS } ?: "其他"
        val tags = j.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        // JSON with an unquoted number is fine; if the model wrote words instead, faces stays unknown
        val faces = if (j.has("faces")) j.optInt("faces", -1) else Regex("\"faces\"\\s*:\\s*(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        return AiLabel(cat, tags, j.optString("caption"), faces, model = ep.label)
    }

    /** Splits a query into concepts that must all match, each with synonyms: [["海边","沙滩","大海"],["日落","夕阳","黄昏"]]. */
    suspend fun concepts(q: String): List<List<String>> = runCatching {
        val t = chat("把这句相册搜索语句拆成 1 到 3 个必须同时满足的概念（物体、场景、人物、活动、颜色），每个概念给 2 到 4 个中文同义说法，第一个是最贴近原句的词。" +
            "忽略「照片」「的」这类虚词。只输出 JSON 数组，例如 [[\"海边\",\"沙滩\",\"大海\"],[\"日落\",\"夕阳\",\"黄昏\"]]。语句：$q", maxTokens = 160)
        val a = JSONArray(t.substring(t.indexOf('['), t.lastIndexOf(']') + 1))
        (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { g -> (0 until g.length()).map { g.optString(it).trim() }.filter { it.isNotEmpty() } }?.takeIf { it.isNotEmpty() } }
    }.getOrDefault(emptyList())

    /** Turns a natural-language query into keywords. */
    suspend fun keywords(q: String): List<String> = runCatching {
        chat("把这句相册搜索语句拆成 3 到 6 个中文关键词（物体、场景、颜色、活动），只输出用逗号分隔的关键词：$q", maxTokens = 60)
            .split(',', '，', '、', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    fun to512(bytes: ByteArray): ByteArray? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 512) s *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val k = 512f / maxOf(b.width, b.height)
        val sc = if (k < 1f) Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true) else b
        return ByteArrayOutputStream().also { sc.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }
}

/**
 * Background AI labelling. WebDAV photos go into the library meta; NAS photos (optional) into a per-account file.
 *
 * 1.0.9: follows [ScanPolicy] like local analysis and faces (no own Wi-Fi/charging switch), never gives up:
 * when the network/charging/budget conditions are not met it waits and resumes by itself, transient API errors
 * are retried with backoff, and [note] tells the AI tab why it is not moving. [pause] is the user's pause button.
 */
object AiRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var jobKey = ""
    /** Last progress / outcome line ("已整理 12 / 300", "完成"). */
    var status by mutableStateOf("")
    /** True only while actually sending photos to the model. */
    var running by mutableStateOf(false)
    /** Why AI is not moving right now (waiting for Wi-Fi, budget reached, API error…); empty while working or done. */
    var note by mutableStateOf("")
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    /** Job alive (working or waiting for conditions). */
    val active: Boolean get() = job?.isActive == true

    /** AI classification is switched on at all: a model is configured, or the on-device fallback (1.0.1 #8) is on. */
    val on: Boolean get() = AiConfig.configured || LocalAi.enabled
    /** The last photo was labelled on the phone (no model available). */
    var usingLocal by mutableStateOf(false)
    /** 「AI 分类 · glm-4.6v-flash」 / 「AI 分类 · 本地」 on the progress card: who is labelling. */
    val rowLabel: String get() = "AI 分类" + (if (usingLocal) " · 本地" else AiPool.active?.label?.let { " · $it" } ?: "")
    /** Why it waits + 「已自动切换到 …」 for the AI card. */
    val cardNote: String get() = listOf(note.takeIf { it != "已暂停" }.orEmpty(), AiPool.switched).filter { it.isNotEmpty() }.distinct().joinToString(" · ")

    fun canRun(c: Context): Boolean = AiConfig.configured && !AiConfig.overBudget && Analyzer.canScan(c)

    /** 「暂停」 on the AI tab: stops now; automatic starts are ignored until a manual start (see [ScanPolicy.paused]). */
    fun pause() { ScanPolicy.paused = true; job?.cancel(); running = false; note = "已暂停" }
    fun resume() { ScanPolicy.paused = false; if (note == "已暂停") note = "" }

    /** Starts (or keeps) labelling the WebDAV library. [manual] = user pressed 继续/立即整理: ignores network/charging rules. */
    fun startDav(c: Context, lib: DavLib, manual: Boolean = false) {
        if (!on) { status = "未配置"; return }
        if (manual) resume() else if (ScanPolicy.paused) return
        val key = "dav:" + lib.accountId + if (manual) ":m" else ""
        if (job?.isActive == true && (jobKey == key || !manual && jobKey.startsWith("dav:" + lib.accountId))) return
        job?.cancel()
        jobKey = key
        val skip = HashSet<String>()
        val marks = LocalMarks.of("dav-" + lib.accountId)
        job = scope.launch {
            loop(c, manual,
                // unlabelled photos first; photos labelled on the phone (本地) are re-done once a model is available
                todo = {
                    val llm = AiConfig.configured && AiPool.usable()
                    lib.photos.filter { !it.isVideo && it.cloudPath !in skip && (lib.meta[it.cloudPath]?.aiCat.isNullOrEmpty() || llm && marks.has(it.cloudPath)) }
                        .sortedBy { marks.has(it.cloudPath) }
                },
                isLocal = { p -> marks.has(p.cloudPath) },
                localLeft = { marks.size },
                fetch = { p -> davImage(lib, p) },
                skip = { p -> skip.add(p.cloudPath) },
                store = { p, l ->
                    val m = lib.meta[p.cloudPath] ?: Meta()
                    m.aiCat = l.cat; m.aiTags = l.tags; m.aiCaption = l.caption; m.aiFaces = l.faces; lib.meta[p.cloudPath] = m
                    if (l.local) marks.add(p.cloudPath) else marks.remove(p.cloudPath)
                },
                flush = { lib.saveMeta(); marks.save(); lib.metaVersion++ })
        }
    }

    /** Last reason a WebDAV photo could not be read (shown in the pause note). */
    @Volatile private var readErr = ""

    /**
     * 1.0.10: picture for the model without downloading every original. 123 云盘 WebDAV limits download traffic,
     * so 1.0.9 (full original per photo) stopped after a few dozen photos with 「读取照片失败」.
     * Order: the EXIF thumbnail in the first 128 KB when it is big enough (≥320 px) → the original (≤25 MB) →
     * the grid thumbnail cache (DavThumb) → any EXIF thumbnail ≥120 px.
     */
    private suspend fun davImage(lib: DavLib, p: Photo): ByteArray? {
        val src = lib.src ?: run { readErr = "WebDAV 未连接"; return null }
        var small: ByteArray? = null
        if (!p.isVideo) runCatching {
            val head = src.headBytes(p.cloudPath)
            if (head != null) {
                val ex = android.media.ExifInterface(java.io.ByteArrayInputStream(head))
                val t = ex.thumbnailBytes
                if (t != null) {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(t, 0, t.size, o)
                    val side = maxOf(o.outWidth, o.outHeight)
                    if (side >= 320) return t
                    if (side >= 120) small = t
                }
            }
        }
        if (p.size <= 25_000_000) {
            src.allBytes(p.cloudPath)?.let { return it }
            readErr = src.lastError
        } else readErr = "文件超过 25 MB"
        runCatching {
            val f = DavThumb.file(p.thumbS.removeSuffix(DavThumb.MARK))
            if (f.length() > 0) return f.readBytes()
        }
        return small
    }

    /**
     * The shared loop: never breaks on a condition, waits instead. Returns when everything readable is labelled,
     * the job is cancelled (pause / source switch), or the AI config was removed.
     */
    private suspend fun loop(c: Context, manual: Boolean, todo: () -> List<Photo>, isLocal: (Photo) -> Boolean, localLeft: () -> Int,
                             fetch: suspend (Photo) -> ByteArray?, skip: (Photo) -> Unit, store: (Photo, AiLabel) -> Unit, flush: () -> Unit) {
        var backoff = 0
        var pass = 0
        try {
            while (currentCoroutineContext().isActive) {
                val list = todo()
                if (list.isEmpty()) { status = "完成"; note = ""; break }
                var n = 0; total = list.size
                var i = 0
                var misses = 0
                while (i < list.size) {
                    currentCoroutineContext().ensureActive()
                    if (!on) { note = "未配置大模型"; running = false; return }
                    // ---- wait for the conditions instead of quitting
                    val why = when {
                        AiConfig.overBudget && !AiPool.usable() && !LocalAi.enabled -> "已到本月预算 ¥${AiConfig.budget}，下月或调高预算后自动继续"
                        !Net.online(c) -> "等待网络"
                        !manual && !Analyzer.canScan(c) -> ScanPolicy.waitingText()
                        else -> ""
                    }
                    if (why.isNotEmpty()) { running = false; note = why; delay(if (AiConfig.overBudget) 120_000 else 20_000); continue }
                    val p = list[i]
                    if (!running) ScanService.ensure(c)
                    running = true
                    if (note.isNotEmpty() && backoff == 0) note = ""
                    val bytes = runCatching { fetch(p) }.getOrNull()
                    val small = bytes?.let { runCatching { AiClient.to512(it) }.getOrNull() }
                    if (small == null) {
                        // unreadable this pass; stays in the todo list for the next pass
                        i++
                        if (++misses >= 8) {
                            misses = 0; running = false
                            val why = readErr.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""
                            note = "连续 8 张照片读取失败$why，1 分钟后重试"
                            Diag.log("AI", "read failed x8: $readErr")
                            delay(60_000)
                        }
                        continue
                    }
                    misses = 0
                    // 1.0.1 #4: the pool tries the configured model, then the other free models (cooling down the ones that fail)
                    // 1.0.1 #8: no model / all cooled / over budget → ML Kit on the phone, so classification never stops
                    val llm = AiConfig.configured && AiPool.usable()
                    var r = if (llm) runCatching { AiPool.classify(small) } else Result.failure(AiPool.AllCooledException(AiPool.nextReady(), null))
                    if (r.exceptionOrNull() is AiPool.AllCooledException && LocalAi.enabled) {
                        if (isLocal(p)) { i++; continue }   // already labelled on the phone; wait for a model to refine it
                        r = runCatching { LocalAi.classify(small, p.fileName) }
                        val le = r.exceptionOrNull()
                        if (le != null) {
                            if (le is CancellationException) throw le
                            Diag.log("AI", "local label failed: ${le.message}")
                            skip(p); i++; continue
                        }
                        usingLocal = true
                        if (AiConfig.configured && note.isEmpty()) note = "大模型暂不可用，先用本地识别" +
                            (AiPool.nextReady().takeIf { it > 0 }?.let { "，${AiPool.hhmm(it)} 起自动细分" } ?: "")
                    } else if (r.isSuccess) usingLocal = false
                    val e = r.exceptionOrNull()
                    if (e is AiPool.AllCooledException) {
                        running = false
                        val at = e.readyAt.takeIf { it > System.currentTimeMillis() } ?: (System.currentTimeMillis() + 600_000L)
                        val wait = (at - System.currentTimeMillis() + 5_000L).coerceIn(30_000L, 6 * 3600_000L)
                        note = "可用的大模型都在冷却（${(e.cause?.message ?: "额度用完或连不上").removePrefix("AI 接口错误").take(80)}）· ${AiPool.hhmm(System.currentTimeMillis() + wait)} 自动继续"
                        Diag.log("AI", "all cooled until ${AiPool.hhmm(at)}")
                        delay(wait)
                        continue
                    }
                    if (e != null) {
                        if (e is CancellationException) throw e
                        backoff++
                        // a request the API rejects for this image only (400/413/415/422) must not block the queue
                        val code = (e as? AiClient.AiHttpException)?.code ?: Regex("HTTP (\\d{3})").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        if (code in setOf(400, 413, 415, 422) && backoff >= 2) { skip(p); i++; backoff = 0; continue }
                        var wait = (15_000L shl (backoff - 1).coerceAtMost(5)).coerceAtMost(600_000L)
                        // 429 (free-tier per-minute / daily limit): honour the server's reset time, up to 6 h
                        val ra = (e as? AiClient.AiHttpException)?.takeIf { it.code == 429 || it.code == 503 }?.retryAfterMs ?: 0L
                        if (ra > 0) wait = (ra + 2_000L).coerceIn(15_000L, 6 * 3600_000L)
                        else if (code == 429) wait = wait.coerceAtLeast(60_000L)
                        running = false
                        val whenTxt = if (wait >= 3600_000L) java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(System.currentTimeMillis() + wait)) + " 自动继续"
                            else if (wait >= 120_000L) "${wait / 60_000} 分钟后重试" else "${wait / 1000} 秒后重试"
                        note = (if (code == 429) "被限流：" else "") + (e.message ?: "AI 接口出错").removePrefix("AI 接口错误（HTTP 429）：").take(150) + " · " + whenTxt
                        Diag.log("AI", "classify failed (${backoff}): ${e.message}")
                        delay(wait)
                        continue
                    }
                    backoff = 0; if (!usingLocal) note = ""
                    store(p, r.getOrThrow())
                    n++; i++; done = n
                    status = "已整理 $n / ${list.size}"
                    if (n % 10 == 0) flush()
                }
                flush()
                pass++
                // whatever was skipped (unreadable, too large, repeatedly failing) is retried after a rest
                val left = todo()
                // photos labelled on the phone wait for a model: sleep until the earliest cooldown ends, then refine them
                if (left.isEmpty() && AiConfig.configured && localLeft() > 0 && !AiPool.usable()) {
                    running = false
                    val at = AiPool.nextReady().takeIf { it > System.currentTimeMillis() } ?: (System.currentTimeMillis() + 3600_000L)
                    status = "完成（${localLeft()} 张为本地识别）"
                    note = "已用本地识别整理；大模型 ${AiPool.hhmm(at)} 恢复后自动细分"
                    delay((at - System.currentTimeMillis() + 5_000L).coerceIn(60_000L, 6 * 3600_000L))
                    continue
                }
                if (left.isEmpty() || pass >= 3) {
                    status = if (left.isEmpty()) (if (localLeft() > 0) "完成（${localLeft()} 张为本地识别）" else "完成") else "完成，${left.size} 张暂时读不到"
                    note = ""; break
                }
                delay(600_000)
            }
        } finally {
            running = false
            runCatching { flush() }
        }
    }

    // ------------------------------------------------ NAS photos (optional)
    private fun nasFile() = File(App.ctx.filesDir, "ai-nas-${NasAccounts.currentId}.json")
    private var nasMeta: JSONObject? = null
    private var nasMetaId = ""
    fun nasMeta(): JSONObject {
        if (nasMetaId != NasAccounts.currentId) { nasMeta = null; nasMetaId = NasAccounts.currentId }
        return nasMeta ?: runCatching { JSONObject(nasFile().readText()) }.getOrElse { JSONObject() }.also { nasMeta = it }
    }

    fun startNas(c: Context, photos: List<Photo>, manual: Boolean = false) {
        if (!AiConfig.nasToo || !on || photos.isEmpty()) return
        if (manual) resume() else if (ScanPolicy.paused) return
        val key = "fn:" + NasAccounts.currentId
        if (job?.isActive == true && (jobKey.startsWith(key) || !manual)) return
        job?.cancel(); jobKey = key
        val meta = nasMeta(); val file = nasFile()
        val skip = HashSet<Int>()
        val snapshot = photos.toList()
        job = scope.launch {
            loop(c, manual,
                todo = {
                    val llm = AiConfig.configured && AiPool.usable()
                    synchronized(meta) { snapshot.filter { !it.isVideo && it.id !in skip && (!meta.has(it.id.toString()) || llm && meta.optJSONObject(it.id.toString())?.has("l") == true) } }
                        .sortedBy { synchronized(meta) { meta.has(it.id.toString()) } }.take(2000)
                },
                isLocal = { p -> synchronized(meta) { meta.optJSONObject(p.id.toString())?.has("l") == true } },
                localLeft = { synchronized(meta) { meta.keys().asSequence().count { meta.optJSONObject(it)?.has("l") == true } } },
                fetch = { p ->
                    val url = p.thumbM.takeIf { it.isNotEmpty() } ?: return@loop null
                    FnClient.http.newCall(Request.Builder().url(url).apply { NasX.streamHeaders(url).forEach { (k, v) -> header(k, v) }; header("accesstoken", FnClient.token) }.build())
                        .execute().use { if (it.isSuccessful) it.body?.bytes() else null }
                },
                skip = { p -> skip.add(p.id) },
                store = { p, l -> synchronized(meta) { meta.put(p.id.toString(), JSONObject().put("c", l.cat).put("t", JSONArray(l.tags)).put("p", l.caption).apply { if (l.local) put("l", 1) }) } },
                flush = { synchronized(meta) { runCatching { file.writeText(meta.toString()) } } })
        }
    }

    fun nasSearchIds(words: List<String>): Set<Int> {
        val m = nasMeta(); val out = HashSet<Int>()
        m.keys().forEach { k -> val o = m.getJSONObject(k); val hay = o.optString("c") + o.optString("p") + o.optJSONArray("t")?.toString().orEmpty()
            if (words.any { it.isNotBlank() && hay.contains(it, true) }) k.toIntOrNull()?.let { out.add(it) } }
        return out
    }

    fun stop() { job?.cancel(); running = false }
}

/** 1.0.1 #8: which photos were labelled on the phone (本地), per library — kept apart from Meta so the model can refine them later. */
class LocalMarks private constructor(private val file: File) {
    private val set: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>().apply {
        runCatching { JSONArray(file.readText()).let { a -> for (i in 0 until a.length()) add(a.optString(i)) } }
    })
    @Volatile private var dirty = false
    val size: Int get() = set.size
    fun has(k: String) = k in set
    fun add(k: String) { if (set.add(k)) dirty = true }
    fun remove(k: String) { if (set.remove(k)) dirty = true }
    fun save() { if (!dirty) return; dirty = false; runCatching { file.writeText(synchronized(set) { JSONArray(set.toList()).toString() }) } }
    companion object {
        private val cache = HashMap<String, LocalMarks>()
        fun of(id: String): LocalMarks = synchronized(cache) { cache.getOrPut(id) { LocalMarks(File(App.ctx.filesDir, "ai-local-$id.json")) } }
    }
}
