package io.legado.app.ai.tool.impl

import com.google.gson.Gson
import io.legado.app.ai.bridge.BookSourceAnalyzer
import io.legado.app.ai.model.ToolDefinition
import io.legado.app.ai.model.ToolDefinitionInfo
import io.legado.app.ai.model.ToolParam
import io.legado.app.ai.model.ToolResult
import io.legado.app.ai.tool.ToolContext

/** 书源：分析 */
class AnalyzeBookSourceTool(private val analyzer: BookSourceAnalyzer) : ToolDefinition {
    override val id = "analyze_book_source"
    override val info = ToolDefinitionInfo(
        name = "analyze_book_source",
        description = "分析书源配置，检测规则完整性等问题",
        parameters = listOf(
            ToolParam("sourceUrl", "string", "书源 URL", required = true)
        )
    )
    override val category = "书源"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val url = args["sourceUrl"]?.toString()
            ?: return ToolResult(text = """{"error":"缺少书源URL"}""")
        return runCatching {
            ToolResult(text = Gson().toJson(analyzer.rules(url)))
        }.getOrElse { ToolResult(text = """{"error":${Gson().toJson(it.localizedMessage)}}""") }
    }
}

/** 书源：规则详情 */
class GetSourceRulesTool(private val analyzer: BookSourceAnalyzer) : ToolDefinition {
    override val id = "get_source_rules"
    override val info = ToolDefinitionInfo(
        name = "get_source_rules",
        description = "获取指定书源的完整规则配置详情（只读，不修改数据）",
        parameters = listOf(
            ToolParam("sourceUrl", "string", "书源 URL", required = true)
        )
    )
    override val category = "书源"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val url = args["sourceUrl"]?.toString()
            ?: return ToolResult(text = """{"error":"缺少书源URL"}""")
        return runCatching {
            ToolResult(text = Gson().toJson(analyzer.rules(url)))
        }.getOrElse { ToolResult(text = """{"error":${Gson().toJson(it.localizedMessage)}}""") }
    }
}

/** 书源：列表 */
class ListBookSourcesTool(private val analyzer: BookSourceAnalyzer) : ToolDefinition {
    override val id = "list_book_sources"
    override val info = ToolDefinitionInfo(
        name = "list_book_sources",
        description = "获取当前所有书源的列表（名称/URL/启用状态）",
        parameters = emptyList()
    )
    override val category = "书源"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult =
        runCatching {
            val all = analyzer.list()
            // 书源可能数百个：截断返回，避免工具结果撑爆模型上下文
            val shown = all.take(50)
            ToolResult(
                text = Gson().toJson(
                    mapOf(
                        "total" to all.size,
                        "shown" to shown.size,
                        "sources" to shown,
                        "note" to if (all.size > shown.size) "仅展示前 ${shown.size} 个，可让用户在书源管理页查看全部" else ""
                    )
                )
            )
        }.getOrElse { ToolResult(text = """{"error":${Gson().toJson(it.localizedMessage)}}""") }
}

/** 书源：统计 */
class GetSourceStatsTool(private val analyzer: BookSourceAnalyzer) : ToolDefinition {
    override val id = "get_source_stats"
    override val info = ToolDefinitionInfo(
        name = "get_source_stats",
        description = "获取书源统计信息（总数/启用数等）",
        parameters = emptyList()
    )
    override val category = "书源"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult =
        runCatching {
            val list = analyzer.list()
            ToolResult(
                text = Gson().toJson(
                    mapOf("total" to list.size, "enabled" to list.count { it["enabled"] == true })
                )
            )
        }.getOrElse { ToolResult(text = """{"error":${Gson().toJson(it.localizedMessage)}}""") }
}

/** 书源：连通测试 */
class TestBookSourceTool(private val analyzer: BookSourceAnalyzer) : ToolDefinition {
    override val id = "test_book_source"
    override val info = ToolDefinitionInfo(
        name = "test_book_source",
        description = "测试指定书源的连通性/规则诊断",
        parameters = listOf(
            ToolParam("sourceUrl", "string", "书源 URL", required = true)
        )
    )
    override val category = "书源"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val url = args["sourceUrl"]?.toString()
            ?: return ToolResult(text = """{"error":"缺少书源URL"}""")
        return runCatching {
            ToolResult(text = Gson().toJson(analyzer.test(url)))
        }.getOrElse { ToolResult(text = """{"error":${Gson().toJson(it.localizedMessage)}}""") }
    }
}