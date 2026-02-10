package com.autonion.automationcompanion.features.screen_understanding_ml.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer

class PerceptionLayer(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val modelFilename = "best_float16.tflite"
    private val labels = listOf("Button", "Input", "Image", "Toggle", "Text")
    
    // Model specific constants (Need to be verified with actual model)
    private val inputSize = 640 
    private val confThreshold = 0.25f

    init {
        setupInterpreter()
    }

    private fun setupInterpreter() {
        val options = Interpreter.Options()
        // Attempt to use GPU Delegate safely
        try {
            options.addDelegate(GpuDelegate())
        } catch (e: Exception) {
            // Fallback to CPU if GPU delegate fails or isn't supported
            options.setNumThreads(4)
        }

        try {
            val modelFile = loadModelFile(context, modelFilename)
            interpreter = Interpreter(modelFile, options)
            android.util.Log.d("PerceptionLayer", "Model loaded successfully")
        } catch (e: Exception) {
            android.util.Log.e("PerceptionLayer", "Error loading model", e)
            e.printStackTrace()
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
                .add(NormalizeOp(0f, 255f)) // Normalize to [0, 1] if needed, depends on model
                .build()
    
            var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
            tensorImage.load(bitmap)
            tensorImage = imageProcessor.process(tensorImage)
    
            // 2. Inference
            val outputTensor = interpreter!!.getOutputTensor(0)
            val outputShape = outputTensor.shape() 
            // android.util.Log.d("PerceptionLayer", "Output Tensor Shape: ${outputShape.joinToString()}")
            
            // Create output array
            // Handle different output ranks safely
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
        val outputData = output[0] // Shape: [9, 8400] (Channels, Anchors)
        
        val numChannels = outputData.size // 9
        val numAnchors = outputData[0].size // 8400
        
        // android.util.Log.d("PerceptionLayer", "Processing output: Channels=$numChannels, Anchors=$numAnchors")

        // Iterate through all anchors (columns)
        for (i in 0 until numAnchors) {
            // Channel layout: 0:cx, 1:cy, 2:w, 3:h, 4..N:class_scores
            
            // Get class scores (indices 4 to 8)
            var maxScore = 0f
            var classId = -1
            
            // outputData[channel][anchor_index]
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

                val left = (cx - w / 2) * imgWidth
                val top = (cy - h / 2) * imgHeight
                val right = (cx + w / 2) * imgWidth
                val bottom = (cy + h / 2) * imgHeight

                if (classId in labels.indices) {
                    elements.add(
                        UIElement(
                            id = java.util.UUID.randomUUID().toString(),
                            label = labels[classId],
                            confidence = maxScore,
                            bounds = RectF(left, top, right, bottom)
                        )
                    )
                }
            }
        }
        
        // NMS (Non-Maximum Suppression) would go here
        // Simple NMS for now to reduce clutter
        val nmsElements = simpleNMS(elements)
        
        // android.util.Log.d("PerceptionLayer", "Found ${elements.size} candidates, ${nmsElements.size} after NMS")
        return nmsElements
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

        return interArea / (boxAArea + boxBArea - interArea)
    }

    fun close() {
        synchronized(lock) {
            isClosed = true
            interpreter?.close()
            interpreter = null
        }
    }
}
