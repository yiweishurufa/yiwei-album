package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPricesTest {
    @Test fun `最长关键词优先`() {
        assertEquals(AiPrices.Price(1.08, 4.32), AiPrices.of("gpt-4o-mini"))
        assertEquals(AiPrices.Price(18.0, 72.0), AiPrices.of("gpt-4o-2024-08-06"))
        assertEquals(AiPrices.Price(1.08, 4.32), AiPrices.of("openai/GPT-4o-mini"))
    }

    @Test fun `免费模型为 0，未知模型为 null`() {
        assertEquals(0.0, AiPrices.of("meta-llama/llama-3.2-11b-vision-instruct:free")!!.inPerM, 0.0)
        assertEquals(0.0, AiPrices.of("whatever", free = true)!!.outPerM, 0.0)
        assertEquals(0.0, AiPrices.of("glm-4v-flash")!!.inPerM, 0.0)
        assertNull(AiPrices.of("my-local-llava"))
        assertNull(AiPrices.of(""))
    }

    @Test fun `费用计算`() {
        assertEquals(0.0, AiPrices.cost(null, 1000, 1000), 0.0)
        val p = assertNotNullAnd(AiPrices.of("qwen-vl-max"))
        assertEquals((1_000_000 * 3.0 + 500_000 * 9.0) / 1_000_000.0, AiPrices.cost(p, 1_000_000, 500_000), 1e-9)
        assertTrue(AiPrices.describe("my-local-llava").contains("无法估算"))
        assertTrue(AiPrices.describe("glm-4v-flash").contains("免费"))
    }

    private fun assertNotNullAnd(p: AiPrices.Price?): AiPrices.Price { assertNotNull(p); return p!! }
}
