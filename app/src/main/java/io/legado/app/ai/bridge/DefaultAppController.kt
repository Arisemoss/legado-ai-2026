package io.legado.app.ai.bridge

import io.legado.app.data.appDb
import io.legado.app.constant.PreferKey
import io.legado.app.utils.putPrefBoolean
import splitties.init.appCtx
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
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
    }}