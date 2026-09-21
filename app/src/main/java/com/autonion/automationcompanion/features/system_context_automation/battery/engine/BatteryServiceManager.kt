package com.autonion.automationcompanion.features.system_context_automation.battery.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase

object BatteryServiceManager {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        Log.e("BatteryServiceManager", "Battery monitoring could not resume", error)
    })

    suspend fun reconcileMonitoring(context: Context) = mutex.withLock {
        val enabled = AppDatabase.get(context).slotDao().getEnabledSlotsByType("BATTERY").isNotEmpty()
        if (enabled) BatteryMonitoringService.startService(context) else BatteryMonitoringService.stopService(context)
    }

    fun startMonitoringIfNeeded(context: Context) {
        scope.launch { reconcileMonitoring(context.applicationContext) }
    }

    fun stopMonitoringIfNeeded(context: Context) {
        scope.launch { reconcileMonitoring(context.applicationContext) }
    }
}
