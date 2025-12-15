package com.example.automationcompanion.features.system_context_automation.location.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

// data/Slot.kt (example)
@Entity(tableName = "slots")
data class Slot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Float,
    val startMillis: Long,
    val endMillis: Long,
    val message: String,
    val contactsCsv: String,
    val sent: Boolean = false,            // new: whether we've already sent message for this slot
    val sentAt: Long? = null,              // new: when it was sent (ms since epoch)
    val remindBeforeMinutes: Int = 0,  // NEW — 0 means "no reminder"
//    val useRootToggle: Boolean = false
)

