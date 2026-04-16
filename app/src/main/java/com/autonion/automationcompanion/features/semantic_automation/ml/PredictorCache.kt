package com.autonion.automationcompanion.features.semantic_automation.ml

import android.content.Context
import android.util.Log

/**
 * Global cache to hold heavy ML objects (MLActionPredictor, OnDeviceSLMEngine)
 * so they survive SemanticAutomationService teardown and don't need
 * to be reloaded from disk on every task.
 */
object PredictorCache {
    private const val TAG = "PredictorCache"

    @Volatile
    private var mlPredictor: MLActionPredictor? = null

    @Volatile
    private var slmEngine: OnDeviceSLMEngine? = null

    fun getMLPredictor(context: Context): MLActionPredictor? {
        if (mlPredictor == null) {
            synchronized(this) {
                if (mlPredictor == null) {
                    try {
                        mlPredictor = MLActionPredictor(context.applicationContext)
                        Log.d(TAG, "Initialized global MLActionPredictor")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load MLActionPredictor", e)
                    }
                }
            }
        }
        return mlPredictor
    }

    suspend fun getSLMEngine(context: Context, storageManager: ModelStorageManager): OnDeviceSLMEngine? {
        if (slmEngine == null) {
            synchronized(this) {
                if (slmEngine == null) {
                    try {
                        slmEngine = OnDeviceSLMEngine(context.applicationContext, storageManager)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to instantiate OnDeviceSLMEngine", e)
                    }
                }
            }
            if (slmEngine != null) {
                try {
                    slmEngine?.initialize()
                    Log.d(TAG, "Initialized global OnDeviceSLMEngine")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize OnDeviceSLMEngine", e)
                    slmEngine = null
                }
            }
        }
        return slmEngine
    }

    /**
     * Unloads models from memory.
     */
    fun disconnect() {
        Log.d(TAG, "Explicitly disconnecting and unloading models from RAM")
        mlPredictor?.close()
        mlPredictor = null
        slmEngine?.close()
        slmEngine = null
    }
}
