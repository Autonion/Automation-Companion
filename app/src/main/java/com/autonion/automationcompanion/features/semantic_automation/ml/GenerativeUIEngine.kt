package com.autonion.automationcompanion.features.semantic_automation.ml

import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Interface for a Generative AI UI Engine.
 * Implementations (like OnDeviceSLMEngine and LocalServerLLMEngine)
 * parse the UI state as text and return an ActionIntent.
 */
interface GenerativeUIEngine {
    
    /**
     * Initializes or warms up the engine.
     * Often throws an exception if the model file is missing or server is offline.
     */
    suspend fun initialize()

    /**
     * Analyzes the text-representation of the UI and returns an action.
     * @param prompt Textual prompt containing System instructions, User Goal, and parsed UI elements.
     * @return Generated ActionIntent or null if parsing failed.
     */
    suspend fun predictNextAction(prompt: String): ActionIntent?

    /**
     * Chat-style prediction with separate system and user prompts.
     * Used by engines that support role-based messaging (e.g. Ollama /api/chat).
     * Default implementation concatenates both into a single prompt for backward compatibility.
     */
    suspend fun predictNextAction(systemPrompt: String, userPrompt: String): ActionIntent? {
        return predictNextAction("$systemPrompt\n\n$userPrompt")
    }

    /**
     * Generates a plain-text chat response (for Omni-Chat Q&A).
     * Default implementation returns null (engine doesn't support chat).
     */
    suspend fun generateChatResponse(prompt: String): String? = null

    /**
     * Generates a plain-text chat response as a streaming token flow.
     * Each emitted string is a new token/chunk to append to the response.
     * Default implementation falls back to the non-streaming version.
     */
    fun generateChatResponseStream(prompt: String): Flow<String> = flow {
        val response = generateChatResponse(prompt)
        if (response != null) emit(response)
    }

    /**
     * Frees any resources held by the engine (e.g. MediaPipe LlmInference instance)
     */
    fun close()
}
