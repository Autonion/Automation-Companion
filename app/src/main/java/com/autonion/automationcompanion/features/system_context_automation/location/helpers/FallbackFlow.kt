package com.autonion.automationcompanion.features.system_context_automation.location.helpers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.autonion.automationcompanion.R
import com.autonion.automationcompanion.features.system_context_automation.location.ui.EnableLocationActivity

object FallbackFlow {
    private const val CHANNEL_ID = "automationcompanion_fallback"
    private const val NOTIF_ID_ENABLE_LOCATION = 1001
    private const val NOTIF_ID_ENABLE_ACCESSIBILITY = 1002

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val chan = NotificationChannel(
                    CHANNEL_ID,
                    "Automation fallback",
                    NotificationManager.IMPORTANCE_HIGH
                )
                chan.description = "Notifications to help enable location/accessibility"
                nm.createNotificationChannel(chan)
            }
        }
    }

    /**
     * Called when a scheduled slot start attempted to toggle Location via Accessibility
     * but failed (e.g., Accessibility not granted or device locked). Opens an Activity
     * that prompts biometric auth or a settings panel for the user.
     */

    fun showEnableLocationNotification(ctx: Context) {
        ensureChannel(ctx)
        val intent = Intent(ctx, EnableLocationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_location) // replace with your icon
            .setContentTitle("Enable Location for automation")
            .setContentText("Tap to quickly enable Location so automation can run.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_ENABLE_LOCATION, notif)
    }

    /**
     * Used when Accessibility permission isn't enabled at all. This notification opens
     * the system Accessibility settings where the user must enable the service.
     */
    fun showEnableAccessibilityNotification(ctx: Context) {
        ensureChannel(ctx)
        val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx,
            0,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_accessibility) // replace with your icon
            .setContentTitle("Enable Automation Accessibility")
            .setContentText("Tap to open Accessibility settings and enable the automation helper.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_ENABLE_ACCESSIBILITY, notif)
    }
}
