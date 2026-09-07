package com.autonion.automationcompanion.features.visual_trigger.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Path
import com.autonion.automationcompanion.core.util.BitmapUtils
import android.graphics.Rect
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonion.automationcompanion.features.visual_trigger.data.VisionRepository
import com.autonion.automationcompanion.features.visual_trigger.models.VisionAction
import com.autonion.automationcompanion.features.visual_trigger.models.VisionPreset
import com.autonion.automationcompanion.features.visual_trigger.models.VisionRegion
import com.autonion.automationcompanion.features.visual_trigger.models.ExecutionMode
import com.autonion.automationcompanion.features.visual_trigger.models.TapDispatchMode
import com.autonion.automationcompanion.features.visual_trigger.models.VisionMatchMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

class VisionEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = VisionRepository(application.applicationContext)

    // Display metrics for downsampling targets
    private val displayWidth: Int
    private val displayHeight: Int
    init {
        val metrics = application.resources.displayMetrics
        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels
    }

    // Full resolution — used by savePreset()/saveForFlowMode() for cropping
    private val _fullResBitmap = MutableStateFlow<Bitmap?>(null)

    // Full-res dimensions — exposed so VisionEditorScreen can compute
    // region rects in full-res coordinate space regardless of what's displayed
    private val _fullResWidth = MutableStateFlow(0)
    val fullResWidth = _fullResWidth.asStateFlow()
    private val _fullResHeight = MutableStateFlow(0)
    val fullResHeight = _fullResHeight.asStateFlow()

    // Downsampled — used by Compose UI for display only
    private val _displayBitmap = MutableStateFlow<Bitmap?>(null)
    val imageBitmap = _displayBitmap.asStateFlow()

    data class TempRegion(
        val id: Int,
        val rect: Rect,
        val color: Int,
        var action: VisionAction = VisionAction.Click,
        val sourceCapturePath: String? = null,  // Which capture page this region belongs to
        val matchThreshold: Float = 0.75f,
        val rotationDegrees: Float = 0f,
        val searchRect: Rect? = null,
        val matchMode: VisionMatchMode = VisionMatchMode.STATIC,
        val movingMatchThreshold: Float = 0.80f,
        val tapLeadMs: Int = 35
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
    private var loadedPresetName: String? = null

    private val _executionMode = MutableStateFlow(ExecutionMode.DETECT_ONLY)
    val executionMode = _executionMode.asStateFlow()

    private val _tapDispatchMode = MutableStateFlow(TapDispatchMode.SEQUENTIAL)
    val tapDispatchMode = _tapDispatchMode.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving = _isSaving.asStateFlow()
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError = _saveError.asStateFlow()
    private var saveCompleted = false

    fun dismissSaveError() { _saveError.value = null }

    // All regions across all pages (for saving)
    private val allRegions = mutableListOf<TempRegion>()

    fun loadImage(path: String) {
        currentImagePath = path
        viewModelScope.launch {
            val (fullRes, display) = withContext(Dispatchers.IO) {
                val full = BitmapFactory.decodeFile(path)
                val sampled = BitmapUtils.decodeSampledBitmapFromFile(path, displayWidth, displayHeight)
                full to sampled
            }
            _fullResBitmap.value = fullRes
            _fullResWidth.value = fullRes?.width ?: 0
            _fullResHeight.value = fullRes?.height ?: 0
            _displayBitmap.value = display
        }
    }

    fun updateExecutionMode(mode: ExecutionMode) {
        _executionMode.value = mode
    }

    fun updateTapDispatchMode(mode: TapDispatchMode) {
        _tapDispatchMode.value = mode
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
            loadedPresetName = preset.name
            _executionMode.value = preset.executionMode
            _tapDispatchMode.value = preset.tapDispatchMode

            // Build list of all regions with their source info
            val tempRegions = preset.regions.map { region ->
                TempRegion(
                    id = region.id,
                    rect = region.toRect(),
                    color = region.color,
                    action = region.action,
                    sourceCapturePath = region.sourceCapturePath,
                    matchThreshold = region.matchThreshold,
                    rotationDegrees = region.rotationDegrees,
                    searchRect = region.customSearchRect(),
                    matchMode = region.matchMode,
                    movingMatchThreshold = region.movingMatchThreshold,
                    tapLeadMs = region.tapLeadMs
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
            val (fullRes, display) = withContext(Dispatchers.IO) {
                val full = BitmapFactory.decodeFile(pagePath)
                val sampled = BitmapUtils.decodeSampledBitmapFromFile(pagePath, displayWidth, displayHeight)
                full to sampled
            }
            _fullResBitmap.value = fullRes
            _fullResWidth.value = fullRes?.width ?: 0
            _fullResHeight.value = fullRes?.height ?: 0
            _displayBitmap.value = display

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
                val (fullRes, display) = withContext(Dispatchers.IO) {
                    val full = BitmapFactory.decodeFile(capturePath)
                    val sampled = BitmapUtils.decodeSampledBitmapFromFile(capturePath, displayWidth, displayHeight)
                    full to sampled
                }
                _fullResBitmap.value = fullRes
                _fullResWidth.value = fullRes?.width ?: 0
                _fullResHeight.value = fullRes?.height ?: 0
                _displayBitmap.value = display

                // Restore regions
                _executionMode.value = preset.executionMode
                _tapDispatchMode.value = preset.tapDispatchMode
                val tempRegions = preset.regions.map { region ->
                    TempRegion(
                        id = region.id,
                        rect = region.toRect(),
                        color = region.color,
                        action = region.action,
                        sourceCapturePath = region.sourceCapturePath,
                        matchThreshold = region.matchThreshold,
                        rotationDegrees = region.rotationDegrees,
                        searchRect = region.customSearchRect(),
                        matchMode = region.matchMode,
                        movingMatchThreshold = region.movingMatchThreshold,
                        tapLeadMs = region.tapLeadMs
                    )
                }
                allRegions.clear()
                allRegions.addAll(tempRegions)
                _regions.value = tempRegions

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
        val newRegion = TempRegion(nextId, rect, color, sourceCapturePath = currentImagePath,
            searchRect = Rect(0, 0, _fullResWidth.value, _fullResHeight.value))
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

    fun updateRegionThreshold(id: Int, threshold: Float) {
        val boundedThreshold = threshold.coerceIn(0.5f, 1.0f)
        val currentList = _regions.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) {
            val region = currentList[index]
            currentList[index] = if (region.matchMode == VisionMatchMode.MOVING) {
                region.copy(movingMatchThreshold = boundedThreshold)
            } else region.copy(matchThreshold = boundedThreshold)
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

    fun updateRegionMatchMode(id: Int, mode: VisionMatchMode) {
        _regions.value = _regions.value.map {
            if (it.id == id) it.copy(matchMode = mode,
                searchRect = if (mode == VisionMatchMode.MOVING && it.searchRect == null)
                    Rect(0, 0, _fullResWidth.value, _fullResHeight.value) else it.searchRect) else it
        }
    }

    fun updateRegionTapLead(id: Int, leadMs: Int) {
        _regions.value = _regions.value.map {
            if (it.id == id) it.copy(tapLeadMs = leadMs.coerceIn(0, 150)) else it
        }
    }

    private fun validateRegions(regions: List<TempRegion>): Boolean {
        val moving = regions.filter { it.matchMode == VisionMatchMode.MOVING }
        val error = when {
            regions.isEmpty() -> "Select at least one target before saving"
            moving.isEmpty() -> null
            _executionMode.value != ExecutionMode.DETECT_ONLY -> "Rotating targets require React to matches"
            moving.any { it.action !is VisionAction.Click } -> "Moving objects require the Tap action"
            moving.any { it.rect.width() < 16 || it.rect.height() < 16 } -> "Moving targets must be at least 16 pixels wide and tall"
            else -> null
        }
        if (error != null) _saveError.value = error
        return error == null
    }

    fun updateRegionRotation(id: Int, rotationDegrees: Float) {
        val currentList = _regions.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) {
            currentList[index] = currentList[index].copy(
                rotationDegrees = normalizeRotationDegrees(rotationDegrees)
            )
            _regions.value = currentList
        }
    }

    fun updateRegionSearchRect(id: Int, searchRect: Rect?) {
        val currentList = _regions.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) {
            currentList[index] = currentList[index].copy(
                searchRect = sanitizeSearchRect(searchRect)
            )
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
        if (_capturePages.value.isEmpty()) return _regions.value.toList()
        syncCurrentPageRegions()
        return allRegions.toList()
    }

    private fun normalizeRotationDegrees(degrees: Float): Float {
        var normalized = degrees % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized <= -180f) normalized += 360f
        return normalized
    }

    private fun sanitizeSearchRect(searchRect: Rect?): Rect? {
        val imageWidth = _fullResWidth.value
        val imageHeight = _fullResHeight.value
        if (searchRect == null || imageWidth <= 0 || imageHeight <= 0) return null

        val clamped = Rect(searchRect)
        if (!clamped.intersect(0, 0, imageWidth, imageHeight)) return null
        return if (clamped.width() > 3 && clamped.height() > 3) clamped else null
    }

    private fun createRegionTemplateBitmap(sourceBitmap: Bitmap, temp: TempRegion): Bitmap {
        val rect = temp.rect
        if (abs(temp.rotationDegrees) < 0.01f) {
            return createAxisAlignedCrop(sourceBitmap, rect)
        }

        val bounds = rotatedBounds(rect, temp.rotationDegrees)
        val clampedBounds = Rect(bounds)
        if (!clampedBounds.intersect(0, 0, sourceBitmap.width, sourceBitmap.height) ||
            clampedBounds.width() <= 0 ||
            clampedBounds.height() <= 0
        ) {
            return createAxisAlignedCrop(sourceBitmap, rect)
        }

        val crop = Bitmap.createBitmap(clampedBounds.width(), clampedBounds.height(), Bitmap.Config.ARGB_8888)
        crop.eraseColor(android.graphics.Color.TRANSPARENT)

        val clipPath = Path()
        val corners = rotatedCorners(rect, temp.rotationDegrees)
        clipPath.moveTo(corners[0].first - clampedBounds.left, corners[0].second - clampedBounds.top)
        corners.drop(1).forEach { (x, y) ->
            clipPath.lineTo(x - clampedBounds.left, y - clampedBounds.top)
        }
        clipPath.close()

        Canvas(crop).apply {
            save()
            clipPath(clipPath)
            drawBitmap(sourceBitmap, -clampedBounds.left.toFloat(), -clampedBounds.top.toFloat(), null)
            restore()
        }

        return crop
    }

    private fun createAxisAlignedCrop(sourceBitmap: Bitmap, rect: Rect): Bitmap {
        val left = rect.left.coerceIn(0, (sourceBitmap.width - 1).coerceAtLeast(0))
        val top = rect.top.coerceIn(0, (sourceBitmap.height - 1).coerceAtLeast(0))
        val right = rect.right.coerceIn(left + 1, sourceBitmap.width)
        val bottom = rect.bottom.coerceIn(top + 1, sourceBitmap.height)
        return Bitmap.createBitmap(sourceBitmap, left, top, right - left, bottom - top)
    }

    private fun rotatedBounds(rect: Rect, rotationDegrees: Float): Rect {
        val corners = rotatedCorners(rect, rotationDegrees)
        return Rect(
            floor(corners.minOf { it.first }).toInt(),
            floor(corners.minOf { it.second }).toInt(),
            ceil(corners.maxOf { it.first }).toInt(),
            ceil(corners.maxOf { it.second }).toInt()
        )
    }

    private fun rotatedCorners(rect: Rect, rotationDegrees: Float): List<Pair<Float, Float>> {
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val cos = cos(radians).toFloat()
        val sin = sin(radians).toFloat()
        val cx = rect.left + rect.width() / 2f
        val cy = rect.top + rect.height() / 2f

        return listOf(
            rect.left.toFloat() to rect.top.toFloat(),
            rect.right.toFloat() to rect.top.toFloat(),
            rect.right.toFloat() to rect.bottom.toFloat(),
            rect.left.toFloat() to rect.bottom.toFloat()
        ).map { (x, y) ->
            val dx = x - cx
            val dy = y - cy
            (cx + dx * cos - dy * sin) to (cy + dx * sin + dy * cos)
        }
    }

    private data class SaveSnapshot(
        val name: String,
        val sourcePath: String,
        val regions: List<TempRegion>,
        val editingId: String?,
        val appendId: String?,
        val executionMode: ExecutionMode,
        val tapDispatchMode: TapDispatchMode
    )

    private fun snapshotForSave(name: String): SaveSnapshot? {
        val regions = (if (appendPresetId != null) _regions.value else gatherAllRegions())
            .map { it.copy(rect = Rect(it.rect), searchRect = it.searchRect?.let(::Rect)) }
        if (!validateRegions(regions)) return null
        val source = currentImagePath
        if (source == null || _fullResBitmap.value?.isRecycled != false) {
            _saveError.value = "The capture is not ready. Reopen or recapture the screen."
            return null
        }
        return SaveSnapshot(
            name, source, regions, editingPresetId, appendPresetId,
            _executionMode.value, _tapDispatchMode.value
        )
    }

    fun savePreset(name: String, onComplete: (String) -> Unit) {
        if (_isSaving.value || saveCompleted) return
        val normalizedName = if (editingPresetId != null && name == "New Automation") {
            loadedPresetName?.trim().orEmpty()
        } else name.trim()
        if (appendPresetId == null && normalizedName.isEmpty()) {
            _saveError.value = "Preset name is required"
            return
        }
        val snapshot = snapshotForSave(normalizedName) ?: return
        launchSave(onComplete) { persistSnapshot(snapshot) }
    }

    fun saveForFlowMode(flowNodeId: String, onComplete: (String) -> Unit) {
        if (_isSaving.value || saveCompleted) return
        val snapshot = snapshotForSave("Flow Vision Config") ?: return
        launchSave(onComplete) { persistSnapshot(snapshot, flowNodeId) }
    }

    private fun launchSave(onComplete: (String) -> Unit, save: suspend () -> String) {
        // Set this synchronously, before launching, so a second click cannot start another writer.
        _isSaving.value = true
        _saveError.value = null
        viewModelScope.launch {
            val savedId = try {
                withContext(Dispatchers.IO) { save() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("VisionEditor", "Save failed; editor remains open", error)
                _saveError.value = error.message ?: "Could not save the preset. Please try again."
                null
            } finally {
                _isSaving.value = false
            }
            if (savedId != null) {
                saveCompleted = true
                Log.i("VisionEditor", "Save completed: $savedId")
                onComplete(savedId)
            }
        }
    }

    private suspend fun persistSnapshot(snapshot: SaveSnapshot, flowNodeId: String? = null): String {
        val application = getApplication<Application>()
        val existingId = snapshot.appendId ?: snapshot.editingId
        val existing = if (flowNodeId == null && existingId != null) {
            requireNotNull(repository.getPreset(existingId)) {
                "The original preset no longer exists. Start a new capture."
            }
        } else null
        if (flowNodeId == null && snapshot.appendId == null) {
            require(!repository.hasPresetNamed(snapshot.name, excludingId = snapshot.editingId)) {
                "A preset with this name already exists"
            }
        }
        val id = if (flowNodeId != null) "flow_$flowNodeId" else existing?.id ?: UUID.randomUUID().toString()
        val revision = UUID.randomUUID().toString()
        val dir = if (flowNodeId != null) File(application.filesDir, "flow_assets") else application.filesDir
        check(dir.exists() || dir.mkdirs()) { "Could not create the preset folder" }
        val created = mutableListOf<File>()
        var published = false
        try {
            val offset = if (snapshot.appendId != null) existing!!.regions.maxOfOrNull { it.id } ?: 0 else 0
            val regions = snapshot.regions.mapIndexed { index, region ->
                if (snapshot.appendId != null) region.copy(id = offset + index + 1) else region
            }
            val reusableCaptures = existing?.let {
                (it.regions.mapNotNull { region -> region.sourceCapturePath } + listOfNotNull(it.captureImagePath)).toSet()
            }.orEmpty()
            val storedCaptures = mutableMapOf<String, String>()
            val savedRegions = mutableMapOf<Int, VisionRegion>()
            val pages = regions.groupBy { it.sourceCapturePath ?: snapshot.sourcePath }

            // Decode private source bitmaps on the save worker. The editor can close or
            // recycle its display image without invalidating an in-flight crop.
            pages.entries.forEachIndexed { pageIndex, (sourcePath, pageRegions) ->
                currentCoroutineContext().ensureActive()
                val source = requireNotNull(BitmapFactory.decodeFile(sourcePath)) {
                    "Could not read the captured image. Recapture the screen and try again."
                }
                try {
                    val capturePath = if (sourcePath in reusableCaptures) sourcePath else {
                        val capture = File(dir, "viz_capture_${id}_${revision}_$pageIndex.png")
                        created.add(capture)
                        writeBitmap(source, capture)
                        capture.absolutePath
                    }
                    storedCaptures[sourcePath] = capturePath
                    pageRegions.forEach { region ->
                        currentCoroutineContext().ensureActive()
                        val template = File(dir, "viz_${id}_${revision}_${region.id}.png")
                        created.add(template)
                        val crop = createRegionTemplateBitmap(source, region)
                        try {
                            writeBitmap(crop, template)
                        } finally {
                            // A full-image crop may alias the source.
                            if (crop !== source) crop.recycle()
                        }
                        savedRegions[region.id] = VisionRegion.fromRect(
                            id = region.id, rect = region.rect, templatePath = template.absolutePath,
                            action = region.action, color = region.color, sourceCapturePath = capturePath,
                            matchThreshold = region.matchThreshold, rotationDegrees = region.rotationDegrees,
                            searchRect = region.searchRect, matchMode = region.matchMode,
                            movingMatchThreshold = region.movingMatchThreshold, tapLeadMs = region.tapLeadMs
                        )
                    }
                } finally {
                    source.recycle()
                }
            }
            val newRegions = regions.map { savedRegions.getValue(it.id) }
            val capturePath = storedCaptures[snapshot.sourcePath] ?: storedCaptures.values.last()
            val preset = if (snapshot.appendId != null) {
                existing!!.copy(regions = existing.regions + newRegions, captureImagePath = capturePath)
            } else VisionPreset(
                id = id, name = snapshot.name, regions = newRegions,
                isActive = existing?.isActive ?: true, executionMode = snapshot.executionMode,
                tapDispatchMode = snapshot.tapDispatchMode, captureImagePath = capturePath
            )
            require(preset.regions.none { it.matchMode == VisionMatchMode.MOVING } ||
                preset.executionMode == ExecutionMode.DETECT_ONLY) {
                "Rotating targets require React to matches in the destination preset"
            }
            currentCoroutineContext().ensureActive()
            // Publish only after every asset is complete. Cancellation must not delete
            // assets after the JSON referencing them has been committed.
            var result = id
            withContext(NonCancellable) {
                if (flowNodeId != null) {
                    val jsonFile = File(application.cacheDir, "flow_vision_${revision}.json")
                    created.add(jsonFile)
                    jsonFile.writeText(Json.encodeToString(preset))
                    result = jsonFile.absolutePath
                } else {
                    repository.savePreset(preset)
                }
                published = true
            }
            Log.i("VisionEditor", "Persisted preset=$id append=${snapshot.appendId != null} regions=${preset.regions.size}")
            return result
        } finally {
            if (!published) created.forEach { it.delete() }
        }
    }

    private fun writeBitmap(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Could not save the captured image" }
        }
    }

    override fun onCleared() {
        super.onCleared()
        _fullResBitmap.value?.recycle()
        _fullResBitmap.value = null
        _displayBitmap.value?.recycle()
        _displayBitmap.value = null
    }
}
