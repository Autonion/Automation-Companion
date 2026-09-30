package com.autonion.automationcompanion.features.semantic_automation.ml

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionIntent
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * On-Device SLM Engine — format-detecting router that delegates to the correct runtime:
 *
 *  • .bin / .task  →  MediaPipe LlmInference  (existing Gemma TFLite models)
 *  • .gguf         →  llama.cpp via GGUFInferenceEngine  (Gemma 4, Phi-3.5, Llama 3.2, etc.)
 *
 * The rest of the codebase (PredictorCache, SemanticAutomationEngine, OmniChatbotViewModel)
 * only interacts with this class, so the runtime swap is transparent.
 */
class OnDeviceSLMEngine(
    private val context: Context,
    private val storageManager: ModelStorageManager,
    private val requestedModelPath: String
) : GenerativeUIEngine {

    private val TAG = "OnDeviceSLM"
    private val operations = Mutex()
    private val retired = AtomicBoolean(false)
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun <T> withLoadedModel(block: suspend () -> T): T = operations.withLock {
        check(!retired.get() && !needsReinitialization()) {
            "The selected model changed. Load the selected model and try again."
        }
        withContext(Dispatchers.IO) { block() }
    }

    // MediaPipe backend (for .bin / .task)
    private var llmInference: LlmInference? = null

    // GGUF backend (for .gguf)
    private var ggufEngine: GGUFInferenceEngine? = null

    // Track which format is currently loaded
    private var activeFormat: ModelFormat? = null
    private var activeModelPath: String? = null

    override suspend fun initialize() {
        operations.withLock {
            check(!retired.get()) { "This on-device engine has been closed." }
            // Loading allocates native resources; retain ownership even if the caller cancels.
            withContext(NonCancellable + Dispatchers.IO) {
                val modelPath = requestedModelPath
                val format = storageManager.getModelFormat(java.io.File(modelPath))

                // If the same model is already loaded, skip re-init
                if (modelPath == activeModelPath && activeFormat == format) {
                    Log.d(TAG, "Model already loaded: $modelPath ($format)")
                    return@withContext
                }

                // Close any previously loaded backend
                closeBackends()

                Log.d(TAG, "Initializing with model: $modelPath (format: $format)")

                when (format) {
                    ModelFormat.MEDIAPIPE -> {
                        val options = LlmInference.LlmInferenceOptions.builder()
                            .setModelPath(modelPath)
                            .setMaxTokens(1024)
                            .build()

                        llmInference = LlmInference.createFromOptions(context, options)
                        Log.d(TAG, "MediaPipe LlmInference initialized successfully")
                    }
                    ModelFormat.GGUF -> {
                        val engine = GGUFInferenceEngine(context, modelPath)
                        engine.initialize()
                        ggufEngine = engine
                        Log.d(TAG, "GGUF engine initialized successfully")
                    }
                }

                activeFormat = format
                activeModelPath = modelPath
            }
            currentCoroutineContext().ensureActive()
        }
    }

    override suspend fun predictNextAction(prompt: String): ActionIntent? = withLoadedModel {
        when (activeFormat) {
            ModelFormat.MEDIAPIPE -> {
                val inference = llmInference
                    ?: throw IllegalStateException("MediaPipe LlmInference not initialized.")

                Log.d(TAG, "MediaPipe: Generating response (${prompt.length} chars)")
                val startTime = System.currentTimeMillis()
                val rawResponse = inference.generateResponse(prompt)
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "MediaPipe response in ${elapsed}ms: $rawResponse")

                parseJsonResponse(rawResponse)
            }
            ModelFormat.GGUF -> {
                (ggufEngine ?: throw IllegalStateException("GGUF engine not initialized.")).predictNextAction(prompt)
            }
            null -> throw IllegalStateException("No model loaded. Call initialize() first.")
        }
    }

    override suspend fun generateChatResponse(prompt: String): String? = withLoadedModel {
        when (activeFormat) {
            ModelFormat.MEDIAPIPE -> {
                val inference = llmInference ?: throw IllegalStateException("MediaPipe model not loaded.")
                Log.d(TAG, "MediaPipe: Generating chat response (${prompt.length} chars)")
                val startTime = System.currentTimeMillis()
                val response = inference.generateResponse(prompt)
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "MediaPipe chat response in ${elapsed}ms")
                response.ifBlank { null }
            }
            ModelFormat.GGUF -> {
                ggufEngine?.generateChatResponse(prompt)
            }
            null -> null
        }
    }

    override fun generateChatResponseStream(prompt: String): Flow<String> = flow {
        operations.withLock {
            check(!retired.get() && !needsReinitialization()) { "The selected model changed. Try again." }
            when (activeFormat) {
                ModelFormat.MEDIAPIPE -> {
                    val response = llmInference?.generateResponse(prompt)
                    if (!response.isNullOrBlank()) emit(response)
                }
                ModelFormat.GGUF -> ggufEngine!!.generateChatResponseStream(prompt).collect { emit(it) }
                null -> error("No on-device model loaded.")
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Returns the currently loaded model format, or null if none is loaded.
     */
    fun getActiveFormat(): ModelFormat? = activeFormat

    /**
     * Returns a human-readable name for the currently loaded model.
     */
    fun getActiveModelName(): String? {
        val path = activeModelPath ?: return null
        val fileName = java.io.File(path).nameWithoutExtension
        // Clean up common naming patterns
        return fileName
            .replace("_", " ")
            .replace("-", " ")
            .replaceFirstChar { it.uppercase() }
    }

    /**
     * Checks if the active model has changed (user switched models) and needs re-initialization.
     */
    fun needsReinitialization(): Boolean {
        val currentPath = storageManager.getActiveModelPath()
        return retired.get() || currentPath != activeModelPath
    }

    /**
     * Parses the raw LLM text output into a structured ActionIntent.
     * Used only for the MediaPipe backend. GGUF has its own parser in GGUFInferenceEngine.
     */
    private fun parseJsonResponse(raw: String): ActionIntent? {
        return try {
            // Strip markdown code fences if present
            var jsonString = raw

            // Extract from markdown block if present
            val jsonBlockStart = jsonString.indexOf("```json")
            if (jsonBlockStart != -1) {
                val blockEnd = jsonString.indexOf("```", jsonBlockStart + 7)
                jsonString = if (blockEnd != -1) {
                    jsonString.substring(jsonBlockStart + 7, blockEnd)
                } else {
                    jsonString.substring(jsonBlockStart + 7)
                }
            } else {
                jsonString = jsonString.replace("```", "")
            }

            jsonString = jsonString.trim()

            val objStart = jsonString.indexOf('{')
            val objEnd = jsonString.lastIndexOf('}')
            val arrStart = jsonString.indexOf('[')
            val arrEnd = jsonString.lastIndexOf(']')

            var jsonToParse: org.json.JSONObject? = null

            fun tryParseObj(text: String): org.json.JSONObject? {
                try {
                    val obj = org.json.JSONObject(text)
                    if (obj.has("action")) return obj
                } catch (e: Exception) {}
                try {
                    val obj = org.json.JSONObject("$text}")
                    if (obj.has("action")) return obj
                } catch (e: Exception) {}
                return null
            }

            fun tryParseArr(text: String): org.json.JSONObject? {
                try {
                    val arr = org.json.JSONArray(text)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i)
                        if (obj != null && obj.has("action")) return obj
                    }
                } catch (e: Exception) {}
                return null
            }

            if (arrStart != -1 && (objStart == -1 || arrStart < objStart)) {
                if (arrEnd > arrStart) {
                    jsonToParse = tryParseArr(jsonString.substring(arrStart, arrEnd + 1))
                }
                if (jsonToParse == null && objStart != -1 && objEnd > objStart) {
                     jsonToParse = tryParseObj(jsonString.substring(objStart, objEnd + 1))
                }
            } else if (objStart != -1 && objEnd > objStart) {
                jsonToParse = tryParseObj(jsonString.substring(objStart, objEnd + 1))
            } else if (objStart != -1) {
                jsonToParse = tryParseObj(jsonString.substring(objStart))
            }

            if (jsonToParse == null) {
                Log.w(TAG, "No valid JSON action found in SLM response")
                return null
            }

            val json = jsonToParse

            val actionStr = json.optString("action", "CLICK").uppercase().trim()
            val elementIndex = json.optInt("element_index", -1)
            val textToType = json.optString("text_to_type", null)

            val actionType = when (actionStr) {
                "CLICK" -> com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType.CLICK
                "INPUT_TEXT" -> com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType.INPUT_TEXT
                "SCROLL_DOWN" -> com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType.SCROLL_DOWN
                "SCROLL_UP" -> com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType.SCROLL_UP
                "FINISH" -> com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType.FINISH
                else -> {
                    Log.w(TAG, "Unknown action: $actionStr, defaulting to CLICK")
                    com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType.CLICK
                }
            }

            ActionIntent(
                type = actionType,
                targetId = if (elementIndex >= 0) "slm_element_$elementIndex" else null,
                targetPoint = null,
                inputText = if (textToType != "null" && !textToType.isNullOrBlank()) textToType else null,
                description = "SLM: $actionStr on element[$elementIndex]"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SLM response", e)
            null
        }
    }

    private fun closeBackends() {
        llmInference?.close()
        llmInference = null
        ggufEngine?.close()
        ggufEngine = null
        activeFormat = null
        activeModelPath = null
    }

    suspend fun closeAndAwait() {
        retired.set(true)
        withContext(NonCancellable + Dispatchers.IO) {
            operations.withLock { closeBackends() }
        }
    }

    override fun close() {
        retired.set(true)
        closeScope.launch { closeAndAwait() }
    }
}
