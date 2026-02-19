package com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas

import androidx.compose.ui.graphics.Color

/**
 * Node visual constants and color mapping.
 */
object NodeColors {
    val StartGreen = Color(0xFF2E7D32)
    val StartGreenBg = Color(0xFF1B5E20)
    val GestureBlue = Color(0xFF1565C0)
    val GestureBlueBg = Color(0xFF0D47A1)
    val VisualTriggerPurple = Color(0xFF7B1FA2)
    val VisualTriggerPurpleBg = Color(0xFF4A148C)
    val ScreenMLAmber = Color(0xFFF9A825)
    val ScreenMLAmberBg = Color(0xFFF57F17)
    val DelayGrey = Color(0xFF546E7A)
    val DelayGreyBg = Color(0xFF37474F)

    val EdgeDefault = Color(0xFF90A4AE)
    val EdgeActive = Color(0xFF64FFDA)
    val EdgeFailure = Color(0xFFEF5350)

    val PortOutput = Color(0xFF81C784)
    val PortFailure = Color(0xFFEF5350)

    val NodeSelected = Color(0xFF64FFDA)
}

object NodeDimensions {
    const val WIDTH = 180f
    const val HEIGHT = 72f
    const val CORNER_RADIUS = 16f
    const val PORT_RADIUS = 8f
    const val GRID_SIZE = 40f
    const val ICON_SIZE = 24f
}
