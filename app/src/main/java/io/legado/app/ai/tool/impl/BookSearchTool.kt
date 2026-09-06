package io.legado.app.ai.tool.impl

import com.google.gson.Gson
import io.legado.app.ai.bridge.BookFetcher
import io.legado.app.ai.model.ToolDefinition
import io.legado.app.ai.model.ToolDefinitionInfo
import io.legado.app.ai.model.ToolParam
import io.legado.app.ai.model.ToolResult
import io.legado.app.ai.tool.ToolContext

/** 选书：跨书源搜索 */
class SearchBooksTool(private val fetcher: BookFetcher) : ToolDefinition {
    override val id = "search_books"
    override val info = ToolDefinitionInfo(
        name = "search_books",
        description = "跨最多6个已启用书源（随机抽取）并行搜索书籍，返回书名/作者/来源",
        parameters = listOf(
            ToolParam("keyword", "string", "书名或作者关键词", required = true),
            ToolParam("limit", "integer", "返回条数，默认5", required = false)
        )
    )
    override val category = "选书"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val kw = args["keyword"]?.toString() ?: return ToolResult(text = """{"error":"缺少关键词"}""")
        // 模型可能把数字传成字符串，统一兜底解析并限制范围
        val limit = (args["limit"] as? Double)?.toInt()
            ?: args["limit"]?.toString()?.trim()?.toDoubleOrNull()?.toInt()
            ?: 5
        return runCatching {
            val books = fetcher.search(kw, limit.coerceIn(1, 20))
            if (books.isEmpty()) {
                ToolResult(text = """{"message":"未找到相关书籍"}""")
            } else {
                ToolResult(text = Gson().toJson(mapOf("books" to books)))
            }
        }.getOrElse { ToolResult(text = """{"error":${Gson().toJson(it.localizedMessage)}}""") }
    }
}

/** 选书：按书名推荐 */
class RecommendBooksTool(private val fetcher: BookFetcher) : ToolDefinition {
    override val id = "recommend_books"
    override val info = ToolDefinitionInfo(
        name = "recommend_books",
        description = "根据书名推荐候选书籍",
        parameters = listOf(
            ToolParam("name", "string", "书名关键词", required = true)
        )
    )
    override val category = "选书"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val name = args["name"]?.toString() ?: return ToolResult(text = """{"error":"缺少书名"}""")
        return runCatching {
            val books = fetcher.recommendByName(name)
            ToolResult(text = Gson().toJson(mapOf("books" to books)))
        }.getOrElse { ToolResult(text = """{"error":${Gson().toJson(it.localizedMessage)}}""") }
    }
}