package com.autonion.automationcompanion.features.omni_chatbot.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface OmniChatDao {

    // Insert a session only if it doesn't already exist.
    // DO NOT use REPLACE here — the ForeignKey CASCADE on messages
    // would delete ALL messages for that session on every "replace".
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSessionIfNew(session: OmniChatSessionEntity)

    // Update the session's metadata (title, preview, timestamp) without
    // triggering a delete+reinsert, which would cascade-delete messages.
    @Query("""
        UPDATE omni_chat_sessions 
        SET title = :title, previewText = :previewText, timestamp = :timestamp 
        WHERE sessionId = :sessionId
    """)
    suspend fun updateSessionMetadata(
        sessionId: String,
        title: String,
        previewText: String,
        timestamp: Long
    )

    // Upsert a session: insert if new, then update metadata.
    @Transaction
    suspend fun upsertSession(session: OmniChatSessionEntity) {
        insertSessionIfNew(session)
        updateSessionMetadata(
            sessionId = session.sessionId,
            title = session.title,
            previewText = session.previewText,
            timestamp = session.timestamp
        )
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: OmniChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<OmniChatMessageEntity>)

    @Query("SELECT * FROM omni_chat_sessions ORDER BY timestamp DESC")
    fun getAllSessions(): Flow<List<OmniChatSessionEntity>>

    @Query("SELECT * FROM omni_chat_sessions WHERE module = :module ORDER BY timestamp DESC")
    fun getSessionsByModule(module: String): Flow<List<OmniChatSessionEntity>>

    @Query("SELECT * FROM omni_chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSession(sessionId: String): List<OmniChatMessageEntity>

    @Query("DELETE FROM omni_chat_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)
    
    @Query("DELETE FROM omni_chat_sessions")
    suspend fun deleteAllSessions()
}
