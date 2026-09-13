package io.legado.app.ai.bridge

import io.legado.app.data.appDb
import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.putPrefBoolean
import splitties.init.appCtx
import io.legado.app.constant.BookType
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.okHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AppController] 默认实现：直接操作 appDb 书架数据。
 */
class DefaultAppController : AppController {

    override suspend fun listShelf(keyword: String?): List<Map<String, Any>> =
        withContext(Dispatchers.IO) {
            val dao = appDb.bookDao
            // 注意：不能用 LiveData.value（无活跃观察者时恒为 null），这里取全量后内存过滤
            val books = if (keyword.isNullOrBlank()) {
                dao.all
            } else {
                dao.all.filter { b ->
                    b.name.contains(keyword, ignoreCase = true) ||
                        (b.author ?: "").contains(keyword, ignoreCase = true)
                }
            }
            books.map {
                mapOf(
                    "name" to it.name,
                    "author" to it.author.orEmpty(),
                    "bookUrl" to it.bookUrl,
                    "chapter" to it.durChapterTitle.orEmpty(),
                    "progressIndex" to it.durChapterIndex,
                    "progressPos" to it.durChapterPos
                )
            }
        }

    override suspend fun locateBook(bookName: String): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val book = appDb.bookDao.findByName(bookName).firstOrNull()
            if (book == null) {
                emptyMap()
            } else {
                mapOf(
                    "name" to book.name,
                    "bookUrl" to book.bookUrl,
                    "author" to book.author.orEmpty()
                )
            }
        }

    override suspend fun removeFromShelf(bookName: String): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val hit = appDb.bookDao.findByName(bookName).firstOrNull()
            if (hit == null) {
                mapOf("ok" to false, "message" to "书架中未找到《$bookName》")
            } else {
                appDb.bookDao.delete(hit)
                mapOf("ok" to true, "message" to "已将《${hit.name}》移出书架")
            }
        }


    override suspend fun addToShelf(book: Map<String, Any?>): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val bookUrl = book["bookUrl"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return@withContext mapOf("ok" to false, "message" to "缺少 bookUrl")
            val dao = appDb.bookDao
            dao.getBook(bookUrl)?.let {
                return@withContext mapOf(
                    "ok" to true,
                    "alreadyExists" to true,
                    "message" to "《${it.name}》已在书架中"
                )
            }
            val sb = SearchBook(
                name = book["name"]?.toString().orEmpty(),
                author = book["author"]?.toString().orEmpty(),
                bookUrl = bookUrl,
                origin = book["origin"]?.toString().orEmpty(),
                originName = book["originName"]?.toString().orEmpty(),
                tocUrl = book["tocUrl"]?.toString().orEmpty(),
                kind = book["kind"]?.toString(),
                coverUrl = book["coverUrl"]?.toString(),
                intro = book["intro"]?.toString(),
                type = (book["type"] as? Number)?.toInt() ?: BookType.text
            )
            val entity = sb.toBook()
            if (entity.name.isBlank()) {
                return@withContext mapOf("ok" to false, "message" to "缺少书名，无法加入书架")
            }
            dao.insert(entity)
            mapOf(
                "ok" to true,
                "alreadyExists" to false,
                "message" to "已加入书架：《${entity.name}》",
                "bookUrl" to bookUrl
            )
        }
    override suspend fun enableSource(url: String, enabled: Boolean): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val dao = appDb.bookSourceDao
            val source = dao.getBookSource(url)
            if (source == null) {
                mapOf("ok" to false, "message" to "书源不存在: $url")
            } else if (source.enabled == enabled) {
                mapOf("ok" to true, "message" to "书源《${source.bookSourceName}》已是" + if (enabled) "启用" else "禁用" + "状态")
            } else {
                dao.update(source.copy(enabled = enabled))
                mapOf("ok" to true, "message" to "已" + if (enabled) "启用" else "禁用" + "《${source.bookSourceName}》")
            }
        }

    override suspend fun getSettings(): Map<String, Any> =
        withContext(Dispatchers.IO) {
            mapOf<String, Any>(
                "nightTheme" to AppConfig.isNightTheme,
                "eInk" to AppConfig.isEInkMode,
                "showRss" to AppConfig.showRSS,
                "threadCount" to AppConfig.threadCount,
                "importBookPath" to AppConfig.importBookPath.orEmpty(),
                "themeMode" to AppConfig.themeMode.orEmpty(),
                "chineseConverterType" to AppConfig.chineseConverterType,
                "ttsSpeechRate" to AppConfig.ttsSpeechRate,
                "readBrightness" to AppConfig.readBrightness,
                "showUnread" to AppConfig.showUnread,
                "bookshelfLayout" to AppConfig.bookshelfLayout,
                "bookGroupStyle" to AppConfig.bookGroupStyle,
                "recordLog" to AppConfig.recordLog,
                "readUrlInBrowser" to AppConfig.readUrlInBrowser,
            )
        }

    override suspend fun setSetting(key: String, value: String): Map<String, Any> =
        withContext(Dispatchers.IO) {
            // 布尔值兜底解析：接受 "true"/"True"/"1"
            fun parseBool(): Boolean =
                value.trim().equals("true", ignoreCase = true) || value.trim() == "1"
            when (key) {
                "nightTheme" -> {
                    AppConfig.isNightTheme = parseBool()
                    mapOf("ok" to true, "key" to key, "value" to value)
                }
                "threadCount" -> {
                    val int = value.toIntOrNull()
                    if (int == null || int !in 1..32) {
                        mapOf("ok" to false, "message" to "threadCount 需为 1..32 的整数")
                    } else {
                        AppConfig.threadCount = int
                        mapOf("ok" to true, "key" to key, "value" to value)
                    }
                }
                "$PreferKey.showRss" -> {
                    appCtx.putPrefBoolean(PreferKey.showRss, parseBool())
                    mapOf("ok" to true, "key" to key, "value" to value)
                }
                "themeMode" -> {
                    val v = value.trim()
                    if (v !in listOf("0", "1", "2", "3")) {
                        mapOf("ok" to false, "message" to "themeMode 需为 0..3（0 跟随系统/1 日/2 夜/3 墨水屏）")
                    } else {
                        AppConfig.themeMode = v
                        mapOf("ok" to true, "key" to key, "value" to v)
                    }
                }
                "eInk" -> {
                    AppConfig.isEInkMode = parseBool()
                    mapOf("ok" to true, "key" to key, "value" to value)
                }
                "chineseConverterType" -> {
                    val int = value.toIntOrNull()
                    if (int == null || int !in 0..2) {
                        mapOf("ok" to false, "message" to "chineseConverterType 需为 0..2")
                    } else {
                        AppConfig.chineseConverterType = int
                        mapOf("ok" to true, "key" to key, "value" to value)
                    }
                }
                "ttsSpeechRate" -> {
                    val int = value.toIntOrNull()
                    if (int == null || int !in 1..100) {
                        mapOf("ok" to false, "message" to "ttsSpeechRate 需为 1..100")
                    } else {
                        AppConfig.ttsSpeechRate = int
                        mapOf("ok" to true, "key" to key, "value" to value)
                    }
                }
                "showUnread" -> {
                    AppConfig.showUnread = parseBool()
                    mapOf("ok" to true, "key" to key, "value" to value)
                }
                "bookshelfLayout" -> {
                    val int = value.toIntOrNull()
                    if (int == null || int !in 0..3) {
                        mapOf("ok" to false, "message" to "bookshelfLayout 需为 0..3")
                    } else {
                        AppConfig.bookshelfLayout = int
                        mapOf("ok" to true, "key" to key, "value" to value)
                    }
                }
                "bookGroupStyle" -> {
                    val int = value.toIntOrNull()
                    if (int == null || int !in 0..2) {
                        mapOf("ok" to false, "message" to "bookGroupStyle 需为 0..2")
                    } else {
                        AppConfig.bookGroupStyle = int
                        mapOf("ok" to true, "key" to key, "value" to value)
                    }
                }
                "recordLog" -> {
                    AppConfig.recordLog = parseBool()
                    mapOf("ok" to true, "key" to key, "value" to value)
                }
                "readUrlInBrowser" -> {
                    AppConfig.readUrlInBrowser = parseBool()
                    mapOf("ok" to true, "key" to key, "value" to value)
                }
                else -> mapOf("ok" to false, "message" to "不支持的设置项: $key")
            }
        }

    // ---------- 替换净化（写操作，均经工具层确认） ----------

    override suspend fun listReplaceRules(keyword: String?): List<Map<String, Any>> =
        withContext(Dispatchers.IO) {
            val rules = appDb.replaceRuleDao.all.filter {
                keyword.isNullOrBlank() ||
                    it.name.contains(keyword, true) ||
                    (it.group ?: "").contains(keyword, true)
            }
            rules.take(200).map {
                mapOf(
                    "id" to it.id,
                    "name" to it.name,
                    "group" to it.group.orEmpty(),
                    "enabled" to it.isEnabled,
                    "isRegex" to it.isRegex,
                    "scopeContent" to it.scopeContent,
                    "pattern" to it.pattern.take(120),
                    "replacement" to it.replacement.take(120)
                )
            }
        }

    override suspend fun upsertReplaceRule(rule: Map<String, Any?>): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val dao = appDb.replaceRuleDao
            val id = (rule["id"] as? Number)?.toLong()
                ?: rule["id"]?.toString()?.toLongOrNull() ?: 0L
            val name = rule["name"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return@withContext mapOf("ok" to false, "message" to "缺少规则名称 name")
            val pattern = rule["pattern"]?.toString().orEmpty()
            if (pattern.isBlank()) {
                return@withContext mapOf("ok" to false, "message" to "缺少匹配 pattern")
            }
            val replacement = rule["replacement"]?.toString().orEmpty()
            val existing = if (id > 0) dao.findById(id) else null
            val obj = if (existing != null) {
                existing.copy(
                    name = name,
                    pattern = pattern,
                    replacement = replacement,
                    group = rule["group"]?.toString() ?: existing.group,
                    isRegex = rule["isRegex"]?.let { looseBool(it) } ?: existing.isRegex,
                    isEnabled = rule["enabled"]?.let { looseBool(it) } ?: existing.isEnabled,
                    scopeContent = rule["scopeContent"]?.let { looseBool(it) } ?: existing.scopeContent
                )
            } else {
                ReplaceRule(
                    name = name,
                    pattern = pattern,
                    replacement = replacement,
                    group = rule["group"]?.toString(),
                    isRegex = rule["isRegex"]?.let { looseBool(it) } ?: true,
                    isEnabled = rule["enabled"]?.let { looseBool(it) } ?: true,
                    scopeContent = rule["scopeContent"]?.let { looseBool(it) } ?: true
                )
            }
            if (existing == null) dao.insert(obj) else dao.update(obj)
            ContentProcessor.upReplaceRules()
            mapOf(
                "ok" to true,
                "id" to obj.id,
                "action" to if (existing == null) "insert" else "update"
            )
        }

    override suspend fun deleteReplaceRule(id: Long): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val rule = appDb.replaceRuleDao.findById(id)
                ?: return@withContext mapOf("ok" to false, "message" to "规则不存在: $id")
            appDb.replaceRuleDao.delete(rule)
            ContentProcessor.upReplaceRules()
            mapOf("ok" to true, "message" to "已删除规则《${rule.name}》")
        }

    override suspend fun enableReplaceRule(id: Long, enabled: Boolean): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val rule = appDb.replaceRuleDao.findById(id)
                ?: return@withContext mapOf("ok" to false, "message" to "规则不存在: $id")
            appDb.replaceRuleDao.update(rule.copy(isEnabled = enabled))
            ContentProcessor.upReplaceRules()
            mapOf(
                "ok" to true,
                "message" to "已" + (if (enabled) "启用" else "禁用") + "《${rule.name}》"
            )
        }

    override suspend fun deleteSource(url: String): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val source = appDb.bookSourceDao.getBookSource(url)
                ?: return@withContext mapOf("ok" to false, "message" to "书源不存在: $url")
            appDb.bookSourceDao.delete(source)
            mapOf("ok" to true, "message" to "已删除书源《${source.bookSourceName}》")
        }

    private fun looseBool(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is String -> v.trim().equals("true", true) || v.trim() == "1"
        is Number -> v.toInt() != 0
        else -> false
    }
    override suspend fun addToShelfBatch(books: List<Map<String, Any?>>): Map<String, Any> =
        withContext(Dispatchers.IO) {
            var ok = 0
            var exists = 0
            var failed = 0
            val names = ArrayList<String>()
            // 审计修复：原实现 take(20) 静默丢弃多余条目却仍返回 ok=true，
            // 现在只做防御性上限（200）并把被截断的数量显式回报
            val capped = books.take(200)
            capped.forEach { b ->
                val r = addToShelf(b)
                when {
                    r["ok"] != true -> failed++
                    r["alreadyExists"] == true -> exists++
                    else -> { ok++; names.add(b["name"]?.toString().orEmpty()) }
                }
            }
            mapOf(
                "ok" to true,
                "added" to ok,
                "exists" to exists,
                "failed" to failed,
                "addedNames" to names,
                "skipped" to (books.size - capped.size)
            )
        }

    override suspend fun importReplaceRules(source: String): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val text = runCatching {
                if (source.startsWith("http", true)) downloadText(source) else source
            }.getOrElse {
                return@withContext mapOf("ok" to false, "message" to "下载失败：${it.localizedMessage}")
            }
            val t = text.trim()
            if (t.isEmpty()) return@withContext mapOf("ok" to false, "message" to "内容为空")
            val rules = runCatching {
                GSON.fromJsonArray<ReplaceRule>(t).getOrThrow()
            }.getOrElse {
                return@withContext mapOf("ok" to false, "message" to "解析失败（需为替换规则 JSON 数组）")
            }
            if (rules.isEmpty()) return@withContext mapOf("ok" to false, "message" to "未解析到规则")
            // 审计修复：ReplaceRuleDao.insert 是 OnConflictStrategy.REPLACE，
            // 外部 JSON 若带 id 会直接覆盖本地同 id 规则（静默改掉用户规则）→ 导入一律作为新规则插入
            val sanitized = rules.map { it.copy(id = 0) }
            appDb.replaceRuleDao.insert(*sanitized.toTypedArray())
            ContentProcessor.upReplaceRules()
            mapOf("ok" to true, "imported" to rules.size)
        }

    override suspend fun resetSetting(key: String): Map<String, Any> =
        withContext(Dispatchers.IO) {
            when (key) {
                "nightTheme" -> { AppConfig.isNightTheme = false; ok(key) }
                "themeMode" -> { AppConfig.themeMode = "0"; ok(key) }
                "eInk" -> { AppConfig.isEInkMode = false; ok(key) }
                "threadCount" -> { AppConfig.threadCount = 16; ok(key) }
                "ttsSpeechRate" -> { AppConfig.ttsSpeechRate = 5; ok(key) }
                "chineseConverterType" -> { AppConfig.chineseConverterType = 0; ok(key) }
                "showUnread" -> { AppConfig.showUnread = true; ok(key) }
                "bookshelfLayout" -> { AppConfig.bookshelfLayout = 0; ok(key) }
                "bookGroupStyle" -> { AppConfig.bookGroupStyle = 0; ok(key) }
                "recordLog" -> { AppConfig.recordLog = false; ok(key) }
                "readUrlInBrowser" -> { AppConfig.readUrlInBrowser = false; ok(key) }
                else -> mapOf("ok" to false, "message" to "不支持的设置项: $key")
            }
        }

    private fun ok(key: String): Map<String, Any> =
        mapOf("ok" to true, "key" to key, "message" to "已恢复默认值")
    /**
     * 下载文本（审计 A-6 / M-3 / M-4）。url 来自模型生成的工具参数，按不可信输入处理：
     * ① 仅允许 http/https（严格 scheme 解析，不用 startsWith 前缀判断）；
     * ② 独立 client 显式超时（不依赖基座共享 client 的隐式配置）；
     * ③ 先看 Content-Length，再流式限长读取，上限 2MB，避免 OOM。
     */
    private fun downloadText(url: String): String {
        // 2026-09：上限由 2MB 提到 8MB（替换规则集合也可能较大）
        val maxBytes = 8L * 1024 * 1024
        val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
        require(scheme == "http" || scheme == "https") { "仅支持 http/https 地址" }
        val client = okHttpClient.newBuilder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(url).get().build()
        return client.newCall(req).execute().use { resp ->
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
}