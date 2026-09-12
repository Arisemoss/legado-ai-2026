package io.legado.app.ai.tool.impl

import com.google.gson.Gson
import io.legado.app.ai.bridge.AiBridge
import io.legado.app.ai.model.ToolDefinition
import io.legado.app.ai.model.ToolDefinitionInfo
import io.legado.app.ai.model.ToolParam
import io.legado.app.ai.model.ToolResult
import io.legado.app.ai.model.ToolResultState
import io.legado.app.ai.tool.ToolContext

/**
 * 书架：把一本书加入书架（写操作，两阶段确认）。
 * 典型用法：先 search_books 搜到目标书，再询问用户是否加入书架，用户同意后调用本工具。
 */
class AddBookToShelfTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "add_book_to_shelf"
    override val info = ToolDefinitionInfo(
        name = "add_book_to_shelf",
        description = "把一本书加入书架（写操作，需用户确认）；通常先 search_books 再询问用户是否加入",
        parameters = listOf(
            ToolParam("bookUrl", "string", "书籍详情页 URL（search_books 返回）", required = true),
            ToolParam("name", "string", "书名", required = true),
            ToolParam("author", "string", "作者", required = false),
            ToolParam("origin", "string", "书源 URL", required = false),
            ToolParam("originName", "string", "书源名称", required = false),
            ToolParam("tocUrl", "string", "目录 URL（可选）", required = false),
            ToolParam("coverUrl", "string", "封面 URL（可选）", required = false),
            ToolParam("intro", "string", "简介（可选）", required = false)
        )
    )
    override val category = "书架"
    override val enabled = true
    override val manualConfirm = true

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val bookUrl = args["bookUrl"]?.toString()
            ?: return ToolResult(text = Gson().toJson(mapOf("error" to "缺少 bookUrl")))
        val name = args["name"]?.toString().orEmpty()
        return ToolResult(
            text = Gson().toJson(
                mapOf(
                    "status" to "pending_confirm",
                    "proposal" to mapOf(
                        "bookUrl" to bookUrl,
                        "name" to name,
                        "author" to args["author"]?.toString().orEmpty(),
                        "originName" to args["originName"]?.toString().orEmpty()
                    )
                )
            ),
            state = ToolResultState.PENDING_CONFIRM
        )
    }

    override suspend fun onApproved(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val result = bridge.appController.addToShelf(args)
        return ToolResult(text = Gson().toJson(result))
    }
}
