package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiMessage

@Dao
interface AiMessageDao {
    /** 取最新 [limit] 条（seq 倒序），调用方需自行反转恢复时序 */
    @Query("SELECT * FROM aiMessages WHERE sessionId=:sid ORDER BY seq DESC LIMIT :limit")
    suspend fun windowLatest(sid: Long, limit: Int): List<AiMessage>

    @Query("SELECT * FROM aiMessages WHERE sessionId=:sid ORDER BY seq ASC")
    suspend fun all(sid: Long): List<AiMessage>

    @Query("SELECT MAX(seq) FROM aiMessages WHERE sessionId=:sid")
    suspend fun maxSeq(sid: Long): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(m: AiMessage): Long

    @Query("DELETE FROM aiMessages WHERE sessionId=:sid AND seq <= :untilSeq")
    suspend fun trimUntil(sid: Long, untilSeq: Int)
}