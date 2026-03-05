package com.autonion.automationcompanion.features.screen_understanding_ml.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.util.UUID

class PerceptionLayer(private val context: Context) {

    private companion object {
        private const val TAG = "PerceptionLayer"
    }

    private var interpreter: Interpreter? = null
    private val modelFilename = "best_int8.tflite"
    private val labels = listOf("button", "icon", "input", "toggle", "radio", "checkbox", "dropdown")
    
    // Model specific constants
    private val inputSize = 512
    private val confThreshold = 0.25f

    init {
        setupInterpreter()
    }

    private fun setupInterpreter() {
        // INT8 quantized models don't work with GPU delegate.
        // Try NNAPI for hardware acceleration, fall back to CPU.
        try {
            val nnapiOptions = Interpreter.Options().apply {
                setUseNNAPI(true)
            }
            val modelFile = loadModelFile(context, modelFilename)
            interpreter = Interpreter(modelFile, nnapiOptions)
            Log.i(TAG, "Model loaded with NNAPI delegate")
            DebugLogger.success(
                context, LogCategory.SCREEN_CONTEXT_AI,
                "ML model loaded",
                "TFLite interpreter ready (NNAPI) for UI detection",
                "PerceptionLayer"
            )
        } catch (e: Exception) {
            Log.w(TAG, "NNAPI delegate failed, falling back to CPU", e)
            try {
                val cpuOptions = Interpreter.Options().apply {
                    setNumThreads(4)
                }
                val modelFile = loadModelFile(context, modelFilename)
                interpreter = Interpreter(modelFile, cpuOptions)
                Log.i(TAG, "Model loaded with CPU (4 threads)")
                DebugLogger.success(
                    context, LogCategory.SCREEN_CONTEXT_AI,
                    "ML model loaded",
                    "TFLite interpreter ready (CPU, 4 threads) for UI detection",
                    "PerceptionLayer"
                )
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to load model on CPU", e2)
                DebugLogger.error(
                    context, LogCategory.SCREEN_CONTEXT_AI,
                    "ML model load failed",
                    "${e2.message}",
                    "PerceptionLayer"
                )
            }
        }
    }

    @Throws(java.io.IOException::class)
    private fun loadModelFile(context: Context, modelFilename: String): java.nio.MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelFilename)
        val inputStream = java.io.FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private val lock = Any()
    private var isClosed = false

    // Inference timing stats
    private var inferenceCount = 0L
    private var totalInferenceTimeNs = 0L

    /** Average inference time in milliseconds across all calls */
    fun getAverageInferenceTimeMs(): Float {
        return if (inferenceCount > 0) (totalInferenceTimeNs / 1_000_000f) / inferenceCount else 0f
    }

    /** Total number of inference calls */
    fun getInferenceCount(): Long = inferenceCount

    fun detect(bitmap: Bitmap): List<UIElement> {
        synchronized(lock) {
            if (isClosed || interpreter == null) return emptyList()

            val frameStartNs = android.os.SystemClock.elapsedRealtimeNanos()
            
            // 1. Preprocess — always use FLOAT32 input; TFLite handles quantization
            val imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(0f, 255f))
                .build()
    
            var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
            tensorImage.load(bitmap)
            tensorImage = imageProcessor.process(tensorImage)

            val preprocessNs = android.os.SystemClock.elapsedRealtimeNanos() - frameStartNs
    
            // 2. Inference — support both float and quantized output
            val inferenceStartNs = android.os.SystemClock.elapsedRealtimeNanos()
            val outputTensor = interpreter!!.getOutputTensor(0)
            val outputShape = outputTensor.shape() 
            val outputType = outputTensor.dataType()
            
            if (outputShape.size != 3) {
                android.util.Log.e(TAG, "Unexpected output rank: ${outputShape.size}")
                return emptyList()
            }

            // Allocate output buffer matching the tensor's data type
            val outputData: Array<Array<FloatArray>>
            
            try {
                if (outputType == org.tensorflow.lite.DataType.FLOAT32) {
                    // Float model — direct read
                    val floatOutput = Array(outputShape[0]) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
                    interpreter!!.run(tensorImage.buffer, floatOutput)
                    outputData = floatOutput
                } else {
                    // Quantized model (INT8/UINT8) — read bytes, then dequantize
                    val quantParams = outputTensor.quantizationParams()
                    val scale = quantParams.scale
                    val zeroPoint = quantParams.zeroPoint
                    
                    val byteOutput = Array(outputShape[0]) { Array(outputShape[1]) { ByteArray(outputShape[2]) } }
                    interpreter!!.run(tensorImage.buffer, byteOutput)
                    
                    // Dequantize: float = (byte_val - zeroPoint) * scale
                    outputData = Array(outputShape[0]) { b ->
                        Array(outputShape[1]) { c ->
                            FloatArray(outputShape[2]) { a ->
                                ((byteOutput[b][c][a].toInt() and 0xFF) - zeroPoint) * scale
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error running inference", e)
                return emptyList()
            }

            val inferenceNs = android.os.SystemClock.elapsedRealtimeNanos() - inferenceStartNs
    
            // 3. Postprocess
            val postStartNs = android.os.SystemClock.elapsedRealtimeNanos()
            val results = processOutput(outputData, bitmap.width, bitmap.height)
            val postprocessNs = android.os.SystemClock.elapsedRealtimeNanos() - postStartNs

            // Track timing
            val totalNs = android.os.SystemClock.elapsedRealtimeNanos() - frameStartNs
            inferenceCount++
            totalInferenceTimeNs += totalNs
            Log.d(TAG, "Inference #$inferenceCount: " +
                "preprocess=${"%.0f".format(preprocessNs / 1_000_000f)}ms, " +
                "inference=${"%.0f".format(inferenceNs / 1_000_000f)}ms, " +
                "postprocess=${"%.0f".format(postprocessNs / 1_000_000f)}ms, " +
                "TOTAL=${"%.0f".format(totalNs / 1_000_000f)}ms (avg: ${"%.1f".format(getAverageInferenceTimeMs())}ms), " +
                "${results.size} elements")

            return results
        }
    }

    private fun processOutput(output: Array<Array<FloatArray>>, imgWidth: Int, imgHeight: Int): List<UIElement> {
        val elements = mutableListOf<UIElement>()
        val outputData = output[0] // Shape: [numChannels, numAnchors]
        
        val numChannels = outputData.size
        val numAnchors = outputData[0].size

        android.util.Log.d("PerceptionLayer", "Output: Channels=$numChannels, Anchors=$numAnchors, Image=${imgWidth}x${imgHeight}")

        // Auto-detect coordinate format by sampling high-confidence detections
        // Normalized: all values typically < 2.0;  Pixel-space (640): values > 10
        var maxCx = 0f
        var maxCy = 0f
        var sampledCount = 0
        for (i in 0 until numAnchors) {
            var score = 0f
            for (c in 4 until numChannels) { score = maxOf(score, outputData[c][i]) }
            if (score > confThreshold) {
                maxCx = maxOf(maxCx, outputData[0][i])
                maxCy = maxOf(maxCy, outputData[1][i])
                sampledCount++
                if (sampledCount >= 20) break
            }
        }
        // If max coordinate is < 3.0, it's definitely normalized 
        // (pixel space at 640 input would have values in 10-640 range)
        val isNormalized = maxCx < 3.0f && maxCy < 3.0f
        android.util.Log.d("PerceptionLayer", "Coord format: maxCx=$maxCx, maxCy=$maxCy, sampled=$sampledCount → ${if (isNormalized) "NORMALIZED" else "PIXEL_SPACE"}")

        for (i in 0 until numAnchors) {
            // Channel layout: 0:cx, 1:cy, 2:w, 3:h, 4..N:class_scores
            var maxScore = 0f
            var classId = -1
            
            for (c in 4 until numChannels) {
                val score = outputData[c][i]
                if (score > maxScore) {
                    maxScore = score
                    classId = c - 4
                }
            }

            if (maxScore > confThreshold) {
                val cx = outputData[0][i]
                val cy = outputData[1][i]
                val w = outputData[2][i]
                val h = outputData[3][i]

                val left: Float
                val top: Float
                val right: Float
                val bottom: Float

                if (isNormalized) {
                    // Normalized (0-1) → multiply by image dimensions directly
                    left = (cx - w / 2) * imgWidth
                    top = (cy - h / 2) * imgHeight
                    right = (cx + w / 2) * imgWidth
                    bottom = (cy + h / 2) * imgHeight
                } else {
                    // Pixel space (0-inputSize) → scale from inputSize to image dimensions
                    val scaleX = imgWidth.toFloat() / inputSize.toFloat()
                    val scaleY = imgHeight.toFloat() / inputSize.toFloat()
                    left = (cx - w / 2) * scaleX
                    top = (cy - h / 2) * scaleY
                    right = (cx + w / 2) * scaleX
                    bottom = (cy + h / 2) * scaleY
                }

                if (classId in labels.indices) {
                    elements.add(
                        UIElement(
                            id = UUID.randomUUID().toString(),
                            label = labels[classId],
                            confidence = maxScore,
                            bounds = RectF(left, top, right, bottom)
                        )
                    )
                }
            }
        }
        
        android.util.Log.d("PerceptionLayer", "Found ${elements.size} candidates before NMS")
        
        // B7 fix: Per-class NMS to avoid suppressing across different classes
        val nmsElements = perClassNMS(elements)
        android.util.Log.d("PerceptionLayer", "After NMS: ${nmsElements.size} elements")
        if (nmsElements.isNotEmpty()) {
            val first = nmsElements[0]
            android.util.Log.d("PerceptionLayer", "Sample element: label=${first.label}, conf=${first.confidence}, bounds=${first.bounds}")
        }
        return nmsElements
    }

    /**
     * Performs NMS per-class to avoid a high-confidence "Button" suppressing
     * a lower-confidence "Input" that happens to overlap.
     */
    private fun perClassNMS(candidates: List<UIElement>, iouThreshold: Float = 0.45f): List<UIElement> {
        if (candidates.isEmpty()) return emptyList()
        
        val result = mutableListOf<UIElement>()
        // Group by class label, then NMS each group independently
        val grouped = candidates.groupBy { it.label }
        for ((_, classCandidates) in grouped) {
            result.addAll(simpleNMS(classCandidates, iouThreshold))
        }
        return result
    }

    private fun simpleNMS(candidates: List<UIElement>, iouThreshold: Float = 0.45f): List<UIElement> {
        if (candidates.isEmpty()) return emptyList()
        
        val sorted = candidates.sortedByDescending { it.confidence }
        val selected = mutableListOf<UIElement>()
        
        for (candidate in sorted) {
            var shouldSelect = true
            for (existing in selected) {
                if (calculateIoU(candidate.bounds, existing.bounds) > iouThreshold) {
                    shouldSelect = false
                    break
                }
            }
            if (shouldSelect) {
                selected.add(candidate)
            }
        }
        return selected
    }
    
    private fun calculateIoU(boxA: RectF, boxB: RectF): Float {
        val xA = maxOf(boxA.left, boxB.left)
        val yA = maxOf(boxA.top, boxB.top)
        val xB = minOf(boxA.right, boxB.right)
        val yB = minOf(boxA.bottom, boxB.bottom) 

        val interArea = maxOf(0f, xB - xA) * maxOf(0f, yB - yA)
        val boxAArea = (boxA.right - boxA.left) * (boxA.bottom - boxA.top)
        val boxBArea = (boxB.right - boxB.left) * (boxB.bottom - boxB.top)

        val unionArea = boxAArea + boxBArea - interArea
        return if (unionArea > 0) interArea / unionArea else 0f
    }

    /**
     * Run UI element detection (TFLite) and enrich results with OCR (ML Kit).
     *
     * For each detected UIElement, if an OCR text block overlaps its bounds
     * (IoU > threshold), the recognized text is written to [UIElement.text].
     *
     * Call this instead of [detect] when you need text content alongside
     * element classifications.
     */
    suspend fun detectWithOcr(bitmap: Bitmap): List<UIElement> {
        // 1. Run standard detection
        val elements = detect(bitmap)
        if (elements.isEmpty()) return elements

        // 2. Run ML Kit OCR
        val ocrEngine = OcrEngine()
        try {
            val ocrResult = ocrEngine.recognizeText(bitmap)
            if (ocrResult.blocks.isEmpty()) return elements

            Log.d(TAG, "OCR found ${ocrResult.blocks.size} text blocks to match against ${elements.size} elements")

            // 3. For each element, find overlapping OCR text
            return elements.map { element ->
                val matchingTexts = ocrResult.blocks
                    .filter { block ->
                        block.bounds != null && calculateIoU(element.bounds, block.bounds) > 0.05f
                    }
                    .sortedByDescending { block ->
                        calculateIoU(element.bounds, block.bounds!!)
                    }

                if (matchingTexts.isNotEmpty()) {
                    val text = matchingTexts.joinToString(" ") { it.text }
                    element.copy(text = text)
                } else {
                    element
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OCR enrichment failed, returning elements without text", e)
            return elements
        } finally {
            ocrEngine.close()
        }
    }

    fun close() {
        synchronized(lock) {
            isClosed = true
            interpreter?.close()
            interpreter = null
        }
    }
}
