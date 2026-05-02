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
    @Volatile
    var isLoaded = false
        private set

    /**
     * Load all markdown documents from assets/knowledge/ directory,
     * chunk them, and compute embeddings.
     */
    suspend fun loadFromAssets(context: Context) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        if (isLoaded) return@withContext

        try {
            val start = System.currentTimeMillis()
            val files = context.assets.list(KNOWLEDGE_DIR) ?: emptyArray()

            for (fileName in files) {
                if (!fileName.endsWith(".md")) continue

                val content = context.assets
                    .open("$KNOWLEDGE_DIR/$fileName")
                    .bufferedReader()
                    .readText()
                    .replace("\r\n", "\n")  // Normalize Windows line endings

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
     * Hybrid search: combines semantic similarity (MiniLM embeddings) with
     * keyword overlap (BM25-like) for robust retrieval.
     *
     * Pure embedding search fails when the MiniLM model can't associate
     * user terms (e.g., "image match") with document phrases (e.g., "Visual
     * Trigger Automation using image template matching"). Keyword boosting
     * fixes this by directly rewarding chunks that contain the query terms.
     */
    fun search(query: String, topK: Int = 3, minSimilarity: Float = 0.30f): List<KnowledgeChunk> {
        if (!isLoaded || chunks.isEmpty()) return emptyList()

        val queryEmbedding = embedder.encode(query)
        val queryKeywords = extractKeywords(query)

        val scored = chunks.map { chunk ->
            val semanticScore = cosineSimilarity(queryEmbedding, chunk.embedding)
            val keywordScore = computeKeywordScore(queryKeywords, chunk.text)
            // Hybrid: 60% semantic + 40% keyword
            val hybridScore = (semanticScore * 0.6f) + (keywordScore * 0.4f)
            Triple(chunk, hybridScore, semanticScore)
        }.sortedByDescending { it.second }

        // Log top 5 scores for debugging
        scored.take(5).forEachIndexed { i, (chunk, hybrid, semantic) ->
            Log.d(TAG, "RAG #$i: hybrid=${"%.3f".format(hybrid)} sem=${"%.3f".format(semantic)} " +
                  "src=${chunk.source}[${chunk.chunkIndex}] text=\"${chunk.text.take(60)}...\"")
        }

        return scored
            .filter { it.second >= minSimilarity }
            .take(topK)
            .map { it.first }
    }

    /**
     * Extract meaningful keywords from a query (lowercase, stop-words removed).
     */
    private fun extractKeywords(query: String): List<String> {
        val stopWords = setOf(
            "i", "me", "my", "we", "our", "you", "your", "it", "its", "the",
            "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would",
            "could", "should", "can", "may", "might", "shall", "to", "of",
            "in", "for", "on", "with", "at", "by", "from", "as", "into",
            "through", "during", "before", "after", "above", "below",
            "this", "that", "these", "those", "and", "but", "or", "nor",
            "not", "so", "if", "then", "than", "too", "very", "just",
            "about", "up", "out", "off", "over", "under", "again",
            "there", "here", "any", "each", "few", "more", "most",
            "some", "such", "only", "own", "same", "no", "how", "what",
            "when", "where", "why", "which", "who", "whom", "all", "many",
            "much", "way", "thing", "things", "help", "use", "using"
        )
        return query.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in stopWords }
    }

    /**
     * Compute a keyword overlap score between query keywords and a chunk text.
     * Returns 0.0 to 1.0 — the fraction of query keywords found in the chunk.
     */
    private fun computeKeywordScore(queryKeywords: List<String>, chunkText: String): Float {
        if (queryKeywords.isEmpty()) return 0f
        val chunkLower = chunkText.lowercase()
        val matches = queryKeywords.count { keyword -> chunkLower.contains(keyword) }
        return matches.toFloat() / queryKeywords.size.toFloat()
    }

    /**
     * Split a markdown document into overlapping word-based chunks.
     * Preserves section headers by prepending them to the following content
     * so that the embedding captures the topic context.
     */
    private fun chunkDocument(content: String, source: String): List<String> {
        // Split by double newlines (paragraphs)
        val paragraphs = content.split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val result = mutableListOf<String>()
        val currentChunk = StringBuilder()
        var wordCount = 0
        var lastHeader = "" // Track the most recent section header

        for (paragraph in paragraphs) {
            // If this paragraph is a markdown header, remember it
            // but don't add it as a standalone chunk
            if (paragraph.startsWith("#")) {
                lastHeader = paragraph.replace(Regex("^#+\\s*"), "").trim()
                continue
            }

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
                // Re-inject header context at the start of the new chunk
                if (lastHeader.isNotBlank()) {
                    currentChunk.insert(0, "Topic: $lastHeader\n\n")
                    wordCount += lastHeader.split(Regex("\\s+")).size + 1
                }
            }

            // If starting a new chunk and we have a header, prepend it
            if (currentChunk.isEmpty() && lastHeader.isNotBlank()) {
                currentChunk.append("Topic: $lastHeader\n\n")
                wordCount += lastHeader.split(Regex("\\s+")).size + 1
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
