package com.autonion.automationcompanion.features.cross_device_automation.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class RawEvent(
    val id: String,
    val timestamp: Long,
    val type: String, // e.g., "clipboard.text_copied"
    val sourceDeviceId: String,
    val payload: Map<String, String> // Simplified payload for now
)

data class EnrichedEvent(
    val originalEvent: RawEvent,
    val detectedTags: Set<String>,
    val metadata: Map<String, String>,
    val processedTimestamp: Long = System.currentTimeMillis()
)
