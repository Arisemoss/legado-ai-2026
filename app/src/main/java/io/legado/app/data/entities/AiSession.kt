package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AI 会话。
 */
@Entity(tableName = "aiSessions")
data class AiSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var title: String,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var archived: Boolean = false,
    var model: String? = null,
    var lastSummaryAt: Long = 0
)