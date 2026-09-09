package com.autonion.automationcompanion.features.system_context_automation.location.helpers

import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class LocationScheduleTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun instant(date: String, hour: Int, minute: Int = 0, zone: TimeZone = utc): Long {
        val (year, month, day) = date.split('-').map(String::toInt)
        return Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis
    }

    private fun slot(startHour: Int, endHour: Int, days: String = "ALL", zone: TimeZone = utc) = Slot(
        id = 7,
        startMillis = instant("2026-01-01", startHour, zone = zone),
        endMillis = instant("2026-01-01", endHour, zone = zone),
        activeDays = days,
        actions = emptyList()
    )

    @Test fun dailyScheduleUsesCurrentOccurrenceInsteadOfSavedDate() {
        val window = LocationSchedule.activeWindow(slot(9, 10), instant("2026-09-08", 9, 30), utc)
        assertNotNull(window)
        assertEquals(instant("2026-09-08", 9), window!!.startMillis)
        assertEquals(instant("2026-09-08", 10), window.endMillis)
        assertEquals("2026-09-08", window.dayKey)
    }

    @Test fun occurrenceIncludesStartAndExcludesEnd() {
        val slot = slot(9, 10)
        assertNull(LocationSchedule.activeWindow(slot, instant("2026-09-08", 9) - 1, utc))
        assertNotNull(LocationSchedule.activeWindow(slot, instant("2026-09-08", 9), utc))
        assertNotNull(LocationSchedule.activeWindow(slot, instant("2026-09-08", 10) - 1, utc))
        assertNull(LocationSchedule.activeWindow(slot, instant("2026-09-08", 10), utc))
    }

    @Test fun nextStartAdvancesAfterDailyAlarmFires() {
        val window = LocationSchedule.nextWindow(slot(9, 10), instant("2026-09-08", 9), utc)
        assertEquals(instant("2026-09-09", 9), window!!.startMillis)
    }

    @Test fun futureStartTodayIsSelected() {
        val window = LocationSchedule.nextWindow(slot(9, 10), instant("2026-09-08", 8, 59), utc)
        assertEquals(instant("2026-09-08", 9), window!!.startMillis)
    }

    @Test fun weeklyAlarmAdvancesToNextSelectedDayAndWrapsWeek() {
        val slot = slot(9, 10, "MON,WED")
        assertEquals(instant("2026-09-09", 9), LocationSchedule.nextWindow(slot, instant("2026-09-07", 9), utc)!!.startMillis)
        assertEquals(instant("2026-09-14", 9), LocationSchedule.nextWindow(slot, instant("2026-09-09", 9), utc)!!.startMillis)
    }

    @Test fun singleWeekdayRearmsOneWeekLaterAtStartBoundary() {
        val window = LocationSchedule.nextWindow(slot(9, 10, "MON"), instant("2026-09-07", 9), utc)
        assertEquals(instant("2026-09-14", 9), window!!.startMillis)
    }

    @Test fun unselectedWeekdayCannotExecute() {
        assertNull(LocationSchedule.activeWindow(slot(9, 10, "MON"), instant("2026-09-08", 9, 30), utc))
    }

    @Test fun overnightAfterMidnightBelongsToStartWeekdayAndExecutionKey() {
        val slot = slot(22, 2, "MON")
        val beforeMidnight = LocationSchedule.activeWindow(slot, instant("2026-09-07", 23), utc)!!
        val afterMidnight = LocationSchedule.activeWindow(slot, instant("2026-09-08", 0, 30), utc)!!
        assertEquals(beforeMidnight, afterMidnight)
        assertEquals("2026-09-07", afterMidnight.dayKey)
        assertEquals(instant("2026-09-08", 2), afterMidnight.endMillis)
        assertNull(LocationSchedule.activeWindow(slot, instant("2026-09-08", 2), utc))
        assertNull(LocationSchedule.activeWindow(slot, instant("2026-09-08", 22, 30), utc))
    }

    @Test fun overnightCrossesMonthAndYear() {
        val window = LocationSchedule.activeWindow(slot(22, 2, "THU"), instant("2027-01-01", 1), utc)!!
        assertEquals("2026-12-31", window.dayKey)
        assertEquals(instant("2026-12-31", 22), window.startMillis)
    }

    @Test fun equalTimesMeanFullLocalDayAndSwitchKeyAtStart() {
        val slot = slot(9, 9)
        val beforeStart = LocationSchedule.activeWindow(slot, instant("2026-09-08", 8, 59), utc)!!
        val atStart = LocationSchedule.activeWindow(slot, instant("2026-09-08", 9), utc)!!
        assertEquals("2026-09-07", beforeStart.dayKey)
        assertEquals("2026-09-08", atStart.dayKey)
        assertEquals(24 * 60 * 60_000L, atStart.endMillis - atStart.startMillis)
    }

    @Test fun missingTimeOrUnselectedDaysHaveNoOccurrences() {
        val candidates = listOf(slot(9, 10).copy(startMillis = null), slot(9, 10).copy(endMillis = null), slot(9, 10, ""), slot(9, 10, "INVALID"))
        candidates.forEach {
            assertNull(LocationSchedule.activeWindow(it, instant("2026-09-08", 9, 30), utc))
            assertNull(LocationSchedule.nextWindow(it, instant("2026-09-08", 9, 30), utc))
        }
    }

    @Test fun daylightSavingDoesNotShiftRecurringWallClockStart() {
        val zone = TimeZone.getTimeZone("America/Los_Angeles")
        val slot = slot(9, 10, zone = zone)
        val next = LocationSchedule.nextWindow(slot, instant("2026-03-07", 9, zone = zone), zone)!!
        assertEquals(instant("2026-03-08", 9, zone = zone), next.startMillis)
        assertEquals(23 * 60 * 60_000L, next.startMillis - instant("2026-03-07", 9, zone = zone))
    }

    @Test fun fullLocalDaySpansTwentyThreeHoursAcrossSpringForward() {
        val zone = TimeZone.getTimeZone("America/Los_Angeles")
        val window = LocationSchedule.activeWindow(slot(12, 12, zone = zone), instant("2026-03-08", 10, zone = zone), zone)!!
        assertEquals("2026-03-07", window.dayKey)
        assertEquals(23 * 60 * 60_000L, window.endMillis - window.startMillis)
    }

    @Test fun fullLocalDaySpansTwentyFiveHoursAcrossFallBack() {
        val zone = TimeZone.getTimeZone("America/Los_Angeles")
        val window = LocationSchedule.activeWindow(slot(12, 12, zone = zone), instant("2026-11-01", 10, zone = zone), zone)!!
        assertEquals("2026-10-31", window.dayKey)
        assertEquals(25 * 60 * 60_000L, window.endMillis - window.startMillis)
    }

    @Test fun collapsedSpringGapOccurrenceIsSkippedWithoutLosingNextWeek() {
        val zone = TimeZone.getTimeZone("America/Los_Angeles")
        val slot = slot(2, 3, "SUN", zone).copy(startMillis = instant("2026-01-01", 2, 30, zone))
        // 02:30 normalizes to 03:30 on March 8, beyond this occurrence's 03:00 end.
        val next = LocationSchedule.nextWindow(slot, instant("2026-03-08", 0, zone = zone), zone)!!
        assertEquals(instant("2026-03-15", 2, 30, zone), next.startMillis)
    }

    @Test fun savedLocalClockRemainsNineAfterTimezoneChange() {
        val india = TimeZone.getTimeZone("Asia/Kolkata")
        val saved = LocationSchedule.withLocalClock(slot(9, 10, zone = india), india)
        val next = LocationSchedule.nextWindow(saved, instant("2026-09-08", 8), utc)!!
        assertEquals(instant("2026-09-08", 9), next.startMillis)
        assertEquals(instant("2026-09-08", 10), next.endMillis)
        assertNotNull(LocationSchedule.activeWindow(saved, instant("2026-09-08", 9, 30), utc))
        assertEquals(9 * 60, LocationSchedule.clockMinutes(saved, start = true, timeZone = utc)!!)
        assertEquals(10 * 60, LocationSchedule.clockMinutes(saved, start = false, timeZone = utc)!!)
    }

    @Test fun migratedClockIsNotRecapturedInANewTimezone() {
        val india = TimeZone.getTimeZone("Asia/Kolkata")
        val original = slot(9, 10, zone = india)
        val migrated = LocationSchedule.ensureLocalClock(original, india)
        val afterTravel = LocationSchedule.ensureLocalClock(migrated, utc)
        assertEquals(migrated, afterTravel)
        assertEquals(instant("2026-09-08", 9), LocationSchedule.nextWindow(afterTravel, instant("2026-09-08", 8), utc)!!.startMillis)
    }

    @Test fun localClockPreservesOvernightWeekdayAfterTimezoneChange() {
        val india = TimeZone.getTimeZone("Asia/Kolkata")
        val saved = LocationSchedule.withLocalClock(slot(22, 2, "MON", india), india)
        val window = LocationSchedule.activeWindow(saved, instant("2026-09-08", 1), utc)!!
        assertEquals("2026-09-07", window.dayKey)
        assertEquals(instant("2026-09-07", 22), window.startMillis)
        assertEquals(instant("2026-09-08", 2), window.endMillis)
    }

    @Test fun editingClockReplacesMetadataAndPreservesOtherConfig() {
        val original = LocationSchedule.withLocalClock(slot(9, 10).copy(triggerConfigJson = "{\"otherSetting\":true}"), utc)
        val edited = LocationSchedule.withLocalClock(original.copy(
            startMillis = instant("2026-09-08", 11),
            endMillis = instant("2026-09-08", 12)
        ), utc)
        assertEquals(11 * 60, LocationSchedule.clockMinutes(edited, start = true, timeZone = utc)!!)
        assertEquals(12 * 60, LocationSchedule.clockMinutes(edited, start = false, timeZone = utc)!!)
        assertTrue(edited.triggerConfigJson!!.contains("\"otherSetting\":true"))
    }

    @Test fun malformedMetadataFallsBackToLegacyClockForMigration() {
        val legacy = slot(9, 10).copy(triggerConfigJson = "{\"locationStartMinute\":-1,\"locationEndMinute\":1500}")
        val migrated = LocationSchedule.ensureLocalClock(legacy, utc)
        assertEquals(9 * 60, LocationSchedule.clockMinutes(migrated, start = true, timeZone = utc)!!)
        assertEquals(10 * 60, LocationSchedule.clockMinutes(migrated, start = false, timeZone = utc)!!)
    }

    @Test fun localClockHelpersDoNotModifyOtherTriggerTypes() {
        val other = slot(9, 10).copy(triggerType = "BATTERY", triggerConfigJson = "{\"level\":50}")
        assertEquals(other, LocationSchedule.withLocalClock(other, utc))
        assertEquals(other, LocationSchedule.ensureLocalClock(other, utc))
    }
}
