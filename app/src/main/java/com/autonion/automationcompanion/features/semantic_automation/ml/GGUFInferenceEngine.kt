package com.autonion.automationcompanion.features.semantic_automation.ml

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionIntent
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.codeshipping.llamakotlin.LlamaModel

/**
 * On-Device SLM Engine using llama.cpp (via llama-kotlin-android) to run GGUF models locally.
 * Supports models like Gemma 4, Gemma 3n, Phi-3.5, Llama 3.2, Qwen 2.5, etc.
 *
 * The .gguf model file must be imported by the user via the ModelManagerScreen.
 */
class GGUFInferenceEngine(
    private val context: Context,
    private val modelPath: String
) : GenerativeUIEngine {

    private val TAG = "GGUFEngine"
    private var llamaModel: LlamaModel? = null

    companion object {
        // Default inference parameters — balanced for mobile devices
        private const val DEFAULT_CONTEXT_SIZE = 2048
        private const val DEFAULT_THREAD_COUNT = 4
        private const val DEFAULT_TEMPERATURE = 0.3f
    }

    override suspend fun initialize() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Initializing llama.cpp with model: $modelPath")

            // Validate the model file exists and is non-trivially sized
            val modelFile = java.io.File(modelPath)
            if (!modelFile.exists()) {
                throw IllegalStateException("GGUF model file not found: $modelPath")
            }
            val fileSizeMB = modelFile.length() / (1024 * 1024)
            Log.d(TAG, "Model file size: ${fileSizeMB}MB")
            if (fileSizeMB < 10) {
                throw IllegalStateException(
                    "GGUF model file appears truncated or corrupted (${fileSizeMB}MB). " +
                    "Expected at least several hundred MB. Please re-download and re-import the model."
                )
            }

            val startTime = System.currentTimeMillis()
            try {
                llamaModel = LlamaModel.load(modelPath) {
                    contextSize = DEFAULT_CONTEXT_SIZE
                    threads = DEFAULT_THREAD_COUNT
                    temperature = DEFAULT_TEMPERATURE
                }
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "llama.cpp model loaded in ${elapsed}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load GGUF model via llama.cpp", e)
                // Provide a more helpful error message
                val message = e.message ?: "Unknown error"
                if (message.contains("Failed to load model")) {
                    throw IllegalStateException(
                        "llama.cpp could not load this model. This usually means:\n" +
                        "• The model's architecture is not supported by this llama.cpp version\n" +
                        "• The .gguf file is corrupted or incompletely downloaded\n" +
                        "Try a different model (e.g., Phi-3.5, Llama 3.2, Qwen 2.5) or re-download this one.\n" +
                        "Original error: $message",
                        e
                    )
                }
                throw e
            }
        }
    }

    override suspend fun predictNextAction(prompt: String): ActionIntent? = withContext(Dispatchers.IO) {
        val model = llamaModel
            ?: throw IllegalStateException("GGUF model not initialized. Call initialize() first.")

        Log.d(TAG, "Generating action response for prompt (${prompt.length} chars)")
        val startTime = System.currentTimeMillis()

        val responseBuilder = StringBuilder()
        try {
            model.generateStream(prompt).collect { token ->
                responseBuilder.append(token)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating stream for action prediction", e)
            return@withContext null
        }

        val rawResponse = responseBuilder.toString()
        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "GGUF response in ${elapsed}ms: ${rawResponse.take(300)}")

        parseJsonResponse(rawResponse)
    }

    override suspend fun generateChatResponse(prompt: String): String? = withContext(Dispatchers.IO) {
        val model = llamaModel
            ?: throw IllegalStateException("GGUF model not initialized. Call initialize() first.")

        Log.d(TAG, "Generating chat response for prompt (${prompt.length} chars)")
        val startTime = System.currentTimeMillis()

        val responseBuilder = StringBuilder()
        try {
            model.generateStream(prompt).collect { token ->
                responseBuilder.append(token)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating stream for chat response", e)
            return@withContext null
        }

        val response = responseBuilder.toString()
        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "GGUF chat response in ${elapsed}ms (${response.length} chars)")

        response.ifBlank { null }
    }

    override fun generateChatResponseStream(prompt: String): Flow<String> = flow {
        val model = llamaModel
            ?: throw IllegalStateException("GGUF model not initialized. Call initialize() first.")

        Log.d(TAG, "Streaming chat response for prompt (${prompt.length} chars)")
        val startTime = System.currentTimeMillis()

        try {
            model.generateStream(prompt).collect { token ->
                emit(token)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in GGUF chat response stream", e)
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "GGUF streaming complete in ${elapsed}ms")
    }.flowOn(Dispatchers.IO)

    /**
     * Parses the raw LLM text output into a structured ActionIntent.
     * Expected format: {"action": "CLICK", "element_index": 1, "text_to_type": null}
     *
     * This is the same parsing logic used by OnDeviceSLMEngine for consistency.
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
                Log.w(TAG, "No valid JSON action found in GGUF response")
                return null
            }

            val json = jsonToParse

            val actionStr = json.optString("action", "CLICK").uppercase().trim()
            val elementIndex = json.optInt("element_index", -1)
            val textToType = json.optString("text_to_type", "").ifBlank { null }

            val actionType = when (actionStr) {
                "CLICK" -> ActionType.CLICK
                "INPUT_TEXT" -> ActionType.INPUT_TEXT
                "SCROLL_DOWN" -> ActionType.SCROLL_DOWN
                "SCROLL_UP" -> ActionType.SCROLL_UP
                "FINISH" -> ActionType.FINISH
                else -> {
                    Log.w(TAG, "Unknown action: $actionStr, defaulting to CLICK")
                    ActionType.CLICK
                }
            }

            ActionIntent(
                type = actionType,
                targetId = if (elementIndex >= 0) "slm_element_$elementIndex" else null,
                targetPoint = null, // Will be resolved by the engine using element_index
                inputText = if (textToType != "null" && !textToType.isNullOrBlank()) textToType else null,
                description = "GGUF: $actionStr on element[$elementIndex]"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GGUF response", e)
            null
        }
    }

    override fun close() {
        try {
            llamaModel?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing llamaModel", e)
        }
        llamaModel = null
        Log.d(TAG, "llama.cpp model closed")
    }
}
