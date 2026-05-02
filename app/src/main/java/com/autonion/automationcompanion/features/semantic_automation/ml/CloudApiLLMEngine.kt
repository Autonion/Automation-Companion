package com.autonion.automationcompanion.features.semantic_automation.ml

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionIntent
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import com.autonion.automationcompanion.features.semantic_automation.security.SecurePrefsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Known cloud API providers with pre-configured endpoints.
 * All use the OpenAI-compatible /v1/chat/completions format.
 */
data class CloudApiProvider(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val suggestedModels: List<String>,
    val description: String
)

val CLOUD_API_PROVIDERS = listOf(
    CloudApiProvider(
        id = "openai",
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1/chat/completions",
        defaultModel = "gpt-4o-mini",
        suggestedModels = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1-nano", "o4-mini"),
        description = "Most popular. GPT-4o-mini is fast & cheap."
    ),
    CloudApiProvider(
        id = "gemini",
        displayName = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        defaultModel = "gemini-2.0-flash",
        suggestedModels = listOf("gemini-2.0-flash", "gemini-2.5-flash-preview-04-17", "gemini-2.5-pro-preview-03-25"),
        description = "Google's Gemini via OpenAI-compatible endpoint."
    ),
    CloudApiProvider(
        id = "groq",
        displayName = "Groq",
        baseUrl = "https://api.groq.com/openai/v1/chat/completions",
        defaultModel = "llama-3.3-70b-versatile",
        suggestedModels = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "gemma2-9b-it", "mixtral-8x7b-32768"),
        description = "Ultra-fast inference. Free tier available."
    ),
    CloudApiProvider(
        id = "deepseek",
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com/chat/completions",
        defaultModel = "deepseek-chat",
        suggestedModels = listOf("deepseek-chat", "deepseek-reasoner"),
        description = "High-quality reasoning at very low cost."
    ),
    CloudApiProvider(
        id = "mistral",
        displayName = "Mistral AI",
        baseUrl = "https://api.mistral.ai/v1/chat/completions",
        defaultModel = "mistral-small-latest",
        suggestedModels = listOf("mistral-small-latest", "mistral-medium-latest", "mistral-large-latest"),
        description = "European AI lab. Strong multilingual support."
    ),
    CloudApiProvider(
        id = "together",
        displayName = "Together AI",
        baseUrl = "https://api.together.xyz/v1/chat/completions",
        defaultModel = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
        suggestedModels = listOf("meta-llama/Llama-3.3-70B-Instruct-Turbo", "Qwen/Qwen2.5-72B-Instruct-Turbo", "google/gemma-2-27b-it"),
        description = "Run open-source models in the cloud."
    ),
    CloudApiProvider(
        id = "openrouter",
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1/chat/completions",
        defaultModel = "google/gemini-2.0-flash-exp:free",
        suggestedModels = listOf("google/gemini-2.0-flash-exp:free", "meta-llama/llama-3.3-70b-instruct:free", "openai/gpt-4o-mini"),
        description = "Aggregator — access any model with one key."
    ),
    CloudApiProvider(
        id = "ollama",
        displayName = "Ollama Cloud",
        baseUrl = "https://ollama.com/v1/chat/completions",
        defaultModel = "",
        suggestedModels = emptyList(),
        description = "Ollama Cloud — your personal cloud LLM. Models fetched automatically."
    ),
    CloudApiProvider(
        id = "custom",
        displayName = "Custom Endpoint",
        baseUrl = "",
        defaultModel = "",
        suggestedModels = emptyList(),
        description = "Any OpenAI-compatible API endpoint."
    )
)

/**
 * Connection status for the Cloud API.
 */
enum class CloudApiConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

// ─── Engine ──────────────────────────────────────────────────

/**
 * Cloud API LLM Engine — OpenAI-compatible REST API client.
 *
 * Supports any provider that implements the OpenAI /v1/chat/completions format:
 * OpenAI, Google Gemini, Groq, DeepSeek, Mistral, Together AI, OpenRouter,
 * and any custom OpenAI-compatible endpoint.
 *
 * API keys are stored using EncryptedSharedPreferences (AES-256-GCM).
 */
