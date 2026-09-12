package com.autonion.automationcompanion.features.screen_understanding_ml.core

import com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationStep
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ExecutionMode
import com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Each search attempt has its own timeout; required targets keep their position in the sequence. */
internal suspend fun findStepTarget(
    step: AutomationStep, mode: ExecutionMode, isPlaying: () -> Boolean,
    search: suspend () -> UIElement?
): UIElement? {
    while (isPlaying()) {
        currentCoroutineContext().ensureActive()
        val found = search()
        if (found != null || step.isOptional || mode == ExecutionMode.FLEXIBLE) return found
        delay(200)
    }
    return null
}
