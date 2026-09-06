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

/** 布尔参数兜底解析：模型可能传 Boolean、"true"/"True"/"1" 等多种形态 */
internal fun boolArg(v: Any?, def: Boolean): Boolean = when (v) {
    is Boolean -> v
    is String -> v.trim().equals("true", ignoreCase = true) || v.trim() == "1"
    else -> def
}

/** 书源：启用/禁用（写操作，两阶段确认） */
class SetSourceEnabledTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "set_source_enabled"
    override val info = ToolDefinitionInfo(
        name = "set_source_enabled",
        description = "启用或禁用指定书源",
        parameters = listOf(
            ToolParam("url", "string", "书源 URL", required = true),
            ToolParam("enabled", "boolean", "true 启用，false 禁用", required = true)
        )
    )
    override val category = "书源"
    override val enabled = true
    override val manualConfirm = true

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val url = args["url"]?.toString() ?: return ToolResult(text = """{"error":"缺少书源URL"}""")
        val enabled = boolArg(args["enabled"], true)
        return ToolResult(
            text = Gson().toJson(
                mapOf("status" to "pending_confirm", "proposal" to mapOf("url" to url, "enabled" to enabled))
            ),
            state = ToolResultState.PENDING_CONFIRM
        )
    }

    override suspend fun onApproved(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val url = args["url"]?.toString() ?: return ToolResult(text = """{"error":"缺少书源URL"}""")
        val enabled = boolArg(args["enabled"], true)
        return ToolResult(text = Gson().toJson(bridge.appController.enableSource(url, enabled)))
    }
}

/** 设置：读取受控设置项 */
class GetSettingTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "get_setting"
    override val info = ToolDefinitionInfo(
        name = "get_setting",
        description = "读取 App 的受控设置项（夜间模式/线程数/RSS 等）",
        parameters = emptyList()
    )
    override val category = "设置"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult =
        ToolResult(text = Gson().toJson(bridge.appController.getSettings()))
}

/** 设置：写入（写操作，两阶段确认） */
class SetSettingTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "set_setting"
    override val info = ToolDefinitionInfo(
        name = "set_setting",
        description = "修改 App 受控设置项；支持 nightTheme(布尔)、threadCount(1..32)、showRss(布尔)",
        parameters = listOf(
            ToolParam("key", "string", "设置项键名", required = true),
            ToolParam("value", "string", "设置值（字符串形式）", required = true)
        )
    )
    override val category = "设置"
    override val enabled = true
    override val manualConfirm = true

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val key = args["key"]?.toString() ?: return ToolResult(text = """{"error":"缺少设置项 key"}""")
        val value = args["value"]?.toString() ?: return ToolResult(text = """{"error":"缺少设置值"}""")
        return ToolResult(
            text = Gson().toJson(
                mapOf("status" to "pending_confirm", "proposal" to mapOf("key" to key, "value" to value))
            ),
            state = ToolResultState.PENDING_CONFIRM
        )
    }

    override suspend fun onApproved(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val key = args["key"]?.toString() ?: return ToolResult(text = """{"error":"缺少设置项 key"}""")
        val value = args["value"]?.toString() ?: return ToolResult(text = """{"error":"缺少设置值"}""")
        return ToolResult(text = Gson().toJson(bridge.appController.setSetting(key, value)))
    }
}

/** 搜索：打开全局搜索界面并填入关键词（发出导航请求） */
class OpenSearchTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "open_search"
    override val info = ToolDefinitionInfo(
        name = "open_search",
        description = "打开全局搜索界面并填入关键词，供用户手动挑选搜索结果",
        parameters = listOf(ToolParam("keyword", "string", "搜索关键词", required = true))
    )
    override val category = "书架"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val kw = args["keyword"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return ToolResult(text = """{"error":"缺少关键词"}""")
        ctx.onNavigate.value = AppNav.GlobalSearch(kw)
        return ToolResult(text = Gson().toJson(mapOf("opened" to true, "keyword" to kw)))
    }
}

/** 书架：切换到书架列表界面（发出导航请求，由宿主消费） */
class ShowBookshelfTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "open_bookshelf"
    override val info = ToolDefinitionInfo(
        name = "open_bookshelf",
        description = "返回 App 主界面并切到书架列表，展示全部书架书籍",
        parameters = emptyList()
    )
    override val category = "书架"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        ctx.onNavigate.value = AppNav.ToBookshelf
        return ToolResult(text = Gson().toJson(mapOf("opened" to true, "view" to "bookshelf")))
    }
}