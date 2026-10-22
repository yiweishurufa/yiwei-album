package com.hark.shiguang

/**
 * 1.1.0：内置常见大模型单价（人民币 / 百万 token，输入 + 输出），用于「本月已用约 ¥x」和每月预算。
 * 取代原来让用户手填的输入/输出单价。价格是各平台公开价的近似值（美元按 7.2 换算），只用于估算预算，
 * 以平台账单为准；平台调价后改这张表即可。
 *
 * 匹配：模型名转小写后按「最长关键词优先」包含匹配；以 ":free" 结尾或在免费列表里的模型 = 0。
 * 不认识的模型返回 null —— 按 0 计费，界面标注「无法估算」。
 */
object AiPrices {
    data class Price(val inPerM: Double, val outPerM: Double)

    /** keyword (lowercase, matched with contains) → price. Longer keywords win, so "gpt-4o-mini" beats "gpt-4o". */
    private val TABLE: Map<String, Price> = mapOf(
        // 智谱
        "glm-4v-flash" to Price(0.0, 0.0), "glm-4-flash" to Price(0.0, 0.0), "glm-4.1v-thinking-flash" to Price(0.0, 0.0),
        "glm-4v-plus" to Price(4.0, 4.0), "glm-4v" to Price(50.0, 50.0), "glm-4.5v" to Price(2.0, 6.0),
        // 阿里 通义千问
        "qwen-vl-max" to Price(3.0, 9.0), "qwen-vl-plus" to Price(1.5, 4.5), "qwen-vl-ocr" to Price(5.0, 5.0),
        "qwen2.5-vl-72b" to Price(16.0, 48.0), "qwen2.5-vl-32b" to Price(8.0, 24.0), "qwen2.5-vl-7b" to Price(2.0, 5.0),
        // 字节 豆包
        "doubao-1.5-vision-pro" to Price(3.0, 9.0), "doubao-1.5-vision-lite" to Price(1.5, 4.5), "doubao-vision-pro" to Price(3.0, 9.0),
        "doubao-vision-lite" to Price(1.5, 4.5), "doubao-seed-1.6" to Price(0.8, 8.0),
        // 其他国内
        "moonshot-v1-8k-vision" to Price(12.0, 12.0), "moonshot-v1-32k-vision" to Price(24.0, 24.0), "step-1v" to Price(5.0, 20.0),
        "hunyuan-vision" to Price(18.0, 18.0), "deepseek-chat" to Price(2.0, 8.0), "deepseek-v3" to Price(2.0, 8.0),
        "yi-vision" to Price(6.0, 6.0), "ernie-4.5-turbo-vl" to Price(3.0, 9.0),
        // 海外（美元价 × 7.2）
        "gpt-4o-mini" to Price(1.08, 4.32), "gpt-4o" to Price(18.0, 72.0), "gpt-4.1-nano" to Price(0.72, 2.88),
        "gpt-4.1-mini" to Price(2.88, 11.52), "gpt-4.1" to Price(14.4, 57.6), "gpt-5-mini" to Price(1.8, 14.4), "gpt-5-nano" to Price(0.36, 2.88),
        "claude-3-5-haiku" to Price(5.76, 28.8), "claude-3-haiku" to Price(1.8, 9.0), "claude-haiku-4" to Price(7.2, 36.0),
        "claude-3-5-sonnet" to Price(21.6, 108.0), "claude-3-7-sonnet" to Price(21.6, 108.0), "claude-sonnet-4" to Price(21.6, 108.0),
        "gemini-1.5-flash" to Price(0.54, 2.16), "gemini-2.0-flash-lite" to Price(0.54, 2.16), "gemini-2.0-flash" to Price(0.72, 2.88),
        "gemini-2.5-flash-lite" to Price(0.72, 2.88), "gemini-2.5-flash" to Price(2.16, 18.0), "gemini-2.5-pro" to Price(9.0, 72.0),
    )
    private val KEYS = TABLE.keys.sortedByDescending { it.length }

    /** Built-in price of [model]; 0 for free models; null = unknown (counted as 0, shown as 「无法估算」). */
    fun of(model: String, free: Boolean = false): Price? {
        val m = model.trim().lowercase()
        if (free || m.endsWith(":free")) return Price(0.0, 0.0)
        if (m.isEmpty()) return null
        val name = m.substringAfterLast('/')   // OpenRouter ids look like "openai/gpt-4o-mini"
        return KEYS.firstOrNull { name.contains(it) }?.let { TABLE[it] }
    }

    /** Cost in ¥ of one request. Unknown model → 0. */
    fun cost(price: Price?, inTok: Int, outTok: Int): Double =
        if (price == null) 0.0 else (inTok * price.inPerM + outTok * price.outPerM) / 1_000_000.0

    /** One line for the settings page. */
    fun describe(model: String, free: Boolean = false): String = when (val p = of(model, free)) {
        null -> "这个模型不在内置价目表里，费用无法估算（按 0 计）"
        else -> if (p.inPerM == 0.0 && p.outPerM == 0.0) "免费模型，不计费" else "约 ¥${fmt(p.inPerM)} / ¥${fmt(p.outPerM)} 每百万 token（输入 / 输出，估算）"
    }
    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toInt().toString() else "%.2f".format(java.util.Locale.US, d).trimEnd('0').trimEnd('.')
}
