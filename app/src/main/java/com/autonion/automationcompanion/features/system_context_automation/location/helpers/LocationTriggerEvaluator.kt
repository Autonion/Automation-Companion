package com.autonion.automationcompanion.features.system_context_automation.location.helpers

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

/** All location event sources share one eligibility check and one persistent occurrence claim. */
object LocationTriggerEvaluator {
    private const val TAG = "LocationTriggerEvaluator"

    suspend fun evaluate(context: Context, slotId: Long) {
        val claimedSlot = LocationAutomationController.mutex.withLock {
            if (!PermissionUtils.isLocationPermissionGranted(context) ||
                !LocationAutomationController.isLocationEnabled(context) ||
                !LocationAutomationController.hasCurrentGeofence(context, slotId)
            ) return@withLock null
            val dao = AppDatabase.get(context).slotDao()
            val slot = dao.getById(slotId) ?: return@withLock null
            if (!slot.enabled || slot.triggerType != "LOCATION" || !slot.isInsideGeofence) {
                return@withLock null
            }
            val window = LocationSchedule.activeWindow(slot) ?: return@withLock null
            if (dao.claimLocationExecution(slotId, window.dayKey) != 1) return@withLock null
            slot
        } ?: return

        // Claim before side effects: simultaneous sources cannot duplicate SMS/actions.
        // Release the lifecycle mutex so long action lists cannot block deletion or Stop.
        // An execution already claimed before a mutation may finish with this snapshot.
        try {
            SendHelper.executeClaimedLocationActions(context, claimedSlot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Failed to execute location slot $slotId", error)
            DebugLogger.error(
                context, LogCategory.SYSTEM_CONTEXT,
                "Location actions failed",
                "Slot $slotId: ${error.message}", TAG
            )
        }
    }
}
