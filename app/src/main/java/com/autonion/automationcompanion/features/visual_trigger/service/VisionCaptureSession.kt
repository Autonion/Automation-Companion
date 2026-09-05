package com.autonion.automationcompanion.features.visual_trigger.service

import java.util.UUID

internal data class VisionCaptureSession(
    val id: String = UUID.randomUUID().toString(),
    val presetName: String = "New Automation",
    val isFlowMode: Boolean = false,
    val flowNodeId: String? = null,
    val flowVisionJson: String? = null,
    val clearOnStart: Boolean = false,
    val activePresetId: String? = null
) {
    fun afterEditorResult(sessionId: String?, savedPresetId: String?): VisionCaptureSession {
        if (sessionId != id || isFlowMode || savedPresetId == null) return this
        return copy(activePresetId = savedPresetId)
    }
}
