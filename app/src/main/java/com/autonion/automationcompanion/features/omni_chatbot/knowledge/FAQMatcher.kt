package com.autonion.automationcompanion.features.omni_chatbot.knowledge

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.semantic_automation.ml.SentenceEmbedder
import org.json.JSONArray

/**
 * Semantic FAQ matcher that uses MiniLM embeddings to find
 * the best matching FAQ for a user question.
 *
 * FAQs are loaded from assets/knowledge/faq_database.json
 * and their question embeddings are precomputed at initialization.
 *
 * If a question's cosine similarity exceeds the threshold,
 * the FAQ answer is returned instantly — no LLM needed.
 */
class FAQMatcher(private val embedder: SentenceEmbedder) {

    companion object {
        private const val TAG = "FAQMatcher"
        private const val FAQ_ASSET_PATH = "knowledge/faq_database.json"
        private const val DEFAULT_THRESHOLD = 0.82f
    }

    data class FAQ(
        val question: String,
        val answer: String,
        val tags: List<String>
    )

    private data class EmbeddedFAQ(
        val faq: FAQ,
        val questionEmbedding: FloatArray
    )

    private val embeddedFAQs = mutableListOf<EmbeddedFAQ>()
    var isLoaded = false
        private set

    /**
     * Load FAQ database from assets and precompute question embeddings.
     * Call this once at startup (or first chatbot expansion).
     */
    fun loadFAQs(context: Context) {
        if (isLoaded) return

        try {
            val jsonStr = context.assets.open(FAQ_ASSET_PATH).bufferedReader().readText()
            val jsonArray = JSONArray(jsonStr)

            val start = System.currentTimeMillis()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val question = obj.getString("question")
                val answer = obj.getString("answer")

                val tags = mutableListOf<String>()
                val tagsArray = obj.optJSONArray("tags")
                if (tagsArray != null) {
                    for (j in 0 until tagsArray.length()) {
                        tags.add(tagsArray.getString(j))
                    }
                }

                val faq = FAQ(question, answer, tags)
                val embedding = embedder.encode(question)
                embeddedFAQs.add(EmbeddedFAQ(faq, embedding))
            }

            val elapsed = System.currentTimeMillis() - start
            isLoaded = true
            Log.d(TAG, "Loaded ${embeddedFAQs.size} FAQs with embeddings in ${elapsed}ms")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load FAQs", e)
        }
    }

    /**
     * Find the best matching FAQ for the given query.
     *
     * @param query The user's question
     * @param threshold Minimum cosine similarity to accept (default 0.82)
     * @return The matching FAQ, or null if no match exceeds threshold
     */
    fun match(query: String, threshold: Float = DEFAULT_THRESHOLD): FAQ? {
        if (!isLoaded || embeddedFAQs.isEmpty()) return null

        val queryEmbedding = embedder.encode(query)

        var bestFAQ: FAQ? = null
        var bestScore = 0f

        for (embedded in embeddedFAQs) {
            val score = cosineSimilarity(queryEmbedding, embedded.questionEmbedding)
            if (score > bestScore) {
                bestScore = score
                bestFAQ = embedded.faq
            }
        }

        Log.d(TAG, "Best FAQ match: score=${"%.3f".format(bestScore)} " +
              "q=\"${bestFAQ?.question?.take(50)}\"")

        return if (bestScore >= threshold) bestFAQ else null
    }

    /**
     * Get all FAQs (for displaying FAQ list/browsing).
     */
    fun getAllFAQs(): List<FAQ> = embeddedFAQs.map { it.faq }

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
