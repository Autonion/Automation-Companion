package com.autonion.automationcompanion.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Simple window width classification aligned with Material 3 breakpoints.
 * No extra dependency required — uses [LocalConfiguration].
 */
sealed class WindowWidthSize {
    /** Phones in portrait (< 600dp) */
    data object Compact : WindowWidthSize()

    /** Small tablets / foldables (600–839dp) */
    data object Medium : WindowWidthSize()

    /** Large tablets / desktop (≥ 840dp) */
    data object Expanded : WindowWidthSize()
}

/**
 * Remember the current [WindowWidthSize] based on the screen width and height.
 * Re-composes automatically on configuration change (rotation, split-screen, etc.).
 */
@Composable
fun rememberWindowWidthSize(): WindowWidthSize {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        classifySize(configuration.screenWidthDp, configuration.screenHeightDp)
    }
}

/**
 * Get the current screen width in dp.
 */
@Composable
fun rememberScreenWidthDp(): Dp {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp.dp
}

/**
 * Convenience: returns `true` on tablet-class screens (Medium or Expanded).
 */
@Composable
fun isTablet(): Boolean {
    return rememberWindowWidthSize() != WindowWidthSize.Compact
}

private fun classifySize(widthDp: Int, heightDp: Int): WindowWidthSize {
    // If the height is very small (phone in landscape), force Compact layout
    // so we don't try to cram a 2-panel tablet UI into a short screen.
    if (heightDp < 480) {
        return WindowWidthSize.Compact
    }
    
    return when {
        widthDp < 600 -> WindowWidthSize.Compact
        widthDp < 840 -> WindowWidthSize.Medium
        else -> WindowWidthSize.Expanded
    }
}
