package com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas

import androidx.compose.ui.graphics.Color

/**
 * Node visual constants and color mapping.
 *
 * Design: Blueprint-style nodes with colored header + darker body.
 * Color-coding: Green=Start, Orange=AI/ML, Blue=Action, Purple=Vision, Grey=Utility
 */
object NodeColors {
    // ── Node type colors (header accent) ──
    val StartGreen      = Color(0xFF43A047)
    val StartGreenBg    = Color(0xFF1B2E1C)
    val GestureBlue     = Color(0xFF1E88E5)
    val GestureBlueBg   = Color(0xFF132640)
    val VisualTriggerPurple   = Color(0xFFAB47BC)
    val VisualTriggerPurpleBg = Color(0xFF2A1533)
    val ScreenMLAmber   = Color(0xFFFFA726)
    val ScreenMLAmberBg = Color(0xFF332410)
    val DelayGrey       = Color(0xFF78909C)
    val DelayGreyBg     = Color(0xFF1E272C)

    // ── Edge & port colors ──
    val EdgeDefault     = Color(0xFFB0BEC5)
    val EdgeActive      = Color(0xFF64FFDA)
    val EdgeFailure     = Color(0xFFEF5350)

    val PortOutput      = Color(0xFF66BB6A)
    val PortFailure     = Color(0xFFEF5350)
    val PortInput       = Color(0xFF90A4AE)

    val NodeSelected    = Color(0xFF64FFDA)
    val NodeBodyBg      = Color(0xFF1A1D23)   // dark body behind subtitle
    val DividerLine     = Color(0x33FFFFFF)    // subtle divider
}

object NodeDimensions {
    const val WIDTH = 300f
    const val HEIGHT = 120f
    const val HEADER_HEIGHT = 36f     // colored header bar
    const val CORNER_RADIUS = 14f
    const val PORT_RADIUS = 9f        // sleek small ports
    const val PORT_HIT_RADIUS = 28f   // generous tap target
    const val GRID_SIZE = 40f
    const val ICON_SIZE = 28f
}
