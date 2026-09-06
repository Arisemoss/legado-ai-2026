package io.legado.app.ai.bridge

import com.google.gson.Gson
import io.legado.app.ai.log.AiLog
import io.legado.app.App
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.BookHelp
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** 桥接层共享 IO 协程域：WebBook 调用复用同一 scope，避免每次调用新建永不取消的协程域 */
private val bridgeIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * [BookFetcher] 默认实现：跨已启用书源**并行**搜索（单源 8s 超时），
 * 返回书名/作者/来源。并行化后整体耗时≈最慢单源，而非各源之和。
 */
class DefaultBookFetcher : BookFetcher {

    companion object {
        private const val MAX_SOURCES = 6
        private const val PER_SOURCE_TIMEOUT_MS = 8_000L
    }

    override suspend fun search(keyword: String, limit: Int): List<Map<String, Any>> =
        withContext(Dispatchers.IO) {
            val seen = HashSet<String>()
            // 随机抽取而非固定头部源：避免搜索结果系统性偏向 customOrder 靠前的书源
            val sources = App.db.bookSourceDao().allEnabled
                .filter { !it.searchUrl.isNullOrBlank() }
                .shuffled()
                .take(MAX_SOURCES)
            AiLog.i(
                "Fetch",
                "搜索 \"$keyword\" · ${sources.size}源并行(${sources.joinToString("、") { it.bookSourceName.orEmpty().ifBlank { it.bookSourceUrl } }})"
            )
            // 并行发起全部书源搜索，单源限时，失败静默跳过
            val startMs = System.currentTimeMillis()
            val found = coroutineScope {
                sources.map { source ->
                    async {
                        val s = System.currentTimeMillis()
                        try {
                            val r = withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                                WebBook(source).searchBookSuspend(
                                    scope = bridgeIoScope,
                                    key = keyword,
                                    page = 1
                                )
                            } ?: emptyList<SearchBook>().also {
                                AiLog.w("Fetch", "源《${source.bookSourceName}》超时(>${PER_SOURCE_TIMEOUT_MS / 1000}s)")
                            }
                            AiLog.d(
                                "Fetch",
                                "源《${source.bookSourceName}》${r.size}条 ${System.currentTimeMillis() - s}ms"
                            )
                            r
                        } catch (_: Exception) {
                            AiLog.w("Fetch", "源《${source.bookSourceName}》失败")
                            emptyList<SearchBook>()
                        }
                    }
                }.awaitAll()
            }
            val result = found.flatten()
                .filter { seen.add(it.name) }   // 按书源优先级去重
                .take(limit)
                .map { book ->
                    mapOf(
                        "name" to book.name,
                        "author" to book.author,
                        "from" to (book.originName.ifBlank { book.origin })
                    )
                }
            AiLog.i(
                "Fetch",
                "搜索完成: ${result.size}条/${System.currentTimeMillis() - startMs}ms"
            )
            result
        }

    override suspend fun recommendByName(name: String): List<Map<String, Any>> = search(name, 5)
}

/**
 * [ChapterReader] 默认实现：定位书架书籍，优先读缓存，无缓存联网抓取。
 */
class DefaultChapterReader : ChapterReader {

