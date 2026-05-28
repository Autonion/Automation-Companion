package com.autonion.automationcompanion.features.gesture_recording_playback.managers

import android.content.Context
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.gesture_recording_playback.models.Action
import kotlinx.serialization.json.Json
import java.io.File

object PresetManager {

    private fun getPresetsDir(context: Context): File {
        val dir = File(context.filesDir, "presets")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun savePreset(context: Context, presetName: String, actions: List<Action>) {
        val normalizedName = presetName.trim()
        require(normalizedName.isNotEmpty()) { "Preset name cannot be blank" }

        val json = Json.encodeToString(actions)
        val file = File(getPresetsDir(context), "$normalizedName.json")
        file.writeText(json)
        DebugLogger.success(
            context, LogCategory.GESTURE_RECORDING,
            "Preset saved: $normalizedName",
            "Saved ${actions.size} actions to file",
            "PresetManager"
        )
    }

    fun loadPreset(context: Context, presetName: String): List<Action> {
        val file = File(getPresetsDir(context), "${presetName.trim()}.json")
        if (!file.exists()) return emptyList()

        val json = file.readText()
        return Json.decodeFromString(json)
    }

    fun deletePreset(context: Context, presetName: String) {
        val normalizedName = presetName.trim()
        if (normalizedName.isEmpty()) return

        val file = File(getPresetsDir(context), "$normalizedName.json")
        if (file.exists()) {
            file.delete()
            DebugLogger.info(
                context, LogCategory.GESTURE_RECORDING,
                "Preset deleted: $normalizedName",
                "Removed preset file from storage",
                "PresetManager"
            )
        }
    }

    fun presetExists(context: Context, presetName: String): Boolean {
        val normalizedName = presetName.trim()
        if (normalizedName.isEmpty()) return false

        return listPresets(context).any { it.equals(normalizedName, ignoreCase = true) }
    }

    fun listPresets(context: Context): List<String> {
        return getPresetsDir(context).listFiles()
            ?.map { it.name.removeSuffix(".json") }
            ?: emptyList()
    }
}
