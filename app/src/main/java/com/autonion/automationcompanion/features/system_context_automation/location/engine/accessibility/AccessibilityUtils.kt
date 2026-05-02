package com.autonion.automationcompanion.features.system_context_automation.location.engine.accessibility

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.autonion.automationcompanion.features.gesture_recording_playback.overlay.AutomationService

fun isAutomationAccessibilityEnabled(context: Context): Boolean {
    val expectedComponent = ComponentName(
        context,
        AutomationService::class.java
    ).flattenToString()


    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    return enabledServices
        .split(':')
        .any { it.equals(expectedComponent, ignoreCase = true) }
}
