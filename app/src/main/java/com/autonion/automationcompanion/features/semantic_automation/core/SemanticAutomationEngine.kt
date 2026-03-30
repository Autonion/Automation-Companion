package com.autonion.automationcompanion.features.semantic_automation.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.screen_understanding_ml.logic.ActionExecutor
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionIntent
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import com.autonion.automationcompanion.features.semantic_automation.ml.LocalServerLLMEngine
import com.autonion.automationcompanion.features.semantic_automation.ml.MLActionPredictor
import com.autonion.automationcompanion.features.semantic_automation.ml.ModelStorageManager
import com.autonion.automationcompanion.features.semantic_automation.ml.OnDeviceSLMEngine
import com.autonion.automationcompanion.features.semantic_automation.ml.ServerConnectionStatus
import com.autonion.automationcompanion.features.semantic_automation.ml.StepRecord
import com.autonion.automationcompanion.features.semantic_automation.ml.UIPromptFormatter
import com.autonion.automationcompanion.features.semantic_automation.model.AutomationStatus
import com.autonion.automationcompanion.features.semantic_automation.model.SemanticGoal
import com.autonion.automationcompanion.features.semantic_automation.model.ScreenUIState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred
import com.autonion.automationcompanion.features.semantic_automation.ml.PredictorCache
import android.view.KeyEvent

/**
 * The orchestration engine that runs the Screen Loop:
 *
 *   0. Parse goal & execute pre-actions (launch target app)
 *   1. Capture screenshot (or use accessibility tree)
 *   2. Build ScreenUIState
 *   3. Predict next action (with step history context)
 *   4. Execute action
 *   5. Verify action had an effect (post-action UI comparison)
 *   6. Record step result in history
 *   7. Wait for screen to settle
 *   8. Repeat until goal is achieved or cancelled
 */
class SemanticAutomationEngine(private val context: Context) {

    companion object {
        private const val TAG = "SemanticEngine"
        private const val MAX_LOOP_ITERATIONS = 50
        private const val POST_ACTION_DELAY_MS = 2500L
        private const val NO_ACTION_DELAY_MS = 1500L
        private const val APP_LAUNCH_DELAY_MS = 3000L
        private const val MAX_STEP_HISTORY = 5
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }

    private val goalParser = GoalParser()
    private val uiStateBuilder = UIStateBuilder(context)
    private val fallbackPredictor = ActionPredictor()
    private val taskPlanner = TaskPlanner()

    // Phase 2/3: Lazy-loaded from global cache to avoid loading times between sessions
    private val mlPredictor: MLActionPredictor?
        get() = PredictorCache.getMLPredictor(context)

    private val modelStorageManager = ModelStorageManager(context)

    // Phase 4: Local Server LLM (Ollama via Retrofit)
    val localServerEngine = LocalServerLLMEngine.getInstance(context)

    // Inference Mode Preference: user chooses which engine to prioritize
    enum class InferenceMode { LOCAL_SLM, SERVER_LLM }
    private val inferencePrefs = context.getSharedPreferences("inference_prefs", Context.MODE_PRIVATE)
    var inferenceMode: InferenceMode
        get() = try {
            InferenceMode.valueOf(inferencePrefs.getString("mode", InferenceMode.SERVER_LLM.name)!!)
        } catch (_: Exception) { InferenceMode.SERVER_LLM }
        set(value) { inferencePrefs.edit().putString("mode", value.name).apply() }

    private val _status = MutableStateFlow(AutomationStatus.IDLE)
    val status: StateFlow<AutomationStatus> = _status.asStateFlow()

    private val _currentGoal = MutableStateFlow<SemanticGoal?>(null)
    val currentGoal: StateFlow<SemanticGoal?> = _currentGoal.asStateFlow()

    private val _loopCount = MutableStateFlow(0)
    val loopCount: StateFlow<Int> = _loopCount.asStateFlow()

    private val _lastActionDescription = MutableStateFlow<String?>(null)
    val lastActionDescription: StateFlow<String?> = _lastActionDescription.asStateFlow()

    // ── Interactive Chat Prompt ──
    val userPromptMessage = MutableStateFlow<String?>(null)
    val userPromptOptions = MutableStateFlow<List<String>>(emptyList())
    private var pendingUserChoice: CompletableDeferred<String>? = null

    @Volatile
    private var isRunning = false

    // ── Step History (v2) ─────────────────────────────────────
    private val stepHistory = mutableListOf<StepRecord>()

