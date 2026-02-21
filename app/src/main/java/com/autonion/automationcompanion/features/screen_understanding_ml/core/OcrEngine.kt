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
import kotlinx.coroutines.suspendCancellableCoroutine

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

    /**
     * Run text recognition on the given bitmap.
     *
     * @return [OcrResult] containing the full text and structured blocks.
     */
    suspend fun recognizeText(bitmap: Bitmap): OcrResult {
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        return suspendCancellableCoroutine { continuation ->
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
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
                            confidence = block.lines.firstOrNull()?.confidence
                        )
                    }

                    val result = OcrResult(
                        fullText = visionText.text,
                        blocks = blocks
                    )
                    Log.d(TAG, "OCR complete: ${blocks.size} blocks, ${result.fullText.length} chars")
                    continuation.resume(result)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed", e)
                    continuation.resumeWithException(e)
                }
        }
    }

    /**
     * Release the recognizer resources.
     */
    fun close() {
        recognizer.close()
        Log.d(TAG, "OcrEngine closed")
    }
}
