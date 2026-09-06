package io.legado.app.ai.bridge

import io.legado.app.App
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AppController] 默认实现：直接操作 App.db 书架数据。
 */
class DefaultAppController : AppController {

    override suspend fun listShelf(keyword: String?): List<Map<String, Any>> =
        withContext(Dispatchers.IO) {
            val dao = App.db.bookDao()
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
            val book = App.db.bookDao().findByName(bookName).firstOrNull()
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
            val hit = App.db.bookDao().findByName(bookName).firstOrNull()
            if (hit == null) {
                mapOf("ok" to false, "message" to "书架中未找到《$bookName》")
            } else {
                App.db.bookDao().delete(hit)
                mapOf("ok" to true, "message" to "已将《${hit.name}》移出书架")
            }
        }

    override suspend fun enableSource(url: String, enabled: Boolean): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val dao = App.db.bookSourceDao()
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
                "showRss" to AppConfig.isShowRSS,
                "threadCount" to AppConfig.threadCount,
                "importBookPath" to AppConfig.importBookPath.orEmpty()
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
                    AppConfig.isShowRSS = parseBool()
                    mapOf("ok" to true, "key" to key, "value" to value)
                }
                else -> mapOf("ok" to false, "message" to "不支持的设置项: $key")
            }
        }
}