package com.autonion.automationcompanion.features.screen_understanding_ml.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract
import com.autonion.automationcompanion.features.screen_understanding_ml.core.*
import com.autonion.automationcompanion.features.screen_understanding_ml.logic.PresetRepository
import com.autonion.automationcompanion.features.screen_understanding_ml.logic.CaptureMetadataStore
import com.autonion.automationcompanion.features.screen_understanding_ml.model.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

internal val AutomationStep.captureKey: String
    get() = captureImagePath ?: "legacy:${captureScreenWidth}x$captureScreenHeight"

internal data class CapturePage(val key: String, val path: String?, val width: Float, val height: Float,
    val metadataPath: String? = null)
enum class EditorDisplayMode { ELEMENTS, TEXT, SELECTED }

/** Owns the draft independently of the capture service and retains it across rotation. */
class CaptureEditorViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val repository = PresetRepository(application)
    private val json = Json { ignoreUnknownKeys = true }
    private val gson = Gson()
    private var initialized = false
    private var original: AutomationPreset? = null
    private val undo = ArrayDeque<List<AutomationStep>>()
    private val ocrEngine = lazy { OcrEngine() }
    private val ocrLock = Mutex()
    private val analyses = mutableMapOf<String, List<UIElement>>()
    private val textAnalyses = java.util.concurrent.ConcurrentHashMap<String, OcrResult>()
    private var analysisVersion = 0
    private var analysisJob: Job? = null
    private var pageJob: Job? = null

    var steps by mutableStateOf<List<AutomationStep>>(emptyList(), neverEqualPolicy()); private set
    internal var pages by mutableStateOf<List<CapturePage>>(emptyList()); private set
    var pageIndex by mutableIntStateOf(0); private set
    var bitmap by mutableStateOf<Bitmap?>(null); private set
    var elements by mutableStateOf<List<UIElement>>(emptyList()); private set
    var textElements by mutableStateOf<List<UIElement>>(emptyList()); private set
    var mode by mutableStateOf(EditorDisplayMode.SELECTED); private set
    var selectedId by mutableStateOf<String?>(null); private set
    var name by mutableStateOf(""); private set
    var executionMode by mutableStateOf(ExecutionMode.STRICT); private set
    var loading by mutableStateOf(true); private set
    var scanning by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var dirty by mutableStateOf(false); private set
    var canUndo by mutableStateOf(false); private set
    var standaloneEdit = false; private set
    var flowMode = false; private set
    var a11yOnly = false; private set
    internal var captureMetadata by mutableStateOf<CaptureMetadata?>(null); private set
    internal val pageAccessibilityOnly get() = captureMetadata?.accessibilityOnly ?: a11yOnly
    internal val missingAccessibilityData get() = !loading && bitmap != null && captureMetadata == null
    val selected get() = steps.find { it.id == selectedId }
    internal val page get() = pages.getOrNull(pageIndex)
    internal val pageSteps get() = steps.filter { it.captureKey == page?.key }
    val canSave get() = !loading && !saving && steps.isNotEmpty() && name.isNotBlank() &&
        steps.none { (it.anchor.source == "ocr" || it.anchor.label.equals("Text", true)) && it.anchor.text.isNullOrBlank() }

    fun load(intent: Intent) {
        if (initialized) return
        initialized = true
        standaloneEdit = intent.hasExtra("PRESET_ID")
        flowMode = intent.getBooleanExtra(FlowOverlayContract.EXTRA_FLOW_MODE, false)
        a11yOnly = intent.getBooleanExtra("A11Y_ONLY_MODE", false)
        viewModelScope.launch {
            try {
                original = if (standaloneEdit) withContext(Dispatchers.IO) {
                    repository.getPreset(intent.getStringExtra("PRESET_ID")!!) ?: error("Preset no longer exists")
                } else ScreenUnderstandingService.instance?.getEditorPreset()
                name = saved["name"] ?: original?.name ?: intent.getStringExtra("PRESET_NAME") ?: "Untitled"
                executionMode = saved.get<String>("executionMode")?.let(ExecutionMode::valueOf)
                    ?: original?.executionMode ?: ExecutionMode.STRICT
                val imagePath = intent.getStringExtra("IMAGE_PATH")
                // Accept older capture intents as well; all new captures already have an asset.
                val metadataPath = intent.getStringExtra(CaptureMetadataStore.EXTRA_METADATA_PATH)
                    ?: if (imagePath != null && (intent.hasExtra("ACC_ELEMENTS_DATA") || intent.hasExtra("ACC_TEXT_DATA"))) {
                        withContext(Dispatchers.IO) {
                            CaptureMetadataStore.saveForImage(File(imagePath),
                                intent.getStringExtra("ACC_ELEMENTS_DATA")?.let { json.decodeFromString<List<UIElement>>(it) }.orEmpty(),
                                intent.getStringExtra("ACC_TEXT_DATA")?.let { json.decodeFromString<List<CapturedTextNode>>(it) }.orEmpty(),
                                a11yOnly)
                        }
                    } else null
                steps = saved.get<String>("steps")?.let { json.decodeFromString<List<AutomationStep>>(it) }
                    ?: original?.steps?.sortedBy { it.orderIndex }
                    ?: intent.getStringExtra("EXTRA_FLOW_ML_JSON")?.takeUnless {
                        intent.getBooleanExtra("EXTRA_CLEAR_ON_START", false)
                    }?.let { json.decodeFromString<List<AutomationStep>>(it) } ?: emptyList()
                // Older Flow nodes kept one screenshot at the node level instead of on each step.
                if (!standaloneEdit && original == null) steps = steps.map {
                    if (it.captureImagePath == null) it.copy(captureImagePath = imagePath) else it
                }
                val metadataByImage = steps.groupBy { it.captureImagePath }.mapValues { (_, targets) ->
                    targets.firstNotNullOfOrNull { it.captureMetadataPath }
                }
                steps = steps.map { step ->
                    step.copy(captureMetadataPath = if (step.captureImagePath == imagePath && metadataPath != null)
                        metadataPath else metadataByImage[step.captureImagePath])
                }
                pages = steps.distinctBy { it.captureKey }.map {
                    CapturePage(it.captureKey, it.captureImagePath, it.captureScreenWidth, it.captureScreenHeight, it.captureMetadataPath)
                }
                if (imagePath != null && pages.none { it.path == imagePath }) {
                    pages = pages + CapturePage(imagePath, imagePath, 0f, 0f, metadataPath)
                }
                mode = saved.get<String>("mode")?.let(EditorDisplayMode::valueOf)
                    ?: if (standaloneEdit) EditorDisplayMode.SELECTED else EditorDisplayMode.ELEMENTS
                selectedId = saved["selectedId"]
                dirty = saved["dirty"] ?: false
                navigate(saved["page"] ?: if (standaloneEdit) 0 else pages.lastIndex.coerceAtLeast(0))
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "Could not open preset"
                loading = false
            }
        }
    }

    fun navigate(index: Int) {
        if (pages.isEmpty()) { loading = false; return }
        pageIndex = index.coerceIn(pages.indices)
        saved["page"] = pageIndex
        pageJob?.cancel()
        analysisVersion++
        analysisJob?.cancel()
        bitmap = null
        captureMetadata = null
        elements = emptyList()
        textElements = emptyList()
        scanning = false
        loading = true
        val current = page!!
        pageJob = viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val image = current.path?.let(BitmapFactory::decodeFile)
                image to image?.let { CaptureMetadataStore.read(current.metadataPath, it.width, it.height) }
            }
            bitmap = loaded.first
            captureMetadata = loaded.second
            if (pageAccessibilityOnly && mode == EditorDisplayMode.TEXT) {
                mode = EditorDisplayMode.ELEMENTS
                saved["mode"] = mode.name
            }
            loading = false
            elements = analyses[current.key].orEmpty()
            textElements = textAnalyses[current.key]?.textElements().orEmpty()
            scanIfNeeded()
        }
    }

    fun chooseMode(value: EditorDisplayMode) {
        mode = value
        saved["mode"] = value.name
        scanIfNeeded()
    }

    private fun scanIfNeeded() {
        val current = page ?: return
        val source = bitmap ?: return
        val metadata = captureMetadata
        val accessibilityOnly = pageAccessibilityOnly
        if (loading || mode == EditorDisplayMode.SELECTED) return
        val requested = mode
        if (requested == EditorDisplayMode.ELEMENTS && analyses.containsKey(current.key)) return
        if (requested == EditorDisplayMode.TEXT && textAnalyses.containsKey(current.key)) return
        analysisJob?.cancel()
        val version = ++analysisVersion
        analysisJob = viewModelScope.launch {
            scanning = true
            try {
                withContext(Dispatchers.Default) {
                    suspend fun recognize(): OcrResult = ocrLock.withLock {
                        textAnalyses[current.key] ?: ocrEngine.value.recognizeText(source).also { textAnalyses[current.key] = it }
                    }
                    if (requested == EditorDisplayMode.TEXT) {
                        val result = recognize()
                        withContext(Dispatchers.Main) { textElements = result.textElements() }
                    } else {
                        val accessibility = metadata?.accessibilityElements.orEmpty()
                        val capturedText = metadata?.textNodes.orEmpty()
                        val detected = if (accessibilityOnly) accessibility.ifEmpty {
                            capturedText.map {
                                UIElement(UUID.randomUUID().toString(), "button", 0.85f,
                                    android.graphics.RectF(it.boundsLeft, it.boundsTop, it.boundsRight, it.boundsBottom),
                                    it.text, source = "accessibility")
                            }
                        } else {
                            val detector = PerceptionLayer(getApplication())
                            val visual = try { detector.detect(source) } finally { detector.close() }
                            val combined = OcrMatching.enrichWithText(
                                visual + AccessibilityAugmenter.filterUndetected(accessibility, visual),
                                capturedText.map { it.textElement() }
                            )
                            if (combined.none { it.text.isNullOrBlank() }) combined else try { OcrMatching.enrich(combined, recognize()) }
                            catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                combined
                            }
                        }
                        withContext(Dispatchers.Main) {
                            analyses[current.key] = detected
                            elements = detected
                            textElements = textAnalyses[current.key]?.textElements().orEmpty()
                        }
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = "Could not detect ${if (requested == EditorDisplayMode.TEXT) "text" else "elements"}. Try again."
            } finally { if (analysisVersion == version) scanning = false }
        }
    }

    fun select(id: String?) {
        selectedId = id
        saved["selectedId"] = id
        selected?.let { target ->
            val index = pages.indexOfFirst { it.key == target.captureKey }
            if (index >= 0 && index != pageIndex) navigate(index)
        }
    }

    fun add(element: UIElement) {
        // A new scan assigns new IDs to the same saved target.
        pageSteps.firstOrNull { it.anchor.id == element.id ||
            (it.anchor.source == element.source && it.anchor.bounds == element.bounds)
        }?.let { select(it.id); return }
        val image = bitmap ?: return
        change(steps + AutomationStep(UUID.randomUUID().toString(), steps.size, element.label,
            anchor = element, captureScreenWidth = image.width.toFloat(), captureScreenHeight = image.height.toFloat(),
            captureImagePath = page?.path, captureMetadataPath = page?.metadataPath))
        select(steps.last().id)
    }

    fun update(step: AutomationStep) = change(steps.map { if (it.id == step.id) step else it })
    fun remove(id: String) { change(steps.filterNot { it.id == id }); if (selectedId == id) select(null) }
    fun move(id: String, delta: Int) {
        val index = steps.indexOfFirst { it.id == id }
        if (index < 0 || index + delta !in steps.indices) return
        val updated = steps.toMutableList()
        val target = updated.removeAt(index)
        updated.add(index + delta, target)
        change(updated)
    }
    private fun change(updated: List<AutomationStep>) {
        undo.addLast(steps)
        if (undo.size > 30) undo.removeFirst()
        steps = updated.mapIndexed { index, step -> step.copy(orderIndex = index) }
        canUndo = true
        changed()
    }
    fun undo() {
        if (undo.isEmpty()) return
        steps = undo.removeLast()
        canUndo = undo.isNotEmpty()
        if (steps.none { it.id == selectedId }) select(null)
        changed()
    }
    fun rename(value: String) { name = value; changed() }
    fun updateExecutionMode(value: ExecutionMode) { executionMode = value; changed() }
    private fun changed() {
        dirty = true
        saved["dirty"] = true
        saved["steps"] = json.encodeToString(steps)
        saved["name"] = name
        saved["executionMode"] = executionMode.name
    }

    private fun draft() = (original ?: AutomationPreset(name = name, scope = ScopeType.GLOBAL,
        executionMode = executionMode, steps = steps)).copy(name = name.trim(), steps = steps, executionMode = executionMode)

    internal fun resultMode(): String = when {
        a11yOnly -> "A11Y_ONLY"
        steps.isNotEmpty() && steps.all { it.anchor.source == "ocr" || it.anchor.label.equals("Text", true) } -> "TEXT"
        else -> "ELEMENTS"
    }

    fun save(onSaved: () -> Unit) {
        if (!canSave) return
        viewModelScope.launch {
            saving = true
            try {
                if (standaloneEdit) {
                    val preset = draft()
                    withContext(Dispatchers.IO) {
                        check(!repository.hasPresetNamed(preset.name, preset.id)) { "A preset with this name already exists" }
                        repository.savePreset(preset)
                    }
                } else {
                    val service = ScreenUnderstandingService.instance ?: error("Capture session ended. Recapture to start again.")
                    service.replaceEditorSteps(steps)
                    service.updateFlowMetadata(page?.path, resultMode())
                    check(service.saveEditorPreset(name.trim(), executionMode)) { "Preset could not be saved" }
                }
                dirty = false
                saved["dirty"] = false
                onSaved()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "Could not save preset"
            } finally { saving = false }
        }
    }

    fun recapture(onReady: (String?) -> Unit) {
        if (saving) return
        viewModelScope.launch {
            try {
                if (standaloneEdit || ScreenUnderstandingService.instance == null) {
                    // Carry unsaved edits through capture consent without overwriting the saved preset.
                    val path = withContext(Dispatchers.IO) {
                        File(getApplication<Application>().cacheDir, "ml_draft_${UUID.randomUUID()}.json")
                            .apply { writeText(gson.toJson(draft())) }.absolutePath
                    }
                    onReady(path)
                } else {
                    ScreenUnderstandingService.instance?.replaceEditorSteps(steps)
                    ScreenUnderstandingService.instance?.setEditorConfiguration(name, executionMode)
                    ScreenUnderstandingService.instance?.updateFlowMetadata(page?.path, resultMode())
                    onReady(null)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "Could not prepare capture"
            }
        }
    }

    override fun onCleared() {
        if (ocrEngine.isInitialized()) ocrEngine.value.close()
        super.onCleared()
    }
}
