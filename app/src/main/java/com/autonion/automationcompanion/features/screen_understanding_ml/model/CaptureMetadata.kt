package com.autonion.automationcompanion.features.screen_understanding_ml.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Immutable editor data for one screenshot. Playback always queries the live screen. */
@Serializable
data class CaptureMetadata(
    val version: Int = 1,
    val captureId: String = UUID.randomUUID().toString(),
    val width: Int,
    val height: Int,
    val accessibilityElements: List<UIElement>,
    val textNodes: List<CapturedTextNode>,
    val accessibilityOnly: Boolean = false
)
