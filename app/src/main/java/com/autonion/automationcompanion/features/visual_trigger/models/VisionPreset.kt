package com.autonion.automationcompanion.features.visual_trigger.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class VisionPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val regions: List<VisionRegion> = emptyList(),
    val executionMode: ExecutionMode = ExecutionMode.MANDATORY_SEQUENTIAL,
    val isActive: Boolean = false,
    val captureImagePath: String? = null
)

@Serializable
enum class ExecutionMode {
    @SerialName("mandatory_sequential") MANDATORY_SEQUENTIAL,
    @SerialName("optional_sequential") OPTIONAL_SEQUENTIAL,
    @SerialName("detect_only") DETECT_ONLY
}
