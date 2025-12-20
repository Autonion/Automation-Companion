package com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.location.engine.accessibility.TileToggleFeature
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.FallbackFlow
//import com.example.automationcompanion.core.helpers.RootLocationToggle
import com.autonion.automationcompanion.features.system_context_automation.location.isAccessibilityEnabled

//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//import kotlin.time.Duration.Companion.seconds

class SlotAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SlotAlarmReceiver"
//        private const val TAG_ROOT = "HandleStartRoot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val slotId = intent.getLongExtra("slotId", -1L)

        Log.i(TAG, "Received alarm action=$action slotId=$slotId")

//        if (slotId == -1L) {
//            // If no slotId provided, fall back to generic behavior (optional)
//        }

        when (action) {
            "com.example.automationcompanion.START_SLOT" -> {
                handleStart(context, slotId)
            }
            "com.example.automationcompanion.STOP_SLOT" -> {
                handleStop(context, slotId)
            }
            else -> {
                Log.w(TAG, "Unknown action: $action")
            }
        }
    }

    /**
     * Acquire a short wake lock and run the provided block.
     * Keeps device awake for up to 15s while we perform work.
     */
    private fun wakeAndDo(context: Context, block: () -> Unit) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "automationcompanion:slot_wl")
            wl.acquire(15_000L) // hold for 15 seconds max
            try {
                block()
            } finally {
                if (wl.isHeld) wl.release()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wakeAndDo failed: ${t.message}")
            // best-effort: still attempt the action
            try { block() } catch (_: Throwable) {}
        }
    }

    private fun handleStart(context: Context, slotId: Long) {
        wakeAndDo(context) {
            // cancel reminder (use same intent/requestCode as scheduled)
            // inside handleStart before starting tracking
            val reminderIntent = Intent(context, LocationReminderReceiver::class.java).apply {
                action = LocationReminderReceiver.ACTION_REMIND
                putExtra(LocationReminderReceiver.EXTRA_SLOT_ID, slotId)
            }

            val reminderPi = PendingIntent.getBroadcast(
                context,
                ("reminder_$slotId").hashCode(),
                reminderIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (reminderPi != null) {
                val am = context.getSystemService(AlarmManager::class.java)
                am.cancel(reminderPi)
                reminderPi.cancel()
                Log.i(TAG, "Cancelled reminder for slot=$slotId from handleStart")
            } else {
                Log.i(TAG, "No existing reminder to cancel for slot=$slotId from handleStart")
            }


            // Try accessibility-based toggle first (non-blocking)
            if (isAccessibilityEnabled(context)) {

                TileToggleFeature.toggleLocation { success ->
                    Log.i(TAG, "Accessibility toggle attempt result: $success")

                    // Start tracking service regardless; service will verify Location status
                    if (slotId != -1L) {
                        TrackingForegroundService.startForSlot(context, slotId)
                    } else {
                        TrackingForegroundService.start(context)
                    }
                }

            } else {
                // Accessibility not enabled — fallback
                if (slotId != -1L) {
                    TrackingForegroundService.startForSlot(context, slotId)
                } else {
                    TrackingForegroundService.start(context)
                }

                FallbackFlow.showEnableAccessibilityNotification(context)
            }
        }
    }


//    fun handleStartWithRootOption(context: Context, slotId: Long, useRootToggleForThisSlot: Boolean = false) {
//        // run in background
//        CoroutineScope(Dispatchers.IO).launch {
//            val prefs = context.getSharedPreferences("automation_prefs", Context.MODE_PRIVATE)
//            val globalRootOptIn = prefs.getBoolean("root_toggle_enabled", false)
//            val rootOptIn = globalRootOptIn || useRootToggleForThisSlot
//
//            var didRootToggle = false
//            var prevMode: Int? = null
//
//            if (rootOptIn) {
//                try {
//                    val suOk = RootLocationToggle.isSuAvailable()
//                    if (!suOk) {
//                        Log.w(TAG_ROOT, "su not available on device; falling back to manual flow")
//                    } else {
//                        // read current mode
//                        prevMode = RootLocationToggle.getLocationModeViaSu()
//                        if (prevMode == null) {
//                            Log.w(TAG_ROOT, "couldn't read prevMode via su")
//                        } else if (prevMode == 0) {
//                            // location currently OFF -> attempt silent enable
//                            val returnedPrev = RootLocationToggle.enableLocationSilently(context, timeout = 10.seconds)
//                            if (returnedPrev != null) {
//                                didRootToggle = true
//                                prevMode = returnedPrev
//                                Log.i(TAG_ROOT, "Root toggle succeeded; will restore to $prevMode later")
//                            } else {
//                                Log.w(TAG_ROOT, "Root toggle attempt failed; falling back")
//                            }
//                        } else {
//                            // already on; nothing to do
//                            Log.i(TAG_ROOT, "location already enabled (mode=$prevMode)")
//                        }
//                    }
//                } catch (t: Throwable) {
//                    Log.w(TAG_ROOT, "root toggle flow failed: ${t.message}", t)
//                }
//            }
//
//            // After attempting root toggle, ensure location is enabled (otherwise fallback)
//            val locationOn = RootLocationToggle.isLocationServicesEnabled(context)
//            if (!locationOn) {
//                // fallback: show notification to enable location and still start tracking (service will handle geofence)
//                FallbackFlow.showEnableLocationNotification(context)
//                // start tracking even if location off — TrackingForegroundService can register geofence etc.
//                TrackingForegroundService.startForSlot(context, slotId)
//            } else {
//                // location on -> proceed: start service + send message if required
//                TrackingForegroundService.startForSlot(context, slotId)
//
//                // If your pattern requires "send message immediately at slot start", call your sending helper here.
//                // Example:
//                // SendHelper.sendMessageForSlot(context, slotId)
//            }
//
//            // Restore previous mode if we toggled via root
//            if (didRootToggle && prevMode != null) {
//                try {
//                    val ok = RootLocationToggle.restoreLocationMode(prevMode)
//                    if (!ok) {
//                        Log.w(TAG_ROOT, "Failed to restore location mode to $prevMode")
//                        // optionally notify the user with a persistent notification
//                    } else {
//                        Log.i(TAG_ROOT, "Successfully restored previous location mode $prevMode")
//                    }
//                } catch (t: Throwable) {
//                    Log.w(TAG_ROOT, "Error while restoring mode: ${t.message}", t)
//                }
//            }
//        }
//    }

    private fun handleStop(context: Context, slotId: Long) {
        wakeAndDo(context) {

            if (isAccessibilityEnabled(context)) {

                // Try to toggle off via Accessibility (non-blocking)
                TileToggleFeature.toggleLocation { success ->
                    Log.i(TAG, "Accessibility toggle (stop) attempt: $success")

                    if (slotId != -1L) {
                        TrackingForegroundService.stopForSlot(context, slotId)
                    } else {
                        TrackingForegroundService.stop(context)
                    }
                }

            } else {
                // Accessibility not enabled — just stop the service
                if (slotId != -1L) {
                    TrackingForegroundService.stopForSlot(context, slotId)
                } else {
                    TrackingForegroundService.stop(context)
                }
            }
        }
    }

}
