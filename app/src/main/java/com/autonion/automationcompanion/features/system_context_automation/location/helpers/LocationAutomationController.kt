package com.autonion.automationcompanion.features.system_context_automation.location.helpers

import android.content.Context
import android.app.NotificationManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.MidnightResetScheduler
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.TrackingForegroundService
import com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One owner for database mutations, alarms, geofences and the monitoring notification. */
object LocationAutomationController {
    val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        Log.e("LocationAutomation", "Location lifecycle operation failed", error)
    })

    // A screen disappearing must not cancel a partially completed delete/save.
    private suspend fun <T> durable(block: suspend () -> T): T = scope.async { block() }.await()

    fun requestReconcile(context: Context, resetPresence: Boolean = false) {
        val app = context.applicationContext
        scope.launch { reconcile(app, resetPresence) }
    }

    fun requestDisable(context: Context, slotId: Long) {
        val app = context.applicationContext
        scope.launch { setEnabled(app, slotId, false) }
    }

    fun requestStopAll(context: Context) {
        val app = context.applicationContext
        scope.launch { stopAll(app) }
    }

    internal fun retireAbandonedRegistration(context: Context, tokens: Set<String>) {
        val app = context.applicationContext
        scope.launch {
            mutex.withLock {
                LocationGeofenceRegistry.retireTokens(app, tokens)
                reconcileLocked(app)
            }
        }
    }

    fun requestAlarm(context: Context, slotId: Long) {
        val app = context.applicationContext
        scope.launch { onAlarm(app, slotId) }
    }

    suspend fun save(context: Context, slot: Slot): Long = saveInternal(context, slot, preserveLocalClock = false)

    private suspend fun saveInternal(context: Context, slot: Slot, preserveLocalClock: Boolean): Long = durable {
        val app = context.applicationContext
        val id = mutex.withLock {
            require(slot.triggerType == "LOCATION" && LocationGeofenceRegistry.validCoordinates(slot)) {
                "Choose valid location coordinates and a positive radius"
            }
            require(LocationSchedule.nextWindow(slot) != null) { "Choose valid times and at least one day" }
            val dao = AppDatabase.get(app).slotDao()
            val old = if (slot.id != 0L) dao.getById(slot.id) else null
            require(slot.id == 0L || old?.triggerType == "LOCATION") { "This location automation was deleted" }
            val clocked = if (preserveLocalClock) LocationSchedule.ensureLocalClock(slot)
                else LocationSchedule.withLocalClock(slot)
            val saved = clocked.copy(
                enabled = old?.enabled ?: slot.enabled,
                lastExecutedDay = old?.lastExecutedDay ?: slot.lastExecutedDay,
                isInsideGeofence = old?.takeIf {
                    it.lat == slot.lat && it.lng == slot.lng && it.radiusMeters == slot.radiusMeters
                }?.isInsideGeofence ?: false
            )
            require(!saved.enabled || dao.getEnabledSlotsByType("LOCATION").count { it.id != saved.id } < 100) {
                "Android supports up to 100 enabled location automations"
            }
            val savedId = if (old == null) dao.insert(saved) else {
                dao.update(saved)
                saved.id
            }
            reconcileLocked(app, changedSlotId = savedId)
            savedId
        }
        LocationTriggerEvaluator.evaluate(app, id)
        id
    }

    suspend fun delete(context: Context, slotId: Long): Slot? = durable {
        mutex.withLock {
            val app = context.applicationContext
            val dao = AppDatabase.get(app).slotDao()
            val slot = dao.getById(slotId)?.takeIf { it.triggerType == "LOCATION" } ?: return@withLock null
            // Capture legacy IDs before dropping the only DB record that identifies them.
            LocationGeofenceRegistry.retireSlot(app, slotId)
            LocationAlarmScheduler.cancel(app, slotId)
            dao.delete(slot)
            reconcileLocked(app)
            slot
        }
    }

    suspend fun restore(context: Context, slot: Slot): Long = saveInternal(
        context, slot.copy(id = 0, isInsideGeofence = false), preserveLocalClock = true
    )

    suspend fun setEnabled(context: Context, slotId: Long, enabled: Boolean) = durable {
        mutex.withLock {
            val app = context.applicationContext
            val dao = AppDatabase.get(app).slotDao()
            val slot = dao.getById(slotId)?.takeIf { it.triggerType == "LOCATION" } ?: return@withLock
            if (enabled) {
                require(LocationGeofenceRegistry.validCoordinates(slot) && LocationSchedule.nextWindow(slot) != null) {
                    "Edit this automation to choose valid location and schedule settings"
                }
                require(dao.getEnabledSlotsByType("LOCATION").count { it.id != slotId } < 100) {
                    "Android supports up to 100 enabled location automations"
                }
            }
            dao.update(slot.copy(enabled = enabled, isInsideGeofence = false))
            LocationGeofenceRegistry.retireSlot(app, slotId)
            reconcileLocked(app, changedSlotId = slotId)
        }
    }

    /** Stop is persistent: it disables location slots, retaining them for explicit re-enabling. */
    suspend fun stopAll(context: Context) = durable {
        mutex.withLock {
            val app = context.applicationContext
            val dao = AppDatabase.get(app).slotDao()
            dao.getLocationSlots().forEach {
                LocationAlarmScheduler.cancel(app, it.id)
                LocationGeofenceRegistry.retireSlot(app, it.id)
            }
            dao.disableLocationSlots()
            reconcileLocked(app)
        }
    }

    suspend fun reconcile(context: Context, resetPresence: Boolean = false) = durable {
        mutex.withLock { reconcileLocked(context.applicationContext, resetPresence, rescheduleAll = true) }
    }

    /** The fallback tick retries unfinished API work, without repeatedly rescheduling alarms. */
    suspend fun ensureMonitoring(context: Context) = durable {
        mutex.withLock { reconcileLocked(context.applicationContext) }
    }

    suspend fun onAlarm(context: Context, slotId: Long) = durable {
        val app = context.applicationContext
        mutex.withLock {
            val slot = AppDatabase.get(app).slotDao().getById(slotId)
            if (slot == null || !slot.enabled || slot.triggerType != "LOCATION") {
                LocationAlarmScheduler.cancel(app, slotId)
                LocationGeofenceRegistry.retireSlot(app, slotId)
                reconcileLocked(app)
                return@withLock
            }
            reconcileLocked(app, changedSlotId = slotId)
        }
        LocationTriggerEvaluator.evaluate(app, slotId)
    }

    private suspend fun reconcileLocked(
        context: Context,
        resetPresence: Boolean = false,
        rescheduleAll: Boolean = false,
        changedSlotId: Long? = null
    ) {
        val dao = AppDatabase.get(context).slotDao()
        if (resetPresence) dao.resetLocationPresence()
        val slots = dao.getLocationSlots().map { slot ->
            LocationSchedule.ensureLocalClock(slot).also { if (it != slot) dao.update(it) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val reminderIds = slots.filter { it.enabled && it.remindBeforeMinutes > 0 }
                .map { ("reminder_${it.id}").hashCode() }.toSet()
            val notifications = context.getSystemService(NotificationManager::class.java)
            notifications?.activeNotifications?.filter {
                it.notification.channelId == "location_reminder" && it.id !in reminderIds
            }?.forEach { notifications.cancel(it.tag, it.id) }
        }
        if (slots.none { it.enabled } || !PermissionUtils.isLocationPermissionGranted(context) ||
            !isLocationEnabled(context)) {
            // Remove status immediately; Play Services cleanup may need a retry.
            TrackingForegroundService.stop(context)
            dao.resetLocationPresence()
        }
        slots.forEach { slot ->
            if (!slot.enabled) LocationAlarmScheduler.cancel(context, slot.id)
            else if (rescheduleAll || slot.id == changedSlotId) LocationAlarmScheduler.schedule(context, slot)
        }
        val registered = LocationGeofenceRegistry.synchronize(context, slots, resetPresence)
        AppInitManager.update(context, slots.any { it.enabled } || LocationGeofenceRegistry.hasPendingCleanup(context))
        // Daily evaluation uses occurrence dates; no global midnight restart is required.
        MidnightResetScheduler.cancel(context)
        if (registered.isEmpty()) TrackingForegroundService.stop(context)
        else TrackingForegroundService.showMonitoring(context)
    }

    fun slotIdForCurrentGeofence(context: Context, requestId: String): Long? =
        LocationGeofenceRegistry.currentSlotId(context, requestId)

    fun hasCurrentGeofence(context: Context, slotId: Long): Boolean =
        slotId in LocationGeofenceRegistry.registeredSlotIds(context)

    suspend fun monitoringCount(context: Context): Int = mutex.withLock {
        if (!PermissionUtils.isLocationPermissionGranted(context) || !isLocationEnabled(context)) return@withLock 0
        val ids = LocationGeofenceRegistry.registeredSlotIds(context)
        AppDatabase.get(context).slotDao().getEnabledSlotsByType("LOCATION").count { it.id in ids }
    }

    fun isLocationEnabled(context: Context): Boolean = try {
        val manager = context.getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager?.isLocationEnabled == true
        else manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
            manager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
    } catch (_: Exception) { false }
}
