package io.legado.app.ai.tool.impl

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.legado.app.ai.bridge.BookSourceAnalyzer
import io.legado.app.ai.bridge.SourceRuleWriter
import io.legado.app.ai.log.AiLog
import io.legado.app.ai.model.AgentError
import io.legado.app.ai.model.AgentErrorCode
import io.legado.app.ai.model.ToolDefinition
import io.legado.app.ai.model.ToolDefinitionInfo
import io.legado.app.ai.model.ToolParam
import io.legado.app.ai.model.ToolResult
import io.legado.app.ai.model.ToolResultState
import io.legado.app.ai.tool.ToolContext

/**
 * 书源修复提案（**写操作**）。
 * 只产出 pending_confirm 提案，不直接写回；用户确认后由 [SourceRuleWriter] 落地。
 *
 * 正确用法：先用 analyze_book_source 分析，再携带 [changes]（规则字段→修正值）调用本工具；
 * 未带 changes 的提案仅作诊断展示，批准时无可落地内容会如实回错。
 */
class SuggestSourceFixTool(
    private val analyzer: BookSourceAnalyzer,
    private val writer: SourceRuleWriter
) : ToolDefinition {
    override val id = "suggest_source_fix"
    override val info = ToolDefinitionInfo(
        name = "suggest_source_fix",
        description = "分析书源问题并产出修复提案，须用户确认后才生效。" +
            "请先用 analyze_book_source 分析，再携带 changes 参数给出具体修复值",
        parameters = listOf(
            ToolParam("sourceUrl", "string", "书源 URL", required = true),
            ToolParam(
                "changes", "object",
                "要应用的修复变更，JSON 对象：键为规则字段（如 searchUrl、ruleSearch.bookList、" +
                    "ruleContent.content、header），值为修正后的完整字符串；仅白名单字段会被写入",
                required = false
            )
        )
    )
    override val category = "书源"
    override val enabled = true
    override val manualConfirm = true

    private val gson = Gson()

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val url = args["sourceUrl"]?.toString()
            ?: return ToolResult(text = """{"error":"缺少书源URL"}""")
        return runCatching {
            val rules = analyzer.rules(url)
            val proposal = linkedMapOf<String, Any?>("url" to url, "analysis" to rules)
            parseChanges(args["changes"])?.let { proposal["changes"] = it }
            ToolResult(
                text = gson.toJson(mapOf("status" to "pending_confirm", "proposal" to proposal)),
                state = ToolResultState.PENDING_CONFIRM
            )
        }.getOrElse { ToolResult(text = """{"error":${Gson().toJson(it.localizedMessage)}}""") }
    }

    /** 用户批准后真正落库：把 changes 合并进书源 */
    override suspend fun onApproved(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val url = args["sourceUrl"]?.toString()
            ?: return ToolResult(text = """{"error":"缺少书源URL"}""")
        val changes = parseChanges(args["changes"])
        if (changes.isNullOrEmpty()) {
            return ToolResult(
                text = "{\"status\":\"no_changes\",\"message\":\"提案未包含 changes 变更，" +
                    "无可落地内容；请先 analyze_book_source 分析后携带 changes 重新提案\"}",
                error = AgentError(AgentErrorCode.TOOL_FAILED, "proposal has no changes")
            )
        }
        return if (writer.apply(url, changes)) {
            ToolResult(
                text = gson.toJson(
                    mapOf("status" to "applied", "url" to url, "appliedCount" to changes.size)
                )
            )
        } else {
            ToolResult(
                text = """{"error":"书源不存在或变更项均被拒绝（越权/未知字段）"}""",
                error = AgentError(AgentErrorCode.TOOL_FAILED, "apply source fix failed")
            )
        }
    }

    /**
     * 解析 changes 参数。兼容三种来源：
     * ① 模型直接传 JSON 字符串；② 原生函数调用传对象（executor 已 toString 成 JSON 形态）；
     * ③ 缺省/非法 → 返回 null。
     */
    private fun parseChanges(raw: Any?): LinkedHashMap<String, String>? {
        val str = when (raw) {
            null -> return null
            is String -> raw.trim()
            else -> raw.toString().trim()
        }
        if (str.isEmpty() || str == "null" || str == "{}") return null
        return runCatching {
            val obj = JsonParser.parseString(str).asJsonObject
            val map = LinkedHashMap<String, String>()
            obj.entrySet().forEach { (k, v) ->
                if (!v.isJsonNull) map[k.trim()] =
                    if (v.isJsonObject || v.isJsonArray) v.toString() else v.asString
            }
            if (map.isEmpty()) null else map
        }.getOrElse {
            AiLog.w("FixTool", "解析 changes 失败: ${str.take(120)}")
            null
        }
    }
}
