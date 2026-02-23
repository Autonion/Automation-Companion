package com.autonion.automationcompanion.features.flow_automation.engine

/**
 * Shared constants for communication between the Flow Editor and feature overlays.
 *
 * When a flow node needs its overlay (gesture recording, vision trigger, screen ML),
 * the flow editor starts the overlay service/activity with FLOW_MODE extras.
 * The overlay does its thing, serializes results to a temp file, and broadcasts
 * one of the ACTION_FLOW_*_DONE intents. The ViewModel picks up the result.
 */
object FlowOverlayContract {

    // ─── Intent extras sent TO overlays ─────────────────────────────────────────

    /** Boolean extra — tells the overlay it's being launched for flow node config. */
    const val EXTRA_FLOW_MODE = "extra_flow_mode"

    /** String extra — the flow node ID requesting the overlay. */
    const val EXTRA_FLOW_NODE_ID = "extra_flow_node_id"

    // ─── Broadcast actions sent FROM overlays back to ViewModel ─────────────────

    /** Gesture recording finished — actions serialized to temp file. */
    const val ACTION_FLOW_GESTURE_DONE = "com.autonion.ACTION_FLOW_GESTURE_DONE"

    /** Vision trigger config finished — VisionPreset serialized to temp file. */
    const val ACTION_FLOW_VISION_DONE = "com.autonion.ACTION_FLOW_VISION_DONE"

    /** Screen ML config finished — automation steps serialized to temp file. */
    const val ACTION_FLOW_ML_DONE = "com.autonion.ACTION_FLOW_ML_DONE"

    // ─── Broadcast extras in result intents ─────────────────────────────────────

    /** String — the flow node ID (echoed back). */
    const val EXTRA_RESULT_NODE_ID = "extra_result_node_id"

    /** String — path to the temp JSON file with serialized data. */
    const val EXTRA_RESULT_FILE_PATH = "extra_result_file_path"

    /** String — path to captured image (for vision trigger / screen ML). */
    const val EXTRA_RESULT_IMAGE_PATH = "extra_result_image_path"
}
