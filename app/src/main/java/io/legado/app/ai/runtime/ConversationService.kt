package io.legado.app.ai.runtime

import io.legado.app.data.appDb
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    private val sessionDao get() = appDb.aiSessionDao
    private val messageDao get() = appDb.aiMessageDao
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
        val kind = inferKind(m)
        // 审计 B-4：maxSeq 读 + insert 必须在同一事务内，否则并发追加会产生重复 seq
        appDb.withTransaction {
            val seq = (messageDao.maxSeq(sid) ?: -1) + 1
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
            sessionDao.get(sid)
                ?.let { sessionDao.update(it.copy(updatedAt = System.currentTimeMillis())) }
        }
        trimIfNeeded(sid)
    }

    suspend fun appendText(sid: Long, role: String, content: String) = append(sid, ChatMessage(role, content))

    private fun inferKind(m: ChatMessage): String = when (m.role) {
        "tool" -> "tool_result"
        "assistant" -> if (m.toolCalls.isNullOrEmpty()) "assistant" else "tool_call"
        "system" -> "system"
        else -> "user"
    }

    /**
     * 超长裁剪（审计修复）。
     * 原实现可能把「最新一条」也裁掉：当单条消息自身 cost > [maxChars]（用户粘贴长文、
     * 长篇总结、大工具结果）时，循环第一次就 break 并把 until 设为最新 seq，
     * 而 trimUntil 是 seq <= until → 整个会话（含刚写入的那条）被清空。
     * 现在恒定保留最新一条。
     */
    private suspend fun trimIfNeeded(sid: Long) {
        val all = messageDao.all(sid)
        if (all.size <= 1) return
        var sum = all.sumOf { cost(it) }
        if (sum <= maxChars) return
        var until = 0
        // 不遍历最后一条：它必须保留
        for (i in 0 until all.lastIndex) {
            sum -= cost(all[i])
            until = all[i].seq
            if (sum <= maxChars) break
        }
        if (until > 0) messageDao.trimUntil(sid, until)
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