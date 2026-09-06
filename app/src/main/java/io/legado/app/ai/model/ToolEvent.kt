package io.legado.app.ai.model

/**
 * 工具执行事件：由 [io.legado.app.ai.runtime.AgentRuntime] 在工具流水线关键节点发布，
 * UI 据此渲染实时「工具卡片」（运行中 → 结果 / 待确认 / 已批准 / 已拒绝 / 出错）。
 *
 * @param seq      单调递增序号，保证 StateFlow 每次发布都是新值（不被 conflation 吞掉）
 * @param callId   对应 OpenAI tool_call id，同一调用的多个事件在 UI 上合并为一张卡片
 * @param toolName 工具名
 * @param phase    running | result | confirm | approved | denied | error
 * @param argsPreview 参数摘要（截断后的 JSON 文本）
 * @param detail   结果/错误详情预览
 * @param elapsedMs 该次调用耗时（running 阶段为 0）
 */
data class ToolEvent(
    val seq: Long,
    val callId: String,
    val toolName: String,
    val phase: String,
    val argsPreview: String = "",
    val detail: String? = null,
    val elapsedMs: Long = 0L
) {
    companion object {
        const val PHASE_RUNNING = "running"
        const val PHASE_RESULT = "result"
        const val PHASE_CONFIRM = "confirm"
        const val PHASE_APPROVED = "approved"
        const val PHASE_DENIED = "denied"
        const val PHASE_ERROR = "error"
    }
}
