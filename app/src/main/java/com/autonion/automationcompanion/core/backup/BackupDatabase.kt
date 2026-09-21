package com.autonion.automationcompanion.core.backup

import android.content.Context
import androidx.room.withTransaction
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.automation_debugger.data.ExecutionLog
import com.autonion.automationcompanion.features.omni_chatbot.data.db.OmniChatSessionEntity
import com.autonion.automationcompanion.features.omni_chatbot.data.db.OmniChatMessageEntity
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationGeofenceRegistry
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationSchedule
import com.autonion.automationcompanion.features.system_context_automation.shared.models.TriggerConfig
import kotlinx.serialization.json.Json
import java.io.File

internal data class BackupRows(
    val slots: List<Slot>, val logs: List<ExecutionLog>,
    val sessions: List<OmniChatSessionEntity>, val messages: List<OmniChatMessageEntity>
)

internal object BackupDatabase {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(db: AppDatabase): BackupRows = db.withTransaction {
        val dao = db.backupDao()
        BackupRows(dao.slots(), dao.logs(), dao.sessions(), dao.messages())
    }

    /** Snapshot rows in one read transaction, then close only the private export database. */
    suspend fun snapshot(context: Context, destination: File) {
        val rows = read(AppDatabase.get(context))
        val copy = AppDatabase.openFile(context, destination.absolutePath)
        try {
            copy.withTransaction {
                val dao = copy.backupDao()
                rows.slots.forEach { dao.slot(it) }
                rows.logs.forEach { dao.log(it) }
                rows.sessions.forEach { dao.session(it) }
                rows.messages.forEach { dao.message(it) }
            }
        } finally { copy.close() }
    }

    /** Room validates/migrates the staged legacy DB before any live data is changed. */
    suspend fun readArchive(context: Context, file: File): BackupRows {
        val copy = AppDatabase.openFile(context, file.absolutePath)
        return try { read(copy) } finally { copy.close() }
    }

    /** Caller owns the live transaction and location lifecycle mutex. IDs are preserved. */
    suspend fun merge(db: AppDatabase, rows: BackupRows, warnings: MutableList<String>): Pair<Int, Int> {
        val dao = db.backupDao()
        var added = 0
        var skipped = 0
        fun record(id: Long) { if (id == -1L) skipped++ else added++ }
        val existingSlots = dao.slots().map { it.id }.toSet()
        var enabledLocations = dao.slots().count { it.triggerType == "LOCATION" && it.enabled }
        rows.slots.forEach { original ->
            require(original.id > 0) { "Invalid slot identity in backup" }
            if (original.id in existingSlots) { skipped++; return@forEach }
            var slot = original.copy(isInsideGeofence = false, lastExecutedDay = null, lastTriggerState = null)
            // Completion is persistent for one-time alarms, including older backups that left them enabled.
            if (original.triggerType == "TIME_OF_DAY" && original.lastExecutedDay != null && runCatching {
                json.decodeFromString<TriggerConfig.TimeOfDay>(original.triggerConfigJson ?: "").repeatDaily == false
            }.getOrDefault(false)) {
                slot = slot.copy(enabled = false, lastExecutedDay = original.lastExecutedDay)
            }
            if (slot.triggerType == "LOCATION") {
                slot = LocationSchedule.ensureLocalClock(slot)
                if (!LocationGeofenceRegistry.validCoordinates(slot) || LocationSchedule.nextWindow(slot) == null || enabledLocations >= 100) {
                    slot = slot.copy(enabled = false)
                    warnings.add("Location preset #${slot.id} was imported paused; review its location, schedule or the 100-preset limit.")
                }
                if (slot.enabled) enabledLocations++
            } else if (runCatching {
                com.autonion.automationcompanion.features.system_context_automation.shared.SystemSlotController.validate(slot)
            }.isFailure) {
                slot = slot.copy(enabled = false)
                warnings.add("System preset #${slot.id} was imported paused; review its trigger settings.")
            }
            record(dao.slot(slot))
        }
        rows.logs.forEach { record(dao.log(it)) }
        val importedSessions = mutableSetOf<String>()
        rows.sessions.forEach {
            val result = dao.session(it)
            record(result)
            if (result != -1L) importedSessions.add(it.sessionId)
        }
        // Preserve an existing conversation as a unit, including its current messages.
        rows.messages.forEach {
            if (it.sessionId in importedSessions) record(dao.message(it)) else skipped++
        }
        return added to skipped
    }
}
