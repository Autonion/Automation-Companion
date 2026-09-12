package com.autonion.automationcompanion.features.screen_understanding_ml.logic

import android.content.Context
import com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationPreset
import com.google.gson.Gson
import java.io.File
import java.io.FileReader
import android.util.AtomicFile
import java.util.UUID

class PresetRepository(private val context: Context) {

    private val gson = Gson()
    private val presetsDir = File(context.filesDir, "ml_presets")

    init {
        if (!presetsDir.exists()) {
            presetsDir.mkdirs()
        }
    }

    fun savePreset(preset: AutomationPreset) {
        val normalizedName = preset.name.trim()
        require(normalizedName.isNotEmpty()) { "Preset name cannot be blank" }

        val images = File(presetsDir, "${preset.id}_images")
        val copied = mutableMapOf<String, String?>()
        val created = mutableListOf<File>()
        try {
            val steps = preset.steps.sortedBy { it.orderIndex }.mapIndexed { index, step ->
                val preview = step.captureImagePath?.let { path ->
                    copied.getOrPut(path) {
                        val source = File(path)
                        when {
                            !source.isFile -> null // Legacy/missing previews must not prevent editing.
                            source.canonicalFile.parentFile == images.canonicalFile -> source.absolutePath
                            else -> {
                                check(images.isDirectory || images.mkdirs()) { "Cannot create snapshot directory" }
                                val destination = File(images, "${UUID.randomUUID()}.png")
                                created.add(destination)
                                source.copyTo(destination)
                                destination.absolutePath
                            }
                        }
                    }
                }
                step.copy(orderIndex = index, captureImagePath = preview)
            }
            val file = AtomicFile(File(presetsDir, "${preset.id}.json"))
            val output = file.startWrite()
            try {
                output.write(gson.toJson(preset.copy(name = normalizedName, steps = steps)).toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
            val retained = steps.mapNotNull { it.captureImagePath }.toSet()
            images.listFiles()?.filter { it.isFile && it.absolutePath !in retained }?.forEach { it.delete() }
        } catch (error: Exception) {
            created.forEach { it.delete() }
            throw error
        }
    }

    fun getAllPresets(): List<AutomationPreset> {
        val files = presetsDir.listFiles { _, name -> name.endsWith(".json") }
        return files?.mapNotNull { file ->
            try {
                FileReader(file).use { reader ->
                    gson.fromJson(reader, AutomationPreset::class.java)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } ?: emptyList()
    }
    
    fun getPreset(id: String): AutomationPreset? {
        val file = File(presetsDir, "$id.json")
        if (!file.exists()) return null
        return try {
            FileReader(file).use { reader ->
                gson.fromJson(reader, AutomationPreset::class.java)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun deletePreset(id: String) {
        val file = File(presetsDir, "$id.json")
        if (file.exists()) {
            check(file.delete()) { "Could not delete preset" }
        }
        // Only files owned by this preset; never delete a supplied external image path.
        val images = File(presetsDir, "${id}_images")
        images.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        images.delete()
    }

    fun hasPresetNamed(name: String, excludingId: String? = null): Boolean {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) return false

        return getAllPresets().any { preset ->
            preset.id != excludingId && preset.name.equals(normalizedName, ignoreCase = true)
        }
    }
}
