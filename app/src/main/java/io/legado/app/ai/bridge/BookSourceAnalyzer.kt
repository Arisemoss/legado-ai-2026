package io.legado.app.ai.bridge

/**
 * 书源分析领域桥接口（只读）。
 * 提供书源列表、规则结构与连通性诊断，供 AI 分析书源问题、提出修复建议。
 */
interface BookSourceAnalyzer {
    /** 已启用书源列表 */
    suspend fun list(): List<Map<String, Any>>
    /** 指定书源的结构化规则 */
    suspend fun rules(url: String): Map<String, Any>
    /** 指定书源的连通性/规则诊断结果 */
    suspend fun test(url: String): Map<String, Any>
}