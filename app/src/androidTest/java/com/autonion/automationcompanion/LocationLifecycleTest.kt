package com.autonion.automationcompanion

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.autonion.automationcompanion.core.backup.BackupManager
import com.autonion.automationcompanion.core.backup.BackupManifest
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.BootReceiver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autonion.automationcompanion.features.system_context_automation.location.data.dao.SlotDao
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.LocationReminderReceiver
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.SlotStartAlarmReceiver
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAlarmScheduler
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAutomationController
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationGeofenceRegistry
import kotlinx.coroutines.sync.withLock
import com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun legacyImportReactivatesPresentPresetWithFreshPresenceAndKeepsLaterPause() = runBlocking {
        val id = createFutureSlot()
        val saved = dao.getById(id)!!.copy(isInsideGeofence = true, lastExecutedDay = "2020-01-01", lastTriggerState = true)
        val backup = legacyBackup(saved)
        try {
            LocationAutomationController.delete(context, id)
            LocationAutomationController.onAlarm(context, id)
            assertAlarms(id, false)
            val imported = BackupManager(context).import(Uri.fromFile(backup), null)
            assertTrueResult(imported)
            val restored = dao.getById(id)!!
            assertTrue(restored.enabled)
            assertFalse(restored.isInsideGeofence)
            assertNull(restored.lastExecutedDay)
            assertNull(restored.lastTriggerState)
            assertAlarms(id, true)
            assertEquals(0, LocationAutomationController.monitoringCount(context)) // No permission: no tracking notification.

            LocationAutomationController.setEnabled(context, id, false)
            assertTrueResult(BackupManager(context).import(Uri.fromFile(backup), null))
            assertFalse("Import must not undo the user's later pause", dao.getById(id)!!.enabled)
            assertAlarms(id, false)
        } finally { backup.delete() }
    }

    @Test
    fun updateRecoveryAndEmptyImportCannotResurrectDeletedLocation() = runBlocking {
        assumeTrue("Requires an isolated empty location configuration", dao.getLocationSlots().isEmpty())
        val id = createFutureSlot()
        // Simulate the old bug: a row was deleted without cancelling its registered alarms.
        dao.delete(dao.getById(id)!!)
        assertAlarms(id, true)
        val registry = context.getSharedPreferences("location_geofence_registry_v2", Context.MODE_PRIVATE)
        registry.edit().putString("slot_$id", "$id:stale-before-update").commit()

        BootReceiver.recoverRegistrations(context)
        assertAlarms(id, false)
        assertFalse(LocationAutomationController.hasCurrentGeofence(context, id))
        assertEquals(0, LocationAutomationController.monitoringCount(context))
        assertNoLocationNotifications(id)

        val backup = legacyBackup(null)
        try {
            assertTrueResult(BackupManager(context).import(Uri.fromFile(backup), null))
            LocationAutomationController.onAlarm(context, id)
            assertNull(dao.getById(id))
            assertAlarms(id, false)
            assertEquals(0, LocationAutomationController.monitoringCount(context))
            assertNoLocationNotifications(id)
        } finally { backup.delete() }
    }

    private fun assertTrueResult(result: BackupManager.ImportResult) {
        org.junit.Assert.assertTrue(result.toString(), result is BackupManager.ImportResult.Success)
    }

    @Test
    fun updateRecoveryRearmsEnabledPresetAndKeepsPausedPresetOff() = runBlocking {
        val enabledId = createFutureSlot()
        val pausedId = createFutureSlot()
        LocationAutomationController.setEnabled(context, pausedId, false)
        dao.updateInsideGeofence(enabledId, true)
        // Reboot/package replacement can discard alarms; recovery must use the saved state.
        LocationAlarmScheduler.cancel(context, enabledId)
        assertAlarms(enabledId, false)

        BootReceiver.recoverRegistrations(context)

        assertTrue(dao.getById(enabledId)!!.enabled)
        assertFalse(dao.getById(enabledId)!!.isInsideGeofence)
        assertAlarms(enabledId, true)
        assertFalse(dao.getById(pausedId)!!.enabled)
        assertAlarms(pausedId, false)
        assertEquals(0, LocationAutomationController.monitoringCount(context)) // No location permission in this suite.
    }

    @Test
    fun upgradeFindsNumericGeofencesEvenWhenTheirOldPresetWasDeleted() = runBlocking {
        val id = createFutureSlot()
        dao.delete(dao.getById(id)!!)
        val prefs = context.getSharedPreferences("location_geofence_registry_v2", Context.MODE_PRIVATE)
        val previous = prefs.all.filterKeys { it == "legacy_range_end" || it == "legacy_range_done" }
        try {
            LocationAutomationController.mutex.withLock {
                prefs.edit().remove("legacy_range_end").remove("legacy_range_done").commit()
                LocationGeofenceRegistry.prepareLegacyCleanup(context)
                val end = prefs.getLong("legacy_range_end", 0)
                assertTrue("Deleted ID must remain covered by migration cleanup", end >= id)
                // Locate the batch containing this ID, also checking that cleanup stays bounded.
                prefs.edit().putLong("legacy_range_done", ((id - 1) / 100) * 100).commit()
                val batch = LocationGeofenceRegistry.legacyCleanupBatch(context)
                assertTrue(id in batch)
                assertTrue(batch.last - batch.first < 100)
            }
            BootReceiver.recoverRegistrations(context)
            assertAlarms(id, false)
            assertNoLocationNotifications(id)
        } finally {
            val edit = prefs.edit().remove("legacy_range_end").remove("legacy_range_done")
            previous.forEach { (key, value) -> edit.putLong(key, value as Long) }
            edit.commit()
        }
    }

    private suspend fun legacyBackup(slot: Slot?): File {
        val root = File(context.cacheDir, "legacy-location-${UUID.randomUUID()}").apply { mkdirs() }
        val database = File(root, "locauto.db")
        val copy = AppDatabase.openFile(context, database.absolutePath)
        try {
            if (slot != null) copy.slotDao().insert(slot) else copy.backupDao().slots()
        } finally { copy.close() }
        val archive = File(context.cacheDir, "legacy-location-${UUID.randomUUID()}.atnbak")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("database/locauto.db"))
            database.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(Json.encodeToString(BackupManifest("1.1.2", 1234, false, listOf(BackupManifest.FEATURE_SYSTEM_CONTEXT_DB))).toByteArray())
            zip.closeEntry()
        }
        root.deleteRecursively()
        return archive
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
