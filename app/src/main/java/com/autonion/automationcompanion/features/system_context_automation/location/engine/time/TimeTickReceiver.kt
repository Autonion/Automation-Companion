package com.autonion.automationcompanion.features.system_context_automation.location.engine.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.SendHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class TimeTickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_TICK) return

        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.get(context).slotDao()
            val slots = dao.getAllEnabled()

            val now = Calendar.getInstance()
            val todayKey = dayKey(now)

            for (slot in slots) {

                // 1️⃣ Must be inside geofence
                if (!slot.isInsideGeofence) continue

                // 2️⃣ Day-of-week
                if (!isAllowedToday(slot.activeDays, now)) continue

                // 3️⃣ Time window
                if (!isWithinTimeWindow(slot, now)) continue

                // 4️⃣ Once per day
                if (slot.lastExecutedDay == todayKey) continue

                Log.i("TimeTick", "Triggering slot ${slot.id}")

                // ✅ Delegate execution
                SendHelper.startSendIfNeeded(context, slot.id)

                // 🔒 Lock for today
                dao.updateLastExecutedDay(slot.id, todayKey)
            }
        }
    }

    private fun isAllowedToday(activeDays: String, now: Calendar): Boolean {
        if (activeDays == "ALL") return true

        val today = when (now.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "MON"
            Calendar.TUESDAY -> "TUE"
            Calendar.WEDNESDAY -> "WED"
            Calendar.THURSDAY -> "THU"
            Calendar.FRIDAY -> "FRI"
            Calendar.SATURDAY -> "SAT"
            Calendar.SUNDAY -> "SUN"
            else -> return false
        }

        return activeDays.split(",").contains(today)
    }

    private fun isWithinTimeWindow(slot: com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot, now: Calendar): Boolean {

        val start = Calendar.getInstance().apply {
            timeInMillis = slot.startMillis ?: return@apply
            set(Calendar.YEAR, now.get(Calendar.YEAR))
            set(Calendar.MONTH, now.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH))
        }

        val end = Calendar.getInstance().apply {
            timeInMillis = slot.endMillis ?: return@apply
            set(Calendar.YEAR, now.get(Calendar.YEAR))
            set(Calendar.MONTH, now.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH))
        }

        // Overnight support
        if (end.before(start)) {
            end.add(Calendar.DATE, 1)
        }

        return now.timeInMillis in start.timeInMillis..end.timeInMillis
    }

    private fun dayKey(cal: Calendar): String =
        "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
}
