package io.legado.app.ai.util

import kotlinx.coroutines.CancellationException

/**
 * 与 [kotlin.runCatching] 同形，但**不会吞掉协程取消**。
 *
 * [CancellationException] 原样向上抛出，其余异常收敛为 [Result.failure]。
 *
 * 背景（本轮修复的系统性问题）：
 * `runCatching {}` 与 `catch (e: Exception)` 都会捕获 [CancellationException]
 * （它继承自 `IllegalStateException` → `Exception`），于是：
 * 1. scope/用户取消被谎报成「失败」，任务取消后仍继续执行后续写库/通知；
 * 2. `withTimeoutOrNull` / `withTimeout` 的超时分支永不生效——
 *    超时抛出的 [kotlinx.coroutines.TimeoutCancellationException] 会先被内层
 *    `runCatching`/`catch (Exception)` 捕获，导致「超时」被误报为「请求失败」。
 *
 * 约定：凡是包裹 **suspend 调用**的地方，一律用本函数替代 `runCatching`。
 * （纯字符串/JSON 解析等非 suspend 分支可继续用 `runCatching`。）
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
