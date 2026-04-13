package com.autonion.automationcompanion.features.nlu

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.semantic_automation.ml.SentenceEmbedder

/**
 * On-device NLU Intent Classifier.
 *
 * Uses a two-tier approach:
 *  1. **Fast regex/heuristic check** for obvious patterns (key presses, toggles, schedules)
 *  2. **Semantic similarity** via MiniLM embeddings for fuzzy intent matching
 *
 * Key vocabulary embeddings are precomputed at initialization.
 * Each classification runs in ~10ms — no LLM or network needed.
 */
class IntentClassifier(context: Context) {

    companion object {
        private const val TAG = "IntentClassifier"

        /** Minimum confidence to accept a heuristic match */
        private const val HEURISTIC_CONFIDENCE = 0.95f

        /** Minimum cosine similarity to accept an intent match */
        private const val EMBEDDING_THRESHOLD = 0.55f

        @Volatile
        private var instance: IntentClassifier? = null

        fun getInstance(context: Context): IntentClassifier {
            return instance ?: synchronized(this) {
                instance ?: IntentClassifier(context.applicationContext).also { instance = it }
            }
        }
    }

    internal val embedder = SentenceEmbedder(context)
    val entityExtractor = EntityExtractor()

    // ═══════════════════════════════════════════════════════════
    //  PRECOMPUTED INTENT EMBEDDINGS
    // ═══════════════════════════════════════════════════════════

    /**
     * Canonical example phrases for each intent type.
     * These are embedded once at init and cached.
     */
    private val intentExamples: Map<IntentType, List<String>> = mapOf(
        IntentType.DIRECT_KEY_ACTION to listOf(
            "press enter",
            "type next",
            "click backspace",
            "hit space",
            "press tab",
            "press the forward button",
            "hit return",
            "push escape",
            "press mute",
            "click play"
        ),
        IntentType.DIRECT_TOGGLE to listOf(
            "turn off wifi",
            "enable bluetooth",
            "disable do not disturb",
            "turn on hotspot",
            "switch off airplane mode",
            "turn on location",
            "activate flashlight",
            "turn off mobile data"
        ),
        IntentType.SCHEDULED_ACTION to listOf(
            "click next every 1 minute",
            "press enter every 30 seconds",
            "type next 5 times",
            "repeat clicking forward",
            "keep pressing space every 10 seconds",
            "continuously click next",
            "click it again and again every minute"
        ),
        IntentType.DEVICE_AUTOMATION to listOf(
            "search shoes on flipkart",
            "open settings",
            "play music on spotify",
            "search for shoes under 2000 in flipkart",
            "open instagram and like posts",
            "send message on whatsapp",
            "open camera",
            "launch youtube",
            "navigate to amazon and search headphones",
            "find restaurants on zomato",
            "order food on swiggy",
            "book a cab on uber"
        ),
        IntentType.CROSS_DEVICE to listOf(
            "on my laptop open chrome",
            "on desktop open notepad",
            "send to desktop",
            "run on my computer",
            "on my pc search for files",
            "open browser on my laptop",
            "on my desktop run spotify"
        ),
        IntentType.Q_AND_A to listOf(
            "how do I connect devices",
            "what features do you have",
            "how to set up ollama",
            "explain semantic automation",
            "how does this app work",
            "what can you do",
            "help me understand cross device sync",
            "guide me through setting up",
            "what is the automation debugger",
            "tell me about gesture recording",
            "how to use the flow builder",
            "why is the automation doing random things",
            "how to sync clipboard",
            "what is the browser extension for"
        )
    )

    /** Precomputed embeddings for all intent examples: Map<IntentType, List<FloatArray>> */
    private val intentEmbeddings: Map<IntentType, List<FloatArray>>

    init {
        Log.d(TAG, "Precomputing intent embeddings...")
        val start = System.currentTimeMillis()

        intentEmbeddings = intentExamples.mapValues { (_, examples) ->
            examples.map { embedder.encode(it) }
        }

        val elapsed = System.currentTimeMillis() - start
        val totalExamples = intentExamples.values.sumOf { it.size }
        Log.d(TAG, "Precomputed $totalExamples intent embeddings in ${elapsed}ms")
    }

    // ═══════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════

