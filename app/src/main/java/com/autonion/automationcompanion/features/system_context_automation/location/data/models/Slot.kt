package com.autonion.automationcompanion.features.system_context_automation.location.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.autonion.automationcompanion.automation.actions.models.AutomationAction

@Entity(tableName = "slots")
data class Slot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // Multi-trigger support
    val triggerType: String = "LOCATION",  // LOCATION, BATTERY, TIME_OF_DAY, WIFI
    val triggerConfigJson: String? = null,  // JSON of TriggerConfig; null for location (uses lat/lng)

    // Legacy location fields (kept for backward compat)
    val lat: Double? = null,
    val lng: Double? = null,
    val radiusMeters: Float? = null,

    val startMillis: Long? = null,
    val endMillis: Long? = null,

    val remindBeforeMinutes: Int = 0,
    val actions: List<AutomationAction>,
    val enabled: Boolean = true,

    // Scheduling
    val activeDays: String = "ALL",          // "ALL" or "MON,TUE,WED"

    // Runtime state (authoritative)
    val isInsideGeofence: Boolean = false,

    // Execution lock (ONE per day)
    val lastExecutedDay: String? = null // "2026-01-12"
)
