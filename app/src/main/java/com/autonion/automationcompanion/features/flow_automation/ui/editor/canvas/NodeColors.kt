package com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas

import androidx.compose.ui.graphics.Color

/**
 * Node visual constants and color mapping.
 *
 * Design: Premium glow-style nodes with glassmorphic backgrounds + border gradients.
 * Color-coding: Green=Start, Orange=AI/ML, Blue=Action, Purple=Vision, Grey=Utility
 */
object NodeColors {
    // ── Node type colors (header accent) ──
    val StartGreen      = Color(0xFF4ADE80)
    val StartGreenBg    = Color(0xFF0F1A14)
    val GestureBlue     = Color(0xFF60A5FA)
    val GestureBlueBg   = Color(0xFF101722)
    val VisualTriggerPurple   = Color(0xFFC084FC)
    val VisualTriggerPurpleBg = Color(0xFF19121F)
    val ScreenMLAmber   = Color(0xFFFBBF24)
    val ScreenMLAmberBg = Color(0xFF1F180E)
    val DelayGrey       = Color(0xFF9CA3AF)
    val DelayGreyBg     = Color(0xFF141517)
    val LaunchAppTeal   = Color(0xFF2DD4BF)
    val LaunchAppTealBg = Color(0xFF0B1F1C)

    // ── Edge & port colors ──
    val EdgeDefault     = Color(0xFF94A3B8)
    val EdgeActive      = Color(0xFF64FFDA)
    val EdgeFailure     = Color(0xFFF87171)

    val PortOutput      = Color(0xFF4ADE80)
    val PortFailure     = Color(0xFFF87171)
    val PortInput       = Color(0xFF94A3B8)

    val NodeSelected    = Color.White
    val NodeBodyBg      = Color(0xFF0D0F12) 
    val DividerLine     = Color(0x33FFFFFF)
}

object NodeDimensions {
    const val WIDTH = 480f
    const val HEIGHT = 160f
    const val HEADER_HEIGHT = 0f
    const val CORNER_RADIUS = 24f
    const val PORT_RADIUS = 12f
    const val PORT_HIT_RADIUS = 40f
    const val GRID_SIZE = 40f
    const val ICON_SIZE = 32f
}
