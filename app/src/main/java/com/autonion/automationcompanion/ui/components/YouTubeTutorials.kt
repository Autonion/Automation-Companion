package com.autonion.automationcompanion.ui.components

object YouTubeTutorials {
    // Replace these placeholder links with your actual YouTube tutorial URLs when ready
    const val GESTURE = "https://youtu.be/9Oy_GG5JbKM"
    const val SCREEN_ML = "https://youtu.be/2dTMgr4hKRc"
    const val VISUAL_TRIGGER = "https://youtu.be/zRNgHQE_CGQ"
    const val FLOW_BUILDER = "https://youtu.be/91rrlWK0Gyk"
    const val SEMANTIC_AUTOMATION = "https://youtu.be/JgEbAtQ4Mos"
    const val SYSTEM_CONTEXT = "https://youtu.be/-0E6HDqMrvQ"
    const val CROSS_DEVICE = "https://youtu.be/8lM1ibF_swM"
    const val OMNI_CHAT = "https://youtu.be/EnItA5BEKrU"

    fun getUrl(featureId: String): String? {
        return when (featureId) {
            "gesture_recording" -> GESTURE
            "screen_ml" -> SCREEN_ML
            "visual_trigger" -> VISUAL_TRIGGER
            "flow_builder" -> FLOW_BUILDER
            "semantic_automation" -> SEMANTIC_AUTOMATION
            "system_context" -> SYSTEM_CONTEXT
            "cross_device" -> CROSS_DEVICE
            "omni_chat" -> OMNI_CHAT
            else -> null
        }
    }
}
