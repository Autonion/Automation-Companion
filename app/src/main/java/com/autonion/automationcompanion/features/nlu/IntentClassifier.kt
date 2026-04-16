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
            "what is the browser extension for",
            "I have a doubt about this feature",
            "can I capture a specific part of the screen",
            "is it possible to automate an action",
            "I was wondering how to use this",
            "can you explain how automation works"
        )
    )

    /** Precomputed embeddings for all intent examples: Map<IntentType, List<FloatArray>> */
    @Volatile
    private var intentEmbeddings: Map<IntentType, List<FloatArray>> = emptyMap()

    @Volatile
    var isWarmedUp = false
        private set

    /**
     * Pre-compute all intent embeddings in the background.
     * Call from a coroutine to avoid blocking the main thread.
     * Until warmUp() completes, classify() uses heuristic-only mode.
     */
    suspend fun warmUp() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        if (isWarmedUp) return@withContext

        Log.d(TAG, "Warming up: loading ONNX model + precomputing intent embeddings...")
        val start = System.currentTimeMillis()

        // This triggers lazy ONNX session creation if not yet initialized
        embedder.ensureInitialized()

        intentEmbeddings = intentExamples.mapValues { (_, examples) ->
            examples.map { embedder.encode(it) }
        }

        isWarmedUp = true
        val elapsed = System.currentTimeMillis() - start
        val totalExamples = intentExamples.values.sumOf { it.size }
        Log.d(TAG, "Warm-up complete: $totalExamples intent embeddings in ${elapsed}ms")
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
        // ── 1. Question check FIRST ──────────────────────────
        // Must run before toggle/action checks because prompts like
        // "is there any way I can automate based ON a LOCATION?"
        // false-match against toggle targets ("on" + "location").
        // Questions should NEVER trigger system actions.
        if (entityExtractor.isLikelyQuestion(lower)) {
            return IntentResult(
                intent = IntentType.Q_AND_A,
                confidence = 0.90f,
                entities = entities,
                rawPrompt = lower
            )
        }

        // 2. Scheduled action (check BEFORE key action — "click next every 1 min" has both)
        if (entityExtractor.isLikelyScheduled(lower)) {
            return IntentResult(
                intent = IntentType.SCHEDULED_ACTION,
                confidence = HEURISTIC_CONFIDENCE,
                entities = entities,
                rawPrompt = lower
            )
        }

        // 3. Direct key action
        if (entityExtractor.isLikelyKeyAction(lower)) {
            return IntentResult(
                intent = IntentType.DIRECT_KEY_ACTION,
                confidence = HEURISTIC_CONFIDENCE,
                entities = entities,
                rawPrompt = lower
            )
        }

        // 4. Toggle
        if (entityExtractor.isLikelyToggle(lower)) {
            return IntentResult(
                intent = IntentType.DIRECT_TOGGLE,
                confidence = HEURISTIC_CONFIDENCE,
                entities = entities,
                rawPrompt = lower
            )
        }

        // 5. Cross-device (explicit device mention)
        if (entityExtractor.isLikelyCrossDevice(lower)) {
            return IntentResult(
                intent = IntentType.CROSS_DEVICE,
                confidence = HEURISTIC_CONFIDENCE,
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
        // If embeddings haven't been warmed up yet, fall back to Q&A
        if (!isWarmedUp || intentEmbeddings.isEmpty()) {
            Log.d(TAG, "Embeddings not ready — defaulting to Q_AND_A")
            return IntentResult(
                intent = IntentType.Q_AND_A,
                confidence = 0.5f,
                entities = entities,
                rawPrompt = lower
            )
        }

        val promptEmbedding = embedder.encode(prompt)

        // Find the best matching intent by max cosine similarity
        var bestIntent = IntentType.Q_AND_A // safe default fallback (won't perform actions)
        var bestScore = 0f

        for ((intentType, embeddings) in intentEmbeddings) {
            val maxSim = embeddings.maxOfOrNull { cosineSimilarity(promptEmbedding, it) } ?: 0f
            if (maxSim > bestScore) {
                bestScore = maxSim
                bestIntent = intentType
            }
        }

        Log.d(TAG, "Embedding classification: $bestIntent (score=${"%.3f".format(bestScore)})")

        // If confidence is too low, fall back to Q_AND_A
        if (bestScore < EMBEDDING_THRESHOLD) {
            Log.d(TAG, "Low confidence (${"%.3f".format(bestScore)}), defaulting to Q_AND_A")
            bestIntent = IntentType.Q_AND_A
            bestScore = EMBEDDING_THRESHOLD
        }

        // ── VALIDATION GATE ──────────────────────────────────
        // Embedding similarity can match the TOPIC of a prompt (e.g. "automation",
        // "bluetooth") without distinguishing whether the user wants to PERFORM an
        // action or ASK about it.  Instead of hardcoding question patterns, we
        // validate that DEVICE_AUTOMATION prompts contain *concrete actionable
        // content*: a target (app/toggle) + command-form verb.  If not, reclassify
        // to Q_AND_A.  This is structural, not pattern-based — so every future
        // prompt is covered.
        if (bestIntent == IntentType.DEVICE_AUTOMATION ||
            bestIntent == IntentType.DIRECT_TOGGLE) {
            if (!hasActionableContent(lower, entities)) {
                Log.d(TAG, "Validation gate: no actionable content → reclassifying to Q_AND_A")
                bestIntent = IntentType.Q_AND_A
            }
        }

        return IntentResult(
            intent = bestIntent,
            confidence = bestScore,
            entities = entities,
            rawPrompt = lower
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  VALIDATION GATE
    // ═══════════════════════════════════════════════════════════

    /**
     * Checks whether a prompt has concrete, executable content that justifies
     * routing to DEVICE_AUTOMATION or DIRECT_TOGGLE.
     *
     * This is a structural check, not a pattern check:
     *  - A concrete app/toggle target  (e.g. "flipkart", "bluetooth")
     *  - A task verb in *command* form  (e.g. "open", "search")
     *  - NOT phrased as a question/inquiry about capabilities
     *
     * Decision matrix:
     *  app target + verb  → actionable ✓  ("search shoes on flipkart")
     *  app target only    → actionable ✓  ("flipkart" — likely wants to open it)
     *  toggle target      → actionable ✓  ("turn on wifi")
     *  verb + question    → NOT actionable ("can I automate a screen action?")
     *  no target, no verb → NOT actionable ("I have a doubt about bluetooth")
     */
    private fun hasActionableContent(lower: String, entities: ExtractedEntities): Boolean {
        val hasAppTarget = entities.appName != null
        val hasToggleTarget = entities.toggleTarget != null
        val hasTaskVerb = entities.taskVerb != null

        // Toggle commands are always actionable if heuristic didn't catch them
        if (hasToggleTarget && entities.toggleDesiredState != null) return true

        // Concrete app target → the user wants to interact with that app
        if (hasAppTarget) return true

        // Task verb in command position, but only if prompt is NOT in question form
        if (hasTaskVerb && !isInquiryForm(lower)) return true

        // Nothing concrete to act on
        return false
    }

    /**
     * Detects whether a prompt is an inquiry/question rather than a command.
     *
     * This is intentionally broad: ANY interrogative signal disqualifies a prompt
     * from being treated as an actionable command (unless overridden by having a
     * concrete app target, which is checked before this in hasActionableContent).
     *
     * Uses structural signals, not hardcoded phrases:
     *  - Interrogative markers (question words, modal verbs in question context)
     *  - Sentence-ending "?"
     *  - First-person inquiry patterns ("I want to know", "I'm curious")
     */
    private fun isInquiryForm(lower: String): Boolean {
        // Question mark is the strongest signal
        if (lower.endsWith("?")) return true

        // Interrogative word anywhere in the first half of the prompt
        // (question words at the end are often part of commands: "search how to cook")
        val firstHalf = lower.take(lower.length / 2 + 10)
        val interrogatives = listOf(
            "how", "what", "why", "when", "where", "which", "who"
        )
        if (interrogatives.any { firstHalf.contains(Regex("\\b$it\\b")) }) return true

        // Modal verbs in question context: "can I", "could I", "is it", "should I"
        if (lower.contains(Regex("\\b(can|could|should|would|is|are|does|do)\\s+(i|it|this|we|you)\\b"))) return true

        // First-person inquiry markers: "I have a doubt", "I want to know", "wondering"
        if (lower.contains(Regex("\\b(doubt|wondering|confused|curious|understand|explain|tell me|help me|guide)\\b"))) return true

        return false
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
