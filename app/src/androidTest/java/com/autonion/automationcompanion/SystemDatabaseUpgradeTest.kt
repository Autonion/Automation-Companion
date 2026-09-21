package com.autonion.automationcompanion

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opens historical on-disk schemas through the production Room migration chain. */
@RunWith(AndroidJUnit4::class)
class SystemDatabaseUpgradeTest {
    @Test fun historicalSchemasPreserveAllSystemTriggerRowsAndHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (version in listOf(4, 5, 6, 7)) {
            val root = File(context.cacheDir, "system-migration-${UUID.randomUUID()}").apply { mkdirs() }
            val file = File(root, "locauto.db")
            try {
                SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                    db.execSQL("""CREATE TABLE slots (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        triggerType TEXT NOT NULL DEFAULT 'LOCATION', triggerConfigJson TEXT,
                        lat REAL, lng REAL, radiusMeters REAL, startMillis INTEGER, endMillis INTEGER,
                        remindBeforeMinutes INTEGER NOT NULL, actions TEXT NOT NULL, enabled INTEGER NOT NULL,
                        activeDays TEXT NOT NULL, isInsideGeofence INTEGER NOT NULL DEFAULT 0, lastExecutedDay TEXT)""")
                    for ((index, type) in listOf("LOCATION", "BATTERY", "TIME_OF_DAY", "WIFI", "APP").withIndex()) {
                        db.execSQL("INSERT INTO slots (id, triggerType, triggerConfigJson, lat, lng, radiusMeters, startMillis, endMillis, remindBeforeMinutes, actions, enabled, activeDays, isInsideGeofence, lastExecutedDay) VALUES (?, ?, '{}', 12.9, 77.5, 200, 100, 200, 10, '[]', ?, 'MON,WED', 0, '2026-09-18')",
                            arrayOf<Any>(index + 1, type, index % 2))
                    }
                    if (version >= 5) {
                        db.execSQL("CREATE TABLE execution_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, category TEXT NOT NULL, level TEXT NOT NULL, title TEXT NOT NULL, message TEXT NOT NULL, source TEXT NOT NULL, metadata TEXT)")
                        db.execSQL("INSERT INTO execution_logs VALUES (1, 1234, 'SYSTEM_CONTEXT', 'INFO', 'Old log', 'Preserve me', 'UpgradeTest', NULL)")
                    }
                    if (version >= 6) {
                        val module = if (version >= 7) ", module TEXT NOT NULL DEFAULT 'omni'" else ""
                        db.execSQL("CREATE TABLE omni_chat_sessions (sessionId TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, timestamp INTEGER NOT NULL, previewText TEXT NOT NULL$module)")
                        db.execSQL("CREATE TABLE omni_chat_messages (messageId TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, text TEXT NOT NULL, isUser INTEGER NOT NULL, mode TEXT NOT NULL, timestamp INTEGER NOT NULL, actionWidgetJson TEXT, suggestedWalkthroughId TEXT, FOREIGN KEY(sessionId) REFERENCES omni_chat_sessions(sessionId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE INDEX index_omni_chat_messages_sessionId ON omni_chat_messages(sessionId)")
                        db.execSQL("INSERT INTO omni_chat_sessions (sessionId,title,timestamp,previewText) VALUES ('old-session','Old chat',1234,'Preview')")
                        db.execSQL("INSERT INTO omni_chat_messages VALUES ('old-message','old-session','Old answer',0,'AUTO',1234,NULL,NULL)")
                    }
                    db.version = version
                }
                val migrated = AppDatabase.openFile(context, file.absolutePath)
                try {
                    val rows = migrated.backupDao().slots()
                    assertEquals(5, rows.size)
                    rows.forEachIndexed { index, slot ->
                        assertEquals(index % 2 == 1, slot.enabled)
                        assertEquals("MON,WED", slot.activeDays)
                        assertEquals("2026-09-18", slot.lastExecutedDay)
                        assertNull(slot.lastTriggerState)
                        assertEquals(12.9, slot.lat!!, 0.001)
                    }
                    assertEquals(setOf("LOCATION", "BATTERY", "TIME_OF_DAY", "WIFI", "APP"), rows.map { it.triggerType }.toSet())
                    if (version >= 5) assertEquals("Old log", migrated.backupDao().logs().single().title)
                    if (version >= 6) {
                        assertEquals("omni", migrated.backupDao().sessions().single().module)
                        assertEquals("Old answer", migrated.backupDao().messages().single().text)
                    }
                    assertEquals(8, migrated.openHelper.readableDatabase.version)
                } finally { migrated.close() }
            } finally { root.deleteRecursively() }
        }
    }
}
