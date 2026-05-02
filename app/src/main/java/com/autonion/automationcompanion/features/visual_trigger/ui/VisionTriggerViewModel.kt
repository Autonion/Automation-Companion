package com.autonion.automationcompanion.features.visual_trigger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonion.automationcompanion.features.visual_trigger.data.VisionRepository
import com.autonion.automationcompanion.features.visual_trigger.models.VisionPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VisionTriggerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = VisionRepository(application.applicationContext)

    private val _presets = MutableStateFlow<List<VisionPreset>>(emptyList())
    val presets: StateFlow<List<VisionPreset>> = _presets.asStateFlow()

    init {
        loadPresets()
    }

    private fun loadPresets() {
        viewModelScope.launch {
            _presets.value = repository.getAllPresets()
        }
    }

    fun refresh() {
        loadPresets()
    }

    fun deletePreset(presetId: String) {
        viewModelScope.launch {
            repository.deletePreset(presetId)
            loadPresets()
        }
    }

    fun togglePresetActive(presetId: String) {
        viewModelScope.launch {
            val preset = repository.getPreset(presetId) ?: return@launch
            val updated = preset.copy(isActive = !preset.isActive)
            repository.savePreset(updated)
            loadPresets()
        }
    }
}
