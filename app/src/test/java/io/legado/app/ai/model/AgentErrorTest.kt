package io.legado.app.ai.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentErrorTest {
    @Test
    fun `timeout is retryable`() {
        assertTrue(AgentErrorCode.RETRYABLE_TIMEOUT.retryable)
    }

    @Test
    fun `auth denied is not retryable`() {
        assertFalse(AgentErrorCode.AUTH_FAILED.retryable)
    }

    @Test
    fun `budget exceeded is not retryable`() {
        assertFalse(AgentErrorCode.BUDGET_EXCEEDED.retryable)
    }
}