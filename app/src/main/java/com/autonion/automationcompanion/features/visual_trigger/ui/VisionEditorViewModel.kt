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
import com.autonion.automationcompanion.features.visual_trigger.models.ExecutionMode
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
        var action: VisionAction = VisionAction.Click,
        val sourceCapturePath: String? = null  // Which capture page this region belongs to
    )

    private val _regions = MutableStateFlow<List<TempRegion>>(emptyList())
    val regions = _regions.asStateFlow()

    // Multi-page capture support: regions visible on the CURRENT page only
    private val _visibleRegions = MutableStateFlow<List<TempRegion>>(emptyList())
    val visibleRegions = _visibleRegions.asStateFlow()

    // All capture pages (ordered list of capture image paths)
    private val _capturePages = MutableStateFlow<List<String>>(emptyList())
    val capturePages = _capturePages.asStateFlow()

    private val _currentPageIndex = MutableStateFlow(0)
    val currentPageIndex = _currentPageIndex.asStateFlow()

    private var currentImagePath: String? = null
    private var editingPresetId: String? = null
    private var appendPresetId: String? = null

    private val _executionMode = MutableStateFlow(ExecutionMode.MANDATORY_SEQUENTIAL)
    val executionMode = _executionMode.asStateFlow()

    // All regions across all pages (for saving)
    private val allRegions = mutableListOf<TempRegion>()

    fun loadImage(path: String) {
        currentImagePath = path
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(path)
            }
            _imageBitmap.value = bitmap
        }
    }

    fun updateExecutionMode(mode: ExecutionMode) {
        _executionMode.value = mode
    }

    fun prepareForAppend(presetId: String) {
        appendPresetId = presetId
    }

    /**
     * Load an existing preset for editing.
     * Groups regions by their sourceCapturePath to support multi-page navigation.
     */
    fun loadExistingPreset(presetId: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val preset = repository.getPreset(presetId)
            if (preset == null) {
                withContext(Dispatchers.Main) { onResult(false) }
                return@launch
            }

            editingPresetId = presetId
            _executionMode.value = preset.executionMode

            // Build list of all regions with their source info
            val tempRegions = preset.regions.map { region ->
                TempRegion(
                    id = region.id,
                    rect = region.toRect(),
                    color = region.color,
                    action = region.action,
                    sourceCapturePath = region.sourceCapturePath
                )
            }
            allRegions.clear()
            allRegions.addAll(tempRegions)
            _regions.value = tempRegions

            // Determine capture pages
            val pages = mutableListOf<String>()

            // Group by sourceCapturePath; regions without sourceCapturePath fall back to preset.captureImagePath
            val defaultCapturePath = preset.captureImagePath
            val uniquePaths = tempRegions
                .map { it.sourceCapturePath ?: defaultCapturePath }
                .filterNotNull()
                .distinct()

            if (uniquePaths.isNotEmpty()) {
                // Only include pages whose files still exist
                pages.addAll(uniquePaths.filter { File(it).exists() })
            }

            // Fallback: if no pages found, use the preset's main capture path
            if (pages.isEmpty() && defaultCapturePath != null && File(defaultCapturePath).exists()) {
                pages.add(defaultCapturePath)
            }

            if (pages.isEmpty()) {
                withContext(Dispatchers.Main) { onResult(false) }
                return@launch
            }

            _capturePages.value = pages
            _currentPageIndex.value = 0
            loadPage(0)

            withContext(Dispatchers.Main) { onResult(true) }
        }
    }

    /**
     * Load a specific capture page and show only its regions.
     */
    fun loadPage(pageIndex: Int) {
        val pages = _capturePages.value
        if (pageIndex < 0 || pageIndex >= pages.size) return

        _currentPageIndex.value = pageIndex
        val pagePath = pages[pageIndex]
        currentImagePath = pagePath

        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(pagePath)
            }
            _imageBitmap.value = bitmap

            // Show only regions belonging to this page
            val defaultCapturePath = if (pages.size == 1) pagePath else null
            val pageRegions = allRegions.filter { region ->
                val regionSource = region.sourceCapturePath ?: defaultCapturePath
                regionSource == pagePath
            }
            _visibleRegions.value = pageRegions
            _regions.value = pageRegions
        }
    }

    fun navigateToPage(pageIndex: Int) {
        // Before navigating, save any region edits from the current page back to allRegions
        syncCurrentPageRegions()
        loadPage(pageIndex)
    }

    /**
     * Sync the current visible regions back to allRegions (in case of edits/moves/deletes).
     */
    private fun syncCurrentPageRegions() {
        val pages = _capturePages.value
        val currentIdx = _currentPageIndex.value
        if (pages.isEmpty() || currentIdx >= pages.size) return

        val currentPagePath = pages[currentIdx]
        val currentRegions = _regions.value

        // Replace regions for the current page in allRegions
        allRegions.removeAll { region ->
            val regionSource = region.sourceCapturePath ?: (if (pages.size == 1) currentPagePath else null)
            regionSource == currentPagePath
        }
        allRegions.addAll(currentRegions)
    }

    val isMultiPage: Boolean
        get() = _capturePages.value.size > 1

    /**
     * Load a preset directly from JSON String (used for Flow mode)
     */
    fun loadFlowPreset(json: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val preset = Json.decodeFromString<VisionPreset>(json)
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
                        action = region.action,
                        sourceCapturePath = region.sourceCapturePath
                    )
                }

                withContext(Dispatchers.Main) { onResult(true) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    fun addRegion(rect: Rect) {
        val currentList = _regions.value.toMutableList()
        // Use global max ID across ALL regions (not just current page)
        val maxGlobalId = maxOf(
            allRegions.maxOfOrNull { it.id } ?: 0,
            currentList.maxOfOrNull { it.id } ?: 0
        )
        val nextId = maxGlobalId + 1
        val color = android.graphics.Color.HSVToColor(floatArrayOf((nextId * 137.5f) % 360, 0.8f, 1f))
        val newRegion = TempRegion(nextId, rect, color, sourceCapturePath = currentImagePath)
        currentList.add(newRegion)
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
        // Also remove from allRegions
        allRegions.removeAll { it.id == id }
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
            val removed = currentList.removeAt(currentList.lastIndex)
            _regions.value = currentList
            // Also remove from allRegions
            allRegions.removeAll { it.id == removed.id }
        }
    }

    /**
     * Gather all regions across all pages before saving.
     */
    private fun gatherAllRegions(): List<TempRegion> {
        syncCurrentPageRegions()
        return allRegions.toList()
    }

    fun savePreset(name: String, onComplete: (String) -> Unit) {
        val bitmap = _imageBitmap.value ?: return
        if (currentImagePath == null) return

        viewModelScope.launch {
            val savedId = withContext(Dispatchers.IO) {
                if (appendPresetId != null) {
                    val existingPreset = repository.getPreset(appendPresetId!!)
                    if (existingPreset != null) {
                        // Save the new screen capture image for this append session
                        val appendCaptureFile = File(getApplication<Application>().filesDir, "viz_capture_${appendPresetId}_${System.currentTimeMillis()}.png")
                        FileOutputStream(appendCaptureFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                        }

                        val maxExistingId = existingPreset.regions.maxOfOrNull { it.id } ?: 0

                        val newVisionRegions = _regions.value.mapIndexed { index, temp ->
                            val uniqueId = maxExistingId + index + 1
                            val templateFile = File(getApplication<Application>().filesDir, "viz_${appendPresetId}_${uniqueId}.png")
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
                                id = uniqueId,
                                rect = temp.rect,
                                templatePath = templateFile.absolutePath,
                                action = temp.action,
                                color = temp.color,
                                sourceCapturePath = appendCaptureFile.absolutePath
                            )
                        }

                        val updatedPreset = existingPreset.copy(
                            regions = existingPreset.regions + newVisionRegions,
                            captureImagePath = appendCaptureFile.absolutePath
                        )
                        repository.savePreset(updatedPreset)
                        return@withContext appendPresetId!!
                    }
                }

                // Editing existing or creating new
                val presetId = editingPresetId ?: UUID.randomUUID().toString()

                if (editingPresetId != null && isMultiPage) {
                    // Multi-page edit: save all regions across all pages, preserving per-page bitmaps
                    val allRegs = gatherAllRegions()
                    val pages = _capturePages.value

                    val visionRegions = allRegs.map { temp ->
                        val sourcePath = temp.sourceCapturePath
                        val sourceBitmap = if (sourcePath != null && File(sourcePath).exists()) {
                            BitmapFactory.decodeFile(sourcePath)
                        } else bitmap

                        val templateFile = File(getApplication<Application>().filesDir, "viz_${presetId}_${temp.id}.png")
                        val crop = Bitmap.createBitmap(
                            sourceBitmap,
                            temp.rect.left.coerceAtLeast(0),
                            temp.rect.top.coerceAtLeast(0),
                            temp.rect.width().coerceAtMost(sourceBitmap.width - temp.rect.left.coerceAtLeast(0)),
                            temp.rect.height().coerceAtMost(sourceBitmap.height - temp.rect.top.coerceAtLeast(0))
                        )
                        FileOutputStream(templateFile).use { out ->
                            crop.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }

                        VisionRegion.fromRect(
                            id = temp.id,
                            rect = temp.rect,
                            templatePath = templateFile.absolutePath,
                            action = temp.action,
                            color = temp.color,
                            sourceCapturePath = temp.sourceCapturePath
                        )
                    }

                    val preset = VisionPreset(
                        id = presetId,
                        name = name,
                        regions = visionRegions,
                        isActive = true,
                        executionMode = _executionMode.value,
                        captureImagePath = pages.lastOrNull() ?: currentImagePath
                    )
                    repository.savePreset(preset)
                    return@withContext presetId
                }

                // Single-page save (new capture or single-image edit)
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
                        color = temp.color,
                        sourceCapturePath = captureFile.absolutePath
                    )
                }

                val preset = VisionPreset(
                    id = presetId,
                    name = name,
                    regions = visionRegions,
                    isActive = true,
                    executionMode = _executionMode.value,
                    captureImagePath = captureFile.absolutePath
                )
                repository.savePreset(preset)
                presetId
            }
            withContext(Dispatchers.Main) {
                onComplete(savedId)
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
                        color = temp.color,
                        sourceCapturePath = captureFile.absolutePath
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
