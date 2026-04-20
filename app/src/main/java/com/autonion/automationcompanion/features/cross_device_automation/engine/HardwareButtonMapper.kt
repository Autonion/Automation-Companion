package com.autonion.automationcompanion.features.cross_device_automation.engine

import android.content.Context
import android.os.PowerManager
import android.view.KeyEvent
import android.util.Log
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.autonion.automationcompanion.features.cross_device_automation.domain.AutomationPrompt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class GestureType { SINGLE_TAP, DOUBLE_TAP, LONG_PRESS }

sealed class DesktopAction {
    data class SendKey(val keyName: String) : DesktopAction()
}

object HardwareButtonMapper {
    private const val TAG = "HardwareButtonMapper"
    private const val DOUBLE_TAP_TIMEOUT = 300L
    private const val LONG_PRESS_TIMEOUT = 500L

    private var activeMappings = mutableMapOf<Pair<Int, GestureType>, DesktopAction>()
    private var applicationContext: Context? = null
    
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    val currentMappings: Map<Pair<Int, GestureType>, DesktopAction> get() = activeMappings.toMap()

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // State for gesture detection
    private var lastKeyDownTime = 0L
    private var lastKeyUpTime = 0L
    private var currentKeyCode = 0
    private var longPressJob: Job? = null
    private var singleTapJob: Job? = null
    private var wasLongPressHandled = false

    fun activate(context: Context, mappings: Map<Pair<Int, GestureType>, DesktopAction>) {
        applicationContext = context.applicationContext
        activeMappings.clear()
        activeMappings.putAll(mappings)
        _isActive.value = true
        acquireWakeLock()
        Log.d(TAG, "Activated with mappings: $mappings")
    }

    fun deactivate() {
        activeMappings.clear()
        _isActive.value = false
        releaseWakeLock()
        cancelAllJobs()
        Log.d(TAG, "Deactivated")
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            applicationContext?.let { ctx ->
                val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AutomationCompanion:HardwareRemoteWakeLock"
                )
            }
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire() // no timeout, we hold it until deactivated
            Log.d(TAG, "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "WakeLock released")
        }
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!_isActive.value) return false

        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false // We only handle volume keys
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    handleActionDown(keyCode)
                }
            }
            KeyEvent.ACTION_UP -> {
                handleActionUp(keyCode)
            }
        }
        return true // Always consume volume keys when active
    }

    private fun handleActionDown(keyCode: Int) {
        val now = System.currentTimeMillis()
        
        // Check if this is the start of a double tap
        if (keyCode == currentKeyCode && (now - lastKeyUpTime) < DOUBLE_TAP_TIMEOUT && singleTapJob?.isActive == true) {
            // It's a double tap!
            singleTapJob?.cancel()
            cancelAllJobs() // Clean up
            executeAction(keyCode, GestureType.DOUBLE_TAP)
            return
        }

        // Fresh press
        currentKeyCode = keyCode
        lastKeyDownTime = now
        wasLongPressHandled = false
        cancelAllJobs()

        // Start long press timer
        longPressJob = scope.launch {
            delay(LONG_PRESS_TIMEOUT)
            wasLongPressHandled = true
            executeAction(keyCode, GestureType.LONG_PRESS)
        }
    }

    private fun handleActionUp(keyCode: Int) {
        if (keyCode != currentKeyCode) return
        
        lastKeyUpTime = System.currentTimeMillis()
        longPressJob?.cancel()

        if (!wasLongPressHandled) {
            // It was a short press. Start the single tap job, waiting to see if a double tap comes
            singleTapJob = scope.launch {
                delay(DOUBLE_TAP_TIMEOUT)
                executeAction(keyCode, GestureType.SINGLE_TAP)
            }
        }
    }

    private fun executeAction(keyCode: Int, gesture: GestureType) {
        Log.d(TAG, "Gesture detected: KeyCode=$keyCode, Gesture=$gesture")
        val action = activeMappings[Pair(keyCode, gesture)]
        if (action != null) {
            when (action) {
                is DesktopAction.SendKey -> {
                    broadcastKeyToDesktop(action.keyName)
                }
            }
        }
    }

    private fun broadcastKeyToDesktop(keyName: String) {
        applicationContext?.let { ctx ->
            val manager = CrossDeviceAutomationManager.getInstance(ctx)
            val command = mapOf(
                "type" to "key_press",
                "keyName" to keyName,
                "transactionId" to UUID.randomUUID().toString()
            )
            manager.networkingManager.broadcast(command)
            Log.d(TAG, "Broadcasted key action to desktop: $keyName")
        }
    }

    private fun cancelAllJobs() {
        longPressJob?.cancel()
        singleTapJob?.cancel()
    }
}
