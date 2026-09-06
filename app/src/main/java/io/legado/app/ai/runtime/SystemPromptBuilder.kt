package io.legado.app.ai.runtime

import io.legado.app.ai.skill.SkillRegistry
import io.legado.app.ai.tool.ToolRegistry

/**
 * 组装系统提示：内置小说读书助手角色 + 可用技能 + 原子工具组合引导 + 输出约定。
 * 强调用「原子工具」组合完成小说任务（搜书 → 读章 → 分析），避免整段臆测。
 *
 * 当传入 [tools]（Operit 式兼容层）时，额外注入文本工具调用协议：
 * 不支持原生 function-calling 的模型可按 XML 协议在回答中直接输出工具调用，
 * 由 [io.legado.app.ai.tool.TextToolCallParser] 解析后走同一执行流水线。
 */
class SystemPromptBuilder(
    private val skills: SkillRegistry,
    private val tools: ToolRegistry? = null
) {
    fun build(): String = buildString {
        appendLine("""
            你是「AI 读书助手」，面向中文网络小说阅读场景。
            【可用能力】${skills.all().joinToString("、") { it.name }}。

            【工具组合原则】优先用原子工具"拆解出可验证的事实"再回答，不要凭空编造书名/剧情/正文。
            - 找书/推荐：用选书工具检索，尽量返回真实书名与来源。
            - 读正文/总结/分析：先读对应章节，再用数据回答，标注出处的书与章节。
            - 书源诊断/修复：先分析书源，再给出有依据的诊断与修复方案。
            同一任务可连续调用多个工具，先拿到结果再组织最终回答。

            【安全边界】涉及"修改书源规则"的写操作，只返回方案与理由，绝不直接改库；必须等待用户确认后才生效。

            【输出约定】回答精炼，中文优先；给结论时给出关键依据；不确定时明确说明。
        """.trimIndent())
        appendToolProtocol()
    }

    /** 追加文本工具协议说明（Operit 兼容层）。无可用工具时不追加。 */
    private fun StringBuilder.appendToolProtocol() {
        val defs = tools?.definitions().orEmpty()
        if (defs.isEmpty()) return
        appendLine()
        appendLine("【工具调用协议】使用工具时，在回答文本中按如下 XML 格式输出（每次可输出多个调用）：")
        appendLine("<tool name=\"工具名\">")
        appendLine("<param name=\"参数名\">参数值</param>")
        appendLine("</tool>")
        appendLine("规则：标签输出后即结束本轮回答，等待工具结果；参数值含特殊字符时用 <![CDATA[...]]> 包裹；不要编造未列出的工具。")
        appendLine("【可用工具清单】")
        defs.forEach { t ->
            appendLine("- ${t.info.name}: ${t.info.description}")
            val params = t.info.parameters
            if (params.isNotEmpty()) {
                appendLine(
                    "  参数: " + params.joinToString(", ") { p ->
                        "${p.name}(${p.type}${if (p.required) ",必填" else ",可选"}): ${p.description}"
                    }
                )
            }
        }
    }
}
