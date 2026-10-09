package com.hark.shiguang


/**
 * 1.0.1 #7/#4: one concrete model endpoint (provider + base URL + key + model). The configured one is
 * [current]; the free-model failover pool builds more of them from the keys the user saved per provider.
 */
data class AiEndpoint(val provider: String, val baseUrl: String, val key: String, val model: String) {
    val protocol: String get() = AiProviders.of(provider).protocol
    val isOpenRouter: Boolean get() = baseUrl.contains("openrouter.ai", true)
    val isZhipu: Boolean get() = baseUrl.contains("bigmodel.cn", true)
    val isSilicon: Boolean get() = baseUrl.contains("siliconflow", true)
    val isModelScope: Boolean get() = baseUrl.contains("modelscope", true)
    /** Stable id for cooldowns: provider + model. */
    val id: String get() = "$provider|$model"
    /** Short name for the AI card: "glm-4.6v-flash", "Qwen3.5-4B". */
    val label: String get() = model.substringAfterLast('/').removeSuffix(":free")
    val providerName: String get() = AiProviders.of(provider).short
    val free: Boolean get() = AiProviders.isFree(provider, model)

    companion object {
        fun current() = AiEndpoint(AiConfig.provider, AiConfig.baseUrl.trim().trimEnd('/'), AiConfig.key.trim(), AiConfig.model.trim())
    }
}

/** API keys per provider, so a user can hold keys for 智谱 + 硅基流动 + 魔搭 (+ OpenRouter) at the same time. */
object AiKeys {
    fun get(id: String): String {
        val k = Store.getStr("ai.key.$id")
        if (k.isNotEmpty()) return k
        // 1.0.0 and older kept one key only (for the selected provider)
        return if (id == Store.getStr("ai.provider")) Store.getStr("ai.key") else ""
    }
    fun set(id: String, key: String) = Store.putStr("ai.key.$id", key.trim())
    fun has(id: String) = get(id).isNotBlank()
}

