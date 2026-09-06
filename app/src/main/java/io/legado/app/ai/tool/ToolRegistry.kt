package io.legado.app.ai.tool

import io.legado.app.ai.model.ToolDefinition
import io.legado.app.ai.model.ToolParam

/**
 * 工具注册中心。注册可被 Agent 调用的 [ToolDefinition]，并生成 OpenAI function-calling tools schema。
 */
class ToolRegistry {
    private val defs = LinkedHashMap<String, ToolDefinition>()

    fun register(t: ToolDefinition) {
        defs[t.id] = t
    }

    fun unregister(id: String) {
        defs.remove(id)
    }

    fun definitions(): List<ToolDefinition> = defs.values.filter { it.enabled }

    fun find(id: String): ToolDefinition? = defs[id]

    /** 生成 OpenAI function-calling tools 数组 */
    fun toOpenAiSchema(): List<Map<String, Any>> = definitions().map { t ->
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to t.info.name,
                "description" to t.info.description + if (t.manualConfirm) {
                    "（涉及数据修改，需用户确认后才生效，请先向用户说明方案与理由）"
                } else {
                    ""
                },
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to t.info.parameters.associate { p ->
                        p.name to buildProperty(p)
                    },
                    "required" to t.info.parameters.filter { it.required }.map { it.name }
                )
            )
        )
    }

    private fun buildProperty(p: ToolParam): Map<String, Any> {
        val m = mutableMapOf<String, Any>(
            "type" to p.type,
            "description" to p.description
        )
        p.enum?.let { m["enum"] = it }
        return m
    }
}