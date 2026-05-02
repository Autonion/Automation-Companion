package com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

/**
 * Fires when a slot's start time arrives.
 * Automatically re-registers the geofence so it's active during the slot window.
 */
class SlotStartAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SlotStartAlarm"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != "com.autonion.automationcompanion.ACTION_SLOT_START") {
            Log.w(TAG, "Received unknown action: ${intent?.action}")
            return
        }

        val slotId = intent.getLongExtra("slotId", -1L)
        if (slotId == -1L) {
            Log.w(TAG, "No slotId in intent")
            return
        }

        Log.i(TAG, "Slot start time reached for slot=$slotId - re-registering geofence")

        // Acquire wake lock to ensure we complete the operation
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "automationcompanion:slot_start_wl")
        wl.acquire(10_000L) // hold for 10 seconds max

        try {
            // Trigger the tracking service to re-register the geofence
            TrackingForegroundService.startForSlot(context, slotId)
        } finally {
            if (wl.isHeld) wl.release()
        }
    }
}
