package com.autonion.automationcompanion.features.visual_trigger.data

import android.content.Context
import android.util.AtomicFile
import com.autonion.automationcompanion.features.visual_trigger.models.VisionPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Simple file-based repository for Vision Presets.
 * Stores presets as JSON files in app's internal storage.
 */
class VisionRepository(private val context: Context) {

    companion object {
        private val writeMutex = Mutex()
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val presetsDir = File(context.filesDir, "vision_presets")

    init {
        if (!presetsDir.exists()) {
            presetsDir.mkdirs()
        }
    }

    suspend fun getAllPresets(): List<VisionPreset> = withContext(Dispatchers.IO) {
        presetsDir.listFiles()?.filter { it.extension == "json" }?.mapNotNull { file ->
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
        val normalizedName = preset.name.trim()
        require(normalizedName.isNotEmpty()) { "Preset name cannot be blank" }

        writeMutex.withLock {
            val file = AtomicFile(File(presetsDir, "${preset.id}.json"))
            val bytes = json.encodeToString(preset.copy(name = normalizedName)).toByteArray(Charsets.UTF_8)
            val output = file.startWrite()
            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        }
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

    suspend fun hasPresetNamed(name: String, excludingId: String? = null): Boolean = withContext(Dispatchers.IO) {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) return@withContext false

        presetsDir.listFiles()?.any { file ->
            try {
                val preset = json.decodeFromString<VisionPreset>(file.readText())
                preset.id != excludingId && preset.name.equals(normalizedName, ignoreCase = true)
            } catch (e: Exception) {
                false
            }
        } ?: false
    }
}
