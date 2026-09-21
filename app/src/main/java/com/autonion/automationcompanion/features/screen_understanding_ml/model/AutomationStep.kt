package com.autonion.automationcompanion.features.screen_understanding_ml.model

import android.graphics.RectF
import com.google.gson.annotations.SerializedName
import kotlinx.serialization.Serializable

// ActionType is defined in ActionIntent.kt

@Serializable
data class AutomationStep(
    @field:SerializedName("id")
    val id: String,
    @field:SerializedName("orderIndex")
    var orderIndex: Int,
    @field:SerializedName("label")
    val label: String, // e.g., "Login Button"
    @field:SerializedName("actionType")
    val actionType: ActionType = ActionType.CLICK,
    @field:SerializedName("anchor")
    val anchor: UIElement, // The visual anchor for this step
    @field:SerializedName("inputText")
    val inputText: String? = null,
    @field:SerializedName("isOptional")
    val isOptional: Boolean = false,
    @field:SerializedName("captureScreenWidth")
    val captureScreenWidth: Float = 0f,  // Screen width (px) at capture time; 0 = legacy preset
    @field:SerializedName("captureScreenHeight")
    val captureScreenHeight: Float = 0f,  // Screen height (px) at capture time; 0 = legacy preset
    @field:SerializedName("captureImagePath")
    val captureImagePath: String? = null, // Optional editor preview; never required for execution.
    @field:SerializedName("captureMetadataPath")
    val captureMetadataPath: String? = null // Shared per screenshot; editor-only accessibility snapshot.
)
