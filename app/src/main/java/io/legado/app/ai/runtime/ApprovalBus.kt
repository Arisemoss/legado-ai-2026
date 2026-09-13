package io.legado.app.ai.runtime

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * 写操作确认总线。与具体 [AgentRuntime] 实例解耦：
 * 配置热更新会重建 runtime，未决确认经此总线仍可送达当前等待者，不随旧实例失效。
 *
 * 实现：token→决策 的并发映射 + 100ms 轮询（审计 A-4：原先单槽会被后到的决策覆盖，
 * 一旦出现多个待确认调用就会错配/丢确认，因此改为多槽）。
 */
object ApprovalBus {

    /** 审计 A-4：单槽 → 多槽，消除"新决策覆盖旧决策"的错配风险 */
    private val decisions = ConcurrentHashMap<String, Boolean>()

    /** UI 决策入口：[token] 对应某次 pending_confirm 的 call id */
    fun offer(token: String, approved: Boolean) {
        decisions[token] = approved
    }

    /**
     * 等待匹配 [token] 的决策；超时或 [isStopped] 置位返回 null（调用方按拒绝处理）。
     * 非匹配的过期决策直接忽略，不消耗等待窗口。
     */
    suspend fun await(
        token: String,
        timeoutMs: Long,
        isStopped: () -> Boolean = { false }
    ): Pair<String, Boolean>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isStopped()) return null
            decisions.remove(token)?.let { approved -> // token 一次性消费，且只取自己的槽
                return token to approved
            }
            delay(100)
        }
        decisions.remove(token) // 超时清理，避免映射无限增长
        return null
    }
}
