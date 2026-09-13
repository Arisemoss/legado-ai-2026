package io.legado.app.ai.tool.impl

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.legado.app.ai.bridge.AiBridge
import io.legado.app.ai.model.ToolDefinition
import io.legado.app.ai.model.ToolDefinitionInfo
import io.legado.app.ai.model.ToolParam
import io.legado.app.ai.model.ToolResult
import io.legado.app.ai.model.ToolResultState
import io.legado.app.ai.source.SourceHealth
import io.legado.app.ai.tool.ToolContext

/** 书架：批量加入书架（写操作，两阶段确认）。参数 booksJson 为 [{bookUrl,name,author,origin,originName}] JSON 数组。 */
class BatchAddToShelfTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "batch_add_to_shelf"
    override val info = ToolDefinitionInfo(
        name = "batch_add_to_shelf",
        description = "批量把书籍加入书架（写操作，需用户确认）；booksJson 为 JSON 数组，元素含 bookUrl/name 等字段",
        parameters = listOf(
            ToolParam("booksJson", "string", "JSON 数组：[{bookUrl,name,author,origin,originName}]", required = true)
        )
    )
    override val category = "书架"
    override val enabled = true
    override val manualConfirm = true

    private fun parse(args: Map<String, Any?>): List<Map<String, Any?>>? {
        val text = args["booksJson"]?.toString() ?: return null
        return runCatching {
            val arr = JsonParser.parseString(text).asJsonArray
            arr.map { el ->
                val o = el.asJsonObject
                mapOf<String, Any?>(
                    "bookUrl" to (o.get("bookUrl")?.asString),
                    "name" to (o.get("name")?.asString),
                    "author" to (o.get("author")?.asString),
                    "origin" to (o.get("origin")?.asString),
                    "originName" to (o.get("originName")?.asString),
                    "tocUrl" to (o.get("tocUrl")?.asString),
                    "coverUrl" to (o.get("coverUrl")?.asString),
                    "intro" to (o.get("intro")?.asString)
                )
            }
        }.getOrNull()
    }

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        // 注意：map 缺失字段的值是 Kotlin null，null.toString() == "null"（非空白），
        // 原来的 !toString().isNullOrBlank() 过滤恒为 true，等于没过滤
        val books = parse(args)?.filter { it["bookUrl"]?.toString()?.isNotBlank() == true }
        if (books.isNullOrEmpty()) {
            return ToolResult(text = Gson().toJson(mapOf("error" to "booksJson 为空或格式不正确")))
        }
        return ToolResult(
            text = Gson().toJson(
                mapOf(
                    "status" to "pending_confirm",
                    "proposal" to mapOf(
                        "count" to books.size,
                        "names" to books.take(10).map { it["name"]?.toString().orEmpty() }
                    )
                )
            ),
            state = ToolResultState.PENDING_CONFIRM
        )
    }

    override suspend fun onApproved(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val books = parse(args)?.filter { it["bookUrl"]?.toString()?.isNotBlank() == true }
            ?: return ToolResult(text = Gson().toJson(mapOf("error" to "booksJson 解析失败")))
        return ToolResult(text = Gson().toJson(bridge.appController.addToShelfBatch(books)))
    }
}

/** 书源：AI 批量测源（只读，最多 20 个，带可用性结论）。 */
class TestSourcesBatchTool : ToolDefinition {
    override val id = "test_sources_batch"
    override val info = ToolDefinitionInfo(
        name = "test_sources_batch",
        description = "批量检测书源可用性（只读；最多 20 个源，逐个发起一次最小搜索请求）",
        parameters = emptyList()
    )
    override val category = "书源"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val sources = SourceHealth.allSources().take(20)
        if (sources.isEmpty()) return ToolResult(text = Gson().toJson(mapOf("total" to 0)))
        val results = SourceHealth.testAll(sources, onProgress = { _, _ -> })
        val ok = results.count { it.ok }
        val bad = results.size - ok
        val details = results.map {
            mapOf(
                "name" to it.source.bookSourceName,
                "url" to it.source.bookSourceUrl,
                "ok" to it.ok,
                "latencyMs" to it.latencyMs,
                "reason" to it.reason
            )
        }
        return ToolResult(
            text = Gson().toJson(mapOf("total" to results.size, "ok" to ok, "bad" to bad, "details" to details))
        )
    }
}

/** 替换净化：从 URL 或 JSON 文本导入规则（写操作，两阶段确认）。 */
class ImportReplaceRulesTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "import_replace_rules"
    override val info = ToolDefinitionInfo(
        name = "import_replace_rules",
        description = "从 URL 或 JSON 文本导入替换净化规则（写操作，需用户确认）",
        parameters = listOf(
            ToolParam("source", "string", "规则 JSON 文本，或 http(s) 规则地址", required = true)
        )
    )
    override val category = "设置"
    override val enabled = true
    override val manualConfirm = true

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val src = args["source"]?.toString()
            ?: return ToolResult(text = Gson().toJson(mapOf("error" to "缺少 source")))
        val preview = if (src.startsWith("http", true)) src else src.take(80)
        return ToolResult(
            text = Gson().toJson(mapOf("status" to "pending_confirm", "proposal" to mapOf("source" to preview))),
            state = ToolResultState.PENDING_CONFIRM
        )
    }

    override suspend fun onApproved(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val src = args["source"]?.toString()
            ?: return ToolResult(text = Gson().toJson(mapOf("error" to "缺少 source")))
        return ToolResult(text = Gson().toJson(bridge.appController.importReplaceRules(src)))
    }
}

/** 设置：单项恢复默认（写操作，两阶段确认）。 */
class ResetSettingTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "reset_setting"
    override val info = ToolDefinitionInfo(
        name = "reset_setting",
        description = "把指定设置项恢复默认值（写操作，需用户确认）",
        parameters = listOf(
            ToolParam("key", "string", "设置项 key（与 set_setting 相同）", required = true)
        )
    )
    override val category = "设置"
    override val enabled = true
    override val manualConfirm = true

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val key = args["key"]?.toString()
            ?: return ToolResult(text = Gson().toJson(mapOf("error" to "缺少 key")))
        return ToolResult(
            text = Gson().toJson(mapOf("status" to "pending_confirm", "proposal" to mapOf("key" to key))),
            state = ToolResultState.PENDING_CONFIRM
        )
    }

    override suspend fun onApproved(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val key = args["key"]?.toString()
            ?: return ToolResult(text = Gson().toJson(mapOf("error" to "缺少 key")))
        return ToolResult(text = Gson().toJson(bridge.appController.resetSetting(key)))
    }
}
