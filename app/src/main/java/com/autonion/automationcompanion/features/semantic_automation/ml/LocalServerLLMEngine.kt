package com.autonion.automationcompanion.features.semantic_automation.ml

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionIntent
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    /** Chat-style completion with structured output. */
    @POST("api/chat")
    suspend fun chat(@Body request: OllamaChatRequest): OllamaChatResponse
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

/**
 * Chat API request body.
 * Uses `format` for Ollama's structured output (JSON schema enforcement)
 * and `options` for temperature control.
 */
data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaChatMessage>,
    val stream: Boolean = false,
    val format: Any? = null,
    val options: Map<String, Any>? = null,
    val think: Boolean? = null  // Qwen3: set to false to disable <think> reasoning
)

data class OllamaChatMessage(
    val role: String,    // "system", "user", or "assistant"
    val content: String
)

data class OllamaChatResponse(
    val model: String = "",
    val message: OllamaChatMessage = OllamaChatMessage("assistant", ""),
    val done: Boolean = false,
    val total_duration: Long = 0,
    val eval_count: Int = 0
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
     * Auto-reconnect using saved URL.
     * If no URL is saved, attempts auto-discovery from cross-device connected devices.
     */
    fun autoConnectIfNeeded() {
        if (_connectionStatus.value != ServerConnectionStatus.DISCONNECTED) return

        if (_serverUrl.value.isNotBlank()) {
            // Case 1: Saved URL exists — just reconnect
            Log.d(TAG, "Auto-reconnecting to saved LLM server: ${_serverUrl.value}")
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                initialize()
            }
        } else {
            // Case 2: No saved URL — try auto-discovery from cross-device connected devices
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                try {
                    val crossDeviceManager = com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager.getInstance(context)
                    val devices = crossDeviceManager.deviceRepository.getAllDevices().first()
                    val onlineDevice = devices.firstOrNull {
                        it.status == com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceStatus.ONLINE
                    }
                    onlineDevice?.let { device ->
                        val url = "http://${device.ipAddress}:11434"
                        Log.d(TAG, "Auto-discovered desktop LLM server from cross-device: $url")
                        setServerUrl(url)
                        initialize()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Cross-device auto-discovery skipped: ${e.message}")
                }
            }
        }
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
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(300, TimeUnit.SECONDS)
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
     * Legacy single-prompt prediction (backward compat).
     * Wraps the prompt in a user message with structured output.
     */
    override suspend fun predictNextAction(prompt: String): ActionIntent? {
        return predictNextAction(
            systemPrompt = "",
            userPrompt = prompt
        )
    }

    /**
     * Chat-style prediction with structured JSON output.
     *
     * Uses Ollama's /api/chat endpoint with:
     *  - Role-based messages (system + user)
     *  - `format` parameter for JSON schema enforcement (structured output)
     *  - `temperature: 0.1` for deterministic behavior
     *
     * The model is physically constrained to output valid JSON matching our schema.
     * No more parsing failures or reasoning text mixed into the response.
     */
    override suspend fun predictNextAction(
        systemPrompt: String,
        userPrompt: String
    ): ActionIntent? = withContext(Dispatchers.IO) {
        val currentApi = api
        val model = selectedModel

        if (currentApi == null || model.isNullOrBlank()) {
            Log.w(TAG, "Server not connected or no model selected")
            return@withContext null
        }

        try {
            val startTime = System.currentTimeMillis()

            // Build messages
            val messages = mutableListOf<OllamaChatMessage>()
            if (systemPrompt.isNotBlank()) {
                messages.add(OllamaChatMessage(role = "system", content = systemPrompt))
            }
            messages.add(OllamaChatMessage(role = "user", content = userPrompt))

            // Attempt 1: With structured JSON output (format schema)
            // num_ctx: 2048 keeps VRAM usage low for 6GB GPUs (default 4096 causes CUDA OOM)
            val inferenceOptions = mapOf(
                "temperature" to 0.1,
                "num_ctx" to 2048
            )

            var response = currentApi.chat(
                OllamaChatRequest(
                    model = model,
                    messages = messages,
                    stream = false,
                    format = UIPromptFormatter.getOutputJsonSchema(),
                    options = inferenceOptions
                )
            )

            var elapsed = System.currentTimeMillis() - startTime
            var content = response.message.content.trim()
            Log.d(TAG, "Server response in ${elapsed}ms (${response.eval_count} tokens): '$content'")

            // Fallback: If structured output returned empty, retry without format constraint
            if (content.isBlank()) {
                Log.w(TAG, "Structured output was empty, retrying without format constraint")
                val retryStart = System.currentTimeMillis()
                response = currentApi.chat(
                    OllamaChatRequest(
                        model = model,
                        messages = messages,
                        stream = false,
                        format = "json", // Basic JSON output mode
                        options = inferenceOptions
                    )
                )
                elapsed = System.currentTimeMillis() - retryStart
                content = response.message.content.trim()
                Log.d(TAG, "Fallback response in ${elapsed}ms: '$content'")
            }

            parseStructuredResponse(content)
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e(TAG, "Server inference failed with HTTP ${e.code()}: $errorBody", e)
            // Only disconnect on 404 (model not found) or 5xx (server crash)
            // Don't disconnect on transient errors — server is still alive
            if (e.code() == 404 || e.code() >= 500) {
                _connectionStatus.value = ServerConnectionStatus.DISCONNECTED
            }
            null
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "Server unreachable", e)
            _connectionStatus.value = ServerConnectionStatus.DISCONNECTED
            null
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "Server host not found", e)
            _connectionStatus.value = ServerConnectionStatus.DISCONNECTED
            null
        } catch (e: Exception) {
            // Timeouts, parse errors, etc. — server is likely still alive
            Log.e(TAG, "Server inference failed (transient): ${e.javaClass.simpleName}", e)
            null
        }
    }

    /**
     * Generic chat method that returns raw JSON string for any schema.
     * Allows callers (like GoalParser) to use their own output schemas
     * without coupling to ActionIntent parsing.
     *
     * @param systemPrompt Role-based system instructions
     * @param userPrompt The user's input
     * @param jsonSchema Ollama structured output schema to constrain the response
     * @return Raw JSON string from the model, or null on failure
     */
    suspend fun chatWithSchema(
        systemPrompt: String,
        userPrompt: String,
        jsonSchema: Map<String, Any>
    ): String? = withContext(Dispatchers.IO) {
        val currentApi = api
        val model = selectedModel

        if (currentApi == null || model.isNullOrBlank()) {
            Log.w(TAG, "chatWithSchema: Server not connected or no model selected")
            return@withContext null
        }

        try {
            val messages = mutableListOf<OllamaChatMessage>()
            if (systemPrompt.isNotBlank()) {
                messages.add(OllamaChatMessage(role = "system", content = systemPrompt))
            }
            messages.add(OllamaChatMessage(role = "user", content = userPrompt))

            var response = currentApi.chat(
                OllamaChatRequest(
                    model = model,
                    messages = messages,
                    stream = false,
                    format = jsonSchema,
                    options = mapOf("temperature" to 0.1, "num_ctx" to 2048)
                )
            )

            var content = response.message.content.trim()
            Log.d(TAG, "chatWithSchema response (${response.eval_count} tokens): $content")

            // Fallback: If structured output returned empty, retry with simpler "json" constraint
            if (content.isBlank()) {
                Log.w(TAG, "chatWithSchema: Structured output was empty, retrying with 'json' constraint")
                val fallbackMessages = messages.toMutableList()
                fallbackMessages.add(0, OllamaChatMessage(role = "system", content = "Return only valid JSON. Do not return markdown or comments outside the JSON object."))
                
                response = currentApi.chat(
                    OllamaChatRequest(
                        model = model,
                        messages = fallbackMessages,
                        stream = false,
                        format = "json",
                        options = mapOf("temperature" to 0.1, "num_ctx" to 2048)
                    )
                )
                content = response.message.content.trim()
                Log.d(TAG, "chatWithSchema Fallback response (${response.eval_count} tokens): $content")
            }

            if (content.isBlank()) null else content
        } catch (e: Exception) {
            Log.e(TAG, "chatWithSchema failed: ${e.javaClass.simpleName}", e)
            if (e is java.net.ConnectException || e is java.net.UnknownHostException) {
                _connectionStatus.value = ServerConnectionStatus.DISCONNECTED
            }
            null
        }
    }

    /**
     * Simple JSON chat that uses Ollama's basic `format: "json"` mode.
     *
     * Unlike [chatWithSchema] which enforces a strict JSON schema (and can cause
     * Ollama's constrained decoding to loop infinitely on null values), this method
     * lets the model output any valid JSON, relying on the system prompt to guide
     * the structure. Ideal for goal parsing where the model may need to omit fields.
     */
    suspend fun chatSimpleJson(
        systemPrompt: String,
        userPrompt: String
    ): String? = withContext(Dispatchers.IO) {
        val currentApi = api
        val model = selectedModel

        if (currentApi == null || model.isNullOrBlank()) {
            Log.w(TAG, "chatSimpleJson: Server not connected or no model selected")
            return@withContext null
        }

        try {
            val messages = mutableListOf<OllamaChatMessage>()
            if (systemPrompt.isNotBlank()) {
                messages.add(OllamaChatMessage(role = "system", content = systemPrompt))
            }
            messages.add(OllamaChatMessage(role = "user", content = userPrompt))

            val response = currentApi.chat(
                OllamaChatRequest(
                    model = model,
                    messages = messages,
                    stream = false,
                    format = "json",
                    options = mapOf("temperature" to 0.1, "num_ctx" to 2048)
                )
            )

            val content = response.message.content.trim()
            Log.d(TAG, "chatSimpleJson response (${response.eval_count} tokens): $content")

            if (content.isBlank()) null else content
        } catch (e: Exception) {
            Log.e(TAG, "chatSimpleJson failed: ${e.javaClass.simpleName}", e)
            if (e is java.net.ConnectException || e is java.net.UnknownHostException) {
                _connectionStatus.value = ServerConnectionStatus.DISCONNECTED
            }
            null
        }
    }

    /**
     * Q&A-optimized chat: plain text answer, larger context window, no JSON schema.
     *
     * Unlike chatWithSchema (designed for action prediction with 2048 tokens),
     * this uses 4096 tokens and returns a free-form text answer — ideal for
     * RAG-based Q&A where the response doesn't need to be structured JSON.
     *
     * @param systemPrompt Role instructions + knowledge context
     * @param userPrompt The user's question
     * @return Plain text answer, or null on failure
     */
    suspend fun chatForQA(
        systemPrompt: String,
        userPrompt: String
    ): String? = withContext(Dispatchers.IO) {
        val currentApi = api
        val model = selectedModel

        if (currentApi == null || model.isNullOrBlank()) {
            Log.w(TAG, "chatForQA: Server not connected or no model selected")
            return@withContext null
        }

        try {
            val messages = listOf(
                OllamaChatMessage(role = "system", content = systemPrompt),
                OllamaChatMessage(role = "user", content = userPrompt)
            )

            val response = currentApi.chat(
                OllamaChatRequest(
                    model = model,
                    messages = messages,
                    stream = false,
                    format = null, // No schema — plain text answer
                    think = false, // Disable Qwen3 thinking mode for Q&A
                    options = mapOf(
                        "temperature" to 0.3,
                        "num_ctx" to 8192,
                        "num_predict" to 1024   // Reserve tokens for answer generation
                    )
                )
            )

            var content = response.message.content.trim()
            // Qwen-style models wrap reasoning in <think>…</think> tags;
            // strip them to get the actual user-facing answer.
            if (content.contains("</think>")) {
                content = content.substringAfter("</think>").trim()
            } else if (content.startsWith("<think>")) {
                // Model is still "thinking" with no answer produced
                content = ""
            }
            Log.d(TAG, "chatForQA response (${response.eval_count} tokens, ${content.length} chars)")

            if (content.isBlank()) null else content
        } catch (e: Exception) {
            Log.e(TAG, "chatForQA failed: ${e.javaClass.simpleName}", e)
            if (e is java.net.ConnectException || e is java.net.UnknownHostException) {
                _connectionStatus.value = ServerConnectionStatus.DISCONNECTED
            }
            null
        }
    }

    override fun close() {
        api = null
        _connectionStatus.value = ServerConnectionStatus.DISCONNECTED
        _availableModels.value = emptyList()
        Log.d(TAG, "LocalServerLLMEngine closed")
    }

    // ── Response Parsing ─────────────────────────────────────

    /**
     * Parses the structured JSON response from Ollama's constrained output.
     *
     * With the `format` parameter, Ollama guarantees the response is valid JSON
     * matching our schema. We still wrap in try-catch for safety, but the heavy
     * markdown-stripping / repair logic from v1 is no longer needed.
     */
    private fun parseStructuredResponse(raw: String): ActionIntent? {
        return try {
            val jsonString = raw.trim()
            
            // With structured output, the response should already be pure JSON.
            // But as a safety net, try to extract JSON object if there's extra text.
            val objStart = jsonString.indexOf('{')
            val objEnd = jsonString.lastIndexOf('}')
            
            if (objStart == -1 || objEnd <= objStart) {
                Log.w(TAG, "No JSON object found in response: ${jsonString.take(200)}")
                return null
            }

            val json = JSONObject(jsonString.substring(objStart, objEnd + 1))

            val actionStr = json.optString("action", "").uppercase().trim()
            val elementIndex = json.optInt("element_index", -1)
            val textToType = json.optString("text_to_type", null)

            if (actionStr.isBlank()) {
                Log.w(TAG, "Empty action in response")
                return null
            }

            val actionType = when (actionStr) {
                "CLICK" -> ActionType.CLICK
                "INPUT_TEXT" -> ActionType.INPUT_TEXT
                "SCROLL_DOWN" -> ActionType.SCROLL_DOWN
                "SCROLL_UP" -> ActionType.SCROLL_UP
                "FINISH" -> ActionType.FINISH
                else -> {
                    Log.w(TAG, "Unknown action: $actionStr")
                    return null // With structured output + enum, this shouldn't happen
                }
            }

            // Validate element_index: CLICK and INPUT_TEXT need a real element
            if (actionType in listOf(ActionType.CLICK, ActionType.INPUT_TEXT) && elementIndex < 0) {
                Log.w(TAG, "Invalid element_index ($elementIndex) for $actionStr")
                return null
            }

            ActionIntent(
                type = actionType,
                targetId = if (elementIndex >= 0) "slm_element_$elementIndex" else null,
                targetPoint = null,
                inputText = if (!textToType.isNullOrBlank() && textToType != "null") textToType else null,
                description = "ServerLLM: $actionStr on element[$elementIndex]"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse structured response: ${raw.take(300)}", e)
            null
        }
    }
}
