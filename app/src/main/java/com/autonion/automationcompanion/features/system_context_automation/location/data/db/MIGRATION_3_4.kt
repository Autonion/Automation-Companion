package com.autonion.automationcompanion.features.system_context_automation.location.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 3→4: Add multi-trigger support
 * - Add triggerType column (defaults to 'LOCATION' for backward compat)
 * - Add triggerConfigJson column (null for existing location slots)
 * - Existing data remains unchanged; location slots work as before
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1️⃣ Create NEW table with multi-trigger support
        db.execSQL("""
            CREATE TABLE slots_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,

                triggerType TEXT NOT NULL DEFAULT 'LOCATION',
                triggerConfigJson TEXT,

                lat REAL,
                lng REAL,
                radiusMeters REAL,

                startMillis INTEGER,
                endMillis INTEGER,

                remindBeforeMinutes INTEGER NOT NULL,
                actions TEXT NOT NULL,

                enabled INTEGER NOT NULL,
                activeDays TEXT NOT NULL,

                isInsideGeofence INTEGER NOT NULL DEFAULT 0,
                lastExecutedDay TEXT
            )
        """.trimIndent())

        // 2️⃣ Copy data from old table to new table
        // Only copy columns that exist in the old schema
        db.execSQL("""
            INSERT INTO slots_new (
                id, triggerType, triggerConfigJson,
                lat, lng, radiusMeters,
                startMillis, endMillis,
                remindBeforeMinutes,
                actions,
                enabled,
                activeDays,
                isInsideGeofence,
                lastExecutedDay
            )
            SELECT
                id, 'LOCATION', NULL,
                lat, lng, radiusMeters,
                startMillis, endMillis,
                remindBeforeMinutes,
                actions,
                enabled,
                activeDays,
                0,
                NULL
            FROM slots
        """.trimIndent())

        // 3️⃣ Drop old table
        db.execSQL("DROP TABLE slots")

        // 4️⃣ Rename
        db.execSQL("ALTER TABLE slots_new RENAME TO slots")
    }
}
