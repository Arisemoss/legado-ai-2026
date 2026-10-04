package io.legado.app.ai.util

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定本轮的「取消语义」修复。
 *
 * 回归点：工具层/运行时此前用 `runCatching {}` 与 `catch (e: Exception)`，
 * 二者都会吞掉 [CancellationException]（它继承自 Exception），造成
 * ① 取消被谎报为「失败」；② `withTimeoutOrNull` 的超时分支永不生效。
 * 因此必须断言：取消异常**原样抛出**，绝不收敛成 Result.failure。
 */
class CancellationSafeTest {

    @Test
    fun successWrapsValue() {
        val r = runCatchingCancellable { 42 }
        assertTrue(r.isSuccess)
        assertEquals(42, r.getOrNull())
    }

    @Test
    fun ordinaryExceptionBecomesFailure() {
        val r = runCatchingCancellable { throw IllegalStateException("boom") }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun cancellationIsRethrownNotSwallowed() {
        var rethrown = false
        try {
            runCatchingCancellable { throw CancellationException("cancelled") }
        } catch (e: CancellationException) {
            rethrown = true
        }
        assertTrue("取消必须向上抛出，不得被收敛成 Result.failure", rethrown)
    }

    @Test
    fun cancellationSubclassIsAlsoRethrown() {
        // TimeoutCancellationException 等子类同理：只有向上抛出，withTimeout 的超时分支才生效
        class MyTimeout : CancellationException("timeout")
        var rethrown = false
        try {
            runCatchingCancellable { throw MyTimeout() }
        } catch (e: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
    }
}
