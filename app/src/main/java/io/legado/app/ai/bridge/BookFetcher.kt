package io.legado.app.ai.bridge

/**
 * 书籍获取领域桥接口（只读）。
 * 隔离 AI 层与 [io.legado.app.model.webBook.WebBook] / Web/Cache 细节，
 * 返回标准化 Map，避免 AI 层直接依赖核心模块。
 */
interface BookFetcher {
    /** 跨最多 N 个已启用书源搜索书籍 */
    suspend fun search(keyword: String, limit: Int): List<Map<String, Any>>
    /** 按书名推荐（可命中书架或搜索） */
    suspend fun recommendByName(name: String): List<Map<String, Any>>
}