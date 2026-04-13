package com.autonion.automationcompanion.features.omni_chatbot.knowledge

/**
 * Builds context-augmented prompts for the LLM using
 * retrieved knowledge chunks from the [KnowledgeStore].
 *
 * The prompt format follows the standard RAG pattern:
 * - System instruction with role and constraints
 * - Retrieved context chunks
 * - User question
 */
class RAGPromptBuilder {

    companion object {
        private const val SYSTEM_INSTRUCTION = """You are Autonion's helpful AI assistant.
Answer the user's question based ONLY on the provided context.
If the context doesn't contain enough information, say so honestly.
Be concise and direct. Use bullet points where appropriate.
Do not make up information that isn't in the context."""

        private const val MAX_CONTEXT_CHARS = 3000
    }

    /**
     * Build a RAG prompt with retrieved context chunks.
     *
     * @param question The user's question
     * @param chunks Retrieved knowledge chunks (sorted by relevance)
     * @return Formatted prompt for the LLM
     */
    fun buildPrompt(
        question: String,
        chunks: List<KnowledgeStore.KnowledgeChunk>
    ): String {
        val contextBuilder = StringBuilder()
        var charCount = 0

        for (chunk in chunks) {
            if (charCount + chunk.text.length > MAX_CONTEXT_CHARS) break
            contextBuilder.append("--- From: ${chunk.source} ---\n")
            contextBuilder.append(chunk.text)
            contextBuilder.append("\n\n")
            charCount += chunk.text.length
        }

        return buildString {
            append("$SYSTEM_INSTRUCTION\n\n")
            append("=== CONTEXT ===\n")
            append(contextBuilder)
            append("=== END CONTEXT ===\n\n")
            append("Question: $question\n\n")
            append("Answer:")
        }
    }

    /**
     * Build a simple prompt WITHOUT RAG context (fallback).
     */
    fun buildSimplePrompt(question: String): String {
        return buildString {
            append("You are Autonion's helpful AI assistant. ")
            append("Autonion is an Android automation app that supports voice/text commands, ")
            append("cross-device control, gesture recording, and AI-powered task automation.\n\n")
            append("Question: $question\n\n")
            append("Answer concisely:")
        }
    }
}
