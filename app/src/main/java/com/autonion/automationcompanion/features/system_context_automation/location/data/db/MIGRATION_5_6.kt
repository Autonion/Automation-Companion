package com.autonion.automationcompanion.features.system_context_automation.location.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `omni_chat_sessions` (
                `sessionId` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `previewText` TEXT NOT NULL,
                PRIMARY KEY(`sessionId`)
            )
            """.trimIndent()
        )
        
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `omni_chat_messages` (
                `messageId` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                `isUser` INTEGER NOT NULL,
                `mode` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `actionWidgetJson` TEXT,
                `suggestedWalkthroughId` TEXT,
                PRIMARY KEY(`messageId`),
                FOREIGN KEY(`sessionId`) REFERENCES `omni_chat_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_omni_chat_messages_sessionId` ON `omni_chat_messages` (`sessionId`)")
    }
}
