package io.legado.app.ai.model

/**
 * Agent 错误码。`retryable=true` 表示该错误可重试（如网络/超时），其余为不可重试的确定性错误。
 */
enum class AgentErrorCode(val retryable: Boolean) {
    RETRYABLE_TIMEOUT(true),
    NETWORK_UNAVAILABLE(true),
    TOOL_FAILED(true),
    AUTH_FAILED(false),
    BUDGET_EXCEEDED(false),
    NO_PERMISSION(false)
}

data class AgentError(val code: AgentErrorCode, val message: String)