    /**
     * Parse a raw user command and kick off the screen loop.
     */
    suspend fun runLoop(rawCommand: String, screenshotProvider: suspend () -> Bitmap?) {
        // ── Step 0: Parse goal ──
        _status.value = AutomationStatus.PARSING_GOAL
        var goal = goalParser.parse(rawCommand)
        _currentGoal.value = goal
        stepHistory.clear()

        DebugLogger.info(
            context, LogCategory.SCREEN_CONTEXT_AI,
            "Semantic goal parsed",
            "task=${goal.task}, query=${goal.query}, app=${goal.targetApp}",
            TAG
        )

        isRunning = true

        // ── Step 1: Pre-actions (launch app / open settings page) ──
        _lastActionDescription.value = "Preparing…"
        val preActionDone = executePreActions(goal)

        // Re-read goal in case executePreActions rerouted (e.g. Browser fallback)
        goal = _currentGoal.value ?: goal

        if (preActionDone && goal.task == "open") {
            // "open settings" → app launched, we're done
            _lastActionDescription.value = "App launched"
            _status.value = AutomationStatus.COMPLETED
            DebugLogger.success(
                context, LogCategory.SCREEN_CONTEXT_AI,
                "Task completed",
                "Launched ${goal.targetApp ?: goal.query}",
                TAG
            )
            isRunning = false
            return
        }

        if (preActionDone && (goal.task == "enable" || goal.task == "disable")) {
            // Opened settings page — now enter loop to tap the toggle
            _lastActionDescription.value = "Opened settings, looking for toggle…"
        }

        var lastInputText: String? = null
        var previousUiState: ScreenUIState? = null
        var consecutiveFailures = 0

        // Track the target package for wrong-app detection (uses potentially rerouted goal)
        val targetPackage = resolveTargetPackage(goal)

        // ── Step 2: Screen loop ──
        var iteration = 0

        while (isRunning && iteration < MAX_LOOP_ITERATIONS) {
            iteration++
            _loopCount.value = iteration
            Log.d(TAG, "── Loop iteration #$iteration ──")

            // 2a. Capture screenshot
            _status.value = AutomationStatus.CAPTURING_SCREEN
            val screenshot = screenshotProvider()

            // 2b. Build UI state
            _status.value = AutomationStatus.BUILDING_UI_STATE
            val uiState = uiStateBuilder.build(screenshot)
            Log.d(TAG, "UI state: ${uiState.elements.size} elements (source=${uiState.source})")

            if (uiState.elements.isEmpty()) {
                Log.w(TAG, "Empty UI state, waiting…")
                _lastActionDescription.value = "Waiting for screen content…"
                delay(NO_ACTION_DELAY_MS)
                consecutiveFailures++
                if (consecutiveFailures >= 5) {
                    Log.w(TAG, "5 consecutive empty states, stopping")
                    _status.value = AutomationStatus.FAILED
                    _lastActionDescription.value = "Cannot read screen — check permissions"
                    break
                }
                continue
            }

            // ── Wrong-app detection ──
            // If we navigated away from the target app, press Back to return
            if (targetPackage != null && uiState.packageName != null
                && !uiState.packageName.contains(targetPackage, ignoreCase = true)
                && uiState.packageName != "com.autonion.automationcompanion"
            ) {
                Log.w(TAG, "Wrong app detected: ${uiState.packageName} (expected $targetPackage), pressing Back")
                _lastActionDescription.value = "Wrong app, going back…"
                
                if (stepHistory.isNotEmpty()) {
                    val updated = stepHistory.last().copy(success = false, action = "FAILED-WRONG-APP")
                    stepHistory[stepHistory.lastIndex] = updated
                }
                
                AccessibilityTreeReader.performPressBack()
                previousUiState = null // Reset previous state so we don't falsely conclude the next state implies success
                delay(POST_ACTION_DELAY_MS)
                continue
            }

            // 2c. Post-action verification (compare with previous UI state)
            if (previousUiState != null && stepHistory.isNotEmpty()) {
                val lastStep = stepHistory.last()
                val uiChanged = hasUiChanged(previousUiState!!, uiState)
                if (!uiChanged && !lastStep.success) {
                    // UI didn't change and we already marked it as failed
                    consecutiveFailures++
                    Log.w(TAG, "UI unchanged after action ($consecutiveFailures consecutive failures)")

                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        Log.w(TAG, "Too many consecutive failures, trying scroll escape")
                        // Try scrolling as an escape hatch
                        val scrollAction = ActionIntent(
                            type = ActionType.SCROLL_DOWN,
                            targetPoint = PointF(540f, 1200f),
                            description = "Escape scroll after $consecutiveFailures failed actions"
                        )
                        executeAction(scrollAction, uiState)
                        delay(POST_ACTION_DELAY_MS)
                        consecutiveFailures = 0
                        continue
                    }
                } else if (uiChanged) {
                    consecutiveFailures = 0
                    // Update the last step's success flag
                    if (stepHistory.isNotEmpty()) {
                        val updated = stepHistory.last().copy(success = true)
                        stepHistory[stepHistory.lastIndex] = updated
                    }
                }
            }

            // 2d. Predict action (Server LLM → SLM → ML → Rules)
            _status.value = AutomationStatus.PREDICTING_ACTION
            var action = predictWithFallback(goal, uiState)

            if (action == null) {
                Log.w(TAG, "No action predicted, waiting…")
                _lastActionDescription.value = "No matching elements found, retrying…"
                delay(NO_ACTION_DELAY_MS)
                continue
            }

            // 2e. Check for completion
            if (action.type == ActionType.FINISH) {
                _lastActionDescription.value = "Task completed"
                _status.value = AutomationStatus.COMPLETED
                DebugLogger.success(
                    context, LogCategory.SCREEN_CONTEXT_AI,
                    "Semantic task completed",
                    "Finished '${goal.rawCommand}' in $iteration iterations",
                    TAG
                )
                break
            }

            // Anti-loop safeguard for INPUT_TEXT
            if (action.type == ActionType.INPUT_TEXT) {
                if (action.inputText == lastInputText) {
                    // Already typed this text — press Enter/Search to submit instead
                    Log.d(TAG, "Anti-Loop: Already typed '${lastInputText}', pressing Enter to submit")
                    _lastActionDescription.value = "Submitting search…"
                    val imeSuccess = AccessibilityTreeReader.performImeAction()
                    Log.d(TAG, "IME submit result: $imeSuccess")

                    // Record this as a step
                    stepHistory.add(
                        StepRecord(
                            iteration = iteration,
                            action = "SUBMIT",
                            elementText = "keyboard Enter",
                            elementIndex = -1,
                            success = imeSuccess
                        )
                    )
                    while (stepHistory.size > MAX_STEP_HISTORY) stepHistory.removeAt(0)

                    previousUiState = uiState
                    delay(POST_ACTION_DELAY_MS)
                    continue
                } else {
                    lastInputText = action.inputText ?: ""
                }
            }

            // 2f. Execute action
            _status.value = AutomationStatus.EXECUTING_ACTION
            _lastActionDescription.value = action.description
            Log.d(TAG, "Executing: ${action.type} – ${action.description}")

            val success = try {
                executeAction(action, uiState)
            } catch (e: Exception) {
                Log.e(TAG, "Action execution crashed: ${e.message}", e)
                DebugLogger.error(
                    context, LogCategory.SCREEN_CONTEXT_AI,
                    "Action #$iteration crashed",
                    "${action.type}: ${e.message}",
                    TAG
                )
                false
            }

            // 2g. Record step in history (success will be verified next iteration)
            val elementText = resolveElementText(action, uiState)
            val elementIndex = action.targetId?.removePrefix("slm_element_")?.toIntOrNull() ?: -1
            stepHistory.add(
                StepRecord(
                    iteration = iteration,
                    action = action.type.name,
                    elementText = elementText,
                    elementIndex = elementIndex,
                    success = success, // Preliminary; updated next iteration via UI comparison
                    inputText = action.inputText
                )
            )
            // Keep history bounded
            while (stepHistory.size > MAX_STEP_HISTORY) {
                stepHistory.removeAt(0)
            }

            if (success) {
                DebugLogger.success(
                    context, LogCategory.SCREEN_CONTEXT_AI,
                    "Action #$iteration: ${action.type}",
                    action.description,
                    TAG
                )
            } else {
                DebugLogger.error(
                    context, LogCategory.SCREEN_CONTEXT_AI,
                    "Action #$iteration failed",
                    "${action.type}: ${action.description}",
                    TAG
                )
            }

            // Save current UI state for next-iteration comparison
            previousUiState = uiState

            // 2h. Wait for screen to settle
            _status.value = AutomationStatus.WAITING_FOR_SCREEN
            delay(POST_ACTION_DELAY_MS)
        }

