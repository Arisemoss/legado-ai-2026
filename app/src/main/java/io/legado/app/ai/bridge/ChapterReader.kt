package io.legado.app.ai.bridge

/**
 * 章节阅读领域桥接口（只读）。
 * 定位书籍后优先读缓存，无缓存经 [io.legado.app.model.webBook.WebBook] 联网抓取。
 */
interface ChapterReader {
    /**
     * 读取指定书籍某章节正文。
     * @param bookName 书名（书架定位）
     * @param chapterTitle 章节标题，为空时使用当前阅读进度章节
     * @return 章节正文（已含元信息提示），找不到返回 null
     */
    suspend fun chapter(bookName: String, chapterTitle: String?): String?
}