    override suspend fun chapter(bookName: String, chapterTitle: String?): String? =
        withContext(Dispatchers.IO) {
            try {
                val book = resolveBook(bookName)
                    ?: return@withContext null
                val chapters = App.db.bookChapterDao().getChapterList(book.bookUrl)
                if (chapters.isEmpty()) return@withContext null

                val chapter = resolveChapter(book, chapters, chapterTitle)
                    ?: return@withContext null

                var content = BookHelp.getContent(book, chapter)
                if (content.isNullOrBlank()) {
                    val source = App.db.bookSourceDao().getBookSource(book.origin)
                    content = if (source != null) {
                        try {
                            // 联网抓取限时 15s，防止慢源拖死整个工具调用
                            AiLog.i(
                                "Chapter", "缓存未命中，联网抓取《${book.name}》·${chapter.title}"
                            )
                            withTimeoutOrNull(15_000L) {
                                WebBook(source).getContentSuspend(
                                    book = book,
                                    bookChapter = chapter,
                                    scope = bridgeIoScope
                                )
                            }
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                }
                if (content.isNullOrBlank()) {
                    AiLog.w("Chapter", "正文获取失败:《${book.name}》·${chapter.title}")
                }
                content?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

    private fun resolveBook(bookName: String): Book? =
        App.db.bookDao().findByName(bookName).firstOrNull()

    private fun resolveChapter(
        book: Book,
        chapters: List<BookChapter>,
        chapterTitle: String?
    ): BookChapter? {
        if (chapterTitle != null) {
            chapters.firstOrNull { it.title.contains(chapterTitle) }
                ?.let { return it }
        }
        return chapters.getOrNull(book.durChapterIndex) ?: chapters.firstOrNull()
    }
}

/**
 * [BookSourceAnalyzer] 默认实现：基于书源 DAO 提供的结构化只读信息。
 * 网络连通性测试在 SourceTestTool（阶段2 工具迁移）中进一步细化。
 */
class DefaultBookSourceAnalyzer : BookSourceAnalyzer {

    private companion object {
        val gson = Gson()
    }

    override suspend fun list(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        App.db.bookSourceDao().allEnabled
            .map {
                mapOf(
                    "name" to it.bookSourceName,
                    "url" to it.bookSourceUrl,
                    "enabled" to it.enabled
                )
            }
    }

    override suspend fun rules(url: String): Map<String, Any> = withContext(Dispatchers.IO) {
        val source = App.db.bookSourceDao().getBookSource(url)
        if (source == null) {
            mapOf("url" to url, "found" to false)
        } else {
            mapOf(
                "url" to url,
                "found" to true,
                "name" to source.bookSourceName,
                "enabled" to source.enabled,
                "rules" to gson.toJson(source)
            )
        }
    }

    override suspend fun test(url: String): Map<String, Any> = withContext(Dispatchers.IO) {
        val source = App.db.bookSourceDao().getBookSource(url)
        if (source == null) {
            return@withContext mapOf(
                "url" to url, "status" to "missing", "message" to "书源不存在"
            )
        }
        val name = source.bookSourceName.ifBlank { url }
        if (source.searchUrl.isNullOrBlank()) {
            return@withContext mapOf(
                "url" to url, "status" to "error", "reachable" to false,
                "name" to name, "enabled" to source.enabled,
                "message" to "书源没有配置搜索URL，无法验证网络连通性"
            )
        }
        val start = System.currentTimeMillis()
        try {
            val results = withTimeout(15_000L) {
                WebBook(source).searchBookSuspend(
                    scope = bridgeIoScope,
                    key = "我的",
                    page = 1
                )
            }
            val latency = System.currentTimeMillis() - start
            mapOf(
                "url" to url, "status" to "ok", "reachable" to true,
                "name" to name, "enabled" to source.enabled,
                "latencyMs" to latency,
                "searchCount" to results.size,
                "message" to if (results.isEmpty()) {
                    "连接正常，但未搜索到结果"
                } else {
                    "连接正常，搜索到 ${results.size} 条结果"
                }
            )
        } catch (e: TimeoutCancellationException) {
            mapOf(
                "url" to url, "status" to "error", "reachable" to false,
                "name" to name, "enabled" to source.enabled,
                "latencyMs" to (System.currentTimeMillis() - start),
                "message" to "请求超时（>15 秒）"
            )
        } catch (e: Exception) {
            mapOf(
                "url" to url, "status" to "error", "reachable" to true,
                "name" to name, "enabled" to source.enabled,
                "latencyMs" to (System.currentTimeMillis() - start),
                "message" to ("网络可达但搜索失败: " + (e.localizedMessage ?: "unknown"))
            )
        }
    }
}