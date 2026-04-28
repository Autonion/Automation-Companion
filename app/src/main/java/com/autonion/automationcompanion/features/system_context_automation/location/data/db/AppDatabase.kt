package com.autonion.automationcompanion.features.system_context_automation.location.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.autonion.automationcompanion.features.automation_debugger.data.ExecutionLog
import com.autonion.automationcompanion.features.automation_debugger.data.ExecutionLogDao
import com.autonion.automationcompanion.features.system_context_automation.location.data.dao.SlotDao
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot

import com.autonion.automationcompanion.features.omni_chatbot.data.db.OmniChatDao
import com.autonion.automationcompanion.features.omni_chatbot.data.db.OmniChatMessageEntity
import com.autonion.automationcompanion.features.omni_chatbot.data.db.OmniChatSessionEntity

@Database(
    entities = [Slot::class, ExecutionLog::class, OmniChatSessionEntity::class, OmniChatMessageEntity::class],
    version = 7, // ⬅️ bump version for module column in chat sessions
    exportSchema = false
)
@TypeConverters(AutomationActionConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun slotDao(): SlotDao
    abstract fun executionLogDao(): ExecutionLogDao
    abstract fun omniChatDao(): OmniChatDao

    companion object {

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "locauto.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
