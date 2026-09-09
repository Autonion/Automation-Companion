package com.autonion.automationcompanion.features.system_context_automation.location.helpers

import android.content.Context

/** Compatibility entry points; lifecycle cleanup is owned by the controller. */
object LocationHelper {
    fun unregisterGeofenceById(context: Context, slotId: Long) =
        LocationAutomationController.requestDisable(context, slotId)

    fun unregisterAllGeofences(context: Context) =
        LocationAutomationController.requestStopAll(context)
}
