package io.legado.app.ai.source

import io.legado.app.ai.log.AiLog
import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
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

    const val DEFAULT_PAGE = "http://yuedu.miaogongzi.net/gx.html"

    data class Entry(val title: String, val src: String)

    data class ImportResult(val total: Int, val inserted: Int, val failed: Int)

    private val linkRegex = Regex("yuedu://booksource/importonline\\?src=([^\"'\\s&<>]+)")

    /** 抓聚合页并解析出可导入的书源地址列表 */
    fun fetchEntries(pageUrl: String = DEFAULT_PAGE): Result<List<Entry>> = runCatching {
        val req = Request.Builder().url(pageUrl).get().build()
        val html = okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        linkRegex.findAll(html).map { m ->
            val raw = m.groupValues[1]
            val src = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            Entry(titleOf(src), src)
        }.distinctBy { it.src }.toList()
    }

    /** 下载单个书源地址（JSON 数组 / 每行一个 JSON 的 TXT）并入库 */
    suspend fun importUrl(src: String): Result<Int> = runCatching {
        val req = Request.Builder().url(src).get().build()
        val text = okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        val sources = parseSources(text)
        if (sources.isEmpty()) throw RuntimeException("未解析到书源")
        SourceHelp.insertBookSource(*sources.toTypedArray())
        AiLog.i("SourceHub", "导入 ${sources.size} 条 ← ${titleOf(src)}")
        sources.size
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
}
