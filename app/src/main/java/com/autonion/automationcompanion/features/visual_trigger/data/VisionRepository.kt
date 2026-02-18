package com.autonion.automationcompanion.features.visual_trigger.data

import android.content.Context
import com.autonion.automationcompanion.features.visual_trigger.models.VisionPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Simple file-based repository for Vision Presets.
 * Stores presets as JSON files in app's internal storage.
 */
class VisionRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val presetsDir = File(context.filesDir, "vision_presets")

    init {
        if (!presetsDir.exists()) {
            presetsDir.mkdirs()
        }
    }

    suspend fun getAllPresets(): List<VisionPreset> = withContext(Dispatchers.IO) {
        presetsDir.listFiles()?.mapNotNull { file ->
            try {
                val text = file.readText()
                json.decodeFromString<VisionPreset>(text)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } ?: emptyList()
    }

    suspend fun savePreset(preset: VisionPreset) = withContext(Dispatchers.IO) {
        val file = File(presetsDir, "${preset.id}.json")
        val text = json.encodeToString(preset)
        file.writeText(text)
    }

    suspend fun deletePreset(presetId: String) = withContext(Dispatchers.IO) {
        val file = File(presetsDir, "$presetId.json")
        if (file.exists()) {
            file.delete()
        }
    }

    suspend fun getPreset(id: String): VisionPreset? = withContext(Dispatchers.IO) {
        val file = File(presetsDir, "$id.json")
        if (file.exists()) {
            try {
                json.decodeFromString<VisionPreset>(file.readText())
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }
}
