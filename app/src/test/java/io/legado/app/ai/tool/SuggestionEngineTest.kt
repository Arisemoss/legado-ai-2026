package io.legado.app.ai.tool

import io.legado.app.ai.model.SuggestedAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 建议动作派生引擎单测：锁定「工具结果 → 快捷按钮」的映射，
 * 并守住红线——引擎不产生任何绕过聊天确认卡的写库动作。
 */
class SuggestionEngineTest {

    private val searchJson = """
        {"books":[
          {"name":"诡秘之主","author":"爱潜水的乌贼","bookUrl":"https://a.com/1",
           "origin":"https://a.com","originName":"源A"},
          {"name":"宿命之环","author":"爱潜水的乌贼","bookUrl":"https://a.com/2",
           "origin":"https://a.com","originName":"源A"}
        ]}
    """.trimIndent()

    @Test
    fun searchResultOffersAddToShelfPromptWithBookUrl() {
        val actions = SuggestionEngine.suggest("search_books", emptyMap(), searchJson)
        assertEquals(3, actions.size)
        val first = actions[0]
        assertEquals(SuggestedAction.KIND_PROMPT, first.kind)
        assertTrue(first.payload.contains("诡秘之主"))
        assertTrue(first.payload.contains("https://a.com/1"))
        // 搜索结果未入架：只允许 prompt（写操作还要过确认卡）与跳书架
        assertTrue(
            actions.all {
                it.kind == SuggestedAction.KIND_PROMPT || it.kind == SuggestedAction.KIND_SHELF
            }
        )
        assertEquals(SuggestedAction.KIND_SHELF, actions.last().kind)
    }

    @Test
    fun searchResultIsCappedAtThreeActions() {
        val many = (1..6).joinToString(",") { i ->
            "{\"name\":\"书" + i + "\",\"bookUrl\":\"https://a.com/" + i + "\"}"
        }
        val actions = SuggestionEngine.suggest("search_books", emptyMap(), "{\"books\":[" + many + "]}")
        assertEquals(3, actions.size)
        assertTrue(actions[0].payload.contains("https://a.com/1"))
    }

    @Test
    fun shelfResultOffersDirectReaderNavigation() {
        val json = """{"books":[{"name":"赘婿","bookUrl":"https://b.com/9","author":"愤怒的香蕉"}]}"""
        val actions = SuggestionEngine.suggest("list_shelf", emptyMap(), json)
        assertEquals(SuggestedAction.KIND_READER, actions[0].kind)
        assertEquals("https://b.com/9", actions[0].payload)
        assertEquals(SuggestedAction.KIND_SHELF, actions[1].kind)
    }

    @Test
    fun bookWithoutBookUrlNeverYieldsReaderAction() {
        val json = """{"books":[{"name":"无源书"}]}"""
        val actions = SuggestionEngine.suggest("list_shelf", emptyMap(), json)
        assertTrue(actions.none { it.kind == SuggestedAction.KIND_READER })
    }

    @Test
    fun approvedAddToShelfOffersReadNow() {
        val args = mapOf<String, Any?>("bookUrl" to "https://c.com/7", "name" to "庆余年")
        val actions = SuggestionEngine.suggest("add_book_to_shelf", args, """{"ok":true}""")
        assertEquals(SuggestedAction.KIND_READER, actions[0].kind)
        assertEquals("https://c.com/7", actions[0].payload)
        assertEquals(SuggestedAction.KIND_SHELF, actions[1].kind)
    }

    @Test
    fun brokenSourcesLeadToHealthPage() {
        val actions = SuggestionEngine.suggest(
            "test_sources_batch", emptyMap(), """{"total":10,"ok":7,"bad":3,"details":[]}"""
        )
        assertEquals(SuggestedAction.KIND_HEALTH, actions[0].kind)
        assertTrue(actions[0].label.contains("3"))
        assertEquals(SuggestedAction.KIND_SOURCES, actions[1].kind)
    }

    @Test
    fun healthySourcesOnlyOfferSourceManage() {
        val actions = SuggestionEngine.suggest(
            "test_sources_batch", emptyMap(), """{"total":5,"ok":5,"bad":0}"""
        )
        assertTrue(actions.none { it.kind == SuggestedAction.KIND_HEALTH })
        assertEquals(SuggestedAction.KIND_SOURCES, actions[0].kind)
    }

    @Test
    fun settingsAndReplaceToolsMapToTheirPages() {
        assertEquals(
            SuggestedAction.KIND_SETTINGS,
            SuggestionEngine.suggest("set_setting", emptyMap(), """{"ok":true}""")[0].kind
        )
        assertEquals(
            SuggestedAction.KIND_REPLACE,
            SuggestionEngine.suggest("list_replace_rules", emptyMap(), """{"rules":[]}""")[0].kind
        )
        assertEquals(
            SuggestedAction.KIND_SOURCES,
            SuggestionEngine.suggest("delete_book_source", emptyMap(), """{"ok":true}""")[0].kind
        )
    }

    @Test
    fun readingToolsFallBackToOpeningCurrentBook() {
        val actions = SuggestionEngine.suggest("summarize_chapter", emptyMap(), """{"summary":"x"}""")
        assertEquals(SuggestedAction.KIND_PROMPT, actions[0].kind)
        assertTrue(actions[0].payload.isNotBlank())
        val named = SuggestionEngine.suggest("plot_recap", mapOf("bookName" to "雪中悍刀行"), "{}")
        assertTrue(named[0].payload.contains("雪中悍刀行"))
    }

    @Test
    fun malformedOrErrorResultsYieldNoActions() {
        assertTrue(SuggestionEngine.suggest("search_books", emptyMap(), "这不是 JSON").isEmpty())
        assertTrue(SuggestionEngine.suggest("search_books", emptyMap(), """{"error":"缺少关键词"}""").isEmpty())
        assertTrue(SuggestionEngine.suggest("search_books", emptyMap(), "").isEmpty())
        assertTrue(SuggestionEngine.suggest("unknown_tool", emptyMap(), searchJson).isEmpty())
    }
}
