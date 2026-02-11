package com.autonion.automationcompanion.features.screen_understanding_ml.logic

import android.content.Context
import com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationPreset
import com.google.gson.Gson
import java.io.File
import java.io.FileReader
import java.io.FileWriter

class PresetRepository(private val context: Context) {

    private val gson = Gson()
    private val presetsDir = File(context.filesDir, "ml_presets")

    init {
        if (!presetsDir.exists()) {
            presetsDir.mkdirs()
        }
    }

    fun savePreset(preset: AutomationPreset) {
        val file = File(presetsDir, "${preset.id}.json")
        FileWriter(file).use { writer ->
            gson.toJson(preset, writer)
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
            file.delete()
        }
    }
}
