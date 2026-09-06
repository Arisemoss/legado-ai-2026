package io.legado.app.ai.runtime

import io.legado.app.ai.model.ChatMessage
import io.legado.app.ai.model.FunctionCall
import io.legado.app.ai.model.ToolCall
import io.legado.app.ai.model.ToolResult
import io.legado.app.ai.model.ToolResultState
import io.legado.app.ai.model.AgentError
import io.legado.app.ai.model.AgentErrorCode
import io.legado.app.ai.model.ToolEvent
import io.legado.app.ai.log.AiLog
import io.legado.app.ai.tool.ConfirmRequest
import io.legado.app.ai.tool.TextToolCallParser
import io.legado.app.ai.tool.ToolContext
import io.legado.app.ai.tool.ToolRegistry
import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

data class AgentResult(
    val answer: String,
    val state: AgentResultState = AgentResultState.DONE,
    val tokensUsed: Long = 0L,
    val rounds: Int = 0
)

enum class AgentResultState { DONE, STOPPED, BUDGET_EXCEEDED, ERROR }

/**
 * Pi Harness 风格的双嵌套 agentLoop：
 *  - 外层：多轮助手补全（[maxRounds] 预算），产出最终答案；
 *  - 内层：对单轮内的整批 tool_calls 并行执行后再回喂，写操作走二次确认。
 * 工具执行经 [ToolExecutor] 三阶段流水线，真实 token 计费强制预算。
 */