        // Loop ended
        if (isRunning && iteration >= MAX_LOOP_ITERATIONS) {
            _status.value = AutomationStatus.FAILED
            _lastActionDescription.value = "Reached max iterations ($MAX_LOOP_ITERATIONS)"
            DebugLogger.warning(
                context, LogCategory.SCREEN_CONTEXT_AI,
                "Semantic loop limit reached",
                "Stopped after $MAX_LOOP_ITERATIONS iterations for '${goal.rawCommand}'",
                TAG
            )
        }

        isRunning = false
    }

    /**
     * Resumes the engine loop if it was suspended waiting for a user choice.
     */
    fun resumeWithUserChoice(choice: String) {
        pendingUserChoice?.complete(choice)
    }

    /**
     * Suspend execution and prompt UI for user choice.
     */
    private suspend fun waitForUserChoice(prompt: String, options: List<String>): String {
        _status.value = AutomationStatus.AWAITING_USER_INPUT
        userPromptMessage.value = prompt
        userPromptOptions.value = options
        pendingUserChoice = CompletableDeferred()
        val result = pendingUserChoice!!.await()
        pendingUserChoice = null
        userPromptMessage.value = null
        userPromptOptions.value = emptyList()
        // Ensure status gets out of AWAITING once resolved
        _status.value = AutomationStatus.EXECUTING_ACTION
        return result
    }

    /**
     * Execute pre-actions before starting the screen loop:
     *  - Launch target app
     *  - Open system settings page (wifi, bluetooth, etc.)
     *
     * Returns true if a pre-action was taken.
     */
    private suspend fun executePreActions(goal: SemanticGoal): Boolean {
        // System actions (turn on wifi, enable bluetooth, etc.)
        if (goal.task in listOf("enable", "disable")) {
            val launched = AppLauncher.launchSystemAction(context, goal.task, goal.query ?: goal.rawCommand)
            if (launched) {
                _lastActionDescription.value = "Opening settings…"
                delay(APP_LAUNCH_DELAY_MS)
                return true
            }
        }

        // App-targeted tasks (search X on amazon, open settings, etc.)
        var targetApp = goal.targetApp
        if (targetApp != null) {
            _lastActionDescription.value = "Checking app: $targetApp…"

            // Interactive Flow: App Not Found
            val appExists = AppLauncher.hasExactApp(context, targetApp)
            if (!appExists) {
                Log.w(TAG, "App '$targetApp' not found exactly. Suspending for user input.")
                val choice = waitForUserChoice(
                    prompt = "$targetApp is not installed. How do you want to proceed?",
                    options = listOf("Play Store", "Browser", "Cancel")
                )

                when (choice) {
                    "Play Store" -> {
                        _lastActionDescription.value = "Sending to Play Store…"
                        // PlayStore fallback
                        AppLauncher.launchApp(context, targetApp)
                        _status.value = AutomationStatus.COMPLETED
                        isRunning = false
                        return true
                    }
                    "Browser" -> {
                        // Reroute via Browser: update targetApp AND rawCommand/query
                        _lastActionDescription.value = "Rerouting to Browser…"
                        targetApp = "chrome"
                        val originalApp = goal.targetApp ?: "website"
                        val browserQuery = "${goal.query ?: goal.rawCommand} on $originalApp"
                        _currentGoal.value = _currentGoal.value?.copy(
                            targetApp = "chrome",
                            rawCommand = "search $browserQuery in browser",
                            query = browserQuery
                        )
                        Log.d(TAG, "Rerouted to Browser with query: $browserQuery")
                    }
                    "Cancel" -> {
                        stop()
                        return false
                    }
                }
            }

            // Launch targetApp (either original or browser)
            _lastActionDescription.value = "Launching $targetApp…"
            val launched = AppLauncher.launchApp(context, targetApp!!)
            if (launched) {
                Log.d(TAG, "Pre-launched app: $targetApp, waiting for it to start…")
                delay(APP_LAUNCH_DELAY_MS)
                return true
            } else {
                Log.w(TAG, "Failed to launch app: $targetApp")
                _lastActionDescription.value = "Could not find app: $targetApp"
            }
        }

        // "open" task without explicit targetApp — try the query as an app name
        if (goal.task == "open" && targetApp == null && !goal.query.isNullOrBlank()) {
            _lastActionDescription.value = "Launching ${goal.query}…"
            val launched = AppLauncher.launchApp(context, goal.query)
            if (launched) {
                delay(APP_LAUNCH_DELAY_MS)
                return true
            }
        }

        return false
    }

    /**
     * Attempts to execute the action using the most reliable method available.
     */
    private suspend fun executeAction(action: ActionIntent, uiState: ScreenUIState): Boolean {
        // Only try direct accessibility actions if the UI state was built from it
        if (uiState.source == com.autonion.automationcompanion.features.semantic_automation.model.ElementSource.ACCESSIBILITY && action.targetPoint != null) {
            
            // Find the UIStateElement that matches the exact center point
            val targetElement = uiState.elements.firstOrNull { el -> 
                val center = android.graphics.PointF(
                    (el.bounds.left + el.bounds.right) / 2f,
                    (el.bounds.top + el.bounds.bottom) / 2f
                )
                Math.abs(center.x - action.targetPoint.x) < 5f && Math.abs(center.y - action.targetPoint.y) < 5f
            }

            if (targetElement != null) {
                if (action.type == ActionType.CLICK) {
                    Log.d(TAG, "Trying direct accessibility CLICK on '${targetElement.text}'")
                    val success = AccessibilityTreeReader.performClickOnElement(targetElement)
                    if (success) return true
                    Log.d(TAG, "Direct CLICK failed, falling back to gesture sweep")
                } 
                else if (action.type == ActionType.INPUT_TEXT && action.inputText != null) {
                    Log.d(TAG, "Trying direct accessibility SET_TEXT on '${targetElement.text}'")
                    val success = AccessibilityTreeReader.performSetText(targetElement, action.inputText)
                    if (success) return true
                    Log.d(TAG, "Direct SET_TEXT failed, falling back to gesture sweep")
                }
            }
        }

        // Fallback: coordinate-based gesture dispatch
        Log.d(TAG, "Using gesture-based ActionExecutor for ${action.type}")
        return ActionExecutor.execute(context, action)
    }

    fun stop() {
        isRunning = false
        _status.value = AutomationStatus.CANCELLED
        _lastActionDescription.value = "Cancelled by user"
    }

    fun cleanup() {
        stop()
        pendingUserChoice?.cancel()
        uiStateBuilder.close()
        // Models are no longer closed here so they persist between runs
        stepHistory.clear()
        _status.value = AutomationStatus.IDLE
        _currentGoal.value = null
        _loopCount.value = 0
        _lastActionDescription.value = null
    }

    /**
     * Multi-tier prediction respecting the user's inference mode preference.
     *
     * SERVER_LLM mode: Server LLM → ML → Rules  (skips on-device SLM)
     * LOCAL_SLM  mode: On-Device SLM → ML → Rules (skips server)
     */
    private suspend fun predictWithFallback(goal: SemanticGoal, uiState: ScreenUIState): ActionIntent? {

        // Tier 0: Deterministic Task Planner (for search/play flows)
        // This handles standard flows without LLM, reserving inference for novel interactions
        try {
            val completedActions = stepHistory.map { it.action }
            val plannerAction = taskPlanner.predict(goal, uiState, completedActions)
            if (plannerAction != null) {
                Log.d(TAG, "TaskPlanner produced action: ${plannerAction.type} - ${plannerAction.description}")
                return plannerAction
            }
        } catch (e: Exception) {
            Log.e(TAG, "TaskPlanner failed, falling through to LLM", e)
        }

        when (inferenceMode) {
            InferenceMode.SERVER_LLM -> {
                // Tier 1: Local Server LLM (Ollama via Chat API + structured output)
                if (localServerEngine.connectionStatus.value == ServerConnectionStatus.CONNECTED) {
                    try {
                        val systemPrompt = UIPromptFormatter.buildSystemPrompt()
                        val userPrompt = UIPromptFormatter.buildUserPrompt(goal, uiState, stepHistory)
                        
                        Log.d(TAG, "Server LLM prompt: ${userPrompt.take(500)}")
                        
                        val serverAction = localServerEngine.predictNextAction(systemPrompt, userPrompt)
                        if (serverAction != null) {
                            val resolved = resolveSlmAction(serverAction, uiState)
                            if (resolved != null) return resolved
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Server LLM prediction failed, falling through to ML", e)
                    }
                } else {
                    Log.w(TAG, "Server LLM not connected, falling through to ML")
                }
            }
            InferenceMode.LOCAL_SLM -> {
                // Tier 1: On-Device SLM (Gemma 2B) — uses legacy single prompt
                val slm = PredictorCache.getSLMEngine(context, modelStorageManager)
                if (slm != null) {
                    try {
                        val prompt = UIPromptFormatter.buildPrompt(goal, uiState)
                        val slmAction = slm.predictNextAction(prompt)
                        if (slmAction != null) {
                            val resolved = resolveSlmAction(slmAction, uiState)
                            if (resolved != null) return resolved
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "SLM prediction failed, falling through to ML", e)
                    }
                }
            }
        }

        // Tier 2: TFLite ML Model (fast but biased)
        try {
            val mlAction = mlPredictor?.predict(goal, uiState)
            if (mlAction != null) return mlAction
        } catch (e: Exception) {
            Log.e(TAG, "ML prediction failed, falling through to rules", e)
        }

        // Tier 3: Rule-based fallback
        return fallbackPredictor.predict(goal, uiState)
    }

    /**
     * Resolves an SLM action (which has an element_index) into concrete screen coordinates.
     */
    private fun resolveSlmAction(action: ActionIntent, uiState: ScreenUIState): ActionIntent? {
        // Extract element index from targetId like "slm_element_3"
        val indexStr = action.targetId?.removePrefix("slm_element_")?.toIntOrNull()

        if (indexStr != null && indexStr >= 0 && indexStr < uiState.elements.size) {
            val el = uiState.elements[indexStr]
            val cx = (el.bounds.left + el.bounds.right) / 2f
            val cy = (el.bounds.top + el.bounds.bottom) / 2f
            return action.copy(
                targetPoint = PointF(cx, cy),
                description = "ServerLLM: ${action.type} on '${el.text?.take(30) ?: "element[$indexStr]"}'"
            )
        } else if (action.type == ActionType.FINISH) {
            return action // FINISH doesn't need coordinates
        } else if (action.type == ActionType.SCROLL_DOWN || action.type == ActionType.SCROLL_UP) {
            // Scroll doesn't need a specific element — use screen center
            return action.copy(
                targetPoint = PointF(540f, 1200f),
                description = "ServerLLM: ${action.type} (full screen)"
            )
        }

        Log.w(TAG, "LLM returned invalid element_index: $indexStr (${uiState.elements.size} elements)")
        return null
    }

    // ── Post-Action Verification ─────────────────────────────

    /**
     * Compares two UI states to determine if the screen changed after an action.
     * Uses element count and a sample of element texts as a fast fingerprint.
     */
    private fun hasUiChanged(oldState: ScreenUIState, newState: ScreenUIState): Boolean {
        // Different element count → definitely changed
        if (oldState.elements.size != newState.elements.size) return true

        // Different package → app switch
        if (oldState.packageName != newState.packageName) return true

        // Compare a fingerprint of element texts (fast heuristic)
        val oldFingerprint = oldState.elements.take(10).mapNotNull { it.text }.joinToString("|")
        val newFingerprint = newState.elements.take(10).mapNotNull { it.text }.joinToString("|")
        return oldFingerprint != newFingerprint
    }

    /**
     * Extracts a human-readable element text for step history logging.
     */
    private fun resolveElementText(action: ActionIntent, uiState: ScreenUIState): String? {
        val index = action.targetId?.removePrefix("slm_element_")?.toIntOrNull() ?: return null
        if (index >= 0 && index < uiState.elements.size) {
            return uiState.elements[index].text?.take(30)
        }
        return null
    }

    // ── Wrong-App Detection ──────────────────────────────────

    /**
     * Maps goal targetApp aliases to Android package name substrings.
     * Used for wrong-app detection: if the current foreground app doesn't contain
     * this substring, we know the agent drifted and should press Back.
     */
    private fun resolveTargetPackage(goal: SemanticGoal): String? {
        val alias = goal.targetApp ?: return null
        return when (alias.lowercase()) {
            "youtube" -> "youtube"
            "amazon" -> "amazon"
            "whatsapp" -> "whatsapp"
            "instagram" -> "instagram"
            "chrome" -> "chrome"
            "gmail" -> "android.gm"
            "maps" -> "maps"
            "playstore" -> "vending"
            "twitter" -> "twitter"
            "spotify" -> "spotify"
            "facebook" -> "facebook"
            "telegram" -> "telegram"
            "netflix" -> "netflix"
            "uber" -> "uber"
            "settings" -> "settings"
            "camera" -> "camera"
            "calculator" -> "calculator"
            "clock" -> "deskclock"
            "calendar" -> "calendar"
            "contacts" -> "contacts"
            "messages" -> "messaging"
            "phone" -> "dialer"
            "files" -> "documentsui"
            else -> alias // Try the alias itself as a substring match
        }
    }
}
