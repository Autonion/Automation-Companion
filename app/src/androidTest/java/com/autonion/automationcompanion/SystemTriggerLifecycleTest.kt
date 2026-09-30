package com.autonion.automationcompanion

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import com.autonion.automationcompanion.core.backup.BackupManager
import com.autonion.automationcompanion.core.backup.BackupManifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autonion.automationcompanion.features.system_context_automation.battery.engine.BatteryMonitoringService
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.system_context_automation.shared.SystemSlotController
import com.autonion.automationcompanion.features.system_context_automation.shared.executor.SlotExecutor
import com.autonion.automationcompanion.features.system_context_automation.shared.models.TriggerConfig
import com.autonion.automationcompanion.features.system_context_automation.timeofday.engine.TimeOfDayReceiver
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SystemTriggerLifecycleTest {
    private val realContext = InstrumentationRegistry.getInstrumentation().targetContext
    // Deterministic lifecycle/claim tests do not let real battery broadcasts race their synthetic levels.
    private val context = object : ContextWrapper(realContext) {
        override fun getApplicationContext(): Context = this
        override fun startForegroundService(service: Intent): ComponentName? = service.component
        override fun startService(service: Intent): ComponentName? = service.component
        override fun stopService(service: Intent) = true
    }
    private val dao get() = AppDatabase.get(realContext).slotDao()
    private val ids = mutableSetOf<Long>()
    private val configs: Map<String, TriggerConfig> = mapOf(
        "BATTERY" to TriggerConfig.Battery(0),
        "TIME_OF_DAY" to TriggerConfig.TimeOfDay(23, 59),
        "WIFI" to TriggerConfig.WiFi(TriggerConfig.WiFi.ConnectionState.CONNECTED, "nonexistent-test-network"),
        "APP" to TriggerConfig.App("example.lifecycle.test")
    )

    @Before fun requireIsolatedInstallation() = runBlocking {
        org.junit.Assume.assumeTrue("Use an isolated emulator without existing non-location presets",
            AppDatabase.get(realContext).backupDao().slots().none { it.triggerType != "LOCATION" })
    }

    @After fun cleanup() = runBlocking {
        ids.forEach { SystemSlotController.delete(realContext, it) }
        BatteryMonitoringService.stopService(realContext)
    }

    private suspend fun create(type: String, enabled: Boolean = true, config: TriggerConfig = configs.getValue(type)): Long =
        SystemSlotController.save(context, Slot(triggerType = type,
            triggerConfigJson = Json.encodeToString(TriggerConfig.serializer(), config), actions = emptyList(), enabled = enabled)).also(ids::add)

    @Test fun everyTriggerPreservesPauseOnEditRejectsDeletedEventsAndRestoresUnderNewId() = runBlocking {
        for (type in configs.keys) {
            val id = create(type)
            SystemSlotController.setEnabled(context, id, false)
            SystemSlotController.save(context, dao.getById(id)!!.copy(enabled = true))
            assertFalse("Editing $type must keep it paused", dao.getById(id)!!.enabled)
            SystemSlotController.setEnabled(context, id, true)
            val deleted = SystemSlotController.delete(context, id)!!
            assertNull(SlotExecutor.claim(context, id, deleted, batteryLevel = 0))
            if (type == "TIME_OF_DAY") {
                TimeOfDayReceiver.handleAlarm(context, id)
                assertFalse(hasTimeAlarm(id))
            }
            val restored = SystemSlotController.restore(context, deleted).also(ids::add)
            assertNotEquals(id, restored)
            assertTrue(dao.getById(restored)!!.enabled)
            if (type == "TIME_OF_DAY") assertTrue(hasTimeAlarm(restored))
            SystemSlotController.delete(context, restored)
        }
    }

    @Test fun undoOfPausedPresetsStaysPausedForEveryTrigger() = runBlocking {
        for (type in configs.keys) {
            val id = create(type, enabled = false)
            val restored = SystemSlotController.restore(context, SystemSlotController.delete(context, id)!!).also(ids::add)
            assertFalse(dao.getById(restored)!!.enabled)
            assertNull(SlotExecutor.claim(context, restored, batteryLevel = 0))
            if (type == "TIME_OF_DAY") assertFalse(hasTimeAlarm(restored))
        }
    }

    @Test fun deletedPresetCannotBeSavedByAnAlreadyOpenEditor() = runBlocking {
        for (type in configs.keys) {
            val id = create(type)
            val snapshot = dao.getById(id)!!
            SystemSlotController.delete(context, id)
            assertTrue(runCatching { SystemSlotController.save(context, snapshot) }.isFailure)
            assertNull(dao.getById(id))
            if (type == "TIME_OF_DAY") assertFalse(hasTimeAlarm(id))
        }
    }

    @Test fun batteryClaimsAreAtomicAndUndoRearmsItsEdge() = runBlocking {
        val id = create("BATTERY")
        val claims = coroutineScope { List(8) { async(Dispatchers.IO) { SlotExecutor.claim(context, id, batteryLevel = 0) } }.awaitAll() }
        assertEquals(1, claims.count { it != null })
        assertNull(SlotExecutor.claim(context, id, batteryLevel = 100))
        assertNotNull(SlotExecutor.claim(context, id, batteryLevel = 0))
        val restored = SystemSlotController.restore(context, SystemSlotController.delete(context, id)!!).also(ids::add)
        assertNotNull(SlotExecutor.claim(context, restored, batteryLevel = 0))
    }

    @Test fun staleWifiAndAppSnapshotsDoNotExecuteEditedPresets() = runBlocking {
        for (type in listOf("WIFI", "APP")) {
            val id = create(type)
            val previous = dao.getById(id)!!
            val config = if (type == "APP") TriggerConfig.App("example.changed") else
                TriggerConfig.WiFi(TriggerConfig.WiFi.ConnectionState.DISCONNECTED)
            SystemSlotController.save(context, previous.copy(triggerConfigJson = Json.encodeToString(TriggerConfig.serializer(), config)))
            assertNull(SlotExecutor.claim(context, id, previous))
            assertNotNull(SlotExecutor.claim(context, id, dao.getById(id)))
        }
    }

    @Test fun completedOneShotDoesNotRearmAfterRecoveryAndLateDeletedAlarmStaysCancelled() = runBlocking {
        val id = create("TIME_OF_DAY", config = TriggerConfig.TimeOfDay(23, 59, repeatDaily = false))
        TimeOfDayReceiver.handleAlarm(context, id)
        assertFalse(dao.getById(id)!!.enabled)
        assertFalse(hasTimeAlarm(id))
        // Simulate the previous version's completed-but-enabled one-shot row.
        dao.setEnabled(id, true)
        SystemSlotController.recover(context)
        assertFalse(dao.getById(id)!!.enabled)
        assertFalse(hasTimeAlarm(id))
        SystemSlotController.delete(context, id)
        TimeOfDayReceiver.handleAlarm(context, id)
        assertFalse(hasTimeAlarm(id))
    }

    @Test fun recoveryKeepsEnabledTimeAlarmsAndPausedPresetsForAllTypes() = runBlocking {
        val active = create("TIME_OF_DAY")
        val paused = configs.keys.map { create(it, enabled = false) }
        TimeOfDayReceiver.cancelAlarm(context, active)
        SystemSlotController.recover(context)
        assertTrue(hasTimeAlarm(active))
        paused.forEach { assertFalse(dao.getById(it)!!.enabled) }
    }

    @Test fun backupRestoresMissingTriggersButKeepsMatchingPausedPresets() = runBlocking {
        val missing = configs.keys.map { create(it) }
        val existing = configs.keys.map { create(it) }
        val archive = File(realContext.cacheDir, "system-backup-${UUID.randomUUID()}.atnbak")
        try {
            assertTrue(BackupManager(context).export(Uri.fromFile(archive), listOf(BackupManifest.FEATURE_SYSTEM_CONTEXT_DB), null))
            missing.forEach { SystemSlotController.delete(context, it) }
            existing.forEach { SystemSlotController.setEnabled(context, it, false) }
            val result = BackupManager(context).import(Uri.fromFile(archive), null)
            assertTrue(result.toString(), result is BackupManager.ImportResult.Success)
            missing.forEach { assertTrue("Missing preset should be restored enabled", dao.getById(it)!!.enabled) }
            existing.forEach { assertFalse("Import must keep the user's current pause", dao.getById(it)!!.enabled) }
            assertTrue(hasTimeAlarm(missing[1]))
            assertFalse(hasTimeAlarm(existing[1]))
        } finally { archive.delete() }
    }

    @Test fun scheduledDaysAndNextOccurrenceAreRespected() {
        val friday = ZonedDateTime.parse("2026-09-18T12:00:00+05:30[Asia/Kolkata]")
        val config = TriggerConfig.TimeOfDay(9, 0, activeDays = "MON,WED")
        assertEquals(ZonedDateTime.parse("2026-09-21T09:00:00+05:30[Asia/Kolkata]").toInstant().toEpochMilli(),
            TimeOfDayReceiver.nextAlarmMillis(config, friday))
        assertFalse(TimeOfDayReceiver.isActiveDay(config.activeDays, DayOfWeek.FRIDAY))
        assertNull(TimeOfDayReceiver.nextAlarmMillis(config.copy(activeDays = ""), friday))
    }

    @Test fun importingLegacyCompletedOneShotKeepsItPausedUntilExplicitlyEnabled() = runBlocking {
        val id = create("TIME_OF_DAY", config = TriggerConfig.TimeOfDay(23, 59, repeatDaily = false))
        dao.update(dao.getById(id)!!.copy(lastExecutedDay = "2026-01-01", enabled = true))
        val archive = File(realContext.cacheDir, "completed-alarm-${UUID.randomUUID()}.atnbak")
        try {
            assertTrue(BackupManager(context).export(Uri.fromFile(archive), listOf(BackupManifest.FEATURE_SYSTEM_CONTEXT_DB), null))
            SystemSlotController.delete(context, id)
            val result = BackupManager(context).import(Uri.fromFile(archive), null)
            assertTrue(result.toString(), result is BackupManager.ImportResult.Success)
            assertFalse(dao.getById(id)!!.enabled)
            assertEquals("2026-01-01", dao.getById(id)!!.lastExecutedDay)
            assertFalse(hasTimeAlarm(id))
            TimeOfDayReceiver.handleAlarm(context, id)
            assertFalse(hasTimeAlarm(id))
            SystemSlotController.setEnabled(context, id, true)
            assertTrue(dao.getById(id)!!.enabled)
            assertNull(dao.getById(id)!!.lastExecutedDay)
            assertTrue(hasTimeAlarm(id))
        } finally { archive.delete() }
    }

    @Test fun batteryNotificationStopPersistsAndLateServiceStartStopsItself() = runBlocking {
        val id = create("BATTERY")
        BatteryMonitoringService.startService(realContext)
        withTimeout(10_000) { while (!batteryRunning()) delay(50) }
        realContext.startService(Intent(realContext, BatteryMonitoringService::class.java).setAction(BatteryMonitoringService.ACTION_STOP))
        withTimeout(10_000) { while (dao.getById(id)!!.enabled || batteryRunning()) delay(50) }
        BatteryMonitoringService.startService(realContext)
        delay(100)
        withTimeout(10_000) { while (batteryRunning()) delay(50) }
        assertFalse(dao.getById(id)!!.enabled)
        assertNull(SlotExecutor.claim(context, id, batteryLevel = 0))
    }

    private fun batteryRunning() = realContext.getSystemService(ActivityManager::class.java).getRunningServices(100)
        .any { it.service.className == BatteryMonitoringService::class.java.name }

    private fun hasTimeAlarm(id: Long) = PendingIntent.getBroadcast(realContext, id.toInt(),
        Intent(realContext, TimeOfDayReceiver::class.java).setAction(TimeOfDayReceiver.ACTION_TIME_ALARM),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) != null
}
