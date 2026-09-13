package io.legado.app.ai.tool.impl

import io.legado.app.ai.model.AiProviderPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具参数解析与预设反查的回归测试（本次自查修复项）。
 * 全部为纯逻辑，无 Android 依赖，可在 JVM 单测运行。
 */
class ToolArgParsingTest {

    @Test
    fun `boolArg handles numbers without flipping direction`() {
        // 数字型布尔：0 必须是 false（此前落到默认值 true，导致"要禁用却启用"）
        assertFalse(boolArg(0, def = true))
        assertFalse(boolArg(0.0, def = true))
        assertTrue(boolArg(1, def = false))
        assertTrue(boolArg(1.0, def = false))
    }

    @Test
    fun `boolArg handles strings and booleans`() {
        assertTrue(boolArg(true, def = false))
        assertFalse(boolArg(false, def = true))
        assertTrue(boolArg("TRUE", def = false))
        assertTrue(boolArg("1", def = false))
        assertTrue(boolArg("yes", def = false))
        assertFalse(boolArg("0", def = true))
        assertFalse(boolArg("false", def = true))
        assertFalse(boolArg("", def = true))
    }

    @Test
    fun `boolArg falls back only for unknown types`() {
        assertEquals(true, boolArg(null, def = true))
        assertEquals(false, boolArg(null, def = false))
    }

    @Test
    fun `provider preset lookup tolerates trailing slash and case`() {
        val preset = AiProviderPresets.byId("deepseek")!!
        assertEquals(preset, AiProviderPresets.byBaseUrl("https://api.deepseek.com/v1"))
        assertEquals(preset, AiProviderPresets.byBaseUrl(" https://api.deepseek.com/v1/ "))
        assertEquals(preset, AiProviderPresets.byBaseUrl("HTTPS://API.DEEPSEEK.COM/V1/"))
        assertNull(AiProviderPresets.byBaseUrl("https://example.com/v1"))
        assertNull(AiProviderPresets.byBaseUrl(null))
    }
}
