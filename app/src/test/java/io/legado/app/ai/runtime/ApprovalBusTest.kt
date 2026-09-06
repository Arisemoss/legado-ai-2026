package io.legado.app.ai.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalBusTest {

    @Test
    fun `offer then await returns decision and consumes token`() = runBlocking {
        ApprovalBus.offer("t1", true)
        val d = ApprovalBus.await("t1", 1000)
        assertEquals("t1", d?.first)
        assertEquals(true, d?.second)
        // token 一次性：消费后再次等待同 token 应超时
        assertNull(ApprovalBus.await("t1", 150))
    }

    @Test
    fun `await ignores stale tokens until match arrives`() = runBlocking {
        ApprovalBus.offer("stale", false)
        val waiter = async { ApprovalBus.await("t2", 2000) }
        delay(200)
        ApprovalBus.offer("t2", true)
        assertEquals(true, waiter.await()?.second)
    }

    @Test
    fun `await times out to null`() = runBlocking {
        assertNull(ApprovalBus.await("none", 200))
    }

    @Test
    fun `isStopped short circuits`() = runBlocking {
        assertNull(ApprovalBus.await("x", 10_000) { true })
    }
}
