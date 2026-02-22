package com.autonion.automationcompanion.features.automation_debugger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a single execution log entry persisted in Room.
 * Each log is categorized by which feature produced it.
 */
@Entity(tableName = "execution_logs")
data class ExecutionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val category: String,   // LogCategory constant
    val level: String,      // LogLevel constant
    val title: String,      // Short summary, e.g. "Slot #3 executed"
    val message: String,    // Detailed description
    val source: String,     // Originating class name
    val metadata: String? = null  // Optional JSON for extra structured data
)

/** Log categories matching the 7 HomeScreen feature cards */
object LogCategory {
    const val GESTURE_RECORDING = "GESTURE_RECORDING"
    const val SCREEN_CONTEXT_AI = "SCREEN_CONTEXT_AI"
    const val SEMANTIC_AUTOMATION = "SEMANTIC_AUTOMATION"
    const val CONDITIONAL_MACROS = "CONDITIONAL_MACROS"
    const val SYSTEM_CONTEXT = "SYSTEM_CONTEXT"
    const val EMERGENCY_TRIGGER = "EMERGENCY_TRIGGER"
    const val CROSS_DEVICE_SYNC = "CROSS_DEVICE_SYNC"
    const val FLOW_BUILDER = "FLOW_BUILDER"
}

object LogLevel {
    const val INFO = "INFO"
    const val SUCCESS = "SUCCESS"
    const val WARNING = "WARNING"
    const val ERROR = "ERROR"
}
