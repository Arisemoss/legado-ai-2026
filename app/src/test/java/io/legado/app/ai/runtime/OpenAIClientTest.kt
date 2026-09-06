package io.legado.app.ai.runtime

import io.legado.app.ai.model.AgentErrorCode
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