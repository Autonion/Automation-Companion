package com.autonion.automationcompanion.features.semantic_automation.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.LongBuffer

/**
 * On-device sentence embedding using MiniLM-L6-v2 via ONNX Runtime.
 * Produces 384-dimensional embeddings identical to the Python
 * `sentence-transformers/all-MiniLM-L6-v2` model used during training.
 *
 * Memory optimization:
 * - Extracts model to disk and uses file-path memory mapping (mmap) instead of
 *   reading 90MB into the Java heap, preventing OutOfMemoryErrors.
 * - Shares a single static OrtSession across all SentenceEmbedder instances.
 */
class SentenceEmbedder(context: Context) {

    companion object {
        private const val TAG = "SentenceEmbedder"
        private const val MODEL_ASSET = "miniLM-model.onnx"
        private const val MODEL_FILENAME = "miniLM-model.onnx"
        private const val EMBEDDING_DIM = 384
        private const val MAX_SEQ_LEN = 128

        // Static shared session and lock to avoid loading the 90MB model multiple times
        @Volatile
        private var sharedSession: OrtSession? = null
        @Volatile
        private var sharedInputNames: List<String>? = null
        private val lock = Any()
    }

    private val appContext = context.applicationContext
    private val tokenizer = WordPieceTokenizer(context)
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val ortSession: OrtSession?
        get() = sharedSession ?: synchronized(lock) {
            sharedSession ?: createSession()?.also { sharedSession = it }
        }

    private val modelInputNames: List<String>
        get() = sharedInputNames ?: ortSession?.inputNames?.toList()?.also { sharedInputNames = it } ?: emptyList()

    private var loggedFirstCall = false

    private fun getOrExtractModelFile(): File? {
        return try {
            val modelsDir = File(appContext.filesDir, "models").apply { if (!exists()) mkdirs() }
            val destFile = File(modelsDir, MODEL_FILENAME)

            val assetLength = try {
                appContext.assets.openFd(MODEL_ASSET).use { it.length }
            } catch (_: Exception) {
                -1L
            }

            // If file already exists and is non-empty (and matches asset size if known), reuse it
            if (destFile.exists() && destFile.length() > 0 && (assetLength <= 0 || destFile.length() == assetLength)) {
                return destFile
            }

            Log.d(TAG, "Extracting MiniLM model to disk (${destFile.absolutePath})...")
            appContext.assets.open(MODEL_ASSET).use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(64 * 1024) // 64KB buffer — very low heap footprint
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            Log.d(TAG, "MiniLM model extracted successfully (${destFile.length() / (1024 * 1024)}MB)")
            destFile
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to extract MiniLM model file to disk", e)
            null
        }
    }

    private fun createSession(): OrtSession? {
        return try {
            val start = System.currentTimeMillis()
            val modelFile = getOrExtractModelFile()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2) // Reduced from 4 to 2 to minimize memory & thread pressure
            }

            val session = if (modelFile != null && modelFile.exists() && modelFile.length() > 0) {
                // Memory-mapped file path — ZERO Java heap memory allocated!
                ortEnv.createSession(modelFile.absolutePath, opts)
            } else {
                // Fallback: direct streaming
                Log.w(TAG, "Falling back to byte-buffer session creation")
                val modelBytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
                ortEnv.createSession(modelBytes, opts)
            }

            val elapsed = System.currentTimeMillis() - start
            Log.d(TAG, "ONNX MiniLM session created via mmap in ${elapsed}ms")
            session
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create ONNX session for SentenceEmbedder", e)
            null
        }
    }

    /**
     * Pre-initialize the ONNX session from a background thread.
     * Call this early to warm up without blocking the main thread.
     */
    fun ensureInitialized() {
        ortSession
    }

    /**
     * Encode a text string into a 384-dimensional embedding vector.
     */
    fun encode(text: String): FloatArray {
        if (text.isBlank()) return FloatArray(EMBEDDING_DIM)

        val session = ortSession
        if (session == null) {
            Log.w(TAG, "ONNX session not available — returning zero embedding vector")
            return FloatArray(EMBEDDING_DIM)
        }

        try {
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

            // 3. Build input map
            val allTensors = listOf(inputIdsTensor, attentionMaskTensor, tokenTypeIdsTensor)
            val inputs = mutableMapOf<String, OnnxTensor>()

            val names = modelInputNames
            for (i in names.indices) {
                if (i < allTensors.size) {
                    inputs[names[i]] = allTensors[i]
                }
            }

            if (!loggedFirstCall) {
                Log.d(TAG, "First encode: passing ${inputs.size} inputs: ${inputs.keys}")
                loggedFirstCall = true
            }

            // 4. Run inference
            val results = session.run(inputs)

            // 5. Extract embedding from output
            val outputNames = session.outputNames.toList()
            val sentenceEmbIdx = outputNames.indexOfFirst {
                it.contains("sentence", ignoreCase = true)
            }

            val embedding: FloatArray = if (sentenceEmbIdx >= 0) {
                val sentTensor = results[sentenceEmbIdx]
                extractDirectEmbedding(sentTensor.value)
            } else {
                val rawOutput = results[0].value
                extractMeanPooled(rawOutput, attentionMask)
            }

            // 6. Cleanup
            inputIdsTensor.close()
            attentionMaskTensor.close()
            tokenTypeIdsTensor.close()
            results.close()

            return embedding
        } catch (e: Throwable) {
            Log.e(TAG, "Error encoding text with SentenceEmbedder: '$text'", e)
            return FloatArray(EMBEDDING_DIM)
        }
    }

    private fun extractDirectEmbedding(rawOutput: Any): FloatArray {
        val result = FloatArray(EMBEDDING_DIM)
        when (rawOutput) {
            is Array<*> -> {
                val first = (rawOutput as Array<*>)[0]
                if (first is FloatArray) {
                    System.arraycopy(first, 0, result, 0, minOf(first.size, EMBEDDING_DIM))
                }
            }
        }
        return l2Normalize(result)
    }

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
        // Shared session is kept alive for app lifecycle, but can be cleared if needed
    }
}
