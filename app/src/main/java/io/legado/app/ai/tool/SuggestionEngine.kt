package io.legado.app.ai.tool

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.ai.model.SuggestedAction

/**
 * 建议动作派生引擎：从工具执行结果推导「用户下一步最可能想做的事」，
 * 交给 UI 渲染成工具卡片下方的快捷按钮（见 [SuggestedAction]）。
 *
 * 设计取向：
 *  - 完全无副作用：只读动作（跳页/打开阅读）直接导航，写操作一律生成 prompt 交回 Agent；
 *  - 完全容错：结果不是合法 JSON、字段缺失、类型不符时静默返回空列表，
 *    绝不抛异常影响 [io.legado.app.ai.runtime.AgentRuntime] 的工具流水线；
 *  - 工具无关：新增工具时只需在 [build] 里补一条 when 分支，无需改动 UI 与运行时。
 */
object SuggestionEngine {

    private const val MAX_ACTIONS = 3
    private const val MAX_BOOKS = 2
    private const val NAME_CAP = 6

    /** 入口：任何异常都收敛为空列表 */
    fun suggest(toolId: String, args: Map<String, Any?>, resultText: String): List<SuggestedAction> =
        runCatching { build(toolId, args, resultText) }
            .getOrDefault(emptyList())
            .filter { it.label.isNotBlank() }
            .distinctBy { it.kind + "|" + it.payload }
            .take(MAX_ACTIONS)

    private fun build(
        toolId: String,
        args: Map<String, Any?>,
        resultText: String
    ): List<SuggestedAction> {
        val json = parse(resultText)
        return when (toolId) {
            "search_books", "recommend_books" -> searchActions(json)
            "list_shelf" -> shelfActions(json)
            "open_book" -> listOf(shelf())
            "add_book_to_shelf" -> addedActions(args)
            "batch_add_to_shelf", "remove_book" -> listOf(shelf())
            "test_sources_batch", "test_book_source", "get_source_stats" -> sourceActions(json)
            "list_book_sources", "import_book_sources", "delete_book_source",
            "analyze_book_source", "get_source_rules", "set_source_enabled",
            "suggest_source_fix" -> listOf(sources())
            "list_replace_rules", "manage_replace_rule", "import_replace_rules" -> listOf(replace())
            "list_settings", "set_setting", "reset_setting", "get_setting" -> listOf(settings())
            "read_chapter", "summarize_chapter", "plot_recap", "explain_text",
            "analyze_characters", "analyze_theme" -> readingActions(args)
            else -> emptyList()
        }
    }

    // ---------- 各工具族的动作 ----------

    /** 搜索类：把候选书直接变成「加入书架」（写操作仍需确认卡） */
    private fun searchActions(json: JsonObject?): List<SuggestedAction> {
        val books = booksOf(json)
        if (books.isEmpty()) return emptyList()
        val out = ArrayList<SuggestedAction>()
        books.take(MAX_BOOKS).forEach { out.add(addShelf(it)) }
        out.add(shelf())
        return out
    }

    /** 书架类：书架里的书已有 bookUrl，可直接一键进阅读页（只读） */
    private fun shelfActions(json: JsonObject?): List<SuggestedAction> {
        val out = ArrayList<SuggestedAction>()
        booksOf(json).take(MAX_BOOKS).forEach { b ->
            if (b.bookUrl.isNotBlank()) {
                out.add(
                    SuggestedAction(
                        label = "阅读《" + b.name.take(NAME_CAP) + "》",
                        kind = SuggestedAction.KIND_READER,
                        payload = b.bookUrl,
                        extra = b.name
                    )
                )
            }
        }
        out.add(shelf())
        return out
    }

