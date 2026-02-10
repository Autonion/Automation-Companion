package com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MidnightResetReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i("MidnightReset", "Midnight reset triggered")

        // Re-register all geofences cleanly
        TrackingForegroundService.startAll(context)
    }
}