class CloudApiLLMEngine private constructor(
    private val context: Context
) : GenerativeUIEngine {

    companion object {
        private const val TAG = "CloudApiLLM"

        @Volatile
        private var instance: CloudApiLLMEngine? = null

        fun getInstance(context: Context): CloudApiLLMEngine {
            return instance ?: synchronized(this) {
                instance ?: CloudApiLLMEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ── State ────────────────────────────────────────────────

    private val _connectionStatus = MutableStateFlow(CloudApiConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<CloudApiConnectionStatus> = _connectionStatus.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _selectedProvider = MutableStateFlow(
        CLOUD_API_PROVIDERS.firstOrNull { it.id == SecurePrefsHelper.getProviderId(context) }
            ?: CLOUD_API_PROVIDERS.last()
    )
    val selectedProvider: StateFlow<CloudApiProvider> = _selectedProvider.asStateFlow()

    // ── Configuration ────────────────────────────────────────

    val apiKey: String get() = SecurePrefsHelper.getApiKey(context)
    val baseUrl: String get() = SecurePrefsHelper.getBaseUrl(context)
    val modelName: String get() = SecurePrefsHelper.getModelName(context)

    val isConfigured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && modelName.isNotBlank()

    fun setProvider(provider: CloudApiProvider) {
        _selectedProvider.value = provider
        SecurePrefsHelper.saveProviderId(context, provider.id)
        if (provider.baseUrl.isNotBlank()) {
            SecurePrefsHelper.saveBaseUrl(context, provider.baseUrl)
        }
        if (provider.defaultModel.isNotBlank()) {
            SecurePrefsHelper.saveModelName(context, provider.defaultModel)
        }
        _connectionStatus.value = CloudApiConnectionStatus.DISCONNECTED
    }

    fun setApiKey(key: String) {
        SecurePrefsHelper.saveApiKey(context, key)
        _connectionStatus.value = CloudApiConnectionStatus.DISCONNECTED
    }

    fun setBaseUrl(url: String) {
        SecurePrefsHelper.saveBaseUrl(context, url)
        _connectionStatus.value = CloudApiConnectionStatus.DISCONNECTED
    }

    fun setModelName(model: String) {
        SecurePrefsHelper.saveModelName(context, model)
    }

    // ── Connection Test ──────────────────────────────────────

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            _connectionStatus.value = CloudApiConnectionStatus.DISCONNECTED
            _errorMessage.value = "API key, URL, or model not configured"
            return@withContext
        }

        _connectionStatus.value = CloudApiConnectionStatus.CONNECTING
        _errorMessage.value = null

        try {
            // Send a minimal test request
            val testBody = JSONObject().apply {
                put("model", modelName)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Hi")
                    })
                })
                put("max_tokens", 5)
                put("temperature", 0.0)
            }

            val request = Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(testBody.toString().toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                _connectionStatus.value = CloudApiConnectionStatus.CONNECTED
                _errorMessage.value = null
                Log.d(TAG, "Connection test successful for ${_selectedProvider.value.displayName}")
            } else {
                _connectionStatus.value = CloudApiConnectionStatus.ERROR
                val errMsg = try {
                    val errJson = JSONObject(responseBody)
                    errJson.optJSONObject("error")?.optString("message")
                        ?: "HTTP ${response.code}"
                } catch (_: Exception) {
                    "HTTP ${response.code}: ${responseBody.take(200)}"
                }
                _errorMessage.value = errMsg
                Log.e(TAG, "Connection test failed: $errMsg")
            }
        } catch (e: Exception) {
            _connectionStatus.value = CloudApiConnectionStatus.ERROR
            _errorMessage.value = "Connection failed: ${e.message}"
            Log.e(TAG, "Connection test error", e)
        }
    }

    /**
     * Fetches available models dynamically using the standard OpenAI /v1/models endpoint.
     */
    suspend fun getAvailableModels(
        currentApiKey: String = this.apiKey,
        currentBaseUrl: String = this.baseUrl
    ): List<String>? = withContext(Dispatchers.IO) {
        if (currentBaseUrl.isBlank()) return@withContext null

        try {
            val modelsUrl = currentBaseUrl.replace("/chat/completions", "/models")
            val requestBuilder = Request.Builder().url(modelsUrl).get()
            if (currentApiKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $currentApiKey")
            }
            val request = requestBuilder.build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val data = json.optJSONArray("data") ?: return@withContext emptyList()
                val models = mutableListOf<String>()
                for (i in 0 until data.length()) {
                    val modelObj = data.optJSONObject(i)
                    val id = modelObj?.optString("id")
                    if (!id.isNullOrBlank()) {
                        models.add(id)
                    }
                }
                Log.d(TAG, "Fetched ${models.size} models dynamically")
                return@withContext models
            } else {
                Log.e(TAG, "Failed to fetch models: HTTP ${response.code} $responseBody")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching models", e)
            return@withContext null
        }
    }

    // ── Action Prediction (OpenAI format) ────────────────────

    override suspend fun predictNextAction(prompt: String): ActionIntent? {
        return predictNextAction(systemPrompt = "", userPrompt = prompt)
    }

    override suspend fun predictNextAction(
        systemPrompt: String,
        userPrompt: String
    ): ActionIntent? = withContext(Dispatchers.IO) {
        val content = sendChatRequest(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            jsonMode = true,
            maxTokens = 512,
            temperature = 0.1
        ) ?: return@withContext null

        parseStructuredResponse(content)
    }

    // ── Chat Methods (mirroring LocalServerLLMEngine) ────────

    /**
     * Chat with structured JSON schema.
     * OpenAI-compatible providers support response_format for JSON mode.
     */
    suspend fun chatWithSchema(
        systemPrompt: String,
        userPrompt: String,
        jsonSchema: Map<String, Any>
    ): String? = withContext(Dispatchers.IO) {
        sendChatRequest(
            systemPrompt = "$systemPrompt\n\nYou MUST respond with ONLY valid JSON matching the required schema. No markdown, no extra text.",
            userPrompt = userPrompt,
            jsonMode = true,
            maxTokens = 512,
            temperature = 0.1
        )
    }

    /**
     * Simple JSON chat — returns raw JSON string.
     */
    suspend fun chatSimpleJson(
        systemPrompt: String,
        userPrompt: String
    ): String? = withContext(Dispatchers.IO) {
        sendChatRequest(
            systemPrompt = "$systemPrompt\n\nReturn only valid JSON. Do not return markdown or comments outside the JSON object.",
            userPrompt = userPrompt,
            jsonMode = true,
            maxTokens = 512,
            temperature = 0.1
        )
    }

    /**
     * Q&A chat — returns plain text answer. Used by Omni-Chat.
     */
    suspend fun chatForQA(
        systemPrompt: String,
        userPrompt: String
    ): String? = withContext(Dispatchers.IO) {
        sendChatRequest(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            jsonMode = false,
            maxTokens = 1024,
            temperature = 0.3
        )
    }

    /**
     * Multi-turn Q&A chat — accepts a pre-assembled list of messages.
     * Used by Omni-Chat to preserve conversation history context.
     *
     * Each entry in [messages] should be a Map with "role" and "content" keys.
     */
    suspend fun chatForQAWithHistory(
        messages: List<Map<String, String>>
    ): String? = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            Log.w(TAG, "Cloud API not configured")
            return@withContext null
        }

        try {
            val startTime = System.currentTimeMillis()

            val messagesArray = JSONArray()
            messages.forEach { msg ->
                messagesArray.put(JSONObject().apply {
                    put("role", msg["role"] ?: "user")
                    put("content", msg["content"] ?: "")
                })
            }

            val body = JSONObject().apply {
                put("model", modelName)
                put("messages", messagesArray)
                put("max_tokens", 1024)
                put("temperature", 0.3)
            }

            val request = Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val elapsed = System.currentTimeMillis() - startTime

            if (!response.isSuccessful) {
                val errMsg = try {
                    JSONObject(responseBody).optJSONObject("error")?.optString("message")
                        ?: "HTTP ${response.code}"
                } catch (_: Exception) { "HTTP ${response.code}" }
                Log.e(TAG, "Chat API error in ${elapsed}ms: $errMsg")

                if (response.code == 401 || response.code == 403) {
                    _connectionStatus.value = CloudApiConnectionStatus.ERROR
                    _errorMessage.value = "Authentication failed — check your API key"
                }
                return@withContext null
            }

            val json = JSONObject(responseBody)
            val content = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim() ?: ""

            val usage = json.optJSONObject("usage")
            Log.d(TAG, "Chat response in ${elapsed}ms (prompt=${usage?.optInt("prompt_tokens", 0)}, completion=${usage?.optInt("completion_tokens", 0)})")

            if (content.isBlank()) null else content
        } catch (e: Exception) {
            Log.e(TAG, "Chat with history failed: ${e.javaClass.simpleName}", e)
            null
        }
    }

    override fun close() {
        _connectionStatus.value = CloudApiConnectionStatus.DISCONNECTED
        _errorMessage.value = null
        Log.d(TAG, "CloudApiLLMEngine closed")
    }

    // ── Core HTTP ────────────────────────────────────────────

    private suspend fun sendChatRequest(
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean,
        maxTokens: Int,
        temperature: Double
    ): String? = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            Log.w(TAG, "Cloud API not configured")
            return@withContext null
        }

        try {
            val startTime = System.currentTimeMillis()

            val messagesArray = JSONArray()
            if (systemPrompt.isNotBlank()) {
                messagesArray.put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            messagesArray.put(JSONObject().apply {
                put("role", "user")
                put("content", userPrompt)
            })

            val body = JSONObject().apply {
                put("model", modelName)
                put("messages", messagesArray)
                put("max_tokens", maxTokens)
                put("temperature", temperature)
                if (jsonMode) {
                    put("response_format", JSONObject().apply {
                        put("type", "json_object")
                    })
                }
            }

            val request = Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val elapsed = System.currentTimeMillis() - startTime

            if (!response.isSuccessful) {
                val errMsg = try {
                    JSONObject(responseBody).optJSONObject("error")?.optString("message")
                        ?: "HTTP ${response.code}"
                } catch (_: Exception) { "HTTP ${response.code}" }
                Log.e(TAG, "API error in ${elapsed}ms: $errMsg")

                // Auto-disconnect on auth errors
                if (response.code == 401 || response.code == 403) {
                    _connectionStatus.value = CloudApiConnectionStatus.ERROR
                    _errorMessage.value = "Authentication failed — check your API key"
                }
                return@withContext null
            }

            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            val content = choices
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim() ?: ""

            val usage = json.optJSONObject("usage")
            val promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0
            val completionTokens = usage?.optInt("completion_tokens", 0) ?: 0

            Log.d(TAG, "Cloud API response in ${elapsed}ms (prompt=$promptTokens, completion=$completionTokens)")

            if (content.isBlank()) null else content
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "Cloud API unreachable", e)
            _connectionStatus.value = CloudApiConnectionStatus.ERROR
            _errorMessage.value = "Cannot reach API server"
            null
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Cloud API timeout", e)
            _errorMessage.value = "Request timed out"
            null
        } catch (e: Exception) {
            Log.e(TAG, "Cloud API request failed: ${e.javaClass.simpleName}", e)
            null
        }
    }

    // ── Response Parsing ─────────────────────────────────────

    private fun parseStructuredResponse(raw: String): ActionIntent? {
        return try {
            var jsonString = raw.trim()

            // Extract JSON object if wrapped in markdown or extra text
            val objStart = jsonString.indexOf('{')
            val objEnd = jsonString.lastIndexOf('}')

            if (objStart == -1 || objEnd <= objStart) {
                Log.w(TAG, "No JSON object in response: ${jsonString.take(200)}")
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
                    return null
                }
            }

            if (actionType in listOf(ActionType.CLICK, ActionType.INPUT_TEXT) && elementIndex < 0) {
                Log.w(TAG, "Invalid element_index ($elementIndex) for $actionStr")
                return null
            }

            ActionIntent(
                type = actionType,
                targetId = if (elementIndex >= 0) "slm_element_$elementIndex" else null,
                targetPoint = null,
                inputText = if (!textToType.isNullOrBlank() && textToType != "null") textToType else null,
                description = "CloudAPI: $actionStr on element[$elementIndex]"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response: ${raw.take(300)}", e)
            null
        }
    }
}
