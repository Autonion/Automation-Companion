package com.example.automationcompanion.features.system_context_automation.location.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import com.example.automationcompanion.features.system_context_automation.location.data.dao.SlotDao
import com.example.automationcompanion.features.system_context_automation.location.data.models.Slot

@Database(entities = [Slot::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun slotDao(): SlotDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val inst = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "locauto.db"
                )
                    .fallbackToDestructiveMigration(false)
                    .build()
                INSTANCE = inst
                inst
            }
        }
    }
}
