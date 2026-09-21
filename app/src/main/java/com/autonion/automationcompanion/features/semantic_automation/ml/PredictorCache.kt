package com.autonion.automationcompanion.features.semantic_automation.ml

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SlmLoadPhase { UNLOADED, LOADING, READY, FAILED }

data class SlmLoadState(
    val modelPath: String? = null,
    val phase: SlmLoadPhase = SlmLoadPhase.UNLOADED,
    val error: String? = null
) {
    fun isReadyFor(path: String?) = path != null && modelPath == path && phase == SlmLoadPhase.READY
    val label: String get() = when (phase) {
        SlmLoadPhase.UNLOADED -> "On-device model not loaded"
        SlmLoadPhase.LOADING -> "Loading on-device model…"
        SlmLoadPhase.READY -> "On-device model ready"
        SlmLoadPhase.FAILED -> error ?: "On-device model could not load"
    }
}

/** Owns model lifetime; an engine is published only after initialization finishes. */
object PredictorCache {
    private const val TAG = "PredictorCache"
    private val initializationMutex = Mutex()
    private val mutableSlmState = MutableStateFlow(SlmLoadState())
    val slmState = mutableSlmState.asStateFlow()
    @Volatile private var mlPredictor: MLActionPredictor? = null
    private var slmEngine: OnDeviceSLMEngine? = null

    @Synchronized
    fun getMLPredictor(context: Context): MLActionPredictor? {
        if (mlPredictor == null) {
            try {
                mlPredictor = MLActionPredictor(context.applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load MLActionPredictor", e)
            }
        }
        return mlPredictor
    }

    suspend fun getSLMEngine(context: Context, storageManager: ModelStorageManager): OnDeviceSLMEngine? =
        initializationMutex.withLock {
            val path = storageManager.getActiveModelPath()
            slmEngine?.takeUnless { it.needsReinitialization() }?.let { return@withLock it }
            mutableSlmState.value = SlmLoadState(path, SlmLoadPhase.LOADING)
            try {
                slmEngine?.closeAndAwait()
            } catch (e: CancellationException) {
                slmEngine = null
                mutableSlmState.value = SlmLoadState()
                throw e
            }
            slmEngine = null
            if (path == null) {
                mutableSlmState.value = SlmLoadState()
                return@withLock null
            }
            val candidate = OnDeviceSLMEngine(context.applicationContext, storageManager, path)
            try {
                candidate.initialize()
                if (candidate.needsReinitialization()) {
                    candidate.closeAndAwait()
                    mutableSlmState.value = SlmLoadState()
                    return@withLock null
                }
                slmEngine = candidate
                mutableSlmState.value = SlmLoadState(path, SlmLoadPhase.READY)
                candidate
            } catch (e: CancellationException) {
                withContext(NonCancellable) { candidate.closeAndAwait() }
                mutableSlmState.value = SlmLoadState()
                throw e
            } catch (e: Exception) {
                candidate.closeAndAwait()
                Log.e(TAG, "Failed to initialize on-device model", e)
                mutableSlmState.value = SlmLoadState(path, SlmLoadPhase.FAILED, e.message)
                null
            }
        }

    suspend fun disconnect() = initializationMutex.withLock {
        slmEngine?.closeAndAwait()
        slmEngine = null
        mutableSlmState.value = SlmLoadState()
        synchronized(this) {
            mlPredictor?.close()
            mlPredictor = null
        }
    }
}
