package io.legado.app.ai.tool

import io.legado.app.ai.model.ToolEvent
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
    val onConfirmRequested: MutableStateFlow<ConfirmRequest?> = MutableStateFlow(null),
    val onNavigate: MutableStateFlow<io.legado.app.ai.bridge.AppNav?> = MutableStateFlow(null),
    val onToolEvent: MutableStateFlow<ToolEvent?> = MutableStateFlow(null),
    /** 流式输出的累积增量文本；null 表示当前没有进行中的流式回答 */
    val onPartialText: MutableStateFlow<String?> = MutableStateFlow(null)
) {
    /** 上下文预设（当前书/章节等）；后台任务中心按任务注入，故为可变 */
    var preset: AiPreset = preset
    val stopRequested = MutableStateFlow(false)

    /** 是否允许写操作二次确认；置 false 进入「无确认」上下文，写类工具会被前置拒绝（Harness 审批分级） */
    var allowConfirm: Boolean = true
}