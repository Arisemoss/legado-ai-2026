package io.legado.app.ai.model

/**
 * Agent 错误码。`retryable=true` 表示该错误可重试（如网络/超时），其余为不可重试的确定性错误。
 */
enum class AgentErrorCode(val retryable: Boolean) {
    RETRYABLE_TIMEOUT(true),
    NETWORK_UNAVAILABLE(true),

    /** 限流（HTTP 429）：瞬态错误，退避后应可重试 */
    RATE_LIMITED(true),

    /** 工具确定性失败（参数/目标/解析问题）：重试无意义（审计 B-3） */
    TOOL_FAILED(false),
    AUTH_FAILED(false),
    BUDGET_EXCEEDED(false),
    NO_PERMISSION(false)
}

data class AgentError(val code: AgentErrorCode, val message: String)

/**
 * 非 2xx 的 HTTP 状态码 → 错误码分类。
 *
 * 修复前：非 2xx 一律映射 [AgentErrorCode.AUTH_FAILED]（不可重试），
 * 导致 429 限流与 5xx 服务端错误被谎报为「鉴权失败」且永不重试（指数退避形同虚设）。
 *
 * 分类：
 * - 401/403：鉴权失败（不可重试，需用户改 Key/权限）
 * - 429：限流（可重试）
 * - 5xx：服务端错误（可重试）
 * - 其余 4xx：确定性失败（不可重试）
 */
internal fun httpStatusToErrorCode(code: Int): AgentErrorCode = when {
    code == 401 || code == 403 -> AgentErrorCode.AUTH_FAILED
    code == 429 -> AgentErrorCode.RATE_LIMITED
    code in 500..599 -> AgentErrorCode.NETWORK_UNAVAILABLE
    else -> AgentErrorCode.TOOL_FAILED
}