    /**
     * Classify a raw user prompt into an intent + extracted entities.
     *
     * Tier 1: Fast heuristic check (regex/patterns) — ~1ms
     * Tier 2: Semantic similarity with embeddings — ~10ms
     */
    fun classify(prompt: String): IntentResult {
        val lower = prompt.lowercase().trim()
        val entities = entityExtractor.extract(prompt)

        // ── Tier 1: Heuristic fast-path ─────────────────────
        heuristicClassify(lower, entities)?.let { return it }

        // ── Tier 2: Embedding-based classification ──────────
        return embeddingClassify(prompt, lower, entities)
    }

    // ═══════════════════════════════════════════════════════════
    //  TIER 1: HEURISTIC CLASSIFICATION
    // ═══════════════════════════════════════════════════════════

    private fun heuristicClassify(lower: String, entities: ExtractedEntities): IntentResult? {
        // 1. Scheduled action (check BEFORE key action — "click next every 1 min" has both)
        if (entityExtractor.isLikelyScheduled(lower)) {
            return IntentResult(
                intent = IntentType.SCHEDULED_ACTION,
                confidence = HEURISTIC_CONFIDENCE,
                entities = entities,
                rawPrompt = lower
            )
        }

        // 2. Direct key action
        if (entityExtractor.isLikelyKeyAction(lower)) {
            return IntentResult(
                intent = IntentType.DIRECT_KEY_ACTION,
                confidence = HEURISTIC_CONFIDENCE,
                entities = entities,
                rawPrompt = lower
            )
        }

        // 3. Toggle
        if (entityExtractor.isLikelyToggle(lower)) {
            return IntentResult(
                intent = IntentType.DIRECT_TOGGLE,
                confidence = HEURISTIC_CONFIDENCE,
                entities = entities,
                rawPrompt = lower
            )
        }

        // 4. Cross-device (explicit device mention)
        if (entityExtractor.isLikelyCrossDevice(lower)) {
            return IntentResult(
                intent = IntentType.CROSS_DEVICE,
                confidence = HEURISTIC_CONFIDENCE,
                entities = entities,
                rawPrompt = lower
            )
        }

        // 5. Question → Q_AND_A (but actual FAQ matching happens later in the ViewModel)
        if (entityExtractor.isLikelyQuestion(lower) && entities.appName == null) {
            return IntentResult(
                intent = IntentType.Q_AND_A,
                confidence = 0.80f,
                entities = entities,
                rawPrompt = lower
            )
        }

        return null // Fall through to embedding-based classification
    }

    // ═══════════════════════════════════════════════════════════
    //  TIER 2: EMBEDDING-BASED CLASSIFICATION
    // ═══════════════════════════════════════════════════════════

    private fun embeddingClassify(
        prompt: String,
        lower: String,
        entities: ExtractedEntities
    ): IntentResult {
        val promptEmbedding = embedder.encode(prompt)

        // Find the best matching intent by max cosine similarity
        var bestIntent = IntentType.DEVICE_AUTOMATION // default fallback
        var bestScore = 0f

        for ((intentType, embeddings) in intentEmbeddings) {
            val maxSim = embeddings.maxOfOrNull { cosineSimilarity(promptEmbedding, it) } ?: 0f
            if (maxSim > bestScore) {
                bestScore = maxSim
                bestIntent = intentType
            }
        }

        Log.d(TAG, "Embedding classification: $bestIntent (score=${"%.3f".format(bestScore)})")

        // If confidence is too low, default to DEVICE_AUTOMATION
        // (let the SemanticAutomationEngine handle it with LLM)
        if (bestScore < EMBEDDING_THRESHOLD) {
            Log.d(TAG, "Low confidence (${"%.3f".format(bestScore)}), defaulting to DEVICE_AUTOMATION")
            bestIntent = IntentType.DEVICE_AUTOMATION
            bestScore = EMBEDDING_THRESHOLD
        }

        return IntentResult(
            intent = bestIntent,
            confidence = bestScore,
            entities = entities,
            rawPrompt = lower
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  UTILITY
    // ═══════════════════════════════════════════════════════════

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())
        return if (denom > 0) (dot / denom.toFloat()) else 0f
    }

    fun close() {
        embedder.close()
        instance = null
    }
}
