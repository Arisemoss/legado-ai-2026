package io.legado.app.ai.tool.impl

import com.google.gson.Gson
import io.legado.app.ai.bridge.AiBridge
import io.legado.app.ai.bridge.AppNav
import io.legado.app.ai.model.ToolDefinition
import io.legado.app.ai.model.ToolDefinitionInfo
import io.legado.app.ai.model.ToolParam
import io.legado.app.ai.model.ToolResult
import io.legado.app.ai.model.ToolResultState
import io.legado.app.ai.tool.ToolContext

/** 书架：查看书架书籍，可按关键词过滤 */
class ListShelfTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "list_shelf"
    override val info = ToolDefinitionInfo(
        name = "list_shelf",
        description = "列出书架中的书籍；可按书名/作者关键词过滤",
        parameters = listOf(ToolParam("keyword", "string", "书名关键词，缺省列出全部", required = false))
    )
    override val category = "书架"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val shelf = bridge.appController.listShelf(args["keyword"]?.toString())
        if (shelf.isEmpty()) return ToolResult(text = """{"error":"书架为空"}""")
        return ToolResult(text = Gson().toJson(mapOf("books" to shelf)))
    }
}

/** 书架：打开一本书，进入阅读界面（发出导航请求，由宿主消费） */
class OpenBookTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "open_book"
    override val info = ToolDefinitionInfo(
        name = "open_book",
        description = "打开一本书进入阅读界面；未在书架则提示无法打开",
        parameters = listOf(ToolParam("bookName", "string", "书名，缺省用当前阅读上下文", required = false))
    )
    override val category = "书架"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val name = args["bookName"]?.toString() ?: ctx.preset.bookName
            ?: return ToolResult(text = """{"error":"缺少书名"}""")
        val hit = bridge.appController.locateBook(name)
        val bookUrl = hit["bookUrl"]?.toString()
        if (bookUrl.isNullOrBlank()) {
            return ToolResult(text = """{"error":"《$name》未加入书架，无法打开"}""")
        }
        ctx.onNavigate.value = AppNav.OpenBook(bookUrl, name)
        return ToolResult(
            text = Gson().toJson(mapOf("opened" to true, "book" to name, "toReader" to true))
        )
    }
}

/** 书架：移出书架（写操作，两阶段确认）：execute 只产提案，onApproved 才真正删库 */
class RemoveBookTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "remove_book"
    override val info = ToolDefinitionInfo(
        name = "remove_book",
        description = "把指定书籍移出书架（不删除本地文件，仅移出书架）",
        parameters = listOf(ToolParam("bookName", "string", "书名", required = true))
    )
    override val category = "书架"
    override val enabled = true
    override val manualConfirm = true

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val name = args["bookName"]?.toString() ?: return ToolResult(text = """{"error":"缺少书名"}""")
        return ToolResult(
            text = Gson().toJson(
                mapOf("status" to "pending_confirm", "proposal" to mapOf("action" to "remove_book", "bookName" to name))
            ),
            state = ToolResultState.PENDING_CONFIRM
        )
    }

    override suspend fun onApproved(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val name = args["bookName"]?.toString() ?: return ToolResult(text = """{"error":"缺少书名"}""")
        val r = bridge.appController.removeFromShelf(name)
        return ToolResult(text = Gson().toJson(r))
    }
}