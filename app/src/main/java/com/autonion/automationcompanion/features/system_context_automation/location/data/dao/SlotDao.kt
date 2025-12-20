package com.autonion.automationcompanion.features.system_context_automation.location.data.dao

import androidx.room.*
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import kotlinx.coroutines.flow.Flow

@Dao
interface SlotDao {
    @Insert
    suspend fun insert(slot: Slot): Long

    @Query("SELECT * FROM slots WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Slot?

    @Query("UPDATE slots SET sent = :sent, sentAt = :sentAt WHERE id = :id")
    suspend fun updateSent(id: Long, sent: Boolean, sentAt: Long?)
    @Query("SELECT * FROM slots")
    fun getAllFlow(): Flow<List<Slot>>

    @Delete
    suspend fun delete(slot: Slot)
}
