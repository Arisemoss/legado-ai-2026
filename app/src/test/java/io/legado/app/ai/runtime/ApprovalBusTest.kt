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

    @Test
    fun `multiple tokens do not overwrite each other`() = runBlocking {
        // 审计 A-4：单槽实现下后到的决策会覆盖先到的；多槽后两个并发等待都应拿到各自结果
        val w1 = async { ApprovalBus.await("multi-1", 2000) }
        val w2 = async { ApprovalBus.await("multi-2", 2000) }
        delay(150)
        ApprovalBus.offer("multi-2", true)
        ApprovalBus.offer("multi-1", false)
        assertEquals(true, w2.await()?.second)
        assertEquals(false, w1.await()?.second)
    }
}
