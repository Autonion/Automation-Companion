package com.example.automationcompanion.features.system_context_automation.location.helpers

import android.Manifest
import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.example.automationcompanion.features.system_context_automation.location.data.models.Slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Centralized helper that checks slot state/time, marks sent (to ensure once-only),
 * and performs the actual "send" action (dev-notification or SMS).
 *
 * Use SendHelper.startSendIfNeeded(context, slotId) from any caller (receiver/service).
 */
object SendHelper {
    private const val TAG = "SendHelper"
    private const val DEV_CHANNEL_ID = "automationcompanion_dev"

    /**
     * Entry point for callers. Launches an IO coroutine.
     */
    fun startSendIfNeeded(context: Context, slotId: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            doSendIfNeeded(context, slotId)
        }
    }

    /**
     * The main logic (runs on IO). Safe against quick duplicates because we update 'sent' before sending.
     */
    private suspend fun doSendIfNeeded(context: Context, slotId: Long) {
        try {
            val db = AppDatabase.get(context)
            val dao = db.slotDao()
            val slot = dao.getById(slotId)
            if (slot == null) {
                Log.w(TAG, "doSendIfNeeded: slot not found id=$slotId")
                return
            }

            val now = System.currentTimeMillis()

            // check time window
            if (now < slot.startMillis || now > slot.endMillis) {
                Log.i(TAG, "doSendIfNeeded: now outside slot window for $slotId (now=$now, start=${slot.startMillis}, end=${slot.endMillis})")
                return
            }

            // ensure only-once: if already sent, skip
            if (slot.sent) {
                Log.i(TAG, "doSendIfNeeded: slot $slotId already sent at ${slot.sentAt}, skipping.")
                return
            }

            // Mark as sent BEFORE actual sending to avoid concurrent duplicates
            val sentAt = System.currentTimeMillis()
            try {
                dao.updateSent(slotId, true, sentAt)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark slot as sent for $slotId: ${e.message}", e)
                // proceed carefully: try to continue but duplicates are possible if marking failed
            }

            // Now perform the send action (dev-notification; swap to SMS for production)
            doPerformSend(context, slot, sentAt)

            Log.i(TAG, "doSendIfNeeded: performed send for slot $slotId and marked sentAt=$sentAt")
        } catch (e: Exception) {
            Log.e(TAG, "doSendIfNeeded: unexpected error for slot $slotId: ${e.message}", e)
        }
    }

    /**
     * Posts notifications (DEV) and optionally sends SMS (commented). Caller must ensure SMS permission at runtime.
     */
    private fun doPerformSend(context: Context, slot: Slot, sentAt: Long) {
        ensureChannel(context)
        val numbers = slot.contactsCsv.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (numbers.isEmpty()) {
            Log.i(TAG, "doPerformSend: no contacts for slot ${slot.id}")
            return
        }

        // Check POST_NOTIFICATIONS permission once (Android 13+)
        val canPostNotifications: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (!canPostNotifications) {
            Log.w(TAG, "doPerformSend: POST_NOTIFICATIONS not granted, skipping local notifications")
            // We continue because we might still send SMS (if permitted). Notifications will be skipped.
        }

        // Check SEND_SMS permission once (if you plan to send SMS)
        val canSendSms: Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

        // DEV: post a notification per contact (if allowed)
        for (num in numbers) {
            try {
                if (canPostNotifications) {
                    val notif = NotificationCompat.Builder(context, DEV_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_dialog_info)
                        .setContentTitle("Reached — would send to $num")
                        .setContentText(slot.message)
                        .setAutoCancel(true)
                        .build()
                    NotificationManagerCompat.from(context).notify(("sent_${slot.id}_$num").hashCode(), notif)
                    Log.i(TAG, "doPerformSend: posted dev notification for $num")
                } else {
                    Log.i(TAG, "doPerformSend: skipping notification for $num because permission missing")
                }

                // Optional: real SMS sending (uncomment to enable). We check permission once above.

                if (canSendSms) {
                    val smsManager = SmsManager.getDefault()
                    try {
                        smsManager.sendTextMessage(num, null, slot.message, null, null)
                        Log.i(TAG, "doPerformSend: SMS sent to $num for slot ${slot.id}")
                    } catch (e: Exception) {
                        Log.e(TAG, "doPerformSend: failed to send SMS to $num: ${e.message}", e)
                    }
                } else {
                    Log.w(TAG, "doPerformSend: SEND_SMS not granted; skipping SMS for $num")
                }


            } catch (se: SecurityException) {
                Log.w(TAG, "doPerformSend: Notification failed due to missing permission for $num: ${se.message}")
            } catch (t: Throwable) {
                Log.e(TAG, "doPerformSend: unexpected error posting notification for $num: ${t.message}", t)
            }
        }
    }

    private fun ensureChannel(context: Context) {
        // create dev channel if needed (same channel id used in other code)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val existing = nm?.getNotificationChannel(DEV_CHANNEL_ID)
            if (existing == null) {
                nm?.createNotificationChannel(
                    NotificationChannel(
                        DEV_CHANNEL_ID,
                        "LocAuto Dev",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
        }
    }
}
