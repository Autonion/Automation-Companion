package com.autonion.automationcompanion.features.system_context_automation.location.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.AutomationAction

@Entity(tableName = "slots")
data class Slot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val lat: Double,
    val lng: Double,
    val radiusMeters: Float,

    val startMillis: Long,
    val endMillis: Long,

    val remindBeforeMinutes: Int = 0,
    val actions: List<AutomationAction>,
    val enabled: Boolean = true,

    // Scheduling
    val activeDays: String,          // "ALL" or "MON,TUE,WED"

    // Runtime state (authoritative)
    val isInsideGeofence: Boolean = false,

    // Execution lock (ONE per day)
    val lastExecutedDay: String? = null // "2026-01-12"
)
