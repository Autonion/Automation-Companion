package com.autonion.automationcompanion.core.backup

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.automation_debugger.data.ExecutionLog
import com.autonion.automationcompanion.features.omni_chatbot.data.db.OmniChatSessionEntity
import com.autonion.automationcompanion.features.omni_chatbot.data.db.OmniChatMessageEntity

@Dao
interface BackupDao {
    @Query("SELECT * FROM slots ORDER BY id") suspend fun slots(): List<Slot>
    @Query("SELECT * FROM execution_logs ORDER BY id") suspend fun logs(): List<ExecutionLog>
    @Query("SELECT * FROM omni_chat_sessions") suspend fun sessions(): List<OmniChatSessionEntity>
    @Query("SELECT * FROM omni_chat_messages") suspend fun messages(): List<OmniChatMessageEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun slot(row: Slot): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun log(row: ExecutionLog): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun session(row: OmniChatSessionEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun message(row: OmniChatMessageEntity): Long
}
