package com.autonion.automationcompanion.features.semantic_automation.ml

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionIntent
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Connection status for the Local Server LLM.
 */
enum class ServerConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

// ─── Retrofit API ────────────────────────────────────────────

private interface OllamaApi {

    /** Lists all locally available models. */
    @GET("api/tags")
    suspend fun listModels(): OllamaTagsResponse

    /** Generates a completion (non-streaming). */
    @POST("api/generate")
    suspend fun generate(@Body request: OllamaGenerateRequest): OllamaGenerateResponse
}

data class OllamaTagsResponse(
    val models: List<OllamaModel> = emptyList()
)

data class OllamaModel(
    val name: String = "",
    val size: Long = 0,
    val digest: String = "",
    val modified_at: String = ""
)

data class OllamaGenerateRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean = false
)

data class OllamaGenerateResponse(
    val model: String = "",
    val response: String = "",
    val done: Boolean = false,
    val total_duration: Long = 0
)

// ─── Engine ──────────────────────────────────────────────────

class LocalServerLLMEngine private constructor(
    private val context: Context
) : GenerativeUIEngine {

    companion object {
        private const val TAG = "LocalServerLLM"
        private const val PREFS_NAME = "local_server_llm_prefs"
        private const val PREF_SERVER_URL = "server_url"
        private const val PREF_SELECTED_MODEL = "selected_model"
        private const val DEFAULT_PORT = 11434

        @Volatile
        private var instance: LocalServerLLMEngine? = null

        fun getInstance(context: Context): LocalServerLLMEngine {
            return instance ?: synchronized(this) {
                instance ?: LocalServerLLMEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var api: OllamaApi? = null
    private var selectedModel: String? = prefs.getString(PREF_SELECTED_MODEL, null)

    private val _connectionStatus = MutableStateFlow(ServerConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ServerConnectionStatus> = _connectionStatus.asStateFlow()

    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    private val _serverUrl = MutableStateFlow(prefs.getString(PREF_SERVER_URL, null) ?: "")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _selectedModelName = MutableStateFlow(selectedModel ?: "")
    val selectedModelName: StateFlow<String> = _selectedModelName.asStateFlow()

    // ── Public API ───────────────────────────────────────────

    /**
     * Sets the server URL and persists it. Call [initialize] after this to connect.
     */
    fun setServerUrl(url: String) {
        val cleanUrl = url.trimEnd('/')
        prefs.edit().putString(PREF_SERVER_URL, cleanUrl).apply()
        _serverUrl.value = cleanUrl
        api = null // Force re-creation on next initialize
    }

    /**
     * Selects which Ollama model to use for generation.
     */
    fun setModel(modelName: String) {
        selectedModel = modelName
        _selectedModelName.value = modelName
        prefs.edit().putString(PREF_SELECTED_MODEL, modelName).apply()
        Log.d(TAG, "Selected model: $modelName")
    }

    /**
     * Attempts to connect to the Ollama server and fetch available models.
     */
    override suspend fun initialize() = withContext(Dispatchers.IO) {
        var url = _serverUrl.value.trim()
        if (url.isBlank()) {
            _connectionStatus.value = ServerConnectionStatus.DISCONNECTED
            Log.w(TAG, "No server URL configured")
            return@withContext
        }
        if (!url.endsWith("/")) {
            url += "/"
        }

        _connectionStatus.value = ServerConnectionStatus.CONNECTING
        Log.d(TAG, "Connecting to Ollama at $url")

        try {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(120, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()

            api = Retrofit.Builder()
                .baseUrl(url)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(OllamaApi::class.java)

            val tagsResponse = api!!.listModels()
            val modelNames = tagsResponse.models.map { it.name }
            _availableModels.value = modelNames

            _connectionStatus.value = ServerConnectionStatus.CONNECTED
            Log.d(TAG, "Connected! Available models: $modelNames")

            // Auto-select first model if none was previously selected
            if (selectedModel.isNullOrBlank() && modelNames.isNotEmpty()) {
                setModel(modelNames.first())
            }
        } catch (e: Exception) {
            _connectionStatus.value = ServerConnectionStatus.DISCONNECTED
            _availableModels.value = emptyList()
            api = null
            Log.e(TAG, "Failed to connect to Ollama server at $url", e)
        }
    }

    /**
     * Refreshes the model list without full re-initialization.
     */
    suspend fun refreshModels() = withContext(Dispatchers.IO) {
        try {
            val tagsResponse = api?.listModels() ?: return@withContext
            _availableModels.value = tagsResponse.models.map { it.name }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh model list", e)
        }
    }

    /**
     * Sends the prompt to the Ollama server and parses the JSON action response.
     */
    override suspend fun predictNextAction(prompt: String): ActionIntent? = withContext(Dispatchers.IO) {
        val currentApi = api
        val model = selectedModel

        if (currentApi == null || model.isNullOrBlank()) {
            Log.w(TAG, "Server not connected or no model selected")
            return@withContext null
        }

        try {
            val startTime = System.currentTimeMillis()

            val response = currentApi.generate(
                OllamaGenerateRequest(
                    model = model,
                    prompt = prompt,
                    stream = false
                )
            )

            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "Server response in ${elapsed}ms: ${response.response.take(500)}")

            parseJsonResponse(response.response)
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e(TAG, "Server inference failed with HTTP ${e.code()}: $errorBody", e)
            _connectionStatus.value = ServerConnectionStatus.DISCONNECTED
            null
        } catch (e: Exception) {
            Log.e(TAG, "Server inference failed", e)
            // Mark as disconnected so the cascade knows to skip next time
            _connectionStatus.value = ServerConnectionStatus.DISCONNECTED
            null
        }
    }

    override fun close() {
        api = null
        _connectionStatus.value = ServerConnectionStatus.DISCONNECTED
        _availableModels.value = emptyList()
        Log.d(TAG, "LocalServerLLMEngine closed")
    }

    // ── JSON Parsing (shared logic with OnDeviceSLMEngine) ───

    private fun parseJsonResponse(raw: String): ActionIntent? {
        return try {
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

            var jsonToParse: JSONObject? = null

            fun tryParseObj(text: String): JSONObject? {
                try {
                    val obj = JSONObject(text)
                    if (obj.has("action")) return obj
                } catch (e: Exception) {}
                try {
                    val obj = JSONObject("$text}")
                    if (obj.has("action")) return obj
                } catch (e: Exception) {}
                return null
            }

            fun tryParseArr(text: String): JSONObject? {
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
                Log.w(TAG, "No valid JSON action found in server response")
                return null
            }

            val actionStr = jsonToParse.optString("action", "CLICK").uppercase().trim()
            val elementIndex = jsonToParse.optInt("element_index", -1)
            val textToType = jsonToParse.optString("text_to_type", null)

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
                targetPoint = null,
                inputText = if (textToType != "null" && !textToType.isNullOrBlank()) textToType else null,
                description = "ServerLLM: $actionStr on element[$elementIndex]"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse server response", e)
            null
        }
    }
}
