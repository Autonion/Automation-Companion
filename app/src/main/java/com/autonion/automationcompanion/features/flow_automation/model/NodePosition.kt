package com.autonion.automationcompanion.features.flow_automation.model

import kotlinx.serialization.Serializable

/**
 * Canvas position for a node in the flow editor.
 */
@Serializable
data class NodePosition(
    val x: Float = 0f,
    val y: Float = 0f
)
