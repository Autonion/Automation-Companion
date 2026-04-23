package com.autonion.automationcompanion.features.cross_device_automation.engine

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.util.Log
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class GestureType { SINGLE_TAP, DOUBLE_TAP, LONG_PRESS }

sealed class DesktopAction {
    data class SendKey(val keyName: String) : DesktopAction()
}

/**
 * Three-layer volume key interception:
 *
 * 1. **onKeyEvent** (AccessibilityService) — Screen ON only. Full gesture detection.
 *    Consumes keys (returns true) so system volume doesn't change.
 *
 * 2. **VolumeProvider** (MediaSession) — Works on stock Android screen-off.
 *    OEM ROMs often bypass this entirely.
 *
 * 3. **ContentObserver** (Settings.System) — THE primary screen-off mechanism.
 *    When screen is off, onKeyEvent does NOT consume keys, so the system volume
 *    changes. ContentObserver detects the change and fires the action.
 *    Volume is held at midpoint so both directions always register.
 *
 * Silent AudioTrack in ForegroundService keeps the audio subsystem alive,
 * preventing deep Doze from suspending ContentObserver delivery.
 */
object HardwareButtonMapper {
    private const val TAG = "HardwareButtonMapper"
    private const val DOUBLE_TAP_TIMEOUT = 300L
    private const val LONG_PRESS_TIMEOUT = 500L
    private const val DEDUP_WINDOW_MS = 800L

    private var activeMappings = mutableMapOf<Pair<Int, GestureType>, DesktopAction>()
    private var applicationContext: Context? = null

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    val currentMappings: Map<Pair<Int, GestureType>, DesktopAction> get() = activeMappings.toMap()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Gesture detection state (onKeyEvent path — screen ON only)
    private var lastKeyDownTime = 0L
    private var lastKeyUpTime = 0L
    private var currentKeyCode = 0
    private var longPressJob: Job? = null
    private var singleTapJob: Job? = null
    private var wasLongPressHandled = false

    private var mediaSession: MediaSession? = null
    private var powerManager: PowerManager? = null

    // Deduplication: timestamp of the last action executed by ANY path
    private val lastActionTime = AtomicLong(0L)

    // ContentObserver for screen-off volume detection
    private var volumeObserver: ContentObserver? = null
    private var audioManager: AudioManager? = null
    private var anchorVolume: Int = -1
    private var originalVolume: Int = -1
    private val isRestoringVolume = AtomicBoolean(false)

    fun activate(context: Context, mappings: Map<Pair<Int, GestureType>, DesktopAction>) {
        applicationContext = context.applicationContext
        powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        activeMappings.clear()
        activeMappings.putAll(mappings)
        _isActive.value = true
        HardwareRemoteForegroundService.start(context)
        setupMediaSession(context)
        setupVolumeObserver(context)
        saveMappings(context, mappings)
        Log.d(TAG, "Activated with mappings: $mappings")
    }

    fun deactivate() {
        activeMappings.clear()
        _isActive.value = false
        applicationContext?.let { HardwareRemoteForegroundService.stop(it) }
        teardownVolumeObserver()
        teardownMediaSession()
        cancelAllJobs()
        powerManager = null
        Log.d(TAG, "Deactivated")
    }

    // ── ContentObserver: primary screen-off mechanism ────────────────────

