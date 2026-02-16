package com.autonion.automationcompanion.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand Colors
val KeyBlue = Color(0xFF0061A4)
val KeyTeal = Color(0xFF006A6A)
val KeyPurple = Color(0xFF6750A4)

// Light Theme Colors
val LightPrimary = KeyBlue
val LightOnPrimary = Color.White
val LightPrimaryContainer = Color(0xFFD1E4FF)
val LightOnPrimaryContainer = Color(0xFF001D36)

val LightSecondary = KeyTeal
val LightOnSecondary = Color.White
val LightSecondaryContainer = Color(0xFF6FF7F7)
val LightOnSecondaryContainer = Color(0xFF002020)

val LightTertiary = KeyPurple
val LightOnTertiary = Color.White
val LightTertiaryContainer = Color(0xFFEADDFF)
val LightOnTertiaryContainer = Color(0xFF21005D)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color.White
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

val LightBackground = Color(0xFFFDFCFF)
val LightOnBackground = Color(0xFF1A1C1E)
val LightSurface = Color(0xFFFDFCFF)
val LightOnSurface = Color(0xFF1A1C1E)

// Dark Theme Colors
val DarkPrimary = Color(0xFF9ECAFF)
val DarkOnPrimary = Color(0xFF003258)
val DarkPrimaryContainer = Color(0xFF00497D)
val DarkOnPrimaryContainer = Color(0xFFD1E4FF)

val DarkSecondary = Color(0xFF4CDADA)
val DarkOnSecondary = Color(0xFF003737)
val DarkSecondaryContainer = Color(0xFF004F4F)
val DarkOnSecondaryContainer = Color(0xFF6FF7F7)

val DarkTertiary = Color(0xFFD0BCFF)
val DarkOnTertiary = Color(0xFF381E72)
val DarkTertiaryContainer = Color(0xFF4F378B)
val DarkOnTertiaryContainer = Color(0xFFEADDFF)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkBackground = Color(0xFF101216) // Much darker, almost black
val DarkOnBackground = Color(0xFFE2E2E6)
val DarkSurface = Color(0xFF22252B) // Lighter than background to show card edges
val DarkOnSurface = Color(0xFFE2E2E6)

// V2 Design Accents
val AccentPurple = Color(0xFF7C4DFF)
val AccentPurpleContainer = Color(0xFFF2E7FE)
val AccentBlue = Color(0xFF00B0FF)
val AccentBlueContainer = Color(0xFFE1F5FE)
val AccentGreen = Color(0xFF00C853)
val AccentGreenContainer = Color(0xFFE8F5E9)

// Light Theme Containers
val AccentOrange = Color(0xFFFF6D00)
val AccentOrangeContainer = Color(0xFFFFF3E0)
val AccentRed = Color(0xFFFF1744)
val AccentRedContainer = Color(0xFFFFEBEE)
val AccentGrey = Color(0xFF546E7A)
val AccentGreyContainer = Color(0xFFECEFF1)

// Dark Theme Containers (New)
val DarkAccentRedContainer = Color(0xFF5A1A1A) // Muted Red for dark mode
val DarkAccentGreyContainer = Color(0xFF2A2D35) // Muted Grey for dark mode

val DarkBannerBackground = Color(0xFF1C2235)
val LightBannerBackground = Color(0xFF2D3A5C)

val AppLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
)

val AppDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
)