    /** 刚加入书架：给出「立刻开读」与「回书架」 */
    private fun addedActions(args: Map<String, Any?>): List<SuggestedAction> {
        val url = args["bookUrl"]?.toString().orEmpty()
        val name = args["name"]?.toString().orEmpty()
        val out = ArrayList<SuggestedAction>()
        if (url.isNotBlank()) {
            out.add(
                SuggestedAction(
                    label = "阅读《" + name.ifBlank { "刚加入的书" }.take(NAME_CAP) + "》",
                    kind = SuggestedAction.KIND_READER,
                    payload = url,
                    extra = name
                )
            )
        }
        out.add(shelf())
        return out
    }

    /** 书源类：检测类结果里若有失效源，优先引导到健康检测/清理页 */
    private fun sourceActions(json: JsonObject?): List<SuggestedAction> {
        val bad = json?.int("bad") ?: 0
        val out = ArrayList<SuggestedAction>()
        if (bad > 0) out.add(SuggestedAction("清理失效书源($bad)", SuggestedAction.KIND_HEALTH))
        out.add(sources())
        return out
    }

    /** 阅读/分析类：不猜书，只给「打开当前书」的 prompt 与书架入口 */
    private fun readingActions(args: Map<String, Any?>): List<SuggestedAction> {
        val name = args["bookName"]?.toString().orEmpty().ifBlank { args["name"]?.toString().orEmpty() }
        val out = ArrayList<SuggestedAction>()
        out.add(
            if (name.isNotBlank()) {
                SuggestedAction("打开《" + name.take(NAME_CAP) + "》", SuggestedAction.KIND_PROMPT, "打开《$name》")
            } else {
                SuggestedAction("打开正在读的书", SuggestedAction.KIND_PROMPT, "打开我正在读的这本书")
            }
        )
        out.add(shelf())
        return out
    }

    // ---------- 基础动作 ----------

    private fun shelf() = SuggestedAction("打开书架", SuggestedAction.KIND_SHELF)

    private fun sources() = SuggestedAction("打开书源管理", SuggestedAction.KIND_SOURCES)

    private fun replace() = SuggestedAction("打开替换规则", SuggestedAction.KIND_REPLACE)

    private fun settings() =
        SuggestedAction("打开设置", SuggestedAction.KIND_SETTINGS, io.legado.app.ui.config.ConfigTag.OTHER_CONFIG)

    private fun addShelf(b: BookRef): SuggestedAction {
        val detail = buildString {
            if (b.bookUrl.isNotBlank()) append("bookUrl=").append(b.bookUrl).append("；")
            if (b.author.isNotBlank()) append("作者=").append(b.author).append("；")
            if (b.originName.isNotBlank()) append("来源=").append(b.originName).append("；")
            if (b.origin.isNotBlank()) append("origin=").append(b.origin)
        }.trimEnd('；')
        val text = if (detail.isBlank()) {
            "把《${b.name}》加入书架"
        } else {
            "把《${b.name}》加入书架（$detail）"
        }
        return SuggestedAction("加书架《" + b.name.take(NAME_CAP) + "》", SuggestedAction.KIND_PROMPT, text)
    }

    // ---------- JSON 解析辅助 ----------

    private data class BookRef(
        val name: String,
        val author: String,
        val bookUrl: String,
        val origin: String,
        val originName: String
    )

    private fun parse(text: String): JsonObject? =
        runCatching { JsonParser.parseString(text) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject

    private fun booksOf(json: JsonObject?): List<BookRef> {
        val arr = runCatching { json?.getAsJsonArray("books") }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val name = o.str("name")
            if (name.isBlank()) return@mapNotNull null
            BookRef(
                name = name,
                author = o.str("author"),
                bookUrl = o.str("bookUrl"),
                origin = o.str("origin"),
                originName = o.str("originName").ifBlank { o.str("from") }
            )
        }
    }

    private fun JsonObject.str(key: String): String =
        runCatching { get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty() }.getOrDefault("")

    private fun JsonObject.int(key: String): Int =
        runCatching { get(key)?.takeIf { !it.isJsonNull }?.asInt ?: 0 }.getOrDefault(0)
}
