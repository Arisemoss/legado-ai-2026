package io.legado.app.ai.bridge

import io.legado.app.data.appDb
import com.google.gson.Gson
import io.legado.app.ai.log.AiLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.BookHelp
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

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
            val sources = appDb.bookSourceDao.allEnabled
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
                                WebBook.searchBookAwait(
                                    bookSource = source,
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
                        } catch (e: CancellationException) {
                            // 用户停止/外层取消：必须向上传播，不能谎报成「该源 0 条」
                            throw e
                        } catch (_: Exception) {
                            AiLog.w("Fetch", "源《${source.bookSourceName}》失败")
                            emptyList<SearchBook>()
                        }
                    }
                }.awaitAll()
            }
            val result = found.flatten()
                // 审计 M-5：按「书名+来源」去重——只按书名会误杀同名不同源的书
                .filter { seen.add(it.name + "|" + it.origin) }
                .take(limit)
                .map { book ->
                    // 字段必须齐全：add_book_to_shelf 需要 bookUrl/origin/originName/tocUrl 等，
                    // 只给「书名+作者」会让模型无从下手（搜索结果无法直接加入书架）
                    mapOf(
                        "name" to book.name,
                        "author" to book.author,
                        "bookUrl" to book.bookUrl,
                        "origin" to book.origin,
                        "originName" to book.originName.ifBlank { book.origin },
                        "tocUrl" to book.tocUrl,
                        "coverUrl" to book.coverUrl.orEmpty(),
                        "intro" to book.intro.orEmpty().take(120),
                        "kind" to book.kind.orEmpty(),
                        "type" to book.type,
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
                val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
                if (chapters.isEmpty()) return@withContext null

                val chapter = resolveChapter(book, chapters, chapterTitle)
                    ?: return@withContext null

                var content = BookHelp.getContent(book, chapter)
                if (content.isNullOrBlank()) {
                    val source = appDb.bookSourceDao.getBookSource(book.origin)
                    content = if (source != null) {
                        try {
                            // 联网抓取限时 15s，防止慢源拖死整个工具调用
                            AiLog.i(
                                "Chapter", "缓存未命中，联网抓取《${book.name}》·${chapter.title}"
                            )
                            withTimeoutOrNull(15_000L) {
                                WebBook.getContentAwait(
                                    bookSource = source,
                                    book = book,
                                    bookChapter = chapter
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e // 取消不是「抓取失败」
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    private fun resolveBook(bookName: String): Book? =
        appDb.bookDao.findByName(bookName).firstOrNull()

    /**
     * 章节定位（审计修复）：
     * - 指定了章节名但匹配不到时返回 null，不再静默回退到「当前阅读章节」——
     *   否则工具会把第 1 章正文当成用户要的那一章上报，模型据此总结出完全无关的内容；
     * - 匹配顺序 精确 → 前缀 → 包含，避免「第1章」命中「第10章 归来」。
     */
    private fun resolveChapter(
        book: Book,
        chapters: List<BookChapter>,
        chapterTitle: String?
    ): BookChapter? {
        val key = chapterTitle?.trim()
        if (!key.isNullOrBlank()) {
            return chapters.firstOrNull { it.title.trim() == key }
                ?: chapters.firstOrNull { it.title.trim().startsWith(key) }
                ?: chapters.firstOrNull { it.title.contains(key) }
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
        appDb.bookSourceDao.allEnabled
            .map {
                mapOf(
                    "name" to it.bookSourceName,
                    "url" to it.bookSourceUrl,
                    "enabled" to it.enabled
                )
            }
    }

    override suspend fun rules(url: String): Map<String, Any> = withContext(Dispatchers.IO) {
        val source = appDb.bookSourceDao.getBookSource(url)
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
        val source = appDb.bookSourceDao.getBookSource(url)
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
                WebBook.searchBookAwait(
                    bookSource = source,
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.net.UnknownHostException) {
            // 审计修复：域名解析失败是「真不可达」，原先一律报「网络可达但搜索失败」，
            // 会把模型引向「规则坏了去改 searchUrl」的错误方向
            mapOf(
                "url" to url, "status" to "error", "reachable" to false,
                "name" to name, "enabled" to source.enabled,
                "message" to ("域名无法解析（网络或书源地址失效）: " + (e.localizedMessage ?: "unknown"))
            )
        } catch (e: java.net.ConnectException) {
            mapOf(
                "url" to url, "status" to "error", "reachable" to false,
                "name" to name, "enabled" to source.enabled,
                "message" to ("连接被拒绝（服务端不可用）: " + (e.localizedMessage ?: "unknown"))
            )
        } catch (e: java.net.NoRouteToHostException) {
            mapOf(
                "url" to url, "status" to "error", "reachable" to false,
                "name" to name, "enabled" to source.enabled,
                "message" to ("网络不可达: " + (e.localizedMessage ?: "unknown"))
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