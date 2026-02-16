package com.autonion.automationcompanion.features.system_context_automation.location.data.db

import androidx.room.TypeConverter
import com.autonion.automationcompanion.automation.actions.models.AutomationAction
import kotlinx.serialization.json.Json

class AutomationActionConverter {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    @TypeConverter
    fun fromActions(actions: List<AutomationAction>): String {
        return json.encodeToString(actions)
    }

    @TypeConverter
    fun toActions(data: String): List<AutomationAction> {
        return json.decodeFromString(data)
    }
}