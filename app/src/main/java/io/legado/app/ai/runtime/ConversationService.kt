package io.legado.app.ai.runtime

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.legado.app.App
import io.legado.app.ai.model.ChatMessage
import io.legado.app.ai.model.ToolCall
import io.legado.app.data.entities.AiMessage
import io.legado.app.data.entities.AiSession

/**
 * Room 持久化会话服务。负责会话增删改、消息落库、按窗口加载与字符数裁剪。
 */
class ConversationService(
    private val window: Int = 50,
    private val maxChars: Int = 12_000
) {
    private val sessionDao get() = App.db.aiSessionDao()
    private val messageDao get() = App.db.aiMessageDao()
    private val gson = Gson()

    suspend fun create(title: String = "新会话"): Long =
        sessionDao.insert(AiSession(title = title))

    suspend fun rename(id: Long, t: String) {
        sessionDao.get(id)?.let { sessionDao.update(it.copy(title = t)) }
    }

    suspend fun delete(id: Long) {
        sessionDao.deleteMessages(id)
        sessionDao.delete(id)
    }

    suspend fun archive(id: Long) {
        sessionDao.get(id)?.let { sessionDao.update(it.copy(archived = true)) }
    }

    /** 载入尾部 [window] 条消息（按 seq 升序返回），供 Agent 作为 history 上下文 */
    suspend fun loadChat(sid: Long): List<ChatMessage> =
        messageDao.windowLatest(sid, window).reversed().map { toChat(it) }

    suspend fun loadAll(sid: Long): List<AiMessage> = messageDao.all(sid)

    suspend fun append(sid: Long, m: ChatMessage) {
        val seq = (messageDao.maxSeq(sid) ?: -1) + 1
        val kind = inferKind(m)
        messageDao.insert(
            AiMessage(
                sessionId = sid,
                seq = seq,
                kind = kind,
                role = m.role,
                content = m.content ?: "",
                payload = m.toolCalls?.let { gson.toJson(it) },
                toolName = m.toolCalls?.firstOrNull()?.function?.name
            )
        )
        sessionDao.get(sid)?.let { sessionDao.update(it.copy(updatedAt = System.currentTimeMillis())) }
        trimIfNeeded(sid)
    }

    suspend fun appendText(sid: Long, role: String, content: String) = append(sid, ChatMessage(role, content))

    private fun inferKind(m: ChatMessage): String = when (m.role) {
        "tool" -> "tool_result"
        "assistant" -> if (m.toolCalls.isNullOrEmpty()) "assistant" else "tool_call"
        "system" -> "system"
        else -> "user"
    }

    private suspend fun trimIfNeeded(sid: Long) {
        val all = messageDao.all(sid)
        var sum = all.sumOf { cost(it) }
        if (sum <= maxChars) return
        var until = 0
        for (m in all) {
            sum -= cost(m)
            until = m.seq
            if (sum <= maxChars) break
        }
        messageDao.trimUntil(sid, until)
    }

    private fun cost(m: AiMessage): Int = m.content.length + (m.payload?.length ?: 0)

    private fun toChat(m: AiMessage): ChatMessage = when (m.kind) {
        "tool_call" -> ChatMessage(
            role = m.role,
            content = null,
            toolCalls = parseToolCalls(m.payload),
            createdAt = m.createdAt
        )
        else -> ChatMessage(
            role = m.role,
            content = m.content,
            createdAt = m.createdAt
        )
    }

    private fun parseToolCalls(payload: String?): List<ToolCall>? {
        if (payload.isNullOrBlank()) return null
        return runCatching {
            gson.fromJson<List<ToolCall>>(payload, object : TypeToken<List<ToolCall>>() {}.type)
        }.getOrNull()
    }
}