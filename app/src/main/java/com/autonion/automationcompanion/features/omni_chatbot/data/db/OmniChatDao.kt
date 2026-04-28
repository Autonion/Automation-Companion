package com.autonion.automationcompanion.features.omni_chatbot.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface OmniChatDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: OmniChatSessionEntity)

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
