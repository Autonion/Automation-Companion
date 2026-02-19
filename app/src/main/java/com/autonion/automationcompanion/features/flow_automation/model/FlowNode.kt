package com.autonion.automationcompanion.features.flow_automation.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * The type discriminator for flow nodes.
 */
@Serializable
enum class FlowNodeType {
    @SerialName("start") START,
    @SerialName("gesture") GESTURE,
    @SerialName("visual_trigger") VISUAL_TRIGGER,
    @SerialName("screen_ml") SCREEN_ML,
    @SerialName("delay") DELAY
}

/**
 * Gesture interaction types.
 */
@Serializable
enum class GestureType {
    @SerialName("tap") TAP,
    @SerialName("long_press") LONG_PRESS,
    @SerialName("swipe") SWIPE,
    @SerialName("custom") CUSTOM
}

/**
 * Coordinate source — either static values or dynamic from FlowContext.
 */
@Serializable
sealed class CoordinateSource {
    @Serializable
    @SerialName("static")
    data class Static(val x: Float, val y: Float) : CoordinateSource()

    @Serializable
    @SerialName("from_context")
    data class FromContext(val key: String) : CoordinateSource()
}

/**
 * ML node operating mode.
 */
@Serializable
enum class ScreenMLMode {
    @SerialName("ocr") OCR,
    @SerialName("object_detection") OBJECT_DETECTION
}

// ─── Node hierarchy ────────────────────────────────────────────────────────────

/**
 * Base sealed class for all flow nodes. Uses kotlinx.serialization polymorphism.
 */
@Serializable
sealed class FlowNode {
    abstract val id: String
    abstract val type: FlowNodeType
    abstract val position: NodePosition
    abstract val label: String
    abstract val outputEdgeIds: List<String>
    abstract val onFailureEdgeId: String?
    abstract val timeoutMs: Long
}

/**
 * Entry point of every flow. Only one per graph.
 * Optionally launches a target app before continuing.
 */
@Serializable
@SerialName("start")
data class StartNode(
    override val id: String = UUID.randomUUID().toString(),
    override val position: NodePosition = NodePosition(200f, 100f),
    override val label: String = "Start",
    override val outputEdgeIds: List<String> = emptyList(),
    override val onFailureEdgeId: String? = null,
    override val timeoutMs: Long = 10_000L,
    val appPackageName: String? = null,
    val launchFlags: Int = 0
) : FlowNode() {
    override val type: FlowNodeType = FlowNodeType.START
}

/**
 * Performs a gesture (tap, swipe, long press) via Accessibility Service.
 * Coordinates can be static or dynamically read from FlowContext.
 */
@Serializable
@SerialName("gesture")
data class GestureNode(
    override val id: String = UUID.randomUUID().toString(),
    override val position: NodePosition = NodePosition(),
    override val label: String = "Gesture",
    override val outputEdgeIds: List<String> = emptyList(),
    override val onFailureEdgeId: String? = null,
    override val timeoutMs: Long = 5_000L,
    val gestureType: GestureType = GestureType.TAP,
    val coordinateSource: CoordinateSource = CoordinateSource.Static(540f, 960f),
    val durationMs: Long = 100L,
    val swipeEndX: Float? = null,
    val swipeEndY: Float? = null
) : FlowNode() {
    override val type: FlowNodeType = FlowNodeType.GESTURE
}

/**
 * Image-based trigger that uses OpenCV template matching to find
 * a reference image on screen.
 * Writes match result coordinates to FlowContext.
 */
@Serializable
@SerialName("visual_trigger")
data class VisualTriggerNode(
    override val id: String = UUID.randomUUID().toString(),
    override val position: NodePosition = NodePosition(),
    override val label: String = "Image Match",
    override val outputEdgeIds: List<String> = emptyList(),
    override val onFailureEdgeId: String? = null,
    override val timeoutMs: Long = 15_000L,
    val templateImagePath: String = "",
    val threshold: Float = 0.8f,
    val searchRegionX: Int = 0,
    val searchRegionY: Int = 0,
    val searchRegionWidth: Int = 0,
    val searchRegionHeight: Int = 0,
    val outputContextKey: String = "match_result"
) : FlowNode() {
    override val type: FlowNodeType = FlowNodeType.VISUAL_TRIGGER
}

/**
 * ML-powered screen understanding node.
 * - OCR: extracts text from a screen capture and writes it to FlowContext.
 * - Object Detection: finds UI elements and writes coordinates to FlowContext.
 */
@Serializable
@SerialName("screen_ml")
data class ScreenMLNode(
    override val id: String = UUID.randomUUID().toString(),
    override val position: NodePosition = NodePosition(),
    override val label: String = "Screen ML",
    override val outputEdgeIds: List<String> = emptyList(),
    override val onFailureEdgeId: String? = null,
    override val timeoutMs: Long = 20_000L,
    val mode: ScreenMLMode = ScreenMLMode.OCR,
    val outputContextKey: String = "ml_result",
    val targetLabel: String? = null
) : FlowNode() {
    override val type: FlowNodeType = FlowNodeType.SCREEN_ML
}

/**
 * Simple delay node that pauses execution for a fixed duration.
 */
@Serializable
@SerialName("delay")
data class DelayNode(
    override val id: String = UUID.randomUUID().toString(),
    override val position: NodePosition = NodePosition(),
    override val label: String = "Delay",
    override val outputEdgeIds: List<String> = emptyList(),
    override val onFailureEdgeId: String? = null,
    override val timeoutMs: Long = 60_000L,
    val delayMs: Long = 2000L
) : FlowNode() {
    override val type: FlowNodeType = FlowNodeType.DELAY
}
