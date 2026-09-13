package io.legado.app.ai.source

import io.legado.app.ai.log.AiLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLDecoder

/**
 * 书源聚合导入（一键获取书源）：
 * 抓取「阅读书源」聚合页 → 解析其中的 yuedu://booksource/importonline?src=... 深链
 * → 下载 JSON/TXT 书源 → 复用 base 的 [SourceHelp] 批量入库。
 *
 * 默认页：yuedu.miaogongzi.net/gx.html（喵公子）；可在设置中自定义页面地址。
 * 提示：书源为第三方内容，请自行确认来源合法性。
 */
object BookSourceHub {

    /** 审计 M-2：默认页由明文 HTTP 改为 HTTPS（2026-09 实测 https 返回 200），避免聚合页被劫持注入书源 */
    const val DEFAULT_PAGE = "https://yuedu.miaogongzi.net/gx.html"

    data class Entry(val title: String, val src: String)

    data class ImportResult(val total: Int, val inserted: Int, val failed: Int)

    private val linkRegex = Regex("yuedu://booksource/importonline\\?src=([^\"'\\s&<>]+)")

    /**
     * 抓聚合页并解析出可导入的书源地址列表。
     * 注意：okHttp 同步 execute 是阻塞调用，必须切到 IO 线程，
     * 否则在主线程调用会抛 NetworkOnMainThreadException（真机已复现）。
     */
    suspend fun fetchEntries(pageUrl: String = DEFAULT_PAGE): Result<List<Entry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val html = downloadTextBlocking(pageUrl)
                linkRegex.findAll(html).map { m ->
                    val raw = m.groupValues[1]
                    val src = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                    Entry(titleOf(src), src)
                }.distinctBy { it.src }.toList()
            }
        }

    /** 下载单个书源地址（JSON 数组 / 每行一个 JSON 的 TXT）并入库（IO 线程） */
    suspend fun importUrl(src: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
        val text = downloadTextBlocking(src)
        val sources = parseSources(text)
        if (sources.isEmpty()) throw RuntimeException("未解析到书源")
        SourceHelp.insertBookSource(*sources.toTypedArray())
        AiLog.i("SourceHub", "导入 ${sources.size} 条 ← ${titleOf(src)}")
        sources.size
        }
    }

    /** 批量导入全部条目 */
    suspend fun importAll(
        entries: List<Entry>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ImportResult {
        var ok = 0
        var failed = 0
        entries.forEachIndexed { index, entry ->
            val r = importUrl(entry.src)
            if (r.isSuccess) ok++ else failed++
            onProgress(index + 1, entries.size)
        }
        return ImportResult(entries.size, ok, failed)
    }

    /** 文本 → 书源列表：支持 JSON 数组与逐行 JSON */
    private fun parseSources(text: String): List<BookSource> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        if (t.startsWith("[")) {
            return runCatching {
                GSON.fromJsonArray<BookSource>(t).getOrThrow().filter { it.bookSourceUrl.isNotBlank() }
            }.getOrDefault(emptyList())
        }
        val out = ArrayList<BookSource>()
        t.lineSequence().forEach { line ->
            val l = line.trim()
            if (l.startsWith("{")) {
                runCatching {
                    GSON.fromJsonObject<BookSource>(l).getOrThrow()
                }.getOrNull()?.takeIf { it.bookSourceUrl.isNotBlank() }?.let { out.add(it) }
            }
        }
        return out
    }

    private fun titleOf(src: String): String =
        runCatching {
            val clean = src.substringBefore('?')
            val name = clean.substringAfterLast('/')
            URLDecoder.decode(name, "UTF-8").ifBlank { clean }
        }.getOrDefault(src)

    /** 预扫描结果：条目内含书源数量、最后更新时间，以及与本地库的比对状态 */
    data class ScanResult(
        val entry: Entry,
        val total: Int,
        val newestUpdate: Long,
        val existsLocal: Boolean,
        val canUpdate: Boolean,
        val error: String? = null
    )

    /** 下载并解析单条书源（供导入与预扫描共用），内部切 IO 线程 */
    suspend fun downloadText(src: String): String =
        withContext(Dispatchers.IO) { downloadTextBlocking(src) }

    /**
     * 阻塞式下载：调用方必须已处于 IO 线程。
     * 审计 A-6 同伴加固：仅 http/https + 大小上限（流式读取，防 OOM）。
     * 上限 2026-09 由 2MB 提到 16MB：真机反馈聚合站多个书源集合超过 2MB 被误判失败；
     * 再大则 JSON 解析的内存峰值风险偏高（真机 heap 约 256MB）。
     */
    private fun downloadTextBlocking(src: String): String {
        val maxBytes = 16L * 1024 * 1024
        val scheme = runCatching { java.net.URI(src).scheme?.lowercase() }.getOrNull()
        require(scheme == "http" || scheme == "https") { "仅支持 http/https 地址" }
        val req = Request.Builder().url(src).get().build()
        return okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val body = resp.body ?: throw RuntimeException("空响应")
            if (body.contentLength() > maxBytes) throw RuntimeException("内容过大（>${maxBytes / 1024}KB）")
            val source = body.source()
            val buf = okio.Buffer()
            var total = 0L
            while (true) {
                val read = source.read(buf, 8192L)
                if (read == -1L) break
                total += read
                if (total > maxBytes) throw RuntimeException("内容超过上限（>${maxBytes / 1024}KB）")
            }
            buf.readUtf8()
        }
    }

    /** 预扫描：解析条目内书源数量/更新时间，并判断本地是否已存在、是否可更新（IO 线程） */
    suspend fun scan(entry: Entry): ScanResult = withContext(Dispatchers.IO) {
        runCatching {
        val text = downloadText(entry.src)
        val list = parseSources(text)
        val newest = list.maxOfOrNull { it.lastUpdateTime } ?: 0L
        val firstUrl = list.firstOrNull()?.bookSourceUrl.orEmpty()
        val local = if (firstUrl.isBlank()) null else appDb.bookSourceDao.getBookSourcePart(firstUrl)
        ScanResult(
            entry = entry,
            total = list.size,
            newestUpdate = newest,
            existsLocal = local != null,
            canUpdate = local != null && newest > local.lastUpdateTime
        )
    }.getOrElse { e ->
        ScanResult(entry, 0, 0L, false, false, e.localizedMessage ?: e.javaClass.simpleName)
    }
    }
}
