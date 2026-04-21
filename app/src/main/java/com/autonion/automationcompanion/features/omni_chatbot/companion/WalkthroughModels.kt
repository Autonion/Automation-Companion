package com.autonion.automationcompanion.features.omni_chatbot.companion

/**
 * A single step in a guided walkthrough.
 *
 * @param instruction   The text shown to the user in the floating bar.
 * @param targetRoute   The navigation route this step requires (null = stay on current).
 * @param stepType      Whether this step navigates, observes, or asks the user to act.
 * @param highlightHint Optional hint describing which UI element to interact with.
 */
data class WalkthroughStep(
    val instruction: String,
    val targetRoute: String? = null,
    val stepType: StepType = StepType.OBSERVE,
    val highlightHint: String? = null
)

/**
 * A complete walkthrough script for a feature.
 *
 * @param featureId   Machine-readable ID, e.g. "flow_builder".
 * @param featureName Human-readable name, e.g. "Flow Builder".
 * @param description One-line description of the feature.
 * @param steps       Ordered list of walkthrough steps.
 */
data class WalkthroughScript(
    val featureId: String,
    val featureName: String,
    val description: String,
    val steps: List<WalkthroughStep>
)

/**
 * Describes what happens during a walkthrough step.
 */
enum class StepType {
    /** The companion navigates to a new screen. */
    NAVIGATE,

    /** The companion shows information about the current screen. */
    OBSERVE,

    /** The user needs to perform a specific action. */
    ACTION
}
