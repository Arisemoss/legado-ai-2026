package io.legado.app.ai.model

import com.google.gson.annotations.SerializedName
import kotlin.jvm.Transient

data class ChatMessage(
    val role: String,        // "system", "user", "assistant", "tool"
    val content: String? = null,
    @SerializedName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @SerializedName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null,
    /** 本地时间戳（transient 不参与 Gson 序列化），供 UI 气泡时间戳与历史回放 */
    @Transient
    var createdAt: Long = System.currentTimeMillis()
)

data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

data class FunctionCall(
    val name: String,
    val arguments: String   // JSON string
)

data class ChatCompletionRequest(
    val model: String = "gpt-4o-mini",
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>? = null,
    val temperature: Double = 0.7,
    @SerializedName("max_tokens")
    val maxTokens: Int = 4096,
    val stream: Boolean = false
)

/** OpenAI 线格式 schema DTO（区别于 [ToolDefinition] 工具抽象接口） */
data class ToolSpec(
    val type: String = "function",
    val function: FunctionDefinition
)

data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

data class ChatCompletionResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice>? = null,
    val usage: Usage? = null,
    val error: ErrorResponse? = null
)

data class Choice(
    val message: ChatMessage,
    @SerializedName("finish_reason")
    val finishReason: String? = null
)

data class Usage(
    @SerializedName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerializedName("completion_tokens")
    val completionTokens: Int? = null,
    @SerializedName("total_tokens")
    val totalTokens: Int? = null
)

data class ErrorResponse(
    val message: String? = null,
    val type: String? = null
)

data class AiModelConfig(
    val name: String = "gpt-4o-mini",
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
    val stream: Boolean = false,
    val timeoutMillis: Long = 120_000L,
    val maxRounds: Int = 5,
    val sessionWindow: Int = 50,
    /**
     * 工具调用协议（Operit 式兼容层）：
     * auto  = 原生 function-calling + 文本 XML 协议双通道（默认，最大兼容）
     * native = 仅原生 function-calling
     * text   = 仅文本 XML 协议（不支持 tools 参数的服务商/本地模型）
     */
    val toolProtocol: String = PROTOCOL_AUTO
) {
    companion object {
        const val PROTOCOL_AUTO = "auto"
        const val PROTOCOL_NATIVE = "native"
        const val PROTOCOL_TEXT = "text"
    }
}