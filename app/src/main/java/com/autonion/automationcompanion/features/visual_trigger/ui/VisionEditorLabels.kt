package com.autonion.automationcompanion.features.visual_trigger.ui

import android.graphics.Rect
import com.autonion.automationcompanion.features.visual_trigger.models.ExecutionMode

internal val ExecutionMode.displayLabel: String
    get() = when (this) {
        ExecutionMode.DETECT_ONLY -> "React to matches"
        ExecutionMode.MANDATORY_SEQUENTIAL -> "Follow a sequence - wait"
        ExecutionMode.OPTIONAL_SEQUENTIAL -> "Follow a sequence - skip"
    }

internal fun isEntireScreenSearch(rect: Rect?, width: Int, height: Int, mode: ExecutionMode): Boolean =
    if (rect == null) mode == ExecutionMode.DETECT_ONLY
    else rect.left == 0 && rect.top == 0 && rect.right == width && rect.bottom == height
