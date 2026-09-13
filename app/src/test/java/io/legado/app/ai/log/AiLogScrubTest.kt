package io.legado.app.ai.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志兜底脱敏单测（审计 A-2）：脱敏必须是机制而非调用方约定。
 * 只调用纯字符串函数，不触发 android.util.Log。
 */
class AiLogScrubTest {

    @Test
    fun `sk key is masked`() {
        val out = AiLog.scrub("调用模型 sk-abcdefghijklmnop123456 完成")
        assertFalse(out.contains("sk-abcdefghijklmnop123456"))
        assertTrue(out.contains("***"))
    }

    @Test
    fun `bearer token is masked`() {
        val out = AiLog.scrub("Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6")
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6"))
    }

    @Test
    fun `api key assignment keeps prefix only`() {
        val out = AiLog.scrub("baseUrl=x api_key=sk-1234567890abcdef")
        assertFalse(out.contains("sk-1234567890abcdef"))
        assertTrue(out.contains("api_key="))
    }

    @Test
    fun `plain conversation text untouched`() {
        val text = "搜索《诡秘之主》并加入书架"
        assertEquals(text, AiLog.scrub(text))
    }

    @Test
    fun `mask hides middle of secret`() {
        val masked = AiLog.mask("sk-1234567890abcdef")
        assertFalse(masked.contains("567890abc"))
        assertTrue(masked.startsWith("sk-1"))
        assertTrue(masked.endsWith("cdef"))
    }
}
