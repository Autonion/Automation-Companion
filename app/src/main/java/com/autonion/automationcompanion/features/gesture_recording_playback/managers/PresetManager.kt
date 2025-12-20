package com.autonion.automationcompanion.features.gesture_recording_playback.managers

import android.content.Context
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
        val json = Json.encodeToString(actions)
        val file = File(getPresetsDir(context), "$presetName.json")
        file.writeText(json)
    }

    fun loadPreset(context: Context, presetName: String): List<Action> {
        val file = File(getPresetsDir(context), "$presetName.json")
        if (!file.exists()) return emptyList()

        val json = file.readText()
        return Json.decodeFromString(json)
    }

    fun deletePreset(context: Context, presetName: String) {
        val file = File(getPresetsDir(context), "$presetName.json")
        if (file.exists()) {
            file.delete()
        }
    }

    fun listPresets(context: Context): List<String> {
        return getPresetsDir(context).listFiles()
            ?.map { it.name.removeSuffix(".json") }
            ?: emptyList()
    }
}
