package io.legado.app.ai.runtime

import com.google.gson.JsonParser
import io.legado.app.ai.log.AiLog
import io.legado.app.ai.model.AgentError
import io.legado.app.ai.model.AgentErrorCode
import io.legado.app.ai.model.ChatMessage
import io.legado.app.ai.model.ToolDefinition
import io.legado.app.ai.model.ToolResult
import io.legado.app.ai.model.ToolResultState
import io.legado.app.ai.tool.ToolContext
import io.legado.app.ai.tool.ToolRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pi 内核风格的 ToolExecutor 三阶段流水线：
 *  ① 参数解析与校验（resolve）
 *  ② 工具执行（invoke，异常自愈不炸弹循环）
 *  ③ 结果格式化/回填（toolMessage / errorMessage）
 *
 * 写操作（PENDING_CONFIRM）的二次确认由 [AgentRuntime] 在主循环接管。
 */
sealed class ToolResolution {
    data class Ready(val def: ToolDefinition, val args: Map<String, Any>) : ToolResolution()
    data class Error(val toolName: String, val message: String) : ToolResolution()
}

class ToolExecutor(
    private val registry: ToolRegistry,
    // 安全网超时：需大于工具内部限时（书源测试 15s / 章节抓取 15s / 单源搜索 8s）
    private val toolTimeoutMs: Long = 45_000L,
    private val maxToolRetries: Int = 1
) {

    /** 阶段①：解析 arguments JSON 并按工具 schema 校验必填 / 枚举取值。 */
    fun resolve(call: ToolCallData): ToolResolution {
        val def = registry.find(call.name)
            ?: return ToolResolution.Error(call.name, "工具不存在: ${call.name}")
        val raw = parseRaw(call.arguments)
            ?: return ToolResolution.Error(call.name, "参数不是合法 JSON 对象")
        val violation = validate(def, raw)
        if (violation != null) return ToolResolution.Error(call.name, violation)
        return ToolResolution.Ready(def, raw)
    }

    /**
     * 阶段②：DeepSeek Harness 风格工具管道——
     *  前置策略守卫（写/确认类工具在无确认上下文时前置拒绝）→ 单工具超时 → 有限重试。
     * 任何异常/超时都收敛为「失败」的 ToolResult，避免中断 Agent 循环。
     */
    suspend fun invoke(def: ToolDefinition, ctx: ToolContext, args: Map<String, Any>): ToolResult {
        if (def.manualConfirm && !ctx.allowConfirm) {
            return ToolResult(
                text = """{"status":"denied","tool":"${def.id}","reason":"需要用户确认但当前不可确认"}""",
                state = ToolResultState.OK
            )
        }
        val startMs = System.currentTimeMillis()
        AiLog.i("Tool", "▶ ${def.id} args=${args.toString().take(200)}")
        return withTimeoutOrNull(toolTimeoutMs) {
            runWithRetry(def, ctx, args).also { r ->
                val ms = System.currentTimeMillis() - startMs
                if (r.error != null) {
                    AiLog.w("Tool", "✕ ${def.id} ${ms}ms ${r.text.take(200)}")
                } else {
                    AiLog.i("Tool", "✓ ${def.id} ${ms}ms ${r.text.take(160)}")
                }
            }
        } ?: run {
            val ms = System.currentTimeMillis() - startMs
            AiLog.e("Tool", "⏱ ${def.id} 超时(>${toolTimeoutMs / 1000}s, 实际${ms}ms)")
            ToolResult(
                text = jsonError(def.id, "工具执行超时（>${toolTimeoutMs / 1000}s）"),
                state = ToolResultState.OK,
                error = AgentError(AgentErrorCode.RETRYABLE_TIMEOUT, "tool timeout")
            )
        }
    }

    private suspend fun runWithRetry(
        def: ToolDefinition,
        ctx: ToolContext,
        args: Map<String, Any>
    ): ToolResult {
        var attempt = 0
        while (true) {
            val r = try {
                def.execute(ctx, args)
            } catch (e: AgentException) {
                ToolResult(
                    text = jsonError(def.id, e.message ?: "tool error"),
                    state = ToolResultState.OK,
                    error = AgentError(e.code, e.message ?: "tool error")
                )
            } catch (e: Exception) {
                AiLog.e("Tool", "${def.id} 异常", e)
                ToolResult(
                    text = jsonError(def.id, e.localizedMessage ?: "tool error"),
                    state = ToolResultState.OK,
                    error = AgentError(AgentErrorCode.TOOL_FAILED, "tool error")
                )
            }
            // 写/确认类工具绝不自动重试；确定性错误不重试
            if (r.state == ToolResultState.PENDING_CONFIRM || def.manualConfirm) return r
            if (r.error == null || !r.error.code.retryable || attempt >= maxToolRetries) return r
            attempt++
            AiLog.w("Tool", "↻ ${def.id} 可重试错误，${300L * attempt}ms 后第${attempt + 1}次尝试")
            delay(300L * attempt) // 退避 0.3s, 0.6s...
        }
    }

    /** 阶段③：生成回喂给模型的标准 tool 消息；[approved]=false 时回填拒绝结果。 */
    fun toolMessage(call: ToolCallData, result: ToolResult, approved: Boolean = true): ChatMessage =
        ChatMessage(
            role = "tool",
            content = if (approved) result.text else """{"status":"denied","tool":"${call.name}"}""",
            toolCallId = call.id
        )

    /** 阶段③：参数/工具级错误的 tool 消息，模型可据此修正重试。 */
    fun errorMessage(call: ToolCallData, message: String): ChatMessage =
        ChatMessage(role = "tool", content = jsonError(call.name, message), toolCallId = call.id)

    private fun parseRaw(arguments: String): Map<String, Any>? {
        val element = try {
            JsonParser().parse(arguments)
        } catch (e: Exception) {
            return null
        }
        if (!element.isJsonObject) return null
        val map = mutableMapOf<String, Any>()
        element.asJsonObject.entrySet().forEach { (k, v) ->
            map[k] = when {
                v.isJsonNull -> ""
                v.isJsonPrimitive -> {
                    val p = v.asJsonPrimitive
                    when {
                        p.isString -> p.asString
                        p.isBoolean -> p.asBoolean
                        p.isNumber -> p.asDouble
                        else -> p.asString
                    }
                }
                v.isJsonObject -> v.asJsonObject.toString()
                else -> v.asJsonArray.toString()
            }
        }
        return map
    }

    private fun validate(def: ToolDefinition, args: Map<String, Any>): String? {
        for (p in def.info.parameters) {
            if (p.required && !args.containsKey(p.name)) return "缺少必填参数: ${p.name}"
            p.enum?.let { allowed ->
                val v = args[p.name]
                if (v != null && v.toString() !in allowed) {
                    return "参数 ${p.name} 取值无效: $v（可选: ${allowed.joinToString("/")}）"
                }
            }
        }
        return null
    }

    private fun jsonError(tool: String, message: String): String =
        """{"error":{"tool":"${escape(tool)}","message":"${escape(message)}"}}"""

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
}