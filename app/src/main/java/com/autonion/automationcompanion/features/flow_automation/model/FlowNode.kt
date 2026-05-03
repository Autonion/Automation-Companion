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
    @SerialName("delay") DELAY,
    @SerialName("launch_app") LAUNCH_APP,
    @SerialName("repeat") REPEAT,
    @SerialName("clipboard") CLIPBOARD,
    @SerialName("input") INPUT
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

/**
 * Clipboard operations.
 */
@Serializable
enum class ClipboardOperation {
    @SerialName("read") READ,
    @SerialName("write") WRITE
}

/**
 * Text input source.
 */
@Serializable
sealed class InputSource {
    @Serializable
    @SerialName("static")
    data class Static(val text: String) : InputSource()

    @Serializable
    @SerialName("from_context")
    data class FromContext(val key: String) : InputSource()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : InputSource()
}

// ─── Node hierarchy ────────────────────────────────────────────────────────────

/**
 * Base sealed class for all flow nodes. Uses kotlinx.serialization polymorphism.
 */
@Serializable
sealed class FlowNode {
    abstract val id: String
    abstract val nodeType: FlowNodeType
    abstract val position: NodePosition
    abstract val label: String
    /**
     * Bug #4: This field is never maintained — edges are resolved via
     * [FlowGraph.outgoingEdges] which filters by [FlowEdge.fromNodeId].
     * Kept for serialization backward compatibility with saved flows.
     */
    @Deprecated("Unused — edge lookup uses FlowGraph.outgoingEdges(nodeId) instead")
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
    override val nodeType: FlowNodeType = FlowNodeType.START
}

/**
 * Performs a gesture (tap, swipe, long press) via Accessibility Service.
 *
 * Configuration modes:
 * 1. **Recorded** (preferred) — User records gestures via the overlay. The
 *    serialized action list is stored in [recordedActionsJson].
 * 2. **Manual** (advanced) — User sets coordinates directly via
 *    [coordinateSource] and [gestureType].
 *
 * During playback the executor checks [recordedActionsJson] first;
 * if empty it falls back to manual coordinate mode.
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
    val swipeEndY: Float? = null,
    val swipeEndX: Float? = null,
    /** Serialized List<Action> JSON from gesture recording overlay. */
    val recordedActionsJson: String = ""
) : FlowNode() {
    override val nodeType: FlowNodeType = FlowNodeType.GESTURE
}

/**
 * Image-based trigger that uses OpenCV template matching.
 *
 * Configuration modes:
 * 1. **Full V-Trigger preset** — User captures via the VisionTrigger overlay,
 *    defining regions + actions. Stored in [visionPresetJson].
 * 2. **Simple template** — Just [templateImagePath] + [threshold] for basic matching.
 *
 * The executor checks [visionPresetJson] first.
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
    val outputContextKey: String = "match_result",
    /** Serialized VisionPreset JSON with regions and actions. */
    val visionPresetJson: String = ""
) : FlowNode() {
    override val nodeType: FlowNodeType = FlowNodeType.VISUAL_TRIGGER
}

/**
 * ML-powered screen understanding node.
 *
 * Configuration modes:
 * 1. **Recorded preset** — User captures screen via CaptureEditor, selects
 *    elements & assigns actions. Stored in [automationStepsJson].
 * 2. **Live mode** — Real-time OCR/object detection via [mode].
 *
 * The executor checks [automationStepsJson] first.
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
    val targetLabel: String? = null,
    /** Serialized automation steps JSON from CaptureEditor. */
    val automationStepsJson: String = "",
    /** Path to captured screenshot used during configuration. */
    val captureImagePath: String = ""
) : FlowNode() {
    override val nodeType: FlowNodeType = FlowNodeType.SCREEN_ML
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
    override val nodeType: FlowNodeType = FlowNodeType.DELAY
}

/**
 * Launches a different app mid-flow.
 *
 * Uses PackageManager to find and launch the target app.
 * When combined with visual/ML nodes, requires full-screen MediaProjection
 * permission to avoid capture issues after the app switch.
 */
@Serializable
@SerialName("launch_app")
data class LaunchAppNode(
    override val id: String = UUID.randomUUID().toString(),
    override val position: NodePosition = NodePosition(),
    override val label: String = "Launch App",
    override val outputEdgeIds: List<String> = emptyList(),
    override val onFailureEdgeId: String? = null,
    override val timeoutMs: Long = 10_000L,
    val appPackageName: String = "",
    val launchDelayMs: Long = 1500L
) : FlowNode() {
    override val nodeType: FlowNodeType = FlowNodeType.LAUNCH_APP
}

/**
 * Repeat/loop node — re-executes its downstream sub-graph a fixed
 * number of times, or infinitely until the flow is stopped.
 *
 * [repeatCount] = 0 means infinite (run until manually stopped).
 * Any positive value is the exact number of iterations.
 */
@Serializable
@SerialName("repeat")
data class RepeatNode(
    override val id: String = UUID.randomUUID().toString(),
    override val position: NodePosition = NodePosition(),
    override val label: String = "Repeat",
    override val outputEdgeIds: List<String> = emptyList(),
    override val onFailureEdgeId: String? = null,
    override val timeoutMs: Long = 600_000L,  // 10 min default for loops
    val repeatCount: Int = 1,
    val delayBetweenMs: Long = 0L
) : FlowNode() {
    override val nodeType: FlowNodeType = FlowNodeType.REPEAT
}

/**
 * Reads from or writes to the system clipboard.
 */
@Serializable
@SerialName("clipboard")
data class ClipboardNode(
    override val id: String = UUID.randomUUID().toString(),
    override val position: NodePosition = NodePosition(),
    override val label: String = "Clipboard",
    override val outputEdgeIds: List<String> = emptyList(),
    override val onFailureEdgeId: String? = null,
    override val timeoutMs: Long = 5_000L,
    val operation: ClipboardOperation = ClipboardOperation.READ,
    val contextKey: String = "clipboard_text",
    val inputSource: InputSource = InputSource.FromContext("clipboard_text") // Used for WRITE operation
) : FlowNode() {
    override val nodeType: FlowNodeType = FlowNodeType.CLIPBOARD
}

/**
 * Injects text into a target field using Accessibility ACTION_SET_TEXT.
 */
@Serializable
@SerialName("input")
data class InputNode(
    override val id: String = UUID.randomUUID().toString(),
    override val position: NodePosition = NodePosition(),
    override val label: String = "Text Input",
    override val outputEdgeIds: List<String> = emptyList(),
    override val onFailureEdgeId: String? = null,
    override val timeoutMs: Long = 5_000L,
    val inputSource: InputSource = InputSource.Static(""),
    // If true, attempt to trigger IME search/enter after setting text
    val submitAfterInput: Boolean = false,
    // By default, it sets text on the currently focused editable.
    // Future enhancements could allow specifying a target element ID.
    val targetElementId: String? = null
) : FlowNode() {
    override val nodeType: FlowNodeType = FlowNodeType.INPUT
}
