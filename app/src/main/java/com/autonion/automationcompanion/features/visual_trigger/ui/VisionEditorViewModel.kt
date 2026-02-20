package com.autonion.automationcompanion.features.visual_trigger.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonion.automationcompanion.features.visual_trigger.data.VisionRepository
import com.autonion.automationcompanion.features.visual_trigger.models.VisionAction
import com.autonion.automationcompanion.features.visual_trigger.models.VisionPreset
import com.autonion.automationcompanion.features.visual_trigger.models.VisionRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class VisionEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = VisionRepository(application.applicationContext)

    private val _imageBitmap = MutableStateFlow<Bitmap?>(null)
    val imageBitmap = _imageBitmap.asStateFlow()

    data class TempRegion(
        val id: Int,
        val rect: Rect,
        val color: Int,
        var action: VisionAction = VisionAction.Click
    )

    private val _regions = MutableStateFlow<List<TempRegion>>(emptyList())
    val regions = _regions.asStateFlow()

    private var currentImagePath: String? = null
    private var editingPresetId: String? = null

    fun loadImage(path: String) {
        currentImagePath = path
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(path)
            }
            _imageBitmap.value = bitmap
        }
    }

    /**
     * Load an existing preset for editing.
     * Returns true if loaded successfully, false if capture image is missing.
     */
    fun loadExistingPreset(presetId: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val preset = repository.getPreset(presetId)
            if (preset == null) {
                withContext(Dispatchers.Main) { onResult(false) }
                return@launch
            }

            editingPresetId = presetId
            val capturePath = preset.captureImagePath

            if (capturePath == null || !File(capturePath).exists()) {
                withContext(Dispatchers.Main) { onResult(false) }
                return@launch
            }

            currentImagePath = capturePath
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(capturePath)
            }
            _imageBitmap.value = bitmap

            // Restore regions
            _regions.value = preset.regions.map { region ->
                TempRegion(
                    id = region.id,
                    rect = region.toRect(),
                    color = region.color,
                    action = region.action
                )
            }

            withContext(Dispatchers.Main) { onResult(true) }
        }
    }

    fun addRegion(rect: Rect) {
        val currentList = _regions.value.toMutableList()
        val nextId = (currentList.maxOfOrNull { it.id } ?: 0) + 1
        val color = android.graphics.Color.HSVToColor(floatArrayOf((nextId * 137.5f) % 360, 0.8f, 1f))
        currentList.add(TempRegion(nextId, rect, color))
        _regions.value = currentList
    }

    fun updateRegionAction(id: Int, action: VisionAction) {
        val currentList = _regions.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) {
            currentList[index] = currentList[index].copy(action = action)
            _regions.value = currentList
        }
    }

    fun removeRegion(id: Int) {
        val currentList = _regions.value.toMutableList()
        currentList.removeAll { it.id == id }
        _regions.value = currentList
    }

    fun updateRegionRect(id: Int, newRect: Rect) {
        val currentList = _regions.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) {
            currentList[index] = currentList[index].copy(rect = newRect)
            _regions.value = currentList
        }
    }

    fun undoLastRegion() {
        val currentList = _regions.value.toMutableList()
        if (currentList.isNotEmpty()) {
            currentList.removeAt(currentList.lastIndex)
            _regions.value = currentList
        }
    }

    fun savePreset(name: String, onComplete: () -> Unit) {
        val bitmap = _imageBitmap.value ?: return
        if (currentImagePath == null) return

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val presetId = editingPresetId ?: UUID.randomUUID().toString()

                // Save capture image to a permanent location
                val captureFile = File(getApplication<Application>().filesDir, "viz_capture_${presetId}.png")
                if (!captureFile.exists() || editingPresetId == null) {
                    FileOutputStream(captureFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                    }
                }

                val visionRegions = _regions.value.map { temp ->
                    val templateFile = File(getApplication<Application>().filesDir, "viz_${presetId}_${temp.id}.png")
                    val crop = Bitmap.createBitmap(
                        bitmap,
                        temp.rect.left.coerceAtLeast(0),
                        temp.rect.top.coerceAtLeast(0),
                        temp.rect.width().coerceAtMost(bitmap.width - temp.rect.left.coerceAtLeast(0)),
                        temp.rect.height().coerceAtMost(bitmap.height - temp.rect.top.coerceAtLeast(0))
                    )
                    FileOutputStream(templateFile).use { out ->
                        crop.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }

                    VisionRegion.fromRect(
                        id = temp.id,
                        rect = temp.rect,
                        templatePath = templateFile.absolutePath,
                        action = temp.action,
                        color = temp.color
                    )
                }

                val preset = VisionPreset(
                    id = presetId,
                    name = name,
                    regions = visionRegions,
                    isActive = true,
                    captureImagePath = captureFile.absolutePath
                )
                repository.savePreset(preset)
            }
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    /**
     * Flow mode save: serializes the setup into a VisionPreset JSON and saves to a temp file,
     * without permanent DB storage.
     */
    fun saveForFlowMode(flowNodeId: String, onComplete: (String) -> Unit) {
        val bitmap = _imageBitmap.value ?: return
        if (currentImagePath == null) return

        viewModelScope.launch {
            val tempFilePath = withContext(Dispatchers.IO) {
                // Save capture image
                val captureFile = File(getApplication<Application>().cacheDir, "flow_viz_cap_${flowNodeId}.png")
                FileOutputStream(captureFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }

                val visionRegions = _regions.value.map { temp ->
                    val templateFile = File(getApplication<Application>().cacheDir, "flow_viz_${flowNodeId}_${temp.id}.png")
                    val crop = Bitmap.createBitmap(
                        bitmap,
                        temp.rect.left.coerceAtLeast(0),
                        temp.rect.top.coerceAtLeast(0),
                        temp.rect.width().coerceAtMost(bitmap.width - temp.rect.left.coerceAtLeast(0)),
                        temp.rect.height().coerceAtMost(bitmap.height - temp.rect.top.coerceAtLeast(0))
                    )
                    FileOutputStream(templateFile).use { out ->
                        crop.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }

                    VisionRegion.fromRect(
                        id = temp.id,
                        rect = temp.rect,
                        templatePath = templateFile.absolutePath,
                        action = temp.action,
                        color = temp.color
                    )
                }

                val preset = VisionPreset(
                    id = "flow_${flowNodeId}",
                    name = "Flow Vision Config",
                    regions = visionRegions,
                    isActive = true,
                    captureImagePath = captureFile.absolutePath
                )
                
                val json = Json.encodeToString(preset)
                val tempFile = File(getApplication<Application>().cacheDir, "flow_vision_${flowNodeId}.json")
                tempFile.writeText(json)
                
                tempFile.absolutePath
            }
            withContext(Dispatchers.Main) {
                onComplete(tempFilePath)
            }
        }
    }
}
