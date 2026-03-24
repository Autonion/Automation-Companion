package com.autonion.automationcompanion.features.semantic_automation.ml

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.PointF
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionIntent
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import com.autonion.automationcompanion.features.semantic_automation.model.ScreenUIState
import com.autonion.automationcompanion.features.semantic_automation.model.SemanticGoal
import com.autonion.automationcompanion.features.semantic_automation.model.UIStateElement
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * ML-based Action Predictor using the trained TFLite transformer model.
 *
 * Replaces the rule-based [ActionPredictor] with learned inference from the
 * AMEX-trained model. Uses [SentenceEmbedder] to produce 384-dim MiniLM
 * embeddings for both the goal text and each UI element's text.
 *
 * ## Model I/O
 * **Inputs:**
 * - goal_embedding: [1, 384]
 * - element_features: [1, 390, 50]  (transposed! each column = one element)
 * - element_masks: [1, 50]
 *
 * **Outputs:**
 * - action_logits: [1, 6]  → {NONE, CLICK, TYPE, SWIPE, PRESS_ENTER, TASK_COMPLETE}
 * - pointer_logits: [1, 50] → index of target element
 */
class MLActionPredictor(private val context: Context) {

    companion object {
        private const val TAG = "MLActionPred"
        private const val MODEL_ASSET = "action_predictor_model_sim_float16.tflite"
        private const val MAX_ELEMENTS = 50
        private const val FEATURE_DIM = 390  // 4 bounds + 2 flags + 384 embedding
        private const val EMBEDDING_DIM = 384
        private const val NUM_ACTIONS = 6
    }

    private val sentenceEmbedder = SentenceEmbedder(context)
    private val interpreter: Interpreter
    private val screenWidth: Float
    private val screenHeight: Float

    init {
        // Load TFLite model
        val modelBuffer = loadModelFile(MODEL_ASSET)
        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }
        interpreter = Interpreter(modelBuffer, options)
        Log.d(TAG, "TFLite Action Predictor loaded")

        // Log input/output tensor shapes
        for (i in 0 until interpreter.inputTensorCount) {
            val t = interpreter.getInputTensor(i)
            Log.d(TAG, "  Input[$i] name=${t.name()} shape=${t.shape().contentToString()} dtype=${t.dataType()}")
        }
        for (i in 0 until interpreter.outputTensorCount) {
            val t = interpreter.getOutputTensor(i)
            Log.d(TAG, "  Output[$i] name=${t.name()} shape=${t.shape().contentToString()} dtype=${t.dataType()}")
        }

