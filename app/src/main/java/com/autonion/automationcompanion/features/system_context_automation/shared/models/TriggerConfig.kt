package com.autonion.automationcompanion.features.system_context_automation.shared.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * TriggerConfig represents the configuration for each trigger type.
 * These are serialized to JSON and stored in the AutomationSlot.triggerConfigJson column.
 * Trigger-agnostic execution: all triggers use the same slot evaluation and action execution flow.
 */
@Serializable
sealed class TriggerConfig {

    @Serializable
    @SerialName("location")
    data class Location(
        val lat: Double,
        val lng: Double,
        val radiusMeters: Float,
        val startMillis: Long,
        val endMillis: Long,
        val activeDays: String,  // "ALL" or "MON,TUE,WED"
        val remindBeforeMinutes: Int = 0,
        val isInsideGeofence: Boolean = false,
        val lastExecutedDay: String? = null
    ) : TriggerConfig()

    @Serializable
    @SerialName("battery")
    data class Battery(
        val batteryPercentage: Int,  // Trigger fires when battery reaches or crosses this %
        val thresholdType: ThresholdType = ThresholdType.REACHES_OR_BELOW
    ) : TriggerConfig() {
        @Serializable
        enum class ThresholdType {
            @SerialName("reaches_or_below")
            REACHES_OR_BELOW,
            @SerialName("reaches_or_above")
            REACHES_OR_ABOVE
        }
    }

    @Serializable
    @SerialName("time_of_day")
    data class TimeOfDay(
        val hour: Int,  // 0-23
        val minute: Int,  // 0-59
        val repeatDaily: Boolean = true,
        val activeDays: String = "ALL",  // "ALL" or "MON,TUE,WED"
        val lastExecutedDay: String? = null  // "2026-01-12"
    ) : TriggerConfig()

    @Serializable
    @SerialName("wifi")
    data class WiFi(
        val connectionState: ConnectionState,
        val optionalSsid: String? = null,  // If null, match any SSID; if set, match only this SSID
        val lastExecutedDay: String? = null  // Execution lock (one per day)
    ) : TriggerConfig() {
        @Serializable
        enum class ConnectionState {
            @SerialName("connected")
            CONNECTED,
            @SerialName("disconnected")
            DISCONNECTED
        }
    }
}
