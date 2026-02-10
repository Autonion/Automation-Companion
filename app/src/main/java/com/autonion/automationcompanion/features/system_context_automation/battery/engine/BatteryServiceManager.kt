package com.autonion.automationcompanion.features.system_context_automation.battery.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase

object BatteryServiceManager {

    fun startMonitoringIfNeeded(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.get(context).slotDao()
            val batterySlots = dao.getAllEnabled().filter { it.triggerType == "BATTERY" }

            if (batterySlots.isNotEmpty()) {
                BatteryMonitoringService.startService(context)
            }
        }
    }

    fun stopMonitoringIfNeeded(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.get(context).slotDao()
            val batterySlots = dao.getAllEnabled().filter { it.triggerType == "BATTERY" }

            if (batterySlots.isEmpty()) {
                BatteryMonitoringService.stopService(context)
            }
        }
    }
}