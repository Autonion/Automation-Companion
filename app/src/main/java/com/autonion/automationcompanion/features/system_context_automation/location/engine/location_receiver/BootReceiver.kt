package com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.battery.engine.BatteryServiceManager
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.AppInitManager
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAutomationController
import com.autonion.automationcompanion.features.system_context_automation.timeofday.engine.TimeOfDayReceiver
import com.autonion.automationcompanion.features.system_context_automation.wifi.engine.WiFiMonitorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

open class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val appContext = context.applicationContext
        if (action == Intent.ACTION_BOOT_COMPLETED) {
            WiFiMonitorManager.initialize(appContext)
            BatteryServiceManager.startMonitoringIfNeeded(appContext)
        }
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    // RTC alarms can point to a stale wall-clock time after a clock edit.
                    LocationAutomationController.mutex.withLock {
                        AppInitManager.update(appContext, false)
                    }
                    // Preserve the other feature's existing reboot recovery.
                    TimeOfDayReceiver.scheduleAllEnabled(appContext)
                    LocationAutomationController.reconcile(appContext, resetPresence = true)
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Could not restore automations after $action", e)
            } finally {
                result.finish()
            }
        }
    }
}
