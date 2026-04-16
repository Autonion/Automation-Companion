package com.autonion.automationcompanion.features.semantic_automation.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.LongBuffer

/**
 * On-device sentence embedding using MiniLM-L6-v2 via ONNX Runtime.
 * Produces 384-dimensional embeddings identical to the Python
 * `sentence-transformers/all-MiniLM-L6-v2` model used during training.
 *
 * The embedding is computed as mean-pooling over the token-level
 * last_hidden_state, masked by the attention mask.
 */
class SentenceEmbedder(context: Context) {

    companion object {
        private const val TAG = "SentenceEmbedder"
        private const val MODEL_ASSET = "miniLM-model.onnx"
        private const val EMBEDDING_DIM = 384
        private const val MAX_SEQ_LEN = 128
    }

    private val appContext = context.applicationContext
    private val tokenizer = WordPieceTokenizer(context)
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()

    // Lazy-loaded ONNX session — defers heavy model I/O from startup to first use
    @Volatile
    private var _ortSession: OrtSession? = null
    private val ortSession: OrtSession
        get() = _ortSession ?: synchronized(this) {
            _ortSession ?: createSession().also { _ortSession = it }
        }

    @Volatile
    private var _modelInputNames: List<String>? = null
    private val modelInputNames: List<String>
        get() = _modelInputNames ?: ortSession.inputNames.toList().also { _modelInputNames = it }

    private var loggedFirstCall = false

    private fun createSession(): OrtSession {
        val start = System.currentTimeMillis()
        val modelBytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
        }
        val session = ortEnv.createSession(modelBytes, opts)
        val elapsed = System.currentTimeMillis() - start
        Log.d(TAG, "ONNX MiniLM session created (${modelBytes.size / 1024}KB) in ${elapsed}ms")
        session.inputNames.forEach { Log.d(TAG, "  Input: $it") }
        session.outputNames.forEach { Log.d(TAG, "  Output: $it") }
        Log.d(TAG, "Model expects ${session.inputNames.size} inputs: ${session.inputNames.toList()}")
        return session
    }

    /**
     * Pre-initialize the ONNX session from a background thread.
     * Call this early to warm up without blocking the main thread.
     */
    fun ensureInitialized() {
        // Accessing ortSession triggers lazy creation
        ortSession
    }

    /**
     * Encode a text string into a 384-dimensional embedding vector.
     */
    fun encode(text: String): FloatArray {
        if (text.isBlank()) return FloatArray(EMBEDDING_DIM) // zero vector for empty text

        // 1. Tokenize
        val (inputIds, attentionMask, tokenTypeIds) = tokenizer.encode(text, MAX_SEQ_LEN)

        // 2. Create ONNX tensors — shape [1, MAX_SEQ_LEN]
        val shape = longArrayOf(1, MAX_SEQ_LEN.toLong())

        val inputIdsTensor = OnnxTensor.createTensor(
            ortEnv, LongBuffer.wrap(inputIds), shape
        )
        val attentionMaskTensor = OnnxTensor.createTensor(
            ortEnv, LongBuffer.wrap(attentionMask), shape
        )
        val tokenTypeIdsTensor = OnnxTensor.createTensor(
            ortEnv, LongBuffer.wrap(tokenTypeIds), shape
        )

        // 3. Build input map: match exactly the number of inputs the model expects
        //    Standard BERT/MiniLM order: input_ids, attention_mask, token_type_ids
        val allTensors = listOf(inputIdsTensor, attentionMaskTensor, tokenTypeIdsTensor)
        val inputs = mutableMapOf<String, OnnxTensor>()

        for (i in modelInputNames.indices) {
            if (i < allTensors.size) {
                inputs[modelInputNames[i]] = allTensors[i]
            }
        }

        // Log once on first call
        if (!loggedFirstCall) {
            Log.d(TAG, "First encode: passing ${inputs.size} inputs: ${inputs.keys}")
            loggedFirstCall = true
        }

        // 4. Run inference
        val results = ortSession.run(inputs)

        // 5. Extract embedding from output
        //    The model has 2 outputs:
        //      [0] token_embeddings: [1, 128, 384] — raw token-level (NOT what we want)
        //      [1] sentence_embedding: [1, 384]    — pooled sentence vector (matches training)
        //    Use sentence_embedding (index 1) if available; fall back to mean-pooling index 0.
        val outputNames = ortSession.outputNames.toList()
        val sentenceEmbIdx = outputNames.indexOfFirst {
            it.contains("sentence", ignoreCase = true)
        }

        val embedding: FloatArray
        if (sentenceEmbIdx >= 0) {
            // Preferred: use pre-pooled sentence_embedding
            val sentTensor = results[sentenceEmbIdx]
            val raw = sentTensor.value
            embedding = extractDirectEmbedding(raw)
        } else {
            // Fallback: mean-pool over token_embeddings
            val rawOutput = results[0].value
            embedding = extractMeanPooled(rawOutput, attentionMask)
        }

        // 6. Cleanup
        inputIdsTensor.close()
        attentionMaskTensor.close()
        tokenTypeIdsTensor.close()
        results.close()

        return embedding
    }

    /**
     * Extract a direct sentence embedding from shape [1, 384].
     */
    private fun extractDirectEmbedding(rawOutput: Any): FloatArray {
        val result = FloatArray(EMBEDDING_DIM)

        when (rawOutput) {
            is Array<*> -> {
                val first = (rawOutput as Array<*>)[0]
                if (first is FloatArray) {
                    System.arraycopy(first, 0, result, 0, minOf(first.size, EMBEDDING_DIM))
                } else {
                    Log.e(TAG, "sentence_embedding unexpected inner type: ${first?.let { it::class.java }}")
                }
            }
            else -> Log.e(TAG, "sentence_embedding unexpected type: ${rawOutput::class.java}")
        }

        return l2Normalize(result)
    }

    /**
     * Mean-pool token_embeddings [1, seq_len, 384] over attention-masked positions.
     */
    private fun extractMeanPooled(rawOutput: Any, attentionMask: LongArray): FloatArray {
        val result = FloatArray(EMBEDDING_DIM)

        when (rawOutput) {
            is Array<*> -> {
                val first = (rawOutput as Array<*>)[0]
                if (first is Array<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val seqEmbeddings = first as Array<FloatArray>
                    var validTokens = 0f
                    for (t in seqEmbeddings.indices) {
                        if (t < attentionMask.size && attentionMask[t] == 1L) {
                            validTokens += 1f
                            for (d in 0 until EMBEDDING_DIM) {
                                result[d] += seqEmbeddings[t][d]
                            }
                        }
                    }
                    if (validTokens > 0f) {
                        for (d in 0 until EMBEDDING_DIM) result[d] /= validTokens
                    }
                }
            }
            else -> Log.e(TAG, "token_embeddings unexpected type: ${rawOutput::class.java}")
        }

        return l2Normalize(result)
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var norm = 0f
        for (v in vec) norm += v * v
        norm = Math.sqrt(norm.toDouble()).toFloat()
        if (norm > 0f) {
            for (d in vec.indices) vec[d] /= norm
        }
        return vec
    }

    fun close() {
        try {
            ortSession.close()
            ortEnv.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ONNX session", e)
        }
    }
}
