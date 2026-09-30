package com.autonion.automationcompanion.features.screen_understanding_ml.core

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.autonion.automationcompanion.features.screen_understanding_ml.model.OcrBlock
import com.autonion.automationcompanion.features.screen_understanding_ml.model.OcrLine
import com.autonion.automationcompanion.features.screen_understanding_ml.model.OcrResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.suspendCoroutine
import java.util.concurrent.Executor

private const val TAG = "OcrEngine"

/**
 * On-device OCR engine powered by Google ML Kit Text Recognition.
 *
 * Usage:
 * ```
 * val engine = OcrEngine()
 * val result = engine.recognizeText(bitmap)
 * Log.d("OCR", "Full text: ${result.fullText}")
 * engine.close()
 * ```
 *
 * Thread-safe and coroutine-friendly. Reusable — create once, call many times.
 */
class OcrEngine {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val lock = Any()
    private var closed = false
    private var pending = 0
    private val completionExecutor = Executor { it.run() }

    /**
     * Run text recognition on the given bitmap.
     *
     * @return [OcrResult] containing the full text and structured blocks.
     */
    suspend fun recognizeText(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        // ML Kit cannot cancel an in-flight recognition. Keep the caller's bitmap alive
        // until completion, then withContext propagates cancellation before returning it.
        val visionText = suspendCoroutine { continuation ->
            val task = synchronized(lock) {
                check(!closed) { "OcrEngine is closed" }
                recognizer.process(inputImage).also { pending++ }
            }
            task.addOnCompleteListener(completionExecutor) { completed ->
                synchronized(lock) {
                    pending--
                    if (closed && pending == 0) recognizer.close()
                }
                if (completed.isSuccessful) continuation.resume(completed.result)
                else continuation.resumeWithException(completed.exception ?: IllegalStateException("OCR was cancelled"))
            }
        }
        currentCoroutineContext().ensureActive()
        val blocks = visionText.textBlocks.map { block ->
            OcrBlock(
                text = block.text,
                bounds = block.boundingBox?.let { r ->
                    RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
                },
                lines = block.lines.map { line ->
                    OcrLine(
                        text = line.text,
                        bounds = line.boundingBox?.let { r ->
                            RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
                        },
                        confidence = line.confidence
                    )
                },
                confidence = block.lines.map { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
            )
        }

        val result = OcrResult(
            fullText = visionText.text,
            blocks = blocks
        )
        Log.d(TAG, "OCR complete: ${blocks.size} blocks, ${result.fullText.length} chars")
        result
    }

    /**
     * Release the recognizer resources.
     */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            if (pending == 0) recognizer.close()
        }
        Log.d(TAG, "OcrEngine closed")
    }
}
