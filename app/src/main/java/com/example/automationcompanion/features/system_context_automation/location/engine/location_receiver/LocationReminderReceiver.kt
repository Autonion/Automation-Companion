package com.example.automationcompanion.features.system_context_automation.location.engine.location_receiver

import android.R
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import android.provider.Settings
import android.util.Log

class LocationReminderReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_SLOT_ID = "slotId"
        const val ACTION_REMIND = "com.example.automationcompanion.ACTION_REMIND_LOCATION"
        private const val TAG = "LocationReminderReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        if (intent.action != ACTION_REMIND) return

        val slotId = intent.getLongExtra(EXTRA_SLOT_ID, -1L)
        if (slotId == -1L) {
            Log.w(TAG, "Reminder received without slotId")
            return
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        // Prefer isLocationEnabled() on API 28+, otherwise check GPS or NETWORK provider
        val locationOn: Boolean = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm?.isLocationEnabled ?: false
            } else {
                val gps = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false
                val net = lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ?: false
                gps || net
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed checking location providers: ${t.message}", t)
            false
        }

        Log.i(TAG, "Reminder fired for slot=$slotId; locationOn=$locationOn")

        if (locationOn) {
            // Cancel the repeating reminder — use identical Intent/action+extra & requestCode
            cancelReminders(context, slotId)
            return
        }

        // Location still OFF -> show notification
        showNotification(context, slotId)
    }

    private fun cancelReminders(context: Context, slotId: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            ("reminder_$slotId").hashCode(),
            Intent(context, LocationReminderReceiver::class.java).apply {
                action = ACTION_REMIND
                putExtra(EXTRA_SLOT_ID, slotId)
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
            Log.i(TAG, "Cancelled repeating reminder for slot=$slotId")
        } else {
            Log.i(TAG, "No repeating reminder pending to cancel for slot=$slotId")
        }
    }

    private fun showNotification(context: Context, slotId: Long) {
        val notifManager = context.getSystemService(NotificationManager::class.java)
        val channelId = "location_reminder"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Location Reminder", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Remind user to enable Location for scheduled automations"
            notifManager.createNotificationChannel(ch)
        }

        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pending = PendingIntent.getActivity(
            context,
            ("openloc_$slotId").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_dialog_alert)
            .setContentTitle("Turn ON Location")
            .setContentText("Automation will run soon. Please enable location.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notifManager.notify(("reminder_$slotId").hashCode(), notification)
        Log.i(TAG, "Posted reminder notification for slot=$slotId")
    }
}
