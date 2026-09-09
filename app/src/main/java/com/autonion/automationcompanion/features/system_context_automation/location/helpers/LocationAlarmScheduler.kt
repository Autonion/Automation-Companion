package com.autonion.automationcompanion.features.system_context_automation.location.helpers

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.LocationReminderReceiver
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.SlotStartAlarmReceiver

/** Owns the stable alarm identities used by save, delete, disable, reboot and Undo. */
object LocationAlarmScheduler {
    const val ACTION_SLOT_START = "com.autonion.automationcompanion.ACTION_SLOT_START"
    const val EXTRA_OCCURRENCE_START = "occurrenceStartMillis"
    private const val REMINDER_INTERVAL_MILLIS = 3 * 60_000L

    fun schedule(context: Context, slot: Slot) {
        cancel(context, slot.id)
        if (!slot.enabled || slot.triggerType != "LOCATION") return
        val now = System.currentTimeMillis()
        val next = LocationSchedule.nextWindow(slot, now) ?: return
        setAlarm(context, next.startMillis, startIntent(context, slot.id, PendingIntent.FLAG_UPDATE_CURRENT)!!)
        if (slot.remindBeforeMinutes > 0) {
            val leadStart = next.startMillis - slot.remindBeforeMinutes.toLong() * 60_000L
            scheduleReminderAt(context, slot.id, maxOf(now + 1, leadStart), next.startMillis)
        }
    }

    fun cancel(context: Context, slotId: Long) {
        cancelIntent(context, startIntent(context, slotId, PendingIntent.FLAG_NO_CREATE))
        cancelReminder(context, slotId)
    }

    fun cancelReminder(context: Context, slotId: Long) {
        cancelIntent(context, reminderIntent(context, slotId, 0, PendingIntent.FLAG_NO_CREATE))
        context.getSystemService(NotificationManager::class.java)?.cancel(("reminder_$slotId").hashCode())
    }

    /** Only called after a valid reminder was delivered; never rearms immediately. */
    fun rearmReminder(context: Context, slot: Slot, nowMillis: Long = System.currentTimeMillis()) {
        val next = LocationSchedule.nextWindow(slot, nowMillis) ?: return
        if (slot.remindBeforeMinutes <= 0 || nowMillis < next.startMillis - slot.remindBeforeMinutes.toLong() * 60_000L) return
        val nextReminder = nowMillis + REMINDER_INTERVAL_MILLIS
        if (nextReminder < next.startMillis) scheduleReminderAt(context, slot.id, nextReminder, next.startMillis)
    }

    private fun scheduleReminderAt(context: Context, slotId: Long, atMillis: Long, occurrenceStart: Long) {
        if (atMillis >= occurrenceStart) return
        setAlarm(context, atMillis, reminderIntent(context, slotId, occurrenceStart, PendingIntent.FLAG_UPDATE_CURRENT)!!)
    }

    private fun startIntent(context: Context, slotId: Long, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, ("slot_start_$slotId").hashCode(),
            Intent(context, SlotStartAlarmReceiver::class.java).apply {
                action = ACTION_SLOT_START
                putExtra(LocationReminderReceiver.EXTRA_SLOT_ID, slotId)
            }, flags or PendingIntent.FLAG_IMMUTABLE)

    private fun reminderIntent(context: Context, slotId: Long, occurrenceStart: Long, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, ("reminder_$slotId").hashCode(),
            Intent(context, LocationReminderReceiver::class.java).apply {
                action = LocationReminderReceiver.ACTION_REMIND
                putExtra(LocationReminderReceiver.EXTRA_SLOT_ID, slotId)
                putExtra(EXTRA_OCCURRENCE_START, occurrenceStart)
            }, flags or PendingIntent.FLAG_IMMUTABLE)

    private fun cancelIntent(context: Context, pendingIntent: PendingIntent?) {
        if (pendingIntent == null) return
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun setAlarm(context: Context, atMillis: Long, pendingIntent: PendingIntent) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
            }
        } catch (e: SecurityException) {
            // Permission can be revoked between checking it and scheduling.
            Log.w("LocationAlarms", "Exact alarm unavailable; using inexact alarm", e)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
        }
    }
}
