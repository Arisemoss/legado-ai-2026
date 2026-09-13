package io.legado.app.ai.tool

import io.legado.app.ai.model.ToolEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 上下文中可注入到工具的领域信息（来自阅读/搜索/书源页面的 preset）。
 * 后续阶段会注入 bridge 只读服务（BookFetcher/ChapterReader/BookSourceAnalyzer）。
 */
data class AiPreset(
    val bookName: String? = null,
    val chapterTitle: String? = null,
    val content: String? = null,        // 章节正文片段（如注入）
    val sourceUrl: String? = null,
    val searchKeyword: String? = null
)

/**
 * 写操作二次确认请求。
 */
data class ConfirmRequest(val confirmToken: String, val proposal: Map<String, Any>)

class ToolContext(
    var sessionId: Long,
    preset: AiPreset = AiPreset(),
    /**
     * 写操作确认请求。审计 A-4：由 StateFlow 单槽改为 SharedFlow 队列语义
     * （replay=0 + 缓冲 8），保证并发/连续到达的确认请求不会互相覆盖丢失。
     */
    val onConfirmRequested: MutableSharedFlow<ConfirmRequest> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    ),
    /**
     * 工具事件。原为 StateFlow 单槽：整批并行执行时事件互相覆盖，
     * 真机表现为「4 个工具只出现 1 张卡、部分卡片停在执行中」——改为队列语义（大缓冲）。
     */
    val onToolEvent: MutableSharedFlow<ToolEvent> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    ),
    /**
     * 导航请求。原为 StateFlow 单槽：同一轮并行调用多个导航工具（open_book + open_search 等）
     * 会互相覆盖，且每个工具都回报成功 → 模型宣称「都打开了」。改为队列语义。
     */
    val onNavigate: MutableSharedFlow<io.legado.app.ai.bridge.AppNav> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    ),
    /** 流式输出的累积增量文本；null 表示当前没有进行中的流式回答 */
    val onPartialText: MutableStateFlow<String?> = MutableStateFlow(null)
) {
    /** 上下文预设（当前书/章节等）；后台任务中心按任务注入，故为可变 */
    var preset: AiPreset = preset
    val stopRequested = MutableStateFlow(false)

    /** 是否允许写操作二次确认；置 false 进入「无确认」上下文，写类工具会被前置拒绝（Harness 审批分级） */
    var allowConfirm: Boolean = true

    /**
     * 最近一次「待确认」请求（sticky）。
     * onConfirmRequested 是 SharedFlow(replay=0)：**没有订阅者时会直接丢弃发射**，
     * 而后台任务期间用户离开页面是常态（确认要等最多 5 分钟），
     * 于是重新进入页面时确认卡会丢、任务被当成拒绝。此槽位用于补卡，决策后清空。
     */
    @Volatile
    var pendingConfirm: ConfirmRequest? = null
}