package io.legado.app.ai.tool

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolCallParserTest {

    private fun args(call: io.legado.app.ai.runtime.ToolCallData) =
        JsonParser.parseString(call.arguments).asJsonObject

    @Test
    fun `parse single call with surrounding text`() {
        val r = TextToolCallParser.parse(
            """我来搜书。<tool name="search_books"><param name="keyword">诡秘之主</param></tool>"""
        )
        assertEquals(1, r.calls.size)
        assertEquals("search_books", r.calls[0].name)
        assertEquals("诡秘之主", args(r.calls[0]).get("keyword").asString)
        assertEquals("我来搜书。", r.strippedContent)
    }

    @Test
    fun `parse multiple calls mixed with text`() {
        val input = "前文\n" +
            "<tool name=\"open_book\"><param name=\"bookName\">A</param></tool>\n" +
            "中间文字\n" +
            "<TOOL name=\"List_Shelf\"></TOOL>\n" +
            "尾部"
        val r = TextToolCallParser.parse(input)
        assertEquals(2, r.calls.size)
        assertEquals("open_book", r.calls[0].name)
        assertEquals("List_Shelf", r.calls[1].name)
        assertFalse(r.strippedContent.contains("<tool"))
        assertFalse(r.strippedContent.contains("<TOOL"))
    }

    @Test
    fun `cdata and entities are unescaped`() {
        val input = "<tool name=\"explain_text\">" +
            "<param name=\"text\"><![CDATA[a <b> & c]]></param>" +
            "<param name=\"context\">&quot;x&quot;&amp;&lt;y&gt;</param>" +
            "</tool>"
        val r = TextToolCallParser.parse(input)
        assertEquals(1, r.calls.size)
        assertEquals("a <b> & c", args(r.calls[0]).get("text").asString)
        assertEquals("\"x\"&<y>", args(r.calls[0]).get("context").asString)
    }

    @Test
    fun `empty code fences are stripped`() {
        val input = "```xml\n<tool name=\"get_setting\"></tool>\n```\n正文保留"
        val r = TextToolCallParser.parse(input)
        assertEquals(1, r.calls.size)
        assertEquals("正文保留", r.strippedContent)
        assertFalse(r.strippedContent.contains("```"))
    }

    @Test
    fun `no tags fast path returns content untouched`() {
        val text = "普通回答，没有工具调用。"
        val r = TextToolCallParser.parse(text)
        assertTrue(r.calls.isEmpty())
        assertEquals(text, r.strippedContent)
    }
}
