package io.legado.app.ai.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 HTTP 状态码 → 错误码分类。
 *
 * 回归点：修复前 OpenAIClient 把「非 2xx 一律映射 AUTH_FAILED（不可重试）」，
 * 导致 429 限流与 5xx 服务端错误被谎报为「鉴权失败」，且指数退避重试形同虚设。
 */
class HttpErrorClassifyTest {

    @Test
    fun unauthorizedAndForbiddenAreAuthFailed() {
        assertEquals(AgentErrorCode.AUTH_FAILED, httpStatusToErrorCode(401))
        assertEquals(AgentErrorCode.AUTH_FAILED, httpStatusToErrorCode(403))
        assertFalse("鉴权失败不应自动重试", AgentErrorCode.AUTH_FAILED.retryable)
    }

    @Test
    fun rateLimitIsRetryable() {
        assertEquals(AgentErrorCode.RATE_LIMITED, httpStatusToErrorCode(429))
        assertTrue("限流应可重试", AgentErrorCode.RATE_LIMITED.retryable)
    }

    @Test
    fun serverErrorsAreRetryable() {
        assertEquals(AgentErrorCode.NETWORK_UNAVAILABLE, httpStatusToErrorCode(500))
        assertEquals(AgentErrorCode.NETWORK_UNAVAILABLE, httpStatusToErrorCode(502))
        assertEquals(AgentErrorCode.NETWORK_UNAVAILABLE, httpStatusToErrorCode(503))
        assertTrue(AgentErrorCode.NETWORK_UNAVAILABLE.retryable)
    }

    @Test
    fun other4xxAreDeterministicFailures() {
        assertEquals(AgentErrorCode.TOOL_FAILED, httpStatusToErrorCode(400))
        assertEquals(AgentErrorCode.TOOL_FAILED, httpStatusToErrorCode(404))
        assertFalse(AgentErrorCode.TOOL_FAILED.retryable)
    }
}
