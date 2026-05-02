package com.autonion.automationcompanion.features.system_context_automation.location.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add module column to omni_chat_sessions with default 'omni'
        db.execSQL(
            "ALTER TABLE omni_chat_sessions ADD COLUMN module TEXT NOT NULL DEFAULT 'omni'"
        )
    }
}
