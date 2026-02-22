package com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Theme-aware colors for the flow editor.
 * Provides light and dark variants for all editor chrome
 * (canvas background, grid, panels, ports, text).
 *
 * Node accent colors (green, blue, purple, amber, grey, teal) remain
 * the same in both themes — only backgrounds and surfaces adapt.
 */
data class FlowEditorColors(
    // Canvas
    val canvasBg: Color,
    val gridLine: Color,
    // Node body
    val nodeBodyBg: Color,
    val nodeShadow: Color,
    val nodeHighlight: Color,
    val nodeBorderBg: Color,
    // Text
    val titleText: Color,
    val subtitleBgAlpha: Float,
    // Ports
    val portBg: Color,
    // Panel chrome
    val panelBg: Color,
    val panelText: Color,
    val panelDimText: Color,
    // Top bar
    val topBarText: Color,
    val topBarDimText: Color,
    val topBarBadgeBg: Color,
    // Misc
    val connectionIndicatorBg: Color,
    val connectionIndicatorText: Color,
) {
    companion object {
        val Dark = FlowEditorColors(
            canvasBg = Color(0xFF101216),
            gridLine = Color(0xFF1E2128),
            nodeBodyBg = Color(0xFF0D0F12),
            nodeShadow = Color.Black,
            nodeHighlight = Color.White.copy(alpha = 0.08f),
            nodeBorderBg = Color(0xFF101216),
            titleText = Color.White,
            subtitleBgAlpha = 0.1f,
            portBg = Color(0xFF1A1C20),
            panelBg = Color(0xFF1A1C1E),
            panelText = Color.White,
            panelDimText = Color.White.copy(alpha = 0.6f),
            topBarText = Color.White,
            topBarDimText = Color.White.copy(alpha = 0.25f),
            topBarBadgeBg = Color.White.copy(alpha = 0.1f),
            connectionIndicatorBg = Color(0xFF64FFDA).copy(alpha = 0.9f),
            connectionIndicatorText = Color.Black,
        )

        val Light = FlowEditorColors(
            canvasBg = Color(0xFFF5F6FA),
            gridLine = Color(0xFFE0E2E8),
            nodeBodyBg = Color.White,
            nodeShadow = Color(0xFF9E9E9E),
            nodeHighlight = Color.White.copy(alpha = 0.5f),
            nodeBorderBg = Color.White,
            titleText = Color(0xFF1A1C1E),
            subtitleBgAlpha = 0.12f,
            portBg = Color(0xFFE8EAED),
            panelBg = Color(0xFFF0F1F5),
            panelText = Color(0xFF1A1C1E),
            panelDimText = Color(0xFF1A1C1E).copy(alpha = 0.5f),
            topBarText = Color(0xFF1A1C1E),
            topBarDimText = Color(0xFF1A1C1E).copy(alpha = 0.3f),
            topBarBadgeBg = Color.Black.copy(alpha = 0.07f),
            connectionIndicatorBg = Color(0xFF00897B).copy(alpha = 0.9f),
            connectionIndicatorText = Color.White,
        )
    }
}

val LocalFlowEditorColors = staticCompositionLocalOf { FlowEditorColors.Dark }

/**
 * Resolve the current editor color palette based on system theme.
 */
@Composable
fun flowEditorColors(): FlowEditorColors {
    return if (isSystemInDarkTheme()) FlowEditorColors.Dark else FlowEditorColors.Light
}

/**
 * Node visual constants and color mapping.
 *
 * Design: Premium glow-style nodes with glassmorphic backgrounds + border gradients.
 * Color-coding: Green=Start, Orange=AI/ML, Blue=Action, Purple=Vision, Grey=Utility
 */
object NodeColors {
    // ── Node type colors (header accent) — same in both themes ──
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
    const val WIDTH = 550f
    const val HEIGHT = 175f
    const val HEADER_HEIGHT = 0f
    const val CORNER_RADIUS = 24f
    const val PORT_RADIUS = 20f
    const val PORT_HIT_RADIUS = 50f
    const val GRID_SIZE = 40f
    const val ICON_SIZE = 32f
}
