package com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAlarmScheduler
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAutomationController
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class LocationReminderReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_SLOT_ID = "slotId"
        const val ACTION_REMIND = "com.autonion.automationcompanion.ACTION_REMIND_LOCATION"
        const val ACTION_SEND_AT_START = "ACTION_SEND_AT_START"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_REMIND) return
        val slotId = intent.getLongExtra(EXTRA_SLOT_ID, -1L)
        if (slotId < 0) return
        val expectedStart = intent.getLongExtra(LocationAlarmScheduler.EXTRA_OCCURRENCE_START, -1L)
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    LocationAutomationController.mutex.withLock {
                        val appContext = context.applicationContext
                        val slot = AppDatabase.get(appContext).slotDao().getById(slotId)
                        if (slot == null || !slot.enabled || slot.triggerType != "LOCATION" || slot.remindBeforeMinutes <= 0) {
                            LocationAlarmScheduler.cancelReminder(appContext, slotId)
                            return@withLock
                        }
                        val now = System.currentTimeMillis()
                        val next = LocationSchedule.nextWindow(slot, now) ?: return@withLock
                        // An already queued old delivery must not affect a newly
                        // scheduled occurrence or post after the old start time.
                        if (expectedStart > 0 && expectedStart != next.startMillis) return@withLock
                        val leadStart = next.startMillis - slot.remindBeforeMinutes.toLong() * 60_000L
                        if (now < leadStart || now >= next.startMillis) return@withLock
                        if (isLocationOn(appContext)) {
                            LocationAlarmScheduler.cancelReminder(appContext, slotId)
                            return@withLock
                        }
                        showNotification(appContext, slotId, next.startMillis - now)
                        LocationAlarmScheduler.rearmReminder(appContext, slot, now)
                    }
                }
            } catch (e: Exception) {
                Log.e("LocationReminder", "Could not process reminder for $slotId", e)
            } finally {
                result.finish()
            }
        }
    }

    private fun isLocationOn(context: Context): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.isLocationEnabled
            else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            Log.w("LocationReminder", "Could not read location availability", e)
            false
        }
    }

    private fun showNotification(context: Context, slotId: Long, remainingMillis: Long) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channelId = "location_reminder"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Location Reminder", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Remind user to enable Location for scheduled automations"
                }
            )
        }
        val pending = PendingIntent.getActivity(context, ("openloc_$slotId").hashCode(),
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_dialog_alert)
            .setContentTitle("Turn ON Location")
            .setContentText("Automation will run soon. Please enable location.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(remainingMillis)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(("reminder_$slotId").hashCode(), notification)
    }
}
