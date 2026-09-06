package io.legado.app.ai.tool

import com.google.gson.Gson
import io.legado.app.ai.runtime.ToolCallData

/**
 * Operit 式文本工具协议解析器。
 *
 * 对不支持原生 function-calling 的模型/服务商（本地小模型、部分中转站），
 * 模型会按系统提示约定的 XML 协议在回答文本中直接输出工具调用：
 * ```
 * <tool name="search_books">
 *   <param name="keyword">诡秘之主</param>
 * </tool>
 * ```
 * 本解析器把这些标签还原为与原生 tool_calls 相同的 [ToolCallData]，
 * 使 Agent 主循环对两种来源完全无感（兼容性核心）。
 *
 * 兼容性约定（对齐 Operit ChatMarkupRegex / unescapeXml）：
 * - 标签名固定为 tool，忽略大小写；name 属性必填
 * - 参数形如 <param name="k">v</param>；值支持 <![CDATA[...]]> 与 XML 实体
 * - 一次回答可包含多个工具调用，也可与普通文本混排
 */
object TextToolCallParser {

    private val gson = Gson()

    /** 工具调用整体：<tool name="xxx" ...>body</tool>（忽略大小写，跨行） */
    private val toolPattern = Regex(
        """<tool\b[^>]*name\s*=\s*"([^"]+)"[^>]*>([\s\S]*?)</tool\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** 参数项：<param name="xxx">value</param>（忽略大小写，跨行） */
    private val paramPattern = Regex(
        """<param\b[^>]*name\s*=\s*"([^"]+)"[^>]*>([\s\S]*?)</param\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** 解析结果：转换出的工具调用 + 剥离标签后的剩余正文 */
    data class Result(
        val calls: List<ToolCallData>,
        val strippedContent: String
    )

    /**
     * 从模型回答文本中提取工具调用。
     * 未命中任何标签时返回空 Result（零开销快速路径）。
     */
    fun parse(content: String): Result {
        if (!content.contains("<tool", ignoreCase = true)) return Result(emptyList(), content)

        val calls = ArrayList<ToolCallData>()
        var seq = 0
        val stripped = toolPattern.replace(content) { match ->
            val name = match.groupValues[1].trim()
            val body = match.groupValues[2]
            if (name.isNotEmpty()) {
                val params = LinkedHashMap<String, Any>()
                paramPattern.findAll(body).forEach { p ->
                    params[p.groupValues[1].trim()] = unescapeXml(p.groupValues[2])
                }
                calls.add(
                    ToolCallData(
                        id = "txt_${System.currentTimeMillis()}_${seq++}",
                        name = name,
                        arguments = gson.toJson(params)
                    )
                )
            }
            "" // 从正文中剥离原始标签
        }

        return Result(calls, stripCodeFence(stripped).trim())
    }

    /** 模型常把整段工具调用包进 ```xml ...``` 代码围栏，剥掉空围栏避免残留 */
    private fun stripCodeFence(text: String): String =
        text.replace(Regex("```(?:xml|html)?\\s*\\n?\\s*```"), "").trim()

    /** XML 实体与 CDATA 还原（对齐 Operit unescapeXml） */
    private fun unescapeXml(input: String): String {
        var result = input.trim()
        if (result.startsWith("<![CDATA[")) {
            result = if (result.endsWith("]]>")) {
                result.substring(9, result.length - 3)
            } else {
                result.substring(9)
            }
        } else if (result.endsWith("]]>")) {
            result = result.substring(0, result.length - 3)
        }
        return result
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
}
