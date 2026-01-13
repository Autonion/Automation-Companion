package com.autonion.automationcompanion.features.system_context_automation.location.data.dao

import androidx.room.*
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import kotlinx.coroutines.flow.Flow

@Dao
interface SlotDao {

    @Insert
    suspend fun insert(slot: Slot): Long

    @Update
    suspend fun update(slot: Slot)

    @Delete
    suspend fun delete(slot: Slot)

    @Query("SELECT * FROM slots WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Slot?

    @Query("SELECT * FROM slots")
    fun getAllFlow(): Flow<List<Slot>>

    @Query("UPDATE slots SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("SELECT * FROM slots WHERE enabled = 1")
    suspend fun getAllEnabled(): List<Slot>

    @Query("UPDATE slots SET isInsideGeofence = :inside WHERE id = :slotId")
    suspend fun setInside(slotId: Long, inside: Boolean)

    @Query("UPDATE slots SET isInsideGeofence = :inside WHERE id = :slotId")
    suspend fun updateInsideGeofence(slotId: Long, inside: Boolean)

    @Query("UPDATE slots SET lastExecutedDay = :day WHERE id = :slotId")
    suspend fun updateLastExecutedDay(slotId: Long, day: String)

}