    private fun setupVolumeObserver(context: Context) {
        val ctx = context.applicationContext
        audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val am = audioManager ?: return

        // Save original volume to restore on deactivate
        originalVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        anchorVolume = maxVol / 2
        // Set to midpoint so both up/down always register a change
        am.setStreamVolume(AudioManager.STREAM_MUSIC, anchorVolume, 0)
        Log.d(TAG, "VolumeObserver: anchor=$anchorVolume, original=$originalVolume, max=$maxVol")

        val handler = Handler(Looper.getMainLooper())
        volumeObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                if (!_isActive.value) return
                if (isRestoringVolume.get()) return

                val am2 = audioManager ?: return
                val currentVol = am2.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (currentVol == anchorVolume) return

                // Deduplicate with onKeyEvent (screen-on path)
                val elapsed = System.currentTimeMillis() - lastActionTime.get()
                if (elapsed < DEDUP_WINDOW_MS) {
                    // onKeyEvent already handled this — just restore anchor
                    restoreAnchorVolume(am2)
                    return
                }

                val keyCode = if (currentVol > anchorVolume) {
                    KeyEvent.KEYCODE_VOLUME_UP
                } else {
                    KeyEvent.KEYCODE_VOLUME_DOWN
                }

                Log.d(TAG, "VolumeObserver: volume $anchorVolume→$currentVol, keyCode=$keyCode")
                restoreAnchorVolume(am2)
                executeAction(keyCode, GestureType.SINGLE_TAP)
            }
        }

        ctx.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI, true, volumeObserver!!
        )
        Log.d(TAG, "VolumeObserver registered")
    }

    private fun restoreAnchorVolume(am: AudioManager) {
        isRestoringVolume.set(true)
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, anchorVolume, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore anchor volume", e)
        } finally {
            Handler(Looper.getMainLooper()).postDelayed({
                isRestoringVolume.set(false)
            }, 250)
        }
    }

    private fun teardownVolumeObserver() {
        volumeObserver?.let { observer ->
            applicationContext?.contentResolver?.unregisterContentObserver(observer)
        }
        volumeObserver = null
        if (originalVolume >= 0) {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            Log.d(TAG, "Restored original volume: $originalVolume")
        }
        audioManager = null
    }

    // ── MediaSession VolumeProvider ─────────────────────────────────────

    private fun setupMediaSession(context: Context) {
        if (mediaSession == null) {
            mediaSession = MediaSession(context, "HardwareRemoteSession").apply {
                val state = PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .build()
                setPlaybackState(state)

                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: android.content.Intent): Boolean {
                        Log.d(TAG, "MediaSession: onMediaButtonEvent")
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }
                })

                val volumeProvider = object : VolumeProvider(
                    VOLUME_CONTROL_RELATIVE, 15, 7
                ) {
                    override fun onAdjustVolume(direction: Int) {
                        if (!_isActive.value) return
                        val elapsed = System.currentTimeMillis() - lastActionTime.get()
                        if (elapsed < DEDUP_WINDOW_MS) return
                        val keyCode = when {
                            direction > 0 -> KeyEvent.KEYCODE_VOLUME_UP
                            direction < 0 -> KeyEvent.KEYCODE_VOLUME_DOWN
                            else -> return
                        }
                        Log.d(TAG, "VolumeProvider: direction=$direction")
                        executeAction(keyCode, GestureType.SINGLE_TAP)
                    }
                }
                setPlaybackToRemote(volumeProvider)
                isActive = true
            }
            Log.d(TAG, "MediaSession active with VolumeProvider")
        }
    }

    private fun teardownMediaSession() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
    }

    // ── AccessibilityService onKeyEvent: screen-ON path ─────────────────

    /**
     * Called by AccessibilityService. When screen is ON, we consume the key
     * (return true) and do full gesture detection. When screen is OFF, we
     * let the key pass through (return false) so the system volume changes
     * and ContentObserver can detect it — this is the only reliable screen-off
     * mechanism on OEM ROMs.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!_isActive.value) return false
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        // If screen is OFF → don't consume, let ContentObserver handle it
        val isScreenOn = powerManager?.isInteractive ?: true
        if (!isScreenOn) {
            Log.d(TAG, "onKeyEvent: screen OFF, passing through to ContentObserver")
            return false
        }

        // Screen is ON — full gesture detection
        lastActionTime.set(System.currentTimeMillis())
        when (event.action) {
            KeyEvent.ACTION_DOWN -> { if (event.repeatCount == 0) handleActionDown(keyCode) }
            KeyEvent.ACTION_UP -> handleActionUp(keyCode)
        }
        return true // consume key when screen is ON
    }

    private fun handleActionDown(keyCode: Int) {
        val now = System.currentTimeMillis()
        if (keyCode == currentKeyCode && (now - lastKeyUpTime) < DOUBLE_TAP_TIMEOUT && singleTapJob?.isActive == true) {
            singleTapJob?.cancel()
            cancelAllJobs()
            executeAction(keyCode, GestureType.DOUBLE_TAP)
            return
        }
        currentKeyCode = keyCode
        lastKeyDownTime = now
        wasLongPressHandled = false
        cancelAllJobs()
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
            singleTapJob = scope.launch {
                delay(DOUBLE_TAP_TIMEOUT)
                executeAction(keyCode, GestureType.SINGLE_TAP)
            }
        }
    }

    // ── Shared action execution ─────────────────────────────────────────

    private fun executeAction(keyCode: Int, gesture: GestureType) {
        lastActionTime.set(System.currentTimeMillis())
        Log.d(TAG, "Gesture detected: KeyCode=$keyCode, Gesture=$gesture")
        val action = activeMappings[Pair(keyCode, gesture)]
        if (action != null) {
            when (action) {
                is DesktopAction.SendKey -> broadcastKeyToDesktop(action.keyName)
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

    // ── Persistence ─────────────────────────────────────────────────────

    fun saveMappings(context: Context, mappings: Map<Pair<Int, GestureType>, DesktopAction>) {
        val prefs = context.getSharedPreferences("HardwareRemotePrefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.clear()
        for ((key, action) in mappings) {
            val keyCode = key.first
            val gesture = key.second.name
            if (action is DesktopAction.SendKey) {
                editor.putString("${keyCode}|${gesture}", action.keyName)
            }
        }
        editor.apply()
        Log.d(TAG, "Saved ${mappings.size} mappings to prefs")
    }

    fun loadMappings(context: Context): Map<Pair<Int, GestureType>, DesktopAction> {
        val prefs = context.getSharedPreferences("HardwareRemotePrefs", Context.MODE_PRIVATE)
        val loadedMappings = mutableMapOf<Pair<Int, GestureType>, DesktopAction>()
        for (key in prefs.all.keys) {
            val parts = key.split("|", limit = 2)
            if (parts.size == 2) {
                val keyCode = parts[0].toIntOrNull()
                val gesture = try { GestureType.valueOf(parts[1]) } catch (e: Exception) { null }
                val actionString = prefs.getString(key, null)
                if (keyCode != null && gesture != null && actionString != null) {
                    loadedMappings[Pair(keyCode, gesture)] = DesktopAction.SendKey(actionString)
                }
            }
        }
        Log.d(TAG, "Loaded ${loadedMappings.size} mappings from prefs")
        return loadedMappings
    }

    private fun cancelAllJobs() {
        longPressJob?.cancel()
        singleTapJob?.cancel()
    }
}
