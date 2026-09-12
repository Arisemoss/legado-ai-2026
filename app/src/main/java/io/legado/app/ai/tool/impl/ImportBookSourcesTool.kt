package io.legado.app.ai.tool.impl

import com.google.gson.Gson
import io.legado.app.ai.model.ToolDefinition
import io.legado.app.ai.model.ToolDefinitionInfo
import io.legado.app.ai.model.ToolParam
import io.legado.app.ai.model.ToolResult
import io.legado.app.ai.model.ToolResultState
import io.legado.app.ai.source.BookSourceHub
import io.legado.app.ai.tool.ToolContext

/**
 * 书源：一键导入聚合页书源（写操作，两阶段确认）。
 * 默认聚合页：喵公子 yuedu.miaogongzi.net/gx.html；仅导入书源规则，不涉及任何版权内容。
 */
class ImportBookSourcesTool : ToolDefinition {
    override val id = "import_book_sources"
    override val info = ToolDefinitionInfo(
        name = "import_book_sources",
        description = "从书源聚合页一键导入书源（默认喵公子 gx.html）；写操作需用户确认",
        parameters = listOf(
            ToolParam("pageUrl", "string", "聚合页地址（可选，默认喵公子）", required = false)
        )
    )
    override val category = "书源"
    override val enabled = true
    override val manualConfirm = true

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val pageUrl = args["pageUrl"]?.toString()?.takeIf { it.isNotBlank() }
            ?: BookSourceHub.DEFAULT_PAGE
        val entries = BookSourceHub.fetchEntries(pageUrl).getOrNull()
        if (entries.isNullOrEmpty()) {
            return ToolResult(
                text = Gson().toJson(mapOf("status" to "error", "message" to "未在页面中发现可导入书源"))
            )
        }
        return ToolResult(
            text = Gson().toJson(
                mapOf(
                    "status" to "pending_confirm",
                    "proposal" to mapOf(
                        "pageUrl" to pageUrl,
                        "count" to entries.size,
                        "samples" to entries.take(5).map { it.title }
                    )
                )
            ),
            state = ToolResultState.PENDING_CONFIRM
        )
    }

    override suspend fun onApproved(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val pageUrl = args["pageUrl"]?.toString()?.takeIf { it.isNotBlank() }
            ?: BookSourceHub.DEFAULT_PAGE
        val entries = BookSourceHub.fetchEntries(pageUrl)
            .getOrElse { return ToolResult(text = Gson().toJson(mapOf("status" to "error", "message" to (it.localizedMessage ?: "fetch failed")))) }
        val r = BookSourceHub.importAll(entries)
        return ToolResult(
            text = Gson().toJson(
                mapOf(
                    "status" to "ok",
                    "total" to r.total,
                    "inserted" to r.inserted,
                    "failed" to r.failed
                )
            )
        )
    }
}
