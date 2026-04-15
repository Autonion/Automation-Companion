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

                _allFAQs.add(FAQ(question, answer, tags))
            }

            val elapsed = System.currentTimeMillis() - start
            isLoaded = true
            Log.d(TAG, "Loaded ${_allFAQs.size} Static FAQs in ${elapsed}ms")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load static FAQs", e)
        }
    }

    /**
     * Get all loaded FAQs for the UI Browser.
     */
    fun getAllFAQs(): List<FAQ> = _allFAQs.toList()
}
