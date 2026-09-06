package io.legado.app.ai.bridge

/**
 * 书源规则写回领域桥接口（读写）。
 * 属于写操作——**必须**经 pending_confirm 二次确认后才可调用。
 */
interface SourceRuleWriter {
    /** 将建议的规则变更写回指定书源，返回是否成功 */
    suspend fun apply(url: String, changes: Map<String, String>): Boolean
}