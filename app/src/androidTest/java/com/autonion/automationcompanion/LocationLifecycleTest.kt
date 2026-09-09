package com.autonion.automationcompanion

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autonion.automationcompanion.features.system_context_automation.location.data.dao.SlotDao
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.LocationReminderReceiver
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.SlotStartAlarmReceiver
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAlarmScheduler
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAutomationController
import com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real controller/Room/AlarmManager lifecycle checks without registering device geofences. */
@RunWith(AndroidJUnit4::class)
class LocationLifecycleTest {
    private lateinit var context: Context
    private lateinit var dao: SlotDao
    private val createdIds = mutableSetOf<Long>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // These tests intentionally exercise the permission-missing lifecycle. Real geofence
        // delivery and its location permission setup belong to a separate device check.
        assumeFalse("Run without fine/background geofencing permission",
            PermissionUtils.isLocationPermissionGranted(context))
        dao = AppDatabase.get(context).slotDao()
    }

    @After
    fun deleteOnlyTestSlots() = runBlocking {
        for (id in createdIds) LocationAutomationController.delete(context, id)
    }

    @Test
    fun deleteCancelsBothAlarmsAndUndoRestoresThemUnderANewId() = runBlocking {
        val id = createFutureSlot()
        assertAlarms(id, expected = true)

        val deleted = LocationAutomationController.delete(context, id)
        assertNotNull(deleted)
        assertNull(dao.getById(id))
        assertAlarms(id, expected = false)
        assertNoLocationNotifications(id)

        val restoredId = LocationAutomationController.restore(context, deleted!!)
        createdIds.add(restoredId)
        assertNotEquals(id, restoredId)
        assertNotNull(dao.getById(restoredId))
        assertAlarms(restoredId, expected = true)
        assertFalse(dao.getById(restoredId)!!.isInsideGeofence)
        assertAlarms(id, expected = false)
    }

    @Test
    fun disablingCancelsAlarmsAndEnablingRecreatesThem() = runBlocking {
        val id = createFutureSlot()
        dao.updateInsideGeofence(id, true)

        LocationAutomationController.setEnabled(context, id, false)

        assertFalse(dao.getById(id)!!.enabled)
        assertFalse(dao.getById(id)!!.isInsideGeofence)
        assertAlarms(id, expected = false)
        assertNoLocationNotifications(id)

        LocationAutomationController.setEnabled(context, id, true)

        assertEquals(true, dao.getById(id)!!.enabled)
        assertFalse(dao.getById(id)!!.isInsideGeofence)
        assertAlarms(id, expected = true)
    }

    @Test
    fun stopPersistsDisabledStateAndLateStartsCannotRestoreMonitoring() = runBlocking {
        // Stop intentionally affects all LOCATION rows. Never run this against another
        // person's presets; an isolated emulator with a fresh test install is expected.
        assumeTrue("Stop lifecycle test requires no existing location presets", dao.getLocationSlots().isEmpty())
        val firstId = createFutureSlot()
        val secondId = createFutureSlot()
        dao.updateInsideGeofence(firstId, true)

        LocationAutomationController.stopAll(context)

        for (id in listOf(firstId, secondId)) {
            assertFalse(dao.getById(id)!!.enabled)
            assertFalse(dao.getById(id)!!.isInsideGeofence)
            assertAlarms(id, expected = false)
            LocationAutomationController.onAlarm(context, id)
            assertAlarms(id, expected = false)
            assertNoLocationNotifications(id)
        }
        assertEquals(0, LocationAutomationController.monitoringCount(context))
    }

    @Test
    fun alreadyDispatchedStartCannotResurrectADeletedSlot() = runBlocking {
        val id = createFutureSlot()
        LocationAutomationController.delete(context, id)

        // onAlarm is the awaited path invoked by SlotStartAlarmReceiver after delivery.
        LocationAutomationController.onAlarm(context, id)
        LocationAutomationController.ensureMonitoring(context)

        assertNull(dao.getById(id))
        assertAlarms(id, expected = false)
        assertFalse(LocationAutomationController.hasCurrentGeofence(context, id))
        assertNoLocationNotifications(id)
    }

    private suspend fun createFutureSlot(): Long {
        val now = System.currentTimeMillis()
        val slot = Slot(
            lat = 12.9716,
            lng = 77.5946,
            radiusMeters = 200f,
            startMillis = now + 60 * 60_000L,
            endMillis = now + 2 * 60 * 60_000L,
            remindBeforeMinutes = 10,
            actions = emptyList()
        )
        return LocationAutomationController.save(context, slot).also(createdIds::add)
    }

    private fun assertAlarms(id: Long, expected: Boolean) {
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        val start = PendingIntent.getBroadcast(
            context, ("slot_start_$id").hashCode(),
            Intent(context, SlotStartAlarmReceiver::class.java).apply {
                action = LocationAlarmScheduler.ACTION_SLOT_START
            }, flags
        )
        val reminder = PendingIntent.getBroadcast(
            context, ("reminder_$id").hashCode(),
            Intent(context, LocationReminderReceiver::class.java).apply {
                action = LocationReminderReceiver.ACTION_REMIND
            }, flags
        )
        assertEquals("Start alarm PendingIntent for slot $id", expected, start != null)
        assertEquals("Reminder alarm PendingIntent for slot $id", expected, reminder != null)
    }

    private fun assertNoLocationNotifications(slotId: Long) {
        val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
        assertFalse(notifications.any { it.id == 1 || it.id == ("reminder_$slotId").hashCode() })
    }
}
