package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 会话内的一条消息。
 * [kind]：user|assistant|tool_call|tool_result|system|confirm_request|confirm_decision
 * [payload]：tool_calls/确认提案的 JSON 序列化。
 */
@Entity(
    tableName = "aiMessages",
    indices = [Index(value = ["sessionId", "seq"])]
)
data class AiMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val seq: Int,
    val kind: String,
    val role: String,
    val content: String,
    val payload: String? = null,
    val toolName: String? = null,
    val quotaBilled: Long? = null,
    val flags: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)