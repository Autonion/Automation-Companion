package com.autonion.automationcompanion.features.system_context_automation.location.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add lastTriggerState column for edge-triggered automations (e.g. Battery)
        db.execSQL(
            "ALTER TABLE slots ADD COLUMN lastTriggerState INTEGER DEFAULT NULL"
        )
    }
}
