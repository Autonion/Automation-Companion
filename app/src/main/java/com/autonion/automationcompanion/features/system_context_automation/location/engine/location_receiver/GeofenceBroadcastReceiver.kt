package com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAutomationController
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationTriggerEvaluator
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        val appContext = context.applicationContext
        if (event.hasError()) {
            Log.w(TAG, "Geofence error: ${event.errorCode}")
            DebugLogger.error(
                appContext, LogCategory.SYSTEM_CONTEXT, "Geofence error",
                "Error code: ${event.errorCode}", TAG
            )
            if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                // Play Services can discard its registrations when the provider goes away.
                // Never keep trusting the old registry or its persisted inside flags.
                launchBroadcastWork {
                    LocationAutomationController.reconcile(appContext, resetPresence = true)
                }
            }
            return
        }

        val transition = event.geofenceTransition
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER &&
            transition != Geofence.GEOFENCE_TRANSITION_EXIT
        ) return
        val triggering = event.triggeringGeofences ?: return

        launchBroadcastWork {
            val dao = AppDatabase.get(appContext).slotDao()
            val inside = transition == Geofence.GEOFENCE_TRANSITION_ENTER
            for (geofence in triggering) {
                val slotId = LocationAutomationController.mutex.withLock {
                    // Generation IDs reject queued events from removed/edited registrations.
                    val id = LocationAutomationController.slotIdForCurrentGeofence(
                        appContext, geofence.requestId
                    ) ?: return@withLock null
                    val slot = dao.getById(id) ?: return@withLock null
                    if (!slot.enabled || slot.triggerType != "LOCATION") return@withLock null
                    dao.updateInsideGeofence(id, inside)
                    id
                } ?: continue

                Log.i(TAG, "Slot $slotId: insideGeofence = $inside")
                DebugLogger.info(
                    appContext, LogCategory.SYSTEM_CONTEXT,
                    if (inside) "Geofence entered" else "Geofence exited",
                    "Slot $slotId ${if (inside) "entered" else "left"} geofence zone", TAG
                )
                // Release the mutation lock before entering the evaluator, which rechecks it.
                if (inside) LocationTriggerEvaluator.evaluate(appContext, slotId)
            }
        }
    }

    private fun launchBroadcastWork(block: suspend () -> Unit) {
        val pendingResult = goAsync()
        // Only the broadcast wait is bounded. A slow lifecycle operation must not cause
        // an EXIT queued behind its mutex to be cancelled and leave stale presence.
        val processing = eventScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Unable to handle geofence event", error)
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) { processing.join() }
            } catch (_: TimeoutCancellationException) {
                Log.w(TAG, "Geofence event is finishing in the application scope")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "GeofenceBroadcastReceiver"
        private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
