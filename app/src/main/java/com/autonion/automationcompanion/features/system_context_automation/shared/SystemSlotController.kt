package com.autonion.automationcompanion.features.system_context_automation.shared

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.battery.engine.BatteryServiceManager
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAutomationController
import com.autonion.automationcompanion.features.system_context_automation.shared.models.TriggerConfig
import com.autonion.automationcompanion.features.system_context_automation.timeofday.engine.TimeOfDayReceiver
import com.autonion.automationcompanion.features.system_context_automation.wifi.engine.WiFiMonitorManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Owns non-location preset mutations. Uses the same lock as backup and trigger claims. */
object SystemSlotController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        Log.e("SystemSlots", "Could not reconcile system automations", error)
    })
    private val json = Json { ignoreUnknownKeys = true }
    private suspend fun <T> durable(block: suspend () -> T): T = scope.async { block() }.await()

    suspend fun save(context: Context, slot: Slot): Long = durable {
        LocationAutomationController.mutex.withLock {
            validate(slot)
            val dao = AppDatabase.get(context).slotDao()
            val old = if (slot.id == 0L) null else dao.getById(slot.id)
            require(slot.id == 0L || old?.triggerType == slot.triggerType) { "This automation was deleted" }
            val saved = slot.copy(enabled = old?.enabled ?: slot.enabled,
                lastExecutedDay = old?.lastExecutedDay,
                lastTriggerState = old?.takeIf { it.triggerConfigJson == slot.triggerConfigJson }?.lastTriggerState)
            val id = if (old == null) dao.insert(saved) else { dao.update(saved); saved.id }
            reconcileType(context, saved.triggerType)
            id
        }
    }

    suspend fun delete(context: Context, id: Long): Slot? = durable {
        LocationAutomationController.mutex.withLock {
            val dao = AppDatabase.get(context).slotDao()
            val slot = dao.getById(id) ?: return@withLock null
            require(slot.triggerType != "LOCATION")
            if (slot.triggerType == "TIME_OF_DAY") TimeOfDayReceiver.cancelAlarm(context, id)
            dao.delete(slot)
            reconcileType(context, slot.triggerType)
            slot
        }
    }

    suspend fun restore(context: Context, slot: Slot): Long = save(context,
        slot.copy(id = 0, isInsideGeofence = false, lastExecutedDay = null, lastTriggerState = null))

    suspend fun setEnabled(context: Context, id: Long, enabled: Boolean) = durable {
        LocationAutomationController.mutex.withLock {
            val dao = AppDatabase.get(context).slotDao()
            val slot = dao.getById(id) ?: return@withLock
            require(slot.triggerType != "LOCATION")
            if (enabled) validate(slot)
            dao.update(slot.copy(enabled = enabled, lastTriggerState = null,
                lastExecutedDay = if (enabled && !slot.enabled) null else slot.lastExecutedDay))
            reconcileType(context, slot.triggerType)
        }
    }

    suspend fun stopBattery(context: Context) = durable {
        LocationAutomationController.mutex.withLock {
            val dao = AppDatabase.get(context).slotDao()
            dao.getEnabledSlotsByType("BATTERY").forEach { dao.update(it.copy(enabled = false, lastTriggerState = null)) }
            BatteryServiceManager.reconcileMonitoring(context)
        }
    }

    fun requestRecovery(context: Context) { scope.launch { recover(context.applicationContext) } }

    suspend fun recover(context: Context) = durable {
        LocationAutomationController.mutex.withLock {
            TimeOfDayReceiver.reconcileAlarms(context)
            BatteryServiceManager.reconcileMonitoring(context)
            // Shared with cross-device events; deleting the last Wi-Fi preset must not disable that event source.
            if (AppDatabase.get(context).slotDao().getEnabledSlotsByType("WIFI").isNotEmpty()) WiFiMonitorManager.initialize(context)
        }
    }

    private suspend fun reconcileType(context: Context, type: String) {
        when (type) {
            "TIME_OF_DAY" -> TimeOfDayReceiver.reconcileAlarms(context)
            "BATTERY" -> BatteryServiceManager.reconcileMonitoring(context)
            "WIFI" -> WiFiMonitorManager.initialize(context)
        }
    }

    internal fun validate(slot: Slot) {
        val raw = requireNotNull(slot.triggerConfigJson) { "Missing trigger settings" }
        when (slot.triggerType) {
            "BATTERY" -> require(json.decodeFromString<TriggerConfig.Battery>(raw).batteryPercentage in 0..100)
            "TIME_OF_DAY" -> json.decodeFromString<TriggerConfig.TimeOfDay>(raw).let {
                require(it.hour in 0..23 && it.minute in 0..59) { "Invalid time" }
                require(TimeOfDayReceiver.nextAlarmMillis(it) != null) { "Choose at least one active day" }
            }
            "WIFI" -> json.decodeFromString<TriggerConfig.WiFi>(raw)
            "APP" -> require(json.decodeFromString<TriggerConfig.App>(raw).packageName.isNotBlank())
            else -> error("Unknown trigger type: ${slot.triggerType}")
        }
    }
}
