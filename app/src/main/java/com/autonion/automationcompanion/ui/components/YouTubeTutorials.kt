package com.autonion.automationcompanion.ui.components

object YouTubeTutorials {
    // Replace these placeholder links with your actual YouTube tutorial URLs when ready
    const val GESTURE = "https://www.youtube.com/watch?v=gesture_tutorial_placeholder"
    const val SCREEN_ML = "https://www.youtube.com/watch?v=screen_ml_tutorial_placeholder"
    const val VISUAL_TRIGGER = "https://www.youtube.com/watch?v=visual_trigger_tutorial_placeholder"
    const val FLOW_BUILDER = "https://www.youtube.com/watch?v=flow_builder_tutorial_placeholder"
    const val SEMANTIC_AUTOMATION = "https://www.youtube.com/watch?v=semantic_automation_tutorial_placeholder"
    const val SYSTEM_CONTEXT = "https://www.youtube.com/watch?v=system_context_tutorial_placeholder"
    const val DEBUGGER = "https://www.youtube.com/watch?v=debugger_tutorial_placeholder"
    const val CROSS_DEVICE = "https://www.youtube.com/watch?v=cross_device_tutorial_placeholder"

    fun getUrl(featureId: String): String? {
        return when (featureId) {
            "gesture_recording" -> GESTURE
            "screen_ml" -> SCREEN_ML
            "visual_trigger" -> VISUAL_TRIGGER
            "flow_builder" -> FLOW_BUILDER
            "semantic_automation" -> SEMANTIC_AUTOMATION
            "system_context" -> SYSTEM_CONTEXT
            "debugger" -> DEBUGGER
            "cross_device" -> CROSS_DEVICE
            else -> null
        }
    }
}
