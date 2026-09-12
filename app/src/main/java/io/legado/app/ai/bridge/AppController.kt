package io.legado.app.ai.bridge

/**
 * Agent 导航目标：通知宿主 App 执行真实跳转（由 AgentHubActivity 消费）。
 */
sealed class AppNav {
    /** 打开阅读界面；bookUrl 非空则直接定位，否则按 bookName 查书架 */
    data class OpenBook(val bookUrl: String?, val bookName: String?) : AppNav()
    /** 进入全局搜索并填入关键词 */
    data class GlobalSearch(val keyword: String) : AppNav()
    /** 打开书架列表 */
    object ToBookshelf : AppNav()
}

/**
 * 全软件动作总线：向 Agent 暴露「读写 App 数据」的能力。
 * 只读能力见 BookFetcher/ChapterReader/BookSourceAnalyzer；导航经 [AppNav] 由宿主消费。
 */
interface AppController {
    suspend fun listShelf(keyword: String?): List<Map<String, Any>>
    suspend fun locateBook(bookName: String): Map<String, Any>      // 空 map 表示未入架
    suspend fun removeFromShelf(bookName: String): Map<String, Any>


    /** 书架：把搜索结果加入书架（写库） */
    suspend fun addToShelf(book: Map<String, Any?>): Map<String, Any>
    /** 书源：启用/禁用（写库） */
    suspend fun enableSource(url: String, enabled: Boolean): Map<String, Any>

    /** 设置：读取一组受控设置项 */
    suspend fun getSettings(): Map<String, Any>

    /** 设置：写入受白名单控制的设置项（越界返回 error） */
    suspend fun setSetting(key: String, value: String): Map<String, Any>

    /** 替换净化：读取规则列表（可按名称/分组过滤） */
    suspend fun listReplaceRules(keyword: String?): List<Map<String, Any>>

    /** 替换净化：新增或更新规则（写库） */
    suspend fun upsertReplaceRule(rule: Map<String, Any?>): Map<String, Any>

    /** 替换净化：删除规则（写库） */
    suspend fun deleteReplaceRule(id: Long): Map<String, Any>

    /** 替换净化：启用/禁用规则（写库） */
    suspend fun enableReplaceRule(id: Long, enabled: Boolean): Map<String, Any>

    /** 书源：删除（写库） */
    suspend fun deleteSource(url: String): Map<String, Any>}