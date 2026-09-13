package io.legado.app.ai.model

/**
 * 工具结果附带的可执行「建议动作」：由 [io.legado.app.ai.tool.SuggestionEngine] 从
 * 「工具 id + 参数 + 结果 JSON」派生，渲染为聊天中工具卡片下方的快捷按钮。
 *
 * 安全约定（重要）：kind 里不存在任何「直接写库」的动作。所有写操作统一走
 * [KIND_PROMPT]——把一句话交回 Agent，由 `manualConfirm=true` 的工具弹出聊天内确认卡，
 * 保证「凡写库/改设置/删数据必先经用户确认」这条红线不被快捷按钮绕过。
 *
 * @param label   按钮文字（建议 ≤10 字，过长会被 UI 截断）
 * @param kind    动作类型，见下方常量
 * @param payload 动作参数：prompt=发给 Agent 的话术；reader=bookUrl；settings=ConfigTag；search=关键词
 * @param extra   附加参数：reader=书名（用于提示）
 */
data class SuggestedAction(
    val label: String,
    val kind: String,
    val payload: String = "",
    val extra: String = ""
) {
    companion object {
        /** 把 payload 作为用户消息发给 Agent（可能触发写操作，仍需确认卡） */
        const val KIND_PROMPT = "prompt"
        /** 打开阅读页：payload=bookUrl, extra=书名 */
        const val KIND_READER = "reader"
        /** 切到书架页 */
        const val KIND_SHELF = "shelf"
        /** 打开书源管理页 */
        const val KIND_SOURCES = "sources"
        /** 打开书源健康检测/清理页 */
        const val KIND_HEALTH = "health"
        /** 打开替换净化规则页 */
        const val KIND_REPLACE = "replace"
        /** 打开设置页：payload=ConfigTag */
        const val KIND_SETTINGS = "settings"
        /** 打开全局搜索：payload=关键词 */
        const val KIND_SEARCH = "search"
    }
}