class AgentRuntime(
    private val client: ChatModelClient,
    private val registry: ToolRegistry,
    private val maxRounds: Int = 5,
    private val maxTokens: Long = 32_000L,
    private val maxRetries: Int = 1,
    private val confirmTimeoutMs: Long = 300_000L,
    /** 开启后模型补全走 SSE 流式，增量文本经 ctx.onPartialText 回流 UI（打字机效果） */
    private val preferStream: Boolean = false,
    /**
     * 工具调用协议（Operit 式兼容层，见 [io.legado.app.ai.model.AiModelConfig]）：
     * auto=原生+文本双通道 / native=仅原生 / text=仅文本 XML 协议
     */
    private val toolProtocol: String = io.legado.app.ai.model.AiModelConfig.PROTOCOL_AUTO
) {
    private val executor = ToolExecutor(registry)
    private var eventSeq = 0L

    companion object {
        /** 发送给模型的历史消息上限，防止长会话每轮请求无限膨胀拖慢响应 */
        private const val HISTORY_CAP = 24
    }

    /** 发布工具事件给 UI（实时工具卡片） */
    private fun postEvent(
        ctx: ToolContext,
        callId: String,
        toolName: String,
        phase: String,
        argsPreview: String = "",
        detail: String? = null,
        elapsedMs: Long = 0L
    ) {
        ctx.onToolEvent.value = ToolEvent(
            seq = ++eventSeq,
            callId = callId,
            toolName = toolName,
            phase = phase,
            argsPreview = argsPreview,
            detail = detail,
            elapsedMs = elapsedMs
        )
    }

    private fun previewArgs(args: Map<String, Any>): String =
        runCatching { Gson().toJson(args) }.getOrDefault(args.toString()).take(160)

    /** 供 UI 调用的确认入口；token 与 [ConfirmRequest.confirmToken] 对应。经共享总线，不随 runtime 重建失效 */
    fun approve(confirmToken: String, approved: Boolean) {
        ApprovalBus.offer(confirmToken, approved)
    }

    suspend fun execute(
        userPrompt: String,
        history: List<ChatMessage>,
        ctx: ToolContext,
        systemPrompt: String
    ): AgentResult {
        val messages = ArrayList<ChatMessage>()
        messages += ChatMessage(role = "system", content = systemPrompt)
        messages += history.takeLast(HISTORY_CAP)
        messages += ChatMessage(role = "user", content = userPrompt)
        AiLog.i(
            "Agent",
            "▶ 开始 \"${userPrompt.take(80)}\" · history=${history.size}(取${minOf(history.size, HISTORY_CAP)}) · ${if (preferStream) "流式" else "非流式"}"
        )

        var billed = 0L
        var rounds = 0
        repeat(maxRounds) {
            if (ctx.stopRequested.value) {
                return AgentResult(lastAnswer(messages), AgentResultState.STOPPED, billed, rounds)
            }
            rounds++
            AiLog.i("Agent", "第 $rounds/$maxRounds 轮 · 请求模型…")

            val completion = try {
                completeOnce(messages, ctx)
                    ?: run {
                        AiLog.w("Agent", "用户停止（第 $rounds 轮前）")
                        return AgentResult(lastAnswer(messages), AgentResultState.STOPPED, billed, rounds)
                    }
            } catch (e: AgentException) {
                AiLog.e("Agent", "模型调用失败: ${e.code} ${e.message}")
                return AgentResult("模型调用失败：${e.message}", AgentResultState.ERROR, billed, rounds)
            }

            // Operit 式兼容层：无原生 tool_calls 时，尝试从回答文本解析 XML 工具协议
            var calls = completion.toolCalls
            var answerContent = completion.content
            if (calls.isNullOrEmpty() && toolProtocol != io.legado.app.ai.model.AiModelConfig.PROTOCOL_NATIVE) {
                val parsed = TextToolCallParser.parse(answerContent.orEmpty())
                if (parsed.calls.isNotEmpty()) {
                    AiLog.i("Agent", "文本协议命中 ${parsed.calls.size} 个工具调用: ${parsed.calls.joinToString { it.name }}")
                    calls = parsed.calls
                    answerContent = parsed.strippedContent.ifBlank { null }
                }
            }

            appendAssistant(messages, answerContent, calls)
            val increment = completion.usage?.totalTokens?.toLong()
                ?: (256L + (completion.content?.length ?: 0) / 3L)
            billed += increment
            AiLog.i(
                "Agent",
                "第 $rounds 轮返回: content=${completion.content?.length ?: 0}字, toolCalls=${calls?.size ?: 0}, +$increment tokens(累计$billed)"
            )
            if (billed > maxTokens) {
                AiLog.w("Agent", "预算超限截断: $billed > $maxTokens")
                return AgentResult(answerContent ?: "已达预算上限", AgentResultState.BUDGET_EXCEEDED, billed, rounds)
            }

            if (calls.isNullOrEmpty()) {
                return AgentResult(answerContent ?: "无回复", AgentResultState.DONE, billed, rounds)
            }

            // 内层①：整批解析（流水线阶段①），失败项就地回填错误消息
            val resolutions = calls.map { executor.resolve(it) }

            // 发布「执行中」事件并记录起始时间，供 UI 渲染实时工具卡片
            val startTimes = HashMap<Int, Long>()
            for (i in calls.indices) {
                val r = resolutions[i]
                if (r is ToolResolution.Ready) {
                    startTimes[i] = System.currentTimeMillis()
                    postEvent(
                        ctx, callId = calls[i].id, toolName = r.def.id,
                        phase = ToolEvent.PHASE_RUNNING,
                        argsPreview = previewArgs(r.args)
                    )
                }
            }

            // 内层②：整批并行执行（流水线阶段②）；异常自愈，不中断循环
            val running = coroutineScope {
                resolutions.map { res ->
                    if (res is ToolResolution.Ready) async { executor.invoke(res.def, ctx, res.args) }
                    else null
                }
            }

            // 内层③：按原顺序收集并回填（流水线阶段③），写操作二次确认
            for (i in calls.indices) {
                val call = calls[i]
                when (val res = resolutions[i]) {
                    is ToolResolution.Error -> {
                        postEvent(
                            ctx, call.id, call.name,
                            ToolEvent.PHASE_ERROR, detail = res.message.take(240)
                        )
                        AiLog.w("Agent", "工具解析失败 ${call.name}: ${res.message}")
                        messages += executor.errorMessage(call, res.message)
                    }
                    is ToolResolution.Ready -> {
                        val result = running[i]?.await() ?: continue
                        val elapsed = System.currentTimeMillis() - (startTimes[i] ?: 0L)
                        when (result.state) {
                            ToolResultState.PENDING_CONFIRM -> {
                                postEvent(
                                    ctx, calls[i].id, res.def.id,
                                    ToolEvent.PHASE_CONFIRM,
                                    argsPreview = previewArgs(res.args),
                                    detail = "写操作待确认…"
                                )
                                ctx.onConfirmRequested.value = ConfirmRequest(call.id, res.args)
                                AiLog.w("Confirm", "写操作待确认: ${res.def.id} args=${previewArgs(res.args)}")
                                val approved = awaitApproval(ctx, call.id)
                                AiLog.i("Confirm", "${res.def.id} → ${if (approved) "用户同意" else "用户拒绝"}")
                                postEvent(
                                    ctx, calls[i].id, res.def.id,
                                    if (approved) ToolEvent.PHASE_APPROVED else ToolEvent.PHASE_DENIED,
                                    detail = if (approved) "已确认，正在写入" else "用户拒绝执行",
                                    elapsedMs = elapsed
                                )
                                val finalResult = if (approved) {
                                    try {
                                        res.def.onApproved(ctx, res.args)
                                    } catch (e: Exception) {
                                        AiLog.e("Confirm", "${res.def.id} 写入失败", e)
                                        postEvent(
                                            ctx, calls[i].id, res.def.id,
                                            ToolEvent.PHASE_ERROR,
                                            detail = e.localizedMessage?.take(240)
                                        )
                                        ToolResult(
                                            text = "{\"error\":${Gson().toJson(e.localizedMessage)}}",
                                            error = AgentError(AgentErrorCode.TOOL_FAILED, "onApproved")
                                        )
                                    }
                                } else {
                                    ToolResult(
                                        text = Gson().toJson(mapOf("status" to "denied", "tool" to res.def.id)),
                                        error = AgentError(AgentErrorCode.NO_PERMISSION, "user rejected")
                                    )
                                }
                                messages += executor.toolMessage(call, finalResult)
                            }
                            else -> {
                                postEvent(
                                    ctx, calls[i].id, res.def.id,
                                    if (result.error != null) ToolEvent.PHASE_ERROR else ToolEvent.PHASE_RESULT,
                                    detail = result.text.take(240),
                                    elapsedMs = elapsed
                                )
                                messages += executor.toolMessage(call, result)
                            }
                        }
                    }
                }
            }
        }
        AiLog.i("Agent", "■ 结束: ${lastAnswer(messages).length}字, $rounds 轮, $billed tokens")
        return AgentResult(lastAnswer(messages), AgentResultState.DONE, billed, rounds)
    }

    /**
     * 单次模型补全；对可重试错误做指数退避重试（默认 1 次重试），
     * 停止标志在重试间隙同样生效。重试耗尽抛出 [AgentException]。
     * 流式模式下增量文本实时写入 ctx.onPartialText，轮次结束后清空。
     */
    private suspend fun completeOnce(messages: List<ChatMessage>, ctx: ToolContext): ChatCompletion? {
        // text 模式不发送原生 tools schema（兼容不支持 tools 参数的服务商）
        val schema = if (toolProtocol == io.legado.app.ai.model.AiModelConfig.PROTOCOL_TEXT) {
            null
        } else {
            registry.toOpenAiSchema()
        }
        var attempt = 0
        while (true) {
            if (ctx.stopRequested.value) return null
            try {
                if (preferStream && client.supportsStream) {
                    val acc = StringBuilder()
                    val completion = client.completeStreaming(
                        messages, schema,
                        onDelta = { delta ->
                            acc.append(delta)
                            ctx.onPartialText.value = acc.toString()
                        },
                        isCancelled = { ctx.stopRequested.value }
                    )
                    // 本轮流结束：清除打字机气泡，最终回答由主循环统一落行
                    ctx.onPartialText.value = null
                    return completion
                }
                return client.complete(messages, schema, stream = false)
            } catch (e: AgentException) {
                if (!e.code.retryable || attempt >= maxRetries) throw e
                attempt++
                delay(1000L * attempt) // 退避 1s, 2s...
            }
        }
    }

    /** 把模型返回追加进上下文（content + tool_calls，供模型下一轮续接 tool 结果） */
    private fun appendAssistant(
        messages: MutableList<ChatMessage>,
        content: String?,
        calls: List<ToolCallData>?
    ) {
        messages += ChatMessage(
            role = "assistant",
            content = content,
            toolCalls = calls?.map { ToolCall(it.id, "function", FunctionCall(it.name, it.arguments)) }
        )
    }

    private suspend fun awaitApproval(ctx: ToolContext, token: String): Boolean =
        // 共享总线上按 token 过滤；超时/停止均按拒绝处理
        ApprovalBus.await(token, confirmTimeoutMs) { ctx.stopRequested.value }?.second ?: false

    private fun lastAnswer(messages: List<ChatMessage>): String {
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            if (m.role == "assistant" && !m.content.isNullOrBlank()) return m.content
        }
        return ""
    }
}