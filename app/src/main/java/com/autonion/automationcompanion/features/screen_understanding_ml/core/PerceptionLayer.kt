package com.autonion.automationcompanion.features.screen_understanding_ml.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.util.UUID

class PerceptionLayer(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val modelFilename = "best_float16.tflite"
    private val labels = listOf("Button", "Input", "Image", "Toggle", "Text")
    
    // Model specific constants
    private val inputSize = 640 
    private val confThreshold = 0.25f

    private var gpuDelegate: GpuDelegate? = null

    init {
        setupInterpreter()
    }

    private fun setupInterpreter() {
        // Try GPU first, fall back to CPU cleanly
        try {
            val delegate = GpuDelegate()
            val gpuOptions = Interpreter.Options().apply {
                addDelegate(delegate)
            }
            val modelFile = loadModelFile(context, modelFilename)
            interpreter = Interpreter(modelFile, gpuOptions)
            gpuDelegate = delegate
            android.util.Log.d("PerceptionLayer", "Model loaded with GPU delegate")
        } catch (e: Exception) {
            android.util.Log.w("PerceptionLayer", "GPU delegate failed, falling back to CPU", e)
            gpuDelegate?.close()
            gpuDelegate = null
            try {
                val cpuOptions = Interpreter.Options().apply {
                    setNumThreads(4)
                }
                val modelFile = loadModelFile(context, modelFilename)
                interpreter = Interpreter(modelFile, cpuOptions)
                android.util.Log.d("PerceptionLayer", "Model loaded with CPU (4 threads)")
            } catch (e2: Exception) {
                android.util.Log.e("PerceptionLayer", "Error loading model", e2)
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

    fun detect(bitmap: Bitmap): List<UIElement> {
        synchronized(lock) {
            if (isClosed || interpreter == null) return emptyList()
            
            // 1. Preprocess
            val imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(0f, 255f))
                .build()
    
            var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
            tensorImage.load(bitmap)
            tensorImage = imageProcessor.process(tensorImage)
    
            // 2. Inference
            val outputTensor = interpreter!!.getOutputTensor(0)
            val outputShape = outputTensor.shape() 
            
            // Create output array — handle different output ranks safely
            val outputArray = if (outputShape.size == 3) {
                 Array(outputShape[0]) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
            } else {
                 android.util.Log.e("PerceptionLayer", "Unexpected output rank: ${outputShape.size}")
                 return emptyList()
            }
    
            try {
                interpreter!!.run(tensorImage.buffer, outputArray)
            } catch (e: Exception) {
                android.util.Log.e("PerceptionLayer", "Error running inference", e)
                return emptyList()
            }
    
            // 3. Postprocess
            return processOutput(outputArray, bitmap.width, bitmap.height)
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

    fun close() {
        synchronized(lock) {
            isClosed = true
            interpreter?.close()
            interpreter = null
            gpuDelegate?.close()
            gpuDelegate = null
        }
    }
}
