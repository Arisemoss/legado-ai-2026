package io.legado.app.ai.tool.impl

import com.google.gson.Gson
import io.legado.app.ai.bridge.AiBridge
import io.legado.app.ai.model.ToolDefinition
import io.legado.app.ai.model.ToolDefinitionInfo
import io.legado.app.ai.model.ToolParam
import io.legado.app.ai.model.ToolResult
import io.legado.app.ai.model.ToolResultState
import io.legado.app.ai.tool.ToolContext

/** 替换净化：读取规则列表 */
class ListReplaceRulesTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "list_replace_rules"
    override val info = ToolDefinitionInfo(
        name = "list_replace_rules",
        description = "读取替换净化规则列表（可按名称/分组过滤，最多 200 条）",
        parameters = listOf(ToolParam("keyword", "string", "名称/分组关键字（可选）", required = false))
    )
    override val category = "设置"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val kw = args["keyword"]?.toString()?.takeIf { it.isNotBlank() }
        return ToolResult(text = Gson().toJson(bridge.appController.listReplaceRules(kw)))
    }
}

/** 替换净化：新增/更新/删除/启停（写操作，两阶段确认） */
class ManageReplaceRuleTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "manage_replace_rule"
    override val info = ToolDefinitionInfo(
        name = "manage_replace_rule",
        description = "管理替换净化规则：action=upsert 新增或更新（需 name+pattern），delete 删除，enable/disable 启停；写操作需用户确认",
        parameters = listOf(
            ToolParam("action", "string", "upsert | delete | enable | disable", required = true),
            ToolParam("id", "number", "规则 id（更新/删除/启停时必填）", required = false),
            ToolParam("name", "string", "规则名称", required = false),
            ToolParam("pattern", "string", "匹配表达式（正则或纯文本）", required = false),
            ToolParam("replacement", "string", "替换内容", required = false),
            ToolParam("group", "string", "分组（可选）", required = false),
            ToolParam("isRegex", "boolean", "是否正则（默认 true）", required = false),
            ToolParam("scopeContent", "boolean", "是否作用于正文（默认 true）", required = false)
        )
    )
    override val category = "设置"
    override val enabled = true
    override val manualConfirm = true

    private fun actionOf(args: Map<String, Any?>) =
        args["action"]?.toString()?.trim()?.lowercase().orEmpty()

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val action = actionOf(args)
        if (action !in listOf("upsert", "delete", "enable", "disable")) {
            return ToolResult(text = Gson().toJson(mapOf("error" to "action 需为 upsert|delete|enable|disable")))
        }
        return ToolResult(
            text = Gson().toJson(mapOf("status" to "pending_confirm", "proposal" to args)),
            state = ToolResultState.PENDING_CONFIRM
        )
    }

    override suspend fun onApproved(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val action = actionOf(args)
        val id = (args["id"] as? Number)?.toLong() ?: args["id"]?.toString()?.toLongOrNull()
        val result: Map<String, Any> = when (action) {
            "upsert" -> bridge.appController.upsertReplaceRule(args)
            "delete" -> if (id == null) mapOf("ok" to false, "message" to "缺少 id")
            else bridge.appController.deleteReplaceRule(id)
            "enable", "disable" -> if (id == null) mapOf("ok" to false, "message" to "缺少 id")
            else bridge.appController.enableReplaceRule(id, action == "enable")
            else -> mapOf("ok" to false, "message" to "不支持的 action: $action")
        }
        return ToolResult(text = Gson().toJson(result))
    }
}

/** 设置：读取全部受控设置项 */
class ListSettingsTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "list_settings"
    override val info = ToolDefinitionInfo(
        name = "list_settings",
        description = "读取 App 全部受控设置项（主题/书架/朗读/净化等），便于修改前先查看当前值",
        parameters = emptyList()
    )
    override val category = "设置"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult =
        ToolResult(text = Gson().toJson(bridge.appController.getSettings()))
}

/** 书源：删除（写操作，两阶段确认） */
class DeleteSourceTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "delete_book_source"
    override val info = ToolDefinitionInfo(
        name = "delete_book_source",
        description = "删除指定书源（按 URL）；写操作需用户确认",
        parameters = listOf(ToolParam("url", "string", "书源 URL", required = true))
    )
    override val category = "书源"
    override val enabled = true
    override val manualConfirm = true

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val url = args["url"]?.toString()
            ?: return ToolResult(text = Gson().toJson(mapOf("error" to "缺少书源URL")))
        return ToolResult(
            text = Gson().toJson(mapOf("status" to "pending_confirm", "proposal" to mapOf("url" to url))),
            state = ToolResultState.PENDING_CONFIRM
        )
    }

    override suspend fun onApproved(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val url = args["url"]?.toString()
            ?: return ToolResult(text = Gson().toJson(mapOf("error" to "缺少书源URL")))
        return ToolResult(text = Gson().toJson(bridge.appController.deleteSource(url)))
    }
}
