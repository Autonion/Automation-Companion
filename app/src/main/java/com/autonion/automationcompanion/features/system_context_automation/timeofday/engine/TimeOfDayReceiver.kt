package com.autonion.automationcompanion.features.system_context_automation.timeofday.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAutomationController
import com.autonion.automationcompanion.features.system_context_automation.shared.executor.SlotExecutor
import com.autonion.automationcompanion.features.system_context_automation.shared.models.TriggerConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.ZonedDateTime

class TimeOfDayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TIME_ALARM) return
        val id = intent.getLongExtra(EXTRA_SLOT_ID, -1)
        if (id <= 0) return
        val pending = goAsync()
        scope.launch {
            try { handleAlarm(context.applicationContext, id) }
            finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_TIME_ALARM = "com.autonion.automationcompanion.action.TIME_OF_DAY_ALARM"
        const val EXTRA_SLOT_ID = "slotId"
        private val json = Json { ignoreUnknownKeys = true }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            Log.e("TimeOfDayReceiver", "Time alarm failed", error)
        })
        private fun prefs(context: Context) = context.getSharedPreferences("time_alarm_registry", Context.MODE_PRIVATE)

        internal fun isActiveDay(days: String, day: DayOfWeek): Boolean = days == "ALL" ||
            day.name.take(3) in days.split(',').map { it.trim().uppercase() }

        internal fun nextAlarmMillis(config: TriggerConfig.TimeOfDay, now: ZonedDateTime = ZonedDateTime.now()): Long? {
            if (config.hour !in 0..23 || config.minute !in 0..59) return null
            for (offset in 0L..7L) {
                val candidate = now.toLocalDate().plusDays(offset).atTime(config.hour, config.minute).atZone(now.zone)
                if (candidate.isAfter(now) && isActiveDay(config.activeDays, candidate.dayOfWeek)) return candidate.toInstant().toEpochMilli()
            }
            return null
        }

        private fun intent(context: Context, id: Long, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
            context, id.toInt(), Intent(context, TimeOfDayReceiver::class.java).apply {
                action = ACTION_TIME_ALARM
                putExtra(EXTRA_SLOT_ID, id)
            }, flags or PendingIntent.FLAG_IMMUTABLE)

        fun cancelAlarm(context: Context, slotId: Long) {
            intent(context, slotId, PendingIntent.FLAG_NO_CREATE)?.let {
                context.getSystemService(AlarmManager::class.java).cancel(it)
                it.cancel()
            }
            val preferences = prefs(context)
            preferences.edit().putStringSet("ids", preferences.getStringSet("ids", emptySet()).orEmpty() - slotId.toString()).commit()
        }

        private fun schedule(context: Context, slot: Slot) {
            cancelAlarm(context, slot.id)
            if (!slot.enabled || slot.triggerType != "TIME_OF_DAY") return
            val config = runCatching { json.decodeFromString<TriggerConfig.TimeOfDay>(slot.triggerConfigJson ?: "") }.getOrNull() ?: return
            if (!config.repeatDaily && slot.lastExecutedDay != null) return
            val next = nextAlarmMillis(config) ?: return
            val preferences = prefs(context)
            preferences.edit().putStringSet("ids", preferences.getStringSet("ids", emptySet()).orEmpty() + slot.id.toString()).commit()
            val pending = intent(context, slot.id, PendingIntent.FLAG_UPDATE_CURRENT)!!
            val alarms = context.getSystemService(AlarmManager::class.java)
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms())
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
            } catch (_: SecurityException) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
            }
        }

        /** Caller owns the lifecycle mutex. Upgrade/import and mutations use this same reconciliation. */
        internal suspend fun reconcileAlarms(context: Context) {
            val dao = AppDatabase.get(context).slotDao()
            val slots = AppDatabase.get(context).backupDao().slots().filter { it.triggerType == "TIME_OF_DAY" }
            val enabled = slots.filter { it.enabled }.map { it.id.toString() }.toSet()
            prefs(context).getStringSet("ids", emptySet()).orEmpty().filter { it !in enabled }
                .mapNotNull(String::toLongOrNull).forEach { cancelAlarm(context, it) }
            slots.forEach { slot ->
                val config = runCatching { json.decodeFromString<TriggerConfig.TimeOfDay>(slot.triggerConfigJson ?: "") }.getOrNull()
                // Old versions left completed one-shot rows enabled, then rearmed them on update/reboot.
                if (config?.repeatDaily == false && slot.lastExecutedDay != null) {
                    dao.setEnabled(slot.id, false)
                    cancelAlarm(context, slot.id)
                } else schedule(context, slot)
            }
        }

        suspend fun scheduleAllEnabled(context: Context) = LocationAutomationController.mutex.withLock { reconcileAlarms(context) }

        internal suspend fun handleAlarm(context: Context, slotId: Long) {
            val slot = AppDatabase.get(context).slotDao().getById(slotId)
            try {
                if (slot?.triggerType == "TIME_OF_DAY") SlotExecutor.executeNow(context, slotId, slot)
            } finally {
                // Re-read after execution, even after an action fails. Never rearm a deleted/paused snapshot.
                LocationAutomationController.mutex.withLock {
                    val current = AppDatabase.get(context).slotDao().getById(slotId)
                    if (current == null) cancelAlarm(context, slotId) else schedule(context, current)
                }
            }
        }
    }
}
