package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.AiSession
import kotlinx.coroutines.flow.Flow

@Dao
interface AiSessionDao {
    @Query("SELECT * FROM aiSessions WHERE archived=0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<AiSession>>

    @Query("SELECT * FROM aiSessions WHERE archived=0 ORDER BY updatedAt DESC")
    suspend fun getAll(): List<AiSession>

    @Query("SELECT * FROM aiSessions WHERE id=:id")
    suspend fun get(id: Long): AiSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: AiSession): Long

    @Update
    suspend fun update(s: AiSession)

    @Query("DELETE FROM aiSessions WHERE id=:id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM aiMessages WHERE sessionId=:id")
    suspend fun deleteMessages(id: Long)
}