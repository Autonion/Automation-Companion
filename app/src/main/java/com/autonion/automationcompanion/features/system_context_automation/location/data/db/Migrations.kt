package com.autonion.automationcompanion.features.system_context_automation.location.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import com.autonion.automationcompanion.automation.actions.models.AutomationAction
import kotlinx.serialization.builtins.ListSerializer

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {

        // 1️⃣ Add new column
        db.execSQL(
            """
            ALTER TABLE slots 
            ADD COLUMN actions TEXT NOT NULL DEFAULT '[]'
            """.trimIndent()
        )

        // 2️⃣ Read old data and convert SMS → actions
        val cursor = db.query(
            "SELECT id, message, contactsCsv FROM slots"
        )

        val json = Json {
            classDiscriminator = "type"
        }

        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val message = cursor.getString(1)
            val contacts = cursor.getString(2)

            val actions = listOf(
                AutomationAction.SendSms(
                    message = message,
                    contactsCsv = contacts
                )
            )

            val actionsJson = json.encodeToString(
                ListSerializer(AutomationAction.serializer()),
                actions
            )


            db.execSQL(
                "UPDATE slots SET actions = ? WHERE id = ?",
                arrayOf<Any>(actionsJson, id)
            )
        }

        cursor.close()
    }
}


