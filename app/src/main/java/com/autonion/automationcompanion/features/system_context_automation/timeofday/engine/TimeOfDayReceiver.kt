package com.autonion.automationcompanion.features.system_context_automation.timeofday.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.shared.executor.SlotExecutor
import com.autonion.automationcompanion.features.system_context_automation.shared.models.TriggerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.Calendar

/**
 * TimeOfDayReceiver fires when the alarm time is reached.
 * Uses AlarmManager to schedule the alarm for the next day after firing.
 */
class TimeOfDayReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TimeOfDayReceiver"
        const val ACTION_TIME_ALARM = "com.autonion.automationcompanion.action.TIME_OF_DAY_ALARM"
        const val EXTRA_SLOT_ID = "slotId"

        fun scheduleAlarm(context: Context, slotId: Long, hour: Int, minute: Int) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
            }

            // If the time has already passed today, schedule for tomorrow
            if (calendar.timeInMillis <= System.currentTimeMillis()) {
                calendar.add(Calendar.DATE, 1)
            }

            val intent = Intent(context, TimeOfDayReceiver::class.java).apply {
                action = ACTION_TIME_ALARM
                putExtra(EXTRA_SLOT_ID, slotId)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                slotId.toInt(),  // Unique ID per slot
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
                Log.i(TAG, "Scheduled alarm for slot $slotId at ${calendar.time}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule alarm", e)
            }
        }

        fun cancelAlarm(context: Context, slotId: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val intent = Intent(context, TimeOfDayReceiver::class.java).apply {
                action = ACTION_TIME_ALARM
                putExtra(EXTRA_SLOT_ID, slotId)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                slotId.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.cancel(pendingIntent)
            Log.i(TAG, "Cancelled alarm for slot $slotId")
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TIME_ALARM) return

        val slotId = intent.getLongExtra(EXTRA_SLOT_ID, -1L)
        if (slotId == -1L) return

        Log.i(TAG, "Time of day alarm fired for slot $slotId")

        CoroutineScope(Dispatchers.IO).launch {
            evaluateTimeOfDaySlot(context, slotId)
        }
    }

    private suspend fun evaluateTimeOfDaySlot(context: Context, slotId: Long) {
        val dao = AppDatabase.get(context).slotDao()
        val slot = dao.getById(slotId) ?: return

        if (slot.triggerType != "TIME_OF_DAY") return
        if (!slot.enabled) return

        // Execute actions
        Log.i(TAG, "Executing time-of-day slot $slotId")
        SlotExecutor.execute(context, slotId)

        // Schedule next day's alarm
        rescheduleAlarm(context, slot)
    }

    private suspend fun rescheduleAlarm(context: Context, slot: com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot) {
        val json = Json { ignoreUnknownKeys = true }
        val config = try {
            slot.triggerConfigJson?.let { json.decodeFromString<TriggerConfig.TimeOfDay>(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize time config for slot ${slot.id}", e)
            return
        } ?: return

        if (!config.repeatDaily) {
            Log.i(TAG, "Repeating disabled for slot ${slot.id}, not rescheduling")
            return
        }

        scheduleAlarm(context, slot.id, config.hour, config.minute)
    }
}
