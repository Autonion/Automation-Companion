package com.autonion.automationcompanion.features.system_context_automation.location.helpers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.location.engine.time.TimeTickReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AppInitManager {
    fun init(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Reuse the registry-aware lifecycle decision, including any cleanup
                // retries left after the last enabled slot was removed.
                LocationAutomationController.ensureMonitoring(appContext)
            } catch (e: Exception) {
                Log.e("LocationInit", "Could not initialize location tick", e)
            }
        }
    }

    /** Keep the fallback tick only while location automations are enabled. */
    fun update(context: Context, hasEnabledLocationSlots: Boolean) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, TimeTickReceiver::class.java).apply {
            action = TimeTickReceiver.ACTION_INTERNAL_TIME_TICK
        }
        val existing = PendingIntent.getBroadcast(context, 2001, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (!hasEnabledLocationSlots) {
            existing?.let {
                manager.cancel(it)
                it.cancel()
            }
            return
        }
        // Reconciliation may run frequently; do not move an already armed tick.
        if (existing != null) return
        val pending = PendingIntent.getBroadcast(context, 2001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60_000L, 60_000L, pending)
    }
}
