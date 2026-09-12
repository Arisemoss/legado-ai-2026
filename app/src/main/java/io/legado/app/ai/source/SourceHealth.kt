package io.legado.app.ai.source

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** 书源可用性检测：对每个源做一次最小搜索请求，返回可用/失败与原因。 */
object SourceHealth {

    data class Result(
        val source: BookSource,
        val ok: Boolean,
        val latencyMs: Long,
        val reason: String? = null
    )

    private const val TIMEOUT_MS = 12_000L

    suspend fun test(source: BookSource): Result {
        if (source.searchUrl.isNullOrBlank()) {
            return Result(source, false, 0L, "未配置搜索 URL")
        }
        val start = System.currentTimeMillis()
        val r = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching { WebBook.searchBookAwait(source, "我的", 1) }
        }
        val cost = System.currentTimeMillis() - start
        return when {
            r == null -> Result(source, false, cost, "超时（>${TIMEOUT_MS / 1000}s）")
            r.isSuccess -> Result(source, true, cost, null)
            else -> Result(
                source, false, cost,
                r.exceptionOrNull()?.localizedMessage ?: "请求失败"
            )
        }
    }

    /** 并发批量检测（默认并发 4、最多 50 个），带进度回调 */
    suspend fun testAll(
        sources: List<BookSource>,
        concurrency: Int = 4,
        limit: Int = 50,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<Result> = coroutineScope {
        val targets = sources.take(limit)
        val sem = Semaphore(concurrency)
        var done = 0
        targets.map { src ->
            async {
                sem.withPermit {
                    val r = test(src)
                    done++
                    onProgress(done, targets.size)
                    r
                }
            }
        }.awaitAll()
    }

    fun allSources(): List<BookSource> = appDb.bookSourceDao.allEnabled
}
