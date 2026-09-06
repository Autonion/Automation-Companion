package com.autonion.automationcompanion.features.omni_chatbot.knowledge

import android.content.Context
import android.util.Log
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight repository to load the static FAQ database.
 * Completely decouples FAQs from semantic NLP embeddings.
 *
 * FAQs are loaded from assets/knowledge/faq_database.json
 * and exposed as a simple list for UI browsing.
 */
class FAQRepository {

    companion object {
        private const val TAG = "FAQRepository"
        private const val FAQ_ASSET_PATH = "knowledge/faq_database.json"
    }

    data class FAQ(
        val question: String,
        val answer: String,
        val tags: List<String>
    )

    private val _allFAQs = mutableListOf<FAQ>()
    private val faqLock = Any()
    
    @Volatile
    var isLoaded = false
        private set

    /**
     * Load FAQ database from assets.
     * Parses the 150+ entry JSON asynchronously.
     */
    suspend fun loadFAQs(context: Context) = withContext(Dispatchers.Default) {
        if (isLoaded) return@withContext

        try {
            val jsonStr = context.assets.open(FAQ_ASSET_PATH).bufferedReader().readText()
            val jsonArray = JSONArray(jsonStr)

            val start = System.currentTimeMillis()
            val loadedFaqs = mutableListOf<FAQ>()

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

                loadedFaqs.add(FAQ(question, answer, tags))
            }

            synchronized(faqLock) {
                _allFAQs.clear()
                _allFAQs.addAll(loadedFaqs)
                isLoaded = true
            }

            val elapsed = System.currentTimeMillis() - start
            Log.d(TAG, "Loaded ${loadedFaqs.size} Static FAQs in ${elapsed}ms")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load static FAQs", e)
        }
    }

    /**
     * Get all loaded FAQs for the UI Browser.
     */
    fun getAllFAQs(): List<FAQ> = synchronized(faqLock) {
        _allFAQs.toList()
    }

    /**
     * Returns a static FAQ when the user's prompt exactly matches a known FAQ
     * question, ignoring case and punctuation.
     */
    fun findExactQuestionMatch(prompt: String): FAQ? {
        val normalizedPrompt = normalizeQuestion(prompt)
        if (normalizedPrompt.isBlank()) return null

        val faqs = synchronized(faqLock) {
            _allFAQs.toList()
        }

        return faqs.firstOrNull { faq ->
            normalizeQuestion(faq.question) == normalizedPrompt
        }
    }

    private fun normalizeQuestion(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
