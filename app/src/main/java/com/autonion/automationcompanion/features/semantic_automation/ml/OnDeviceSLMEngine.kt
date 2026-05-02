package com.autonion.automationcompanion.features.semantic_automation.ml

import android.content.Context
import android.graphics.PointF
import android.util.Log
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionIntent
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * On-Device SLM Engine using MediaPipe's LlmInference API to run Gemma 2B locally.
 * The .bin model file must be imported by the user via the ModelManagerScreen.
 */
class OnDeviceSLMEngine(
    private val context: Context,
    private val storageManager: ModelStorageManager
) : GenerativeUIEngine {

    private val TAG = "OnDeviceSLM"
    private var llmInference: LlmInference? = null

    override suspend fun initialize() {
        withContext(Dispatchers.IO) {
            val modelPath = storageManager.getActiveModelPath()
                ?: throw IllegalStateException("No SLM model imported. Please import a .bin model via the SLM Hub.")

            Log.d(TAG, "Initializing LlmInference with model: $modelPath")

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            Log.d(TAG, "LlmInference initialized successfully")
        }
    }

    override suspend fun predictNextAction(prompt: String): ActionIntent? = withContext(Dispatchers.IO) {
        val inference = llmInference
            ?: throw IllegalStateException("LlmInference not initialized. Call initialize() first.")

        Log.d(TAG, "Generating response for prompt (${prompt.length} chars)")
        val startTime = System.currentTimeMillis()

        val rawResponse = inference.generateResponse(prompt)

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "SLM response in ${elapsed}ms: $rawResponse")

        parseJsonResponse(rawResponse)
    }

    /**
     * Parses the raw LLM text output into a structured ActionIntent.
     * Expected format: {"action": "CLICK", "element_index": 1, "text_to_type": null}
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
                description = "SLM: $actionStr on element[$elementIndex]"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SLM response", e)
            null
        }
    }

    override fun close() {
        llmInference?.close()
        llmInference = null
        Log.d(TAG, "LlmInference closed")
    }
}
