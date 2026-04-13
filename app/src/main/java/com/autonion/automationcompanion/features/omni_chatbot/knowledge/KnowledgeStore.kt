package com.autonion.automationcompanion.features.omni_chatbot.knowledge

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.semantic_automation.ml.SentenceEmbedder

/**
 * In-memory vector store for RAG (Retrieval-Augmented Generation).
 *
 * Loads knowledge documents from assets/knowledge/,
 * chunks them into paragraphs, computes MiniLM embeddings,
 * and supports semantic search via cosine similarity.
 */
class KnowledgeStore(private val embedder: SentenceEmbedder) {

    companion object {
        private const val TAG = "KnowledgeStore"
        private const val KNOWLEDGE_DIR = "knowledge"
        private const val CHUNK_SIZE_WORDS = 200
        private const val CHUNK_OVERLAP_WORDS = 30
    }

    data class KnowledgeChunk(
        val text: String,
        val source: String,
        val chunkIndex: Int,
        val embedding: FloatArray
    )

    private val chunks = mutableListOf<KnowledgeChunk>()
    var isLoaded = false
        private set

    /**
     * Load all markdown documents from assets/knowledge/ directory,
     * chunk them, and compute embeddings.
     */
    fun loadFromAssets(context: Context) {
        if (isLoaded) return

        try {
            val start = System.currentTimeMillis()
            val files = context.assets.list(KNOWLEDGE_DIR) ?: emptyArray()

            for (fileName in files) {
                if (!fileName.endsWith(".md")) continue

                val content = context.assets
                    .open("$KNOWLEDGE_DIR/$fileName")
                    .bufferedReader()
                    .readText()

                val docChunks = chunkDocument(content, fileName)
                for ((index, chunkText) in docChunks.withIndex()) {
                    val embedding = embedder.encode(chunkText)
                    chunks.add(KnowledgeChunk(
                        text = chunkText,
                        source = fileName,
                        chunkIndex = index,
                        embedding = embedding
                    ))
                }

                Log.d(TAG, "Loaded $fileName → ${docChunks.size} chunks")
            }

            val elapsed = System.currentTimeMillis() - start
            isLoaded = true
            Log.d(TAG, "Knowledge store loaded: ${chunks.size} chunks from ${files.size} files in ${elapsed}ms")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load knowledge base", e)
        }
    }

    /**
     * Semantic search: find the top-K most relevant chunks for a query.
     */
    fun search(query: String, topK: Int = 5): List<KnowledgeChunk> {
        if (!isLoaded || chunks.isEmpty()) return emptyList()

        val queryEmbedding = embedder.encode(query)

        return chunks
            .map { chunk -> chunk to cosineSimilarity(queryEmbedding, chunk.embedding) }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    /**
     * Split a markdown document into overlapping word-based chunks.
     * Respects paragraph boundaries where possible.
     */
    private fun chunkDocument(content: String, source: String): List<String> {
        // Split by double newlines (paragraphs) first
        val paragraphs = content.split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") } // Skip headers as standalone

        val result = mutableListOf<String>()
        val currentChunk = StringBuilder()
        var wordCount = 0

        for (paragraph in paragraphs) {
            val paraWords = paragraph.split(Regex("\\s+")).size

            if (wordCount + paraWords > CHUNK_SIZE_WORDS && currentChunk.isNotEmpty()) {
                result.add(currentChunk.toString().trim())
                // Keep overlap
                val words = currentChunk.toString().split(Regex("\\s+"))
                currentChunk.clear()
                if (words.size > CHUNK_OVERLAP_WORDS) {
                    currentChunk.append(
                        words.takeLast(CHUNK_OVERLAP_WORDS).joinToString(" ")
                    )
                    currentChunk.append("\n\n")
                    wordCount = CHUNK_OVERLAP_WORDS
                } else {
                    wordCount = 0
                }
            }

            currentChunk.append(paragraph).append("\n\n")
            wordCount += paraWords
        }

        if (currentChunk.isNotBlank()) {
            result.add(currentChunk.toString().trim())
        }

        return result
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())
        return if (denom > 0) (dot / denom.toFloat()) else 0f
    }
}
