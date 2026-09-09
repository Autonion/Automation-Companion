package com.autonion.automationcompanion.features.system_context_automation.location.helpers

import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Recurring local-time windows. The start date owns both the weekday and execution key. */
object LocationSchedule {
    data class Window(val startMillis: Long, val endMillis: Long, val dayKey: String)

    private const val START_MINUTE = "locationStartMinute"
    private const val END_MINUTE = "locationEndMinute"

    /** Preserve the user's wall-clock choice when the device later changes timezone. */
    fun withLocalClock(slot: Slot, timeZone: TimeZone = TimeZone.getDefault()): Slot {
        if (slot.triggerType != "LOCATION") return slot
        val start = legacyClockMinutes(slot.startMillis, timeZone) ?: return slot
        val end = legacyClockMinutes(slot.endMillis, timeZone) ?: return slot
        val config = localConfig(slot).apply {
            addProperty(START_MINUTE, start)
            addProperty(END_MINUTE, end)
        }
        return slot.copy(triggerConfigJson = config.toString())
    }

    /** Migrate legacy timestamps once, without overwriting an already stored local clock. */
    fun ensureLocalClock(slot: Slot, timeZone: TimeZone = TimeZone.getDefault()): Slot {
        if (slot.triggerType != "LOCATION") return slot
        val config = localConfig(slot)
        val storedStart = metadataMinute(config, START_MINUTE)
        val storedEnd = metadataMinute(config, END_MINUTE)
        if (storedStart != null && storedEnd != null) return slot
        val start = storedStart ?: legacyClockMinutes(slot.startMillis, timeZone) ?: return slot
        val end = storedEnd ?: legacyClockMinutes(slot.endMillis, timeZone) ?: return slot
        config.addProperty(START_MINUTE, start)
        config.addProperty(END_MINUTE, end)
        return slot.copy(triggerConfigJson = config.toString())
    }

    /** The editor and evaluator use the same persisted local clock. */
    fun clockMinutes(slot: Slot, start: Boolean, timeZone: TimeZone = TimeZone.getDefault()): Int? =
        metadataMinute(localConfig(slot), if (start) START_MINUTE else END_MINUTE)
            ?: legacyClockMinutes(if (start) slot.startMillis else slot.endMillis, timeZone)

    fun activeWindow(
        slot: Slot,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): Window? {
        val today = localDate(nowMillis, timeZone)
        // An overnight (or full-day) occurrence may have started yesterday.
        for (offset in -1..0) {
            val day = (today.clone() as Calendar).apply { add(Calendar.DATE, offset) }
            val window = windowForDay(slot, day) ?: continue
            if (nowMillis >= window.startMillis && nowMillis < window.endMillis) return window
        }
        return null
    }

    /** The next selected occurrence whose start is strictly later than [nowMillis]. */
    fun nextWindow(
        slot: Slot,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): Window? {
        val today = localDate(nowMillis, timeZone)
        // Include the same weekday next week. A DST gap can collapse one occurrence,
        // so search another week rather than leaving the schedule permanently unarmed.
        for (offset in 0..14) {
            val day = (today.clone() as Calendar).apply { add(Calendar.DATE, offset) }
            val window = windowForDay(slot, day) ?: continue
            if (window.startMillis > nowMillis) return window
        }
        return null
    }

    private fun windowForDay(slot: Slot, day: Calendar): Window? {
        val startMinute = clockMinutes(slot, start = true, timeZone = day.timeZone) ?: return null
        val endMinute = clockMinutes(slot, start = false, timeZone = day.timeZone) ?: return null
        val days = slot.activeDays.uppercase(Locale.ROOT).split(',').map { it.trim() }.toSet()
        val dayName = arrayOf("", "SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")[day.get(Calendar.DAY_OF_WEEK)]
        if ("ALL" !in days && dayName !in days) return null

        val start = atClock(day, startMinute)
        val endDay = (day.clone() as Calendar).apply {
            // Equal wall-clock times mean one full local day (23/25 hours across DST).
            if (endMinute <= startMinute) add(Calendar.DATE, 1)
        }
        val end = atClock(endDay, endMinute)
        if (end.timeInMillis <= start.timeInMillis) return null
        return Window(
            start.timeInMillis,
            end.timeInMillis,
            String.format(Locale.US, "%04d-%02d-%02d", day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH))
        )
    }

    private fun localDate(millis: Long, timeZone: TimeZone) = Calendar.getInstance(timeZone).apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun atClock(day: Calendar, minuteOfDay: Int) = (day.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(Calendar.MINUTE, minuteOfDay % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun legacyClockMinutes(millis: Long?, timeZone: TimeZone): Int? = millis?.let {
        val clock = Calendar.getInstance(timeZone).apply { timeInMillis = it }
        clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE)
    }

    private fun localConfig(slot: Slot): JsonObject = try {
        slot.triggerConfigJson?.let { JsonParser.parseString(it) }?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject()
    } catch (_: RuntimeException) {
        JsonObject()
    }

    private fun metadataMinute(config: JsonObject, key: String): Int? {
        val value = config.get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        if (!value.isNumber) return null
        return value.asString.toIntOrNull()?.takeIf { it in 0 until 24 * 60 }
    }
}
