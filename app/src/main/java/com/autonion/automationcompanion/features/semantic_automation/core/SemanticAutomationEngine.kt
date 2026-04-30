package com.autonion.automationcompanion.features.semantic_automation.core

import com.autonion.automationcompanion.features.semantic_automation.memory.AutomationChatMemory

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
import com.autonion.automationcompanion.features.semantic_automation.ml.CloudApiLLMEngine
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

    private val goalParser = GoalParser(context)
    private val uiStateBuilder = UIStateBuilder(context)
    private val fallbackPredictor = ActionPredictor()
    private val taskPlanner = TaskPlanner()
    private val taskDecomposer = TaskDecomposer()
    private val chatMemory = AutomationChatMemory.getInstance(context)

    // Phase 2/3: Lazy-loaded from global cache to avoid loading times between sessions
    private val mlPredictor: MLActionPredictor?
        get() = PredictorCache.getMLPredictor(context)

    private val modelStorageManager = ModelStorageManager(context)

    // Phase 4: Local Server LLM (Ollama via Retrofit)
    val localServerEngine = LocalServerLLMEngine.getInstance(context)

    // Phase 6: Cloud API LLM (OpenAI-compatible endpoints)
    val cloudApiEngine = CloudApiLLMEngine.getInstance(context)

    // Phase 5: Browser Extension Bridge (starts WebSocket server on port 54321)
    val extensionBridge: ExtensionBridgeServer = ExtensionBridgeServer.getInstance(context)

    // Inference Mode Preference: user chooses which engine to prioritize
    enum class InferenceMode { LOCAL_SLM, SERVER_LLM, CLOUD_API }
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

    init {
        localServerEngine.autoConnectIfNeeded()
    }

    /**
     * Parse a raw user command, decompose if complex, and execute sub-goals sequentially.
     *
     * This is the main entry point for semantic automation. It:
     *   1. Decomposes the command via TaskDecomposer (Regex → NLP → LLM fallback)
     *   2. For simple commands (1 sub-goal): delegates to [runSingleGoal] (unchanged behavior)
     *   3. For complex commands (N sub-goals): parses the FULL command once via GoalParser,
     *      then runs the screen loop for each sub-goal with inherited app context.
     */
    suspend fun runLoop(rawCommand: String, screenshotProvider: suspend () -> Bitmap?) {
        // ── Step 0: Decompose command ──
        val subGoals = taskDecomposer.decompose(
            rawCommand,
            llmEngine = if (inferenceMode == InferenceMode.CLOUD_API) null else localServerEngine,
            conversationContext = chatMemory.buildContextSummary()
        )

        if (subGoals.size <= 1) {
            // Simple command — use existing full flow (backward compatible)
            chatMemory.recordGoalStart(rawCommand)
            runSingleGoal(rawCommand, screenshotProvider)
            val success = _status.value == AutomationStatus.COMPLETED
            chatMemory.recordGoalOutcome(
                rawCommand,
                if (success) "completed" else "failed",
                success,
                appUsed = _currentGoal.value?.targetApp
            )
            return
        }

        // ── Multi-step: parse ONCE, execute sub-goals sequentially ──
        Log.d(TAG, "Multi-step command: ${subGoals.size} sub-goals decomposed")
        chatMemory.recordGoalStart(rawCommand)
        _status.value = AutomationStatus.PARSING_GOAL
        stepHistory.clear()

        // Parse the FULL original command → get app/domain context (1 LLM call)
        val masterGoal = try {
            kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                if (inferenceMode == InferenceMode.CLOUD_API)
                    goalParser.parse(rawCommand, cloudApiEngine, chatMemory.buildContextSummary())
                else
                    goalParser.parse(rawCommand, localServerEngine, chatMemory.buildContextSummary())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Goal parsing exception: ${e.message}", e)
            null
        }

        if (masterGoal == null) {
            _status.value = AutomationStatus.FAILED
            _lastActionDescription.value = "Could not reach LLM — check connection"
            chatMemory.recordGoalOutcome(rawCommand, "failed: could not parse", false)
            return
        }

        _currentGoal.value = masterGoal

        val validationError = validateGoal(masterGoal)
        if (validationError != null) {
            _status.value = AutomationStatus.FAILED
            _lastActionDescription.value = validationError
            chatMemory.recordGoalOutcome(rawCommand, "failed: $validationError", false)
            isRunning = false
            return
        }

        isRunning = true

        // Pre-actions (launch app) using the master goal
        _lastActionDescription.value = "Preparing…"
        val preActionDone = executePreActions(masterGoal)
        val activeGoal = _currentGoal.value ?: masterGoal

        // Execute each sub-goal sequentially
        for ((index, subGoal) in subGoals.withIndex()) {
            if (!isRunning) break

            _lastActionDescription.value = "Step ${subGoal.stepNumber}/${subGoals.size}: ${subGoal.description}"
            Log.d(TAG, "── Sub-goal ${subGoal.stepNumber}/${subGoals.size}: ${subGoal.description} ──")

            // Skip first sub-goal if it was just "open app" and pre-action handled it
            if (index == 0 && preActionDone && activeGoal.task == "open" && activeGoal.targetApp != "browser") {
                Log.d(TAG, "Sub-goal 1 (open app) already handled by pre-action, skipping")
                chatMemory.recordAgentTurn(subGoal.description, "completed (pre-action)")
                _status.value = AutomationStatus.COMPLETED
                continue
            }

            // Create a derived goal: same app/domain context, sub-goal's description
            val derivedGoal = activeGoal.copy(
                rawCommand = subGoal.description,
                query = subGoal.description,
                task = inferTaskFromDescription(subGoal.description) ?: activeGoal.task
            )

            // Run screen loop for this sub-goal (NO additional GoalParser call)
            stepHistory.clear()
            _currentGoal.value = derivedGoal
            runScreenLoop(derivedGoal, screenshotProvider)

            // Loose verification: if completed, move to next
            if (_status.value != AutomationStatus.COMPLETED) {
                _lastActionDescription.value = "Failed at step ${subGoal.stepNumber}: ${subGoal.description}"
                chatMemory.recordGoalOutcome(rawCommand, "failed at step ${subGoal.stepNumber}", false, activeGoal.targetApp)
                isRunning = false
                return
            }

            chatMemory.recordAgentTurn(subGoal.description, "completed")
            delay(POST_ACTION_DELAY_MS) // Let screen settle between sub-goals
        }

        _status.value = AutomationStatus.COMPLETED
        _lastActionDescription.value = "All ${subGoals.size} steps completed"
        chatMemory.recordGoalOutcome(rawCommand, "completed all steps", true, activeGoal.targetApp)
        isRunning = false
    }

    /**
     * Full single-goal flow: parse → validate → pre-actions → screen loop.
     * This is the original [runLoop] body, used for simple (1-step) commands.
     */
    private suspend fun runSingleGoal(rawCommand: String, screenshotProvider: suspend () -> Bitmap?) {
        // ── Step 0: Parse goal via LLM ──
        _status.value = AutomationStatus.PARSING_GOAL
        stepHistory.clear()

        val parsedGoal = try {
            kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                if (inferenceMode == InferenceMode.CLOUD_API)
                    goalParser.parse(rawCommand, cloudApiEngine, chatMemory.buildContextSummary())
                else
                    goalParser.parse(rawCommand, localServerEngine, chatMemory.buildContextSummary())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Goal parsing exception: ${e.message}", e)
            null
        }
        if (parsedGoal == null) {
            _status.value = AutomationStatus.FAILED
            _lastActionDescription.value = "Could not reach LLM — check connection"
            DebugLogger.error(
                context, LogCategory.SCREEN_CONTEXT_AI,
                "Goal parsing failed",
                "LLM not connected or timed out. Please configure the server/API in Settings.",
                TAG
            )
            return
        }

        var goal = parsedGoal
        _currentGoal.value = goal

        DebugLogger.info(
            context, LogCategory.SCREEN_CONTEXT_AI,
            "Semantic goal parsed",
            "task=${goal.task}, query=${goal.query}, app=${goal.targetApp}, domain=${goal.domain}, confidence=${goal.confidence}",
            TAG
        )

        // ── Validation gate: reject hallucinated / gibberish goals ──
        val validationError = validateGoal(goal)
        if (validationError != null) {
            Log.w(TAG, "Goal validation failed: $validationError")
            _status.value = AutomationStatus.FAILED
            _lastActionDescription.value = validationError
            DebugLogger.error(
                context, LogCategory.SCREEN_CONTEXT_AI,
                "Goal rejected by validation gate",
                validationError,
                TAG
            )
            isRunning = false
            return
        }

        isRunning = true

        // ── Step 1: Pre-actions (launch app / open settings page) ──
        _lastActionDescription.value = "Preparing…"
        val preActionDone = executePreActions(goal)

        // Re-read goal in case executePreActions rerouted (e.g. Browser fallback)
        goal = _currentGoal.value ?: goal

        if (preActionDone && goal.task == "open" && goal.targetApp != "browser") {
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
            _lastActionDescription.value = "Opened settings, looking for toggle…"
        }

        // Run the screen loop
        runScreenLoop(goal, screenshotProvider)

        isRunning = false
    }

    /**
     * The core screen interaction loop. Takes a pre-parsed [SemanticGoal] and runs:
     *   capture → build UI → predict → execute → verify → repeat
     *
     * Used by both [runSingleGoal] (single commands) and [runLoop] (multi-step sub-goals).
     * Does NOT set [isRunning] = false — the caller manages that.
     */
    private suspend fun runScreenLoop(goal: SemanticGoal, screenshotProvider: suspend () -> Bitmap?) {
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

            // 2b. Build UI state — prefer extension DOM when in a browser
            _status.value = AutomationStatus.BUILDING_UI_STATE
            val uiState = uiStateBuilder.build(screenshot, extensionBridge)
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
            val isInExpectedBrowser = targetPackage == "browser" && uiState.packageName?.let { pkg ->
                pkg.contains("chrome", ignoreCase = true) ||
                pkg.contains("firefox", ignoreCase = true) ||
                pkg.contains("fenix", ignoreCase = true) ||    // Firefox Nightly = org.mozilla.fenix
                pkg.contains("mozilla", ignoreCase = true) ||   // Any Mozilla browser
                pkg.contains("browser", ignoreCase = true) ||
                pkg.contains("opera", ignoreCase = true) ||
                pkg.contains("edge", ignoreCase = true) ||
                pkg.contains("duckduckgo", ignoreCase = true) ||
                pkg.contains("brave", ignoreCase = true) ||
                pkg.contains("kiwi", ignoreCase = true) ||     // Kiwi Browser
                pkg.contains("lemur", ignoreCase = true)        // Lemur Browser
            } == true
            
            val exemptOwnApp = uiState.packageName == "com.autonion.automationcompanion" && iteration < 3
            
            val isWrongApp = targetPackage != null && uiState.packageName != null &&
                    !isInExpectedBrowser &&
                    !uiState.packageName.contains(targetPackage, ignoreCase = true) &&
                     !exemptOwnApp
            
            if (isWrongApp) {
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
                        // Reroute via Browser: open targetURL directly
                        _lastActionDescription.value = "Opening in Browser…"
                        val originalApp = goal.targetApp ?: "website"
                        targetApp = "browser_url" // Special flag to launch via URL
                        
                        // We ask the LLM to search FOR the query ON the website we are already at
                        val browserQuery = goal.query ?: goal.rawCommand
                        val rawCmd = "search $browserQuery"
                        
                        _currentGoal.value = _currentGoal.value?.copy(
                            targetApp = "browser", // 'browser' unlocks the special wrong-app browser list
                            task = if (browserQuery.isNullOrBlank()) "open" else "search", // Update task so loop doesn't early-exit
                            rawCommand = rawCmd,
                            query = browserQuery
                        )
                        Log.d(TAG, "Rerouted to Browser URL ($originalApp) with query: $browserQuery, task updated to 'search'")
                    }
                    "Cancel" -> {
                        stop()
                        return false
                    }
                }
            }

            // Launch targetApp (either original or browser URL)
            _lastActionDescription.value = "Launching $targetApp…"
            val isBrowserLaunch = targetApp == "browser_url" || (_currentGoal.value?.targetApp == "browser" && goal.targetApp != "browser")
            
            if (isBrowserLaunch) {
                // BROWSER PREREQUISITE CHECK
                val hasKiwi = AppLauncher.hasExactApp(context, "com.kiwibrowser.browser")
                val hasLemur = AppLauncher.hasExactApp(context, "com.lemurbrowser.exts")
                val hasFenix = AppLauncher.hasExactApp(context, "org.mozilla.fenix")
                
                if (!hasKiwi && !hasLemur && !hasFenix) {
                    Log.w(TAG, "No supported extension browser found. Suspending for user input.")
                    val installChoice = waitForUserChoice(
                        prompt = "To use web automation, please install a supported browser.",
                        options = listOf("Install Kiwi (Recommended)", "Install Firefox Nightly", "Install Lemur", "Cancel")
                    )
                    
                    when (installChoice) {
                        "Install Kiwi (Recommended)" -> {
                            AppLauncher.launchPlayStore(context, "com.kiwibrowser.browser")
                            stop()
                            return false
                        }
                        "Install Firefox Nightly" -> {
                            AppLauncher.launchPlayStore(context, "org.mozilla.fenix")
                            stop()
                            return false
                        }
                        "Install Lemur" -> {
                            AppLauncher.launchPlayStore(context, "com.lemurbrowser.exts")
                            stop()
                            return false
                        }
                        else -> {
                            stop()
                            return false
                        }
                    }
                }
                
                // Use LLM-resolved domain (e.g. flipkart.com, amazon.in)
                val rGoal = _currentGoal.value ?: goal
                val baseDomain = rGoal.domain ?: goal.domain ?: goal.targetApp
                val browserQuery = rGoal.query ?: goal.query
                val rawPrompt = rGoal.rawCommand ?: goal.rawCommand
                val taskType = rGoal.task ?: goal.task

                // Always launch the base domain. The agentic loop will wait for the extension
                // to connect and provide the DOM to click the website's search box and interact.
                val urlToLaunch = baseDomain

                // Build a command for the extension's content script
                val extensionCommand = mutableMapOf(
                    "cmd" to (taskType ?: "prompt"),
                    "raw" to (rawPrompt ?: ""),
                )
                if (!browserQuery.isNullOrBlank()) {
                    extensionCommand["q"] = browserQuery
                }

                val launched = AppLauncher.launchBrowserUrlWithCommand(context, urlToLaunch!!, extensionCommand)
                if (launched) {
                    Log.d(TAG, "Launched browser with URL: $urlToLaunch and command: $extensionCommand")
                    delay(APP_LAUNCH_DELAY_MS)

                    // ── EXTENSION CONNECTION CHECK ──
                    // After the browser launches, give the extension a moment to connect,
                    // then check if the bridge has an active WebSocket connection.
                    if (!extensionBridge.isConnected()) {
                        // Wait a bit longer — the extension may still be loading the page
                        delay(3000L)
                    }

                    if (!extensionBridge.isConnected()) {
                        Log.w(TAG, "Browser launched but extension not connected. Prompting user.")
                        DebugLogger.warning(
                            context, LogCategory.SCREEN_CONTEXT_AI,
                            "Browser extension not detected",
                            "Browser is open but the Autonion Android Extension is not connected. " +
                            "Web automation will fall back to the accessibility tree, which cannot interact with page content.",
                            TAG
                        )

                        val extChoice = waitForUserChoice(
                            prompt = "⚠️ Browser extension not detected. For better web automation, install the Autonion Android Extension from the releases page.",
                            options = listOf("Continue without extension", "Download Extension", "Cancel")
                        )

                        when (extChoice) {
                            "Download Extension" -> {
                                _lastActionDescription.value = "Opening extension download page…"
                                val downloadUrl = "https://github.com/Autonion/Autonion-Android-Extension/releases"
                                AppLauncher.launchBrowserUrl(context, downloadUrl)
                                stop()
                                return false
                            }
                            "Cancel" -> {
                                stop()
                                return false
                            }
                            else -> {
                                // "Continue without extension" — proceed with accessibility tree fallback
                                Log.d(TAG, "User chose to continue without extension")
                                _lastActionDescription.value = "Continuing without extension (limited web interaction)…"
                                DebugLogger.info(
                                    context, LogCategory.SCREEN_CONTEXT_AI,
                                    "Continuing without extension",
                                    "User chose to proceed with accessibility tree fallback for web automation.",
                                    TAG
                                )
                            }
                        }
                    }

                    return true
                }
            } else {
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
        }

        // ── Generic Clarification: Task needs an app but none was specified ──
        // Works for ANY task type (play, search, send_message, navigate, etc.)
        if (targetApp == null && !goal.query.isNullOrBlank()) {
            val category = AppLauncher.taskToCategory(goal.task)
            if (category != null) {
                Log.d(TAG, "Task '${goal.task}' needs an app but none specified — discovering ${category.name} apps")
                val relevantApps = AppLauncher.getInstalledAppsByCategory(context, category)
                val options = relevantApps + listOf("Browser", "Cancel")

                // Build a natural-sounding prompt based on task type
                val verb = when (goal.task) {
                    "play"         -> "play"
                    "search"       -> "search for"
                    "send_message" -> "send"
                    "navigate"     -> "navigate to"
                    "open"         -> "open"
                    "login"        -> "log into"
                    else           -> goal.task
                }

                val choice = waitForUserChoice(
                    prompt = "In which app should I $verb \"${goal.query}\"?",
                    options = emptyList() // Render no chips; wait for user to type their answer naturally
                )

                when (choice.lowercase()) {
                    "cancel" -> {
                        stop()
                        return false
                    }
                    "browser", "web" -> {
                        _currentGoal.value = _currentGoal.value?.copy(
                            targetApp = "browser",
                            domain = "youtube.com", // This will be dynamic based on further LLM updates if needed
                            task = "search"
                        )
                        _lastActionDescription.value = "Opening in Browser…"
                    }
                    else -> {
                        // User typed an app name (e.g., "in youtube", "open spotify")
                        var cleanedChoice = choice.trim().lowercase()
                        val prefixes = listOf("in ", "on ", "using ", "open ", "start ", "play on ", "search on ")
                        for (prefix in prefixes) {
                            if (cleanedChoice.startsWith(prefix)) {
                                cleanedChoice = cleanedChoice.removePrefix(prefix).trim()
                                break
                            }
                        }

                        val appAlias = cleanedChoice
                        _currentGoal.value = _currentGoal.value?.copy(
                            targetApp = appAlias,
                            task = goal.task // Preserve original task instead of hardcoding "search"
                        )
                        val launched = AppLauncher.launchApp(context, appAlias)
                        if (launched) {
                            _lastActionDescription.value = "Opening $cleanedChoice…"
                            delay(APP_LAUNCH_DELAY_MS)
                            return true
                        } else {
                            Log.w(TAG, "Failed to launch app after clarification: $appAlias")
                            _lastActionDescription.value = "Could not find app: $cleanedChoice"
                            stop()
                            return false
                        }
                    }
                }
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
        // ── Extension DOM path: route actions through the browser extension ──
        // When the UI state came from DOM snapshots, actions must go through the extension
        // because Android accessibility can't interact with web page content inside Firefox/Chrome.
        if (uiState.source == com.autonion.automationcompanion.features.semantic_automation.model.ElementSource.EXTENSION_DOM
            && extensionBridge.isConnected()) {

            val targetElement = if (action.targetId != null && action.targetId.startsWith("slm_element_")) {
                val index = action.targetId.removePrefix("slm_element_").toIntOrNull()
                if (index != null && index >= 0 && index < uiState.elements.size) {
                    uiState.elements[index]
                } else null
            } else if (action.targetId != null) {
                uiState.elements.firstOrNull { it.id == action.targetId }
            } else if (action.targetPoint != null) {
                // Find element by center point match
                uiState.elements.firstOrNull { el ->
                    val center = android.graphics.PointF(
                        (el.bounds.left + el.bounds.right) / 2f,
                        (el.bounds.top + el.bounds.bottom) / 2f
                    )
                    Math.abs(center.x - action.targetPoint.x) < 5f &&
                    Math.abs(center.y - action.targetPoint.y) < 5f
                }
            } else null

            val elementId = targetElement?.id ?: action.targetId

            val result = when (action.type) {
                ActionType.CLICK -> {
                    if (elementId != null) {
                        Log.d(TAG, "Extension bridge CLICK on '$elementId' (${targetElement?.text})")
                        extensionBridge.clickElement(elementId)
                    } else {
                        Log.w(TAG, "No element ID for extension CLICK, falling through to gesture")
                        null
                    }
                }
                ActionType.INPUT_TEXT -> {
                    if (elementId != null && action.inputText != null) {
                        Log.d(TAG, "Extension bridge TYPE into '$elementId': '${action.inputText}'")
                        extensionBridge.typeInto(elementId, action.inputText, pressEnter = false)
                    } else {
                        Log.w(TAG, "Missing element/text for extension TYPE")
                        null
                    }
                }
                ActionType.SCROLL_DOWN -> {
                    Log.d(TAG, "Extension bridge SCROLL_DOWN")
                    extensionBridge.scrollDown()
                }
                ActionType.SCROLL_UP -> {
                    Log.d(TAG, "Extension bridge SCROLL_UP")
                    extensionBridge.scrollUp()
                }
                else -> null
            }

            if (result != null) {
                Log.d(TAG, "Extension bridge action result: success=${result.success}, msg=${result.message ?: result.error}")
                return result.success
            }
            Log.d(TAG, "Extension bridge could not handle ${action.type}, falling through to accessibility")
        }

        // ── Accessibility path: native Android UI actions ──
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
                        val userPrompt = UIPromptFormatter.buildUserPrompt(goal, uiState, stepHistory, chatMemory.buildContextSummary())
                        
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
            InferenceMode.CLOUD_API -> {
                // Tier 1: Cloud API LLM (OpenAI-compatible endpoints)
                if (cloudApiEngine.isConfigured) {
                    try {
                        val systemPrompt = UIPromptFormatter.buildSystemPrompt()
                        val userPrompt = UIPromptFormatter.buildUserPrompt(goal, uiState, stepHistory, chatMemory.buildContextSummary())

                        Log.d(TAG, "Cloud API prompt: ${userPrompt.take(500)}")

                        val cloudAction = cloudApiEngine.predictNextAction(systemPrompt, userPrompt)
                        if (cloudAction != null) {
                            val resolved = resolveSlmAction(cloudAction, uiState)
                            if (resolved != null) return resolved
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Cloud API prediction failed, falling through to ML", e)
                    }
                } else {
                    Log.w(TAG, "Cloud API not configured, falling through to ML")
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

        // Compare a fingerprint of element texts and checkout state
        val oldFingerprint = oldState.elements.take(10).joinToString("|") { "${it.text}_${it.isChecked}" }
        val newFingerprint = newState.elements.take(10).joinToString("|") { "${it.text}_${it.isChecked}" }
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
        if (goal.task == "enable" || goal.task == "disable") return "settings"
        val alias = goal.targetApp ?: return null
        return when (alias.lowercase()) {
            "youtube" -> "youtube"
            "amazon" -> "amazon"
            "whatsapp" -> "whatsapp"
            "instagram" -> "instagram"
            "chrome" -> "chrome" // Note: "browser" keeps targetPackage = "browser" because it falls to the 'else' block, picking it up
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

    // buildSearchUrl() has been REMOVED.
    // The LLM-based GoalParser now provides the correct domain (via goal.domain)
    // and when the extension is not connected, we use a simple Google search
    // with the user's query + app name. No hardcoded URL patterns needed.

    // ── Anti-hallucination validation gate ──────────────────────

    /**
     * Validates a parsed goal BEFORE executing any pre-actions.
     * Returns null if the goal is valid, or a human-readable error message if it should be rejected.
     *
     * Three checks:
     * 1. Confidence threshold — LLM self-rated confidence must be >= 0.4
     * 2. Unknown task — if the LLM explicitly said "unknown", reject
     * 3. Target app existence — if an app was specified, it must be recognizable
     */
    private fun validateGoal(goal: SemanticGoal): String? {
        // Check 1: Low confidence = likely hallucination
        if (goal.confidence < 0.4f) {
            Log.w(TAG, "Goal confidence too low: ${goal.confidence}")
            return "I couldn't understand that command. Could you rephrase it? (confidence: ${(goal.confidence * 100).toInt()}%)"
        }

        // Check 2: LLM explicitly classified as unknown
        if (goal.task == "unknown") {
            Log.w(TAG, "Goal task is 'unknown'")
            return "I didn't understand what you want me to do. Try something like \"play music on youtube\" or \"search shoes on amazon\"."
        }

        // Check 3: If a target app is specified, verify it's recognizable
        // Skip this check for system tasks (enable/disable) and tasks without an app target
        val targetApp = goal.targetApp
        if (targetApp != null && goal.task !in listOf("enable", "disable", "back", "scroll")) {
            val isRecognizable = AppLauncher.isRecognizableTarget(context, targetApp)
            if (!isRecognizable) {
                // Also check domain — maybe the app isn't installed but the domain is valid
                val hasDomain = goal.domain != null && goal.domain.contains(".")
                if (!hasDomain) {
                    Log.w(TAG, "Unrecognizable target app: '$targetApp'")
                    return "I don't recognize \"$targetApp\" as an app or website. Did you mean something else?"
                }
            }
        }

        Log.d(TAG, "Goal validation passed ✓ (confidence=${goal.confidence})")
        return null
    }

    // ── Sub-goal task inference (deterministic) ──────────────────

    /**
     * Infers the task type from a sub-goal description string without an LLM call.
     * Returns null if no clear task type can be determined (caller keeps the master task).
     */
    private fun inferTaskFromDescription(description: String): String? {
        val lower = description.lowercase().trim()
        return when {
            lower.startsWith("open ") || lower.startsWith("launch ") -> "open"
            lower.startsWith("search ") || lower.startsWith("find ") -> "search"
            lower.startsWith("go to ") || lower.startsWith("navigate ") -> "navigate"
            lower.startsWith("click ") || lower.startsWith("tap ") || lower.startsWith("press ") || lower.startsWith("select ") -> "tap"
            lower.startsWith("type ") || lower.startsWith("enter ") || lower.startsWith("write ") -> "type"
            lower.startsWith("delete ") || lower.startsWith("remove ") -> "navigate" // delete = navigate to item then interact
            lower.startsWith("play ") -> "play"
            lower.startsWith("scroll ") || lower.startsWith("swipe ") -> "scroll"
            lower.startsWith("enable ") || lower.startsWith("turn on ") -> "enable"
            lower.startsWith("disable ") || lower.startsWith("turn off ") -> "disable"
            lower.startsWith("send ") -> "send_message"
            lower.startsWith("close ") || lower.startsWith("exit ") -> "back"
            lower.startsWith("save ") || lower.startsWith("download ") -> "navigate"
            else -> null
        }
    }
}
