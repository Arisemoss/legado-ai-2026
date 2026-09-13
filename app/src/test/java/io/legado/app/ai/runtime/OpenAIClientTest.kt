package io.legado.app.ai.runtime

import io.legado.app.ai.model.AgentErrorCode
import io.legado.app.ai.model.ChatMessage
import io.legado.app.ai.model.FunctionCall
import io.legado.app.ai.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIClientTest {

    private fun client() = OpenAIClient("https://api.deepseek.com/", "k", "deepseek-chat")

    @Test
    fun `parse plain content`() {
        val json = """{"choices":[{"message":{"role":"assistant","content":"你好"}}]}"""
        val r = client().parseCompletion(json)
        assertEquals("你好", r.content)
        assertNull(r.toolCalls)
    }

    @Test
    fun `parse empty content`() {
        val json = """{"choices":[{"message":{"role":"assistant"}}]}"""
        assertNull(client().parseCompletion(json).content)
    }

    @Test
    fun `parse tool calls`() {
        val json = """{"choices":[{"message":{"role":"assistant","tool_calls":[
            {"id":"c1","type":"function","function":{"name":"search_books","arguments":"{\"kw\":\"斗破\"}"}}]}}]}"""
        val r = client().parseCompletion(json)
        assertEquals(1, r.toolCalls?.size)
        assertEquals("search_books", r.toolCalls!![0].name)
        assertEquals("c1", r.toolCalls!![0].id)
    }

    @Test
    fun `api error maps to auth failed`() {
        val json = """{"error":{"message":"invalid api key","type":"auth"}}"""
        val thrown = try {
            client().parseCompletion(json)
            null
        } catch (e: AgentException) {
            e
        }
        assertEquals(AgentErrorCode.AUTH_FAILED, thrown?.code)
    }

    @Test
    fun `parse reasoning content of thinking model`() {
        val json = """{"choices":[{"message":{"role":"assistant",
            "content":null,
            "reasoning_content":"我先看看书架",
            "tool_calls":[{"id":"c1","type":"function","function":{"name":"list_shelf","arguments":"{}"}}]}}]}"""
        val r = client().parseCompletion(json)
        assertEquals("我先看看书架", r.reasoning)
        assertEquals(1, r.toolCalls?.size)
    }

    @Test
    fun `tool call message echoes reasoning_content back`() {
        // 真机 bug：思考模式回过 tool_calls 但丢掉 reasoning_content → 下一轮 HTTP 400
        val assistant = ChatMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(ToolCall("c1", "function", FunctionCall("list_shelf", "{}"))),
            reasoningContent = "先确认书架内容"
        )
        val body = client().buildBody(listOf(assistant), null, stream = false)
        assertTrue("应回传 reasoning_content", body.contains("reasoning_content"))
        assertTrue(body.contains("先确认书架内容"))
        assertTrue(body.contains("tool_calls"))
    }

    @Test
    fun `plain assistant message does not send reasoning_content`() {
        // 无 tool_calls 的普通 assistant 消息不应带思维链（避免被严格服务商拒绝/浪费上下文）
        val assistant = ChatMessage(role = "assistant", content = "你好", reasoningContent = "思考中")
        val body = client().buildBody(listOf(assistant), null, stream = false)
        assertTrue(!body.contains("reasoning_content"))
    }

    @Test
    fun `no choices throws tool failed`() {
        val thrown = try {
            client().parseCompletion("""{"choices":[]}""")
            null
        } catch (e: AgentException) {
            e
        }
        assertTrue(thrown?.code == AgentErrorCode.TOOL_FAILED)
    }
}