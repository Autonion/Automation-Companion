package com.autonion.automationcompanion

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autonion.automationcompanion.features.system_context_automation.location.data.dao.SlotDao
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual SQLite claim used by ENTER, tick, and alarm evaluation. */
@RunWith(AndroidJUnit4::class)
class LocationExecutionClaimTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: SlotDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).build()
        dao = database.slotDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun concurrentSourcesClaimANewSlotOnlyOnceAndAllowTheNextOccurrence() = runBlocking {
        val id = dao.insert(Slot(actions = emptyList(), isInsideGeofence = true))

        val claims = List(20) {
            async(Dispatchers.IO) { dao.claimLocationExecution(id, "2026-09-08") }
        }.awaitAll()

        assertEquals(1, claims.sum())
        assertEquals("2026-09-08", dao.getById(id)?.lastExecutedDay)
        assertEquals(1, dao.claimLocationExecution(id, "2026-09-09"))
        assertEquals(0, dao.claimLocationExecution(id, "2026-09-09"))
    }

    @Test
    fun disabledOutsideDeletedAndOtherTriggerSlotsCannotBeClaimed() = runBlocking {
        val disabled = dao.insert(Slot(actions = emptyList(), enabled = false, isInsideGeofence = true))
        val outside = dao.insert(Slot(actions = emptyList(), isInsideGeofence = false))
        val battery = dao.insert(Slot(actions = emptyList(), triggerType = "BATTERY", isInsideGeofence = true))
        val deleted = Slot(id = dao.insert(Slot(actions = emptyList(), isInsideGeofence = true)), actions = emptyList())
        dao.delete(deleted)

        for (id in listOf(disabled, outside, battery, deleted.id)) {
            assertEquals(0, dao.claimLocationExecution(id, "2026-09-08"))
        }
    }

    @Test
    fun exitClearsEligibilityBeforeTheNextOccurrence() = runBlocking {
        val id = dao.insert(Slot(actions = emptyList(), isInsideGeofence = true))
        assertEquals(1, dao.claimLocationExecution(id, "2026-09-08"))
        dao.updateInsideGeofence(id, false)
        assertEquals(0, dao.claimLocationExecution(id, "2026-09-09"))
        dao.updateInsideGeofence(id, true)
        assertEquals(1, dao.claimLocationExecution(id, "2026-09-09"))
    }

    @Test
    fun stopDisablesAllLocationSlotsAndClearsPresenceWithoutChangingOtherTriggers() = runBlocking {
        val location = dao.insert(Slot(actions = emptyList(), isInsideGeofence = true))
        val disabledLocation = dao.insert(Slot(actions = emptyList(), enabled = false, isInsideGeofence = true))
        val battery = dao.insert(Slot(actions = emptyList(), triggerType = "BATTERY", isInsideGeofence = true))

        assertEquals(setOf(location, disabledLocation), dao.getLocationSlots().map { it.id }.toSet())
        dao.disableLocationSlots()

        for (id in listOf(location, disabledLocation)) {
            assertFalse(dao.getById(id)!!.enabled)
            assertFalse(dao.getById(id)!!.isInsideGeofence)
            assertEquals(0, dao.claimLocationExecution(id, "2026-09-08"))
        }
        assertTrue(dao.getById(battery)!!.enabled)
        assertTrue(dao.getById(battery)!!.isInsideGeofence)
    }

    @Test
    fun rebootPresenceResetPreservesEnabledStateAndOtherTriggerState() = runBlocking {
        val location = dao.insert(Slot(actions = emptyList(), isInsideGeofence = true))
        val wifi = dao.insert(Slot(actions = emptyList(), triggerType = "WIFI", isInsideGeofence = true))

        dao.resetLocationPresence()

        assertTrue(dao.getById(location)!!.enabled)
        assertFalse(dao.getById(location)!!.isInsideGeofence)
        assertTrue(dao.getById(wifi)!!.isInsideGeofence)
    }
}
