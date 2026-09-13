package io.legado.app.ai.source

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 书源可用性检测：对每个源做一次最小搜索请求，返回可用/失败与原因。
 *
 * 线程模型（真机"检测到一半卡死/疑似崩溃"的根因修复）：
 * 搜索会走书源规则解析（含 Rhino JS），必须整体放到 [Dispatchers.IO]；
 * 之前只在调用方协程（主线程）里跑，295 个源时主线程被占满 → 界面无响应、点「停止」也卡住。
 */
object SourceHealth {

    data class Result(
        val source: BookSource,
        val ok: Boolean,
        val latencyMs: Long,
        val reason: String? = null
    )

    private const val TIMEOUT_MS = 10_000L

    /** 默认并发（真机反馈并发 4 太慢，默认提到 8） */
    const val DEFAULT_CONCURRENCY = 8

    /** 分块大小：避免一次性起 N 个协程（295 个源时的调度与内存压力），并让取消更及时 */
    private const val CHUNK_SIZE = 32

    /** 单源检测：网络 + 规则解析全部在 IO 线程执行 */
    suspend fun test(source: BookSource): Result = withContext(Dispatchers.IO) {
        if (source.searchUrl.isNullOrBlank()) {
            return@withContext Result(source, false, 0L, "未配置搜索 URL")
        }
        val start = System.currentTimeMillis()
        val r = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching { WebBook.searchBookAwait(source, "我的", 1) }
        }
        val cost = System.currentTimeMillis() - start
        when {
            r == null -> Result(source, false, cost, "超时（>${TIMEOUT_MS / 1000}s）")
            r.isSuccess -> Result(source, true, cost, null)
            else -> Result(
                source, false, cost,
                r.exceptionOrNull()?.localizedMessage ?: "请求失败"
            )
        }
    }

    /**
     * 批量检测：分块 + 并发，带进度与逐条结果回调。
     * [limit] <= 0 表示检测全部；[onResult] 的 index 与 [sources] 下标对齐（调用方可实时回填列表）。
     */
    suspend fun testAll(
        sources: List<BookSource>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        limit: Int = 0,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onResult: (index: Int, result: Result) -> Unit = { _, _ -> }
    ): List<Result> {
        val targets = if (limit <= 0) sources else sources.take(limit)
        if (targets.isEmpty()) return emptyList()
        val results = MutableList(targets.size) { Result(targets[it], false, 0L, "未检测") }
        var done = 0
        var from = 0
        while (from < targets.size) {
            val to = minOf(from + CHUNK_SIZE, targets.size)
            coroutineScope {
                val sem = Semaphore(concurrency)
                (from until to).map { i ->
                    async {
                        sem.withPermit {
                            ensureActive() // 取消更及时：每源开始前检查一次
                            val r = test(targets[i])
                            results[i] = r
                            done++
                            onProgress(done, targets.size)
                            onResult(i, r)
                        }
                    }
                }.awaitAll()
            }
            from = to
        }
        return results
    }

    fun allSources(): List<BookSource> = appDb.bookSourceDao.allEnabled
}
