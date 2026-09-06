package io.legado.app.ai.runtime

import kotlinx.coroutines.delay

/**
 * 写操作确认总线。与具体 [AgentRuntime] 实例解耦：
 * 配置热更新会重建 runtime，未决确认经此总线仍可送达当前等待者，不随旧实例失效。
 *
 * 实现：单槽 + 100ms 轮询。coroutines 1.3.x 无 SharedFlow/BroadcastChannel 订阅语义，
 * 而本项目为单任务槽设计（同一时刻至多一个 pending_confirm），单槽轮询足够且最稳。
 */
object ApprovalBus {

    @Volatile
    private var decision: Pair<String, Boolean>? = null

    /** UI 决策入口：[token] 对应某次 pending_confirm 的 call id */
    fun offer(token: String, approved: Boolean) {
        decision = token to approved
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
            decision?.let { d ->
                if (d.first == token) {
                    decision = null // token 一次性消费
                    return d
                }
            }
            delay(100)
        }
        return null
    }
}
