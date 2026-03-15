package com.autonion.automationcompanion.features.semantic_automation.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.screen_understanding_ml.logic.ActionExecutor
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import com.autonion.automationcompanion.features.semantic_automation.model.AutomationStatus
import com.autonion.automationcompanion.features.semantic_automation.model.SemanticGoal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The orchestration engine that runs the Screen Loop:
 *
 *   0. Parse goal & execute pre-actions (launch target app)
 *   1. Capture screenshot (or use accessibility tree)
 *   2. Build ScreenUIState
 *   3. Predict next action
 *   4. Execute action
 *   5. Wait for screen to settle
 *   6. Repeat until goal is achieved or cancelled
 */
class SemanticAutomationEngine(private val context: Context) {

    companion object {
        private const val TAG = "SemanticEngine"
        private const val MAX_LOOP_ITERATIONS = 50
        private const val POST_ACTION_DELAY_MS = 2500L
        private const val NO_ACTION_DELAY_MS = 1500L
        private const val APP_LAUNCH_DELAY_MS = 3000L  // Wait for app to fully start
    }

    private val goalParser = GoalParser()
    private val uiStateBuilder = UIStateBuilder(context)
    private val actionPredictor = ActionPredictor()

    private val _status = MutableStateFlow(AutomationStatus.IDLE)
    val status: StateFlow<AutomationStatus> = _status.asStateFlow()

    private val _currentGoal = MutableStateFlow<SemanticGoal?>(null)
    val currentGoal: StateFlow<SemanticGoal?> = _currentGoal.asStateFlow()

    private val _loopCount = MutableStateFlow(0)
    val loopCount: StateFlow<Int> = _loopCount.asStateFlow()

    private val _lastActionDescription = MutableStateFlow<String?>(null)
    val lastActionDescription: StateFlow<String?> = _lastActionDescription.asStateFlow()

    @Volatile
    private var isRunning = false

    /**
     * Parse a raw user command and kick off the screen loop.
     */
    suspend fun runLoop(rawCommand: String, screenshotProvider: suspend () -> Bitmap?) {
        // ── Step 0: Parse goal ──
        _status.value = AutomationStatus.PARSING_GOAL
        val goal = goalParser.parse(rawCommand)
        _currentGoal.value = goal

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

        // ── Step 2: Screen loop ──
        var iteration = 0
        var consecutiveNoAction = 0

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
                consecutiveNoAction++
                if (consecutiveNoAction >= 5) {
                    Log.w(TAG, "5 consecutive empty states, stopping")
                    _status.value = AutomationStatus.FAILED
                    _lastActionDescription.value = "Cannot read screen — check permissions"
                    break
                }
                continue
            }
            consecutiveNoAction = 0

            // 2c. Predict action
            _status.value = AutomationStatus.PREDICTING_ACTION
            val action = actionPredictor.predict(goal, uiState)

            if (action == null) {
                Log.w(TAG, "No action predicted, waiting…")
                _lastActionDescription.value = "No matching elements found, retrying…"
                delay(NO_ACTION_DELAY_MS)
                continue
            }

            // 2d. Check for completion
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

            // 2e. Execute action
            _status.value = AutomationStatus.EXECUTING_ACTION
            _lastActionDescription.value = action.description
            Log.d(TAG, "Executing: ${action.type} – ${action.description}")

            val success = try {
                ActionExecutor.execute(context, action)
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

            // 2f. Wait for screen to settle
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
        val targetApp = goal.targetApp
        if (targetApp != null) {
            _lastActionDescription.value = "Launching $targetApp…"
            val launched = AppLauncher.launchApp(context, targetApp)
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

    fun stop() {
        isRunning = false
        _status.value = AutomationStatus.CANCELLED
        _lastActionDescription.value = "Cancelled by user"
    }

    fun cleanup() {
        stop()
        uiStateBuilder.close()
        _status.value = AutomationStatus.IDLE
        _currentGoal.value = null
        _loopCount.value = 0
        _lastActionDescription.value = null
    }
}
