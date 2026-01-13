package com.autonion.automationcompanion.features.system_context_automation.location.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.autonion.automationcompanion.features.system_context_automation.location.data.dao.SlotDao
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot

@Database(
    entities = [Slot::class],
    version = 3, // ⬅️ bump version
    exportSchema = false
)
@TypeConverters(AutomationActionConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun slotDao(): SlotDao

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
                    .addMigrations(MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
