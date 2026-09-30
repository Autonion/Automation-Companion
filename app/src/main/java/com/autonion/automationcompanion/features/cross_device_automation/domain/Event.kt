package com.autonion.automationcompanion.features.cross_device_automation.domain

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class RawEvent(
    @field:SerializedName("id")
    val id: String = "",
    @field:SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis(),
    @field:SerializedName("type")
    val type: String = "", // e.g., "clipboard.text_copied"
    @field:SerializedName("sourceDeviceId")
    val sourceDeviceId: String = "",
    @field:SerializedName("payload")
    val payload: Map<String, String> = emptyMap() // Simplified payload for now
)

data class EnrichedEvent(
    val originalEvent: RawEvent,
    val detectedTags: Set<String>,
    val metadata: Map<String, String>,
    val processedTimestamp: Long = System.currentTimeMillis()
)