        // Get screen dimensions for normalizing bounds
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels.toFloat()
        screenHeight = metrics.heightPixels.toFloat()
        Log.d(TAG, "Screen: ${screenWidth}x${screenHeight}")
    }

    /**
     * Predict the next action given a semantic goal and UI state.
     * Returns an [ActionIntent] or null if the model predicts NONE.
     */
    fun predict(goal: SemanticGoal, uiState: ScreenUIState): ActionIntent? {
        val elements = uiState.elements.take(MAX_ELEMENTS)
        val numElements = elements.size

        if (numElements == 0) {
            Log.w(TAG, "No elements — cannot predict")
            return null
        }

        val startTime = System.currentTimeMillis()

        // ── 1. Encode goal text ──
        val goalText = goal.rawCommand
        val goalEmbedding = sentenceEmbedder.encode(goalText)
        Log.d(TAG, "Goal embedding computed in ${System.currentTimeMillis() - startTime}ms")

        // ── 2. Build element features [1, 390, 50] (transposed!) ──
        //   Column j = element j's features
        //   Row layout: [left, top, right, bottom, clickable, scrollable, emb[0..383]]
        val elementFeatures = ByteBuffer.allocateDirect(1 * FEATURE_DIM * MAX_ELEMENTS * 4)
            .order(ByteOrder.nativeOrder())

        // Pre-compute element embeddings using the EXACT string format from training data
        val elementEmbeddings = Array(numElements) { i ->
            val el = elements[i]
            val textToEmbed = "type=${el.type} text='${el.text}' " +
                    "clickable=${el.isClickable} checkable=${el.isChecked != null} " +
                    "checked=${el.isChecked} editable=${el.isEditable} " +
                    "class=${el.className} bounds=${el.bounds}"
            sentenceEmbedder.encode(textToEmbed)
        }

        val embedTime = System.currentTimeMillis()
        Log.d(TAG, "Element embeddings ($numElements) computed in ${embedTime - startTime}ms")

        // Fill the transposed tensor: shape [1, 390, 50]
        // Memory layout: for feat in 0..389, for elem in 0..49
        for (feat in 0 until FEATURE_DIM) {
            for (elem in 0 until MAX_ELEMENTS) {
                val value = if (elem < numElements) {
                    getFeatureValue(elements[elem], elementEmbeddings[elem], feat)
                } else {
                    0f
                }
                elementFeatures.putFloat(value)
            }
        }
        elementFeatures.rewind()

        // ── 3. Build element masks [1, 50] ──
        val elementMasks = ByteBuffer.allocateDirect(1 * MAX_ELEMENTS * 4)
            .order(ByteOrder.nativeOrder())
        for (i in 0 until MAX_ELEMENTS) {
            elementMasks.putFloat(if (i < numElements) 1f else 0f)
        }
        elementMasks.rewind()

        // ── 4. Build goal embedding buffer [1, 384] ──
        val goalBuffer = ByteBuffer.allocateDirect(1 * EMBEDDING_DIM * 4)
            .order(ByteOrder.nativeOrder())
        for (v in goalEmbedding) goalBuffer.putFloat(v)
        goalBuffer.rewind()

        // ── 5. Run TFLite inference ──
        // Map inputs by index (order matches model signature)
        val inputMap = mapOf(
            0 to goalBuffer,
            1 to elementFeatures,
            2 to elementMasks
        )

        val actionLogits = Array(1) { FloatArray(NUM_ACTIONS) }
        val pointerLogits = Array(1) { FloatArray(MAX_ELEMENTS) }

        val outputMap = mapOf(
            0 to actionLogits,
            1 to pointerLogits
        )

        interpreter.runForMultipleInputsOutputs(
            arrayOf(goalBuffer, elementFeatures, elementMasks),
            outputMap
        )

        val inferTime = System.currentTimeMillis()
        Log.d(TAG, "Inference completed in ${inferTime - embedTime}ms (total: ${inferTime - startTime}ms)")

        // ── 6. Parse outputs ──
        // Mathematically inject a Zero-Shot Semantic Prior into the logits to overcome Behavioral Cloning Dataset Bias.
        val isSearchGoal = goal.task?.equals("search", ignoreCase = true) == true
        val queryText = goal.query ?: ""

        var hasVideoResults = false

        for (i in 0 until numElements) {
            val el = uiState.elements[i]
            val textRaw = el.text ?: ""
            val sim = dotProduct(goalEmbedding, elementEmbeddings[i])
            
            // Anti-Loop for Text Fields: If the field is editable and ALREADY contains the query, heavily penalize it!
            val isFilledInput = el.isEditable && queryText.isNotBlank() && textRaw.contains(queryText, ignoreCase = true)
            
            // Check if this element looks like a YouTube video/search result
            val isVideoResult = !el.isEditable && (textRaw.contains(" views") || textRaw.contains(" ago") || textRaw.contains("play Short"))
            if (isVideoResult) hasVideoResults = true

            if (isFilledInput) {
                pointerLogits[0][i] -= 20.0f // Heavy penalty to stop typing loop
            } else if (isSearchGoal && isVideoResult) {
                pointerLogits[0][i] -= 20.0f // Heavy penalty to prevent clicking videos when goal is just "search"
            } else {
                pointerLogits[0][i] += sim * 8.0f // Boost matching elements significantly
            }
        }

        // If goal is just "search" and we found video results, the task is effectively complete!
        // Boost the TASK_COMPLETE action logit to force the model to stop gracefully.
        if (isSearchGoal && hasVideoResults) {
            Log.d(TAG, "Search results detected. Boosting FINISH action logit.")
            actionLogits[0][5] += 50.0f
        }

        val actionIdx = argmax(actionLogits[0])
        val pointerIdx = argmax(pointerLogits[0])

        Log.d(TAG, "Action logits: ${actionLogits[0].contentToString()}")
        Log.d(TAG, "Pointer logits (top5): ${pointerLogits[0].take(numElements).joinToString()}")
        Log.d(TAG, "Predicted: action=$actionIdx, pointer=$pointerIdx")

        // ── 7. Map to ActionIntent ──
        return mapToActionIntent(actionIdx, pointerIdx, elements, goal)
    }

    /**
     * Get the feature value at a specific index for an element.
     * Layout: [0]=left, [1]=top, [2]=right, [3]=bottom, [4]=clickable, [5]=scrollable, [6..389]=embedding
     */
    private fun getFeatureValue(element: UIStateElement, embedding: FloatArray, featIdx: Int): Float {
        return when (featIdx) {
            0 -> element.bounds.top / screenHeight    // ymin (TensorFlow / AitW format)
            1 -> element.bounds.left / screenWidth    // xmin
            2 -> element.bounds.bottom / screenHeight // ymax
            3 -> element.bounds.right / screenWidth   // xmax
            4 -> if (element.isClickable) 1f else 0f
            5 -> if (element.isScrollable) 1f else 0f
            else -> {
                val embIdx = featIdx - 6
                if (embIdx < embedding.size) embedding[embIdx] else 0f
            }
        }
    }

    private fun dotProduct(vec1: FloatArray, vec2: FloatArray): Float {
        var dot = 0f
        for (i in vec1.indices) {
            dot += vec1[i] * vec2[i]
        }
        return dot
    }

    /**
     * Map model output indices to ActionIntent.
     *
     * Model action mapping:
     * 0: NONE, 1: CLICK, 2: TYPE, 3: SWIPE, 4: PRESS_ENTER, 5: TASK_COMPLETE
     */
    private fun mapToActionIntent(
        actionIdx: Int,
        pointerIdx: Int,
        elements: List<UIStateElement>,
        goal: SemanticGoal
    ): ActionIntent? {
        val targetElement = if (pointerIdx < elements.size) elements[pointerIdx] else null
        val targetPoint = targetElement?.let { centerOf(it) }

        return when (actionIdx) {
            0 -> {
                // NONE — model couldn't decide
                Log.d(TAG, "Model predicted NONE")
                null
            }
            1 -> {
                // CLICK / TAP
                // Upgrade: If model predicts CLICK on a text field, but we have text to type,
                // execute INPUT_TEXT directly to avoid infinite single-frame focus loops.
                if (targetElement?.isEditable == true && !goal.query.isNullOrBlank()) {
                    val currentText = targetElement.text ?: ""
                    if (!currentText.contains(goal.query)) {
                        Log.d(TAG, "Upgrading ML CLICK on editable field to INPUT_TEXT")
                        ActionIntent(
                            type = ActionType.INPUT_TEXT,
                            targetPoint = targetPoint ?: PointF(540f, 400f),
                            inputText = goal.query,
                            description = "ML (Upgraded): Type '${goal.query}' into '${targetElement.text}'"
                        )
                    } else {
                        Log.d(TAG, "Text already typed, converting ML CLICK to CLICK to submit")
                        ActionIntent(
                            type = ActionType.CLICK,
                            targetPoint = targetPoint ?: PointF(540f, 1200f),
                            description = "ML: Submit typed text '${goal.query}'"
                        )
                    }
                } else {
                    ActionIntent(
                        type = ActionType.CLICK,
                        targetPoint = targetPoint ?: PointF(540f, 1200f),
                        description = "ML: Click '${targetElement?.text ?: "element $pointerIdx"}'"
                    )
                }
            }
            2 -> {
                // TYPE — click the target input field and type the query
                ActionIntent(
                    type = ActionType.INPUT_TEXT,
                    targetPoint = targetPoint ?: PointF(540f, 400f),
                    inputText = goal.query ?: goal.rawCommand,
                    description = "ML: Type '${goal.query ?: goal.rawCommand}' into '${targetElement?.text ?: "input"}'"
                )
            }
            3 -> {
                // SWIPE / SCROLL
                ActionIntent(
                    type = ActionType.SCROLL_DOWN,
                    targetPoint = targetPoint ?: PointF(540f, 1200f),
                    description = "ML: Scroll at '${targetElement?.text ?: "screen center"}'"
                )
            }
            4 -> {
                // PRESS_ENTER — submit / confirm
                ActionIntent(
                    type = ActionType.CLICK,
                    targetPoint = targetPoint ?: PointF(540f, 1200f),
                    description = "ML: Press enter / submit at '${targetElement?.text ?: "element"}'"
                )
            }
            5 -> {
                // TASK_COMPLETE
                ActionIntent(
                    type = ActionType.FINISH,
                    description = "ML: Task completed"
                )
            }
            else -> {
                Log.w(TAG, "Unknown action index: $actionIdx")
                null
            }
        }
    }

    private fun centerOf(el: UIStateElement): PointF {
        return PointF(
            (el.bounds.left + el.bounds.right) / 2f,
            (el.bounds.top + el.bounds.bottom) / 2f
        )
    }

    private fun argmax(arr: FloatArray): Int {
        var maxIdx = 0
        var maxVal = arr[0]
        for (i in 1 until arr.size) {
            if (arr[i] > maxVal) {
                maxVal = arr[i]
                maxIdx = i
            }
        }
        return maxIdx
    }

    private fun loadModelFile(assetName: String): MappedByteBuffer {
        val fd: AssetFileDescriptor = context.assets.openFd(assetName)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    fun close() {
        try {
            interpreter.close()
            sentenceEmbedder.close()
            Log.d(TAG, "ML predictor closed")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ML predictor", e)
        }
    }
}
