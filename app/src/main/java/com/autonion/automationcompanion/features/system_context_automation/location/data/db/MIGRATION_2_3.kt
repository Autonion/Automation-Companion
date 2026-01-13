package com.autonion.automationcompanion.features.system_context_automation.location.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {

        // 1️⃣ Create NEW table matching Slot EXACTLY
        db.execSQL("""
            CREATE TABLE slots_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,

                lat REAL NOT NULL,
                lng REAL NOT NULL,
                radiusMeters REAL NOT NULL,

                startMillis INTEGER NOT NULL,
                endMillis INTEGER NOT NULL,

                executed INTEGER NOT NULL,
                executedAt INTEGER,

                remindBeforeMinutes INTEGER NOT NULL,
                actions TEXT NOT NULL,

                enabled INTEGER NOT NULL,
                activeDays TEXT NOT NULL,
                lastExecutedDate TEXT
            )
        """.trimIndent())

        // 2️⃣ Copy data from old table
        db.execSQL("""
            INSERT INTO slots_new (
                id, lat, lng, radiusMeters,
                startMillis, endMillis,
                executed, executedAt,
                remindBeforeMinutes,
                actions,
                enabled,
                activeDays,
                lastExecutedDate
            )
            SELECT
                id, lat, lng, radiusMeters,
                startMillis, endMillis,
                executed, executedAt,
                remindBeforeMinutes,
                actions,
                enabled,
                'ALL',           -- default: everyday
                NULL             -- never executed yet
            FROM slots
        """.trimIndent())

        // 3️⃣ Drop old table
        db.execSQL("DROP TABLE slots")

        // 4️⃣ Rename
        db.execSQL("ALTER TABLE slots_new RENAME TO slots")
    }
}

