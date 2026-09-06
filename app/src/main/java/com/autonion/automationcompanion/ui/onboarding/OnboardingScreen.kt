@file:OptIn(ExperimentalMaterial3Api::class)
package com.autonion.automationcompanion.ui.onboarding

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.core.onboarding.OnboardingPreferences
import com.autonion.automationcompanion.features.gesture_recording_playback.overlay.AutomationService
import com.autonion.automationcompanion.ui.components.AuroraBackground
import kotlinx.coroutines.launch

private val AccentPurple = Color(0xFF7C4DFF)
private val AccentBlue = Color(0xFF448AFF)
private val AccentGreen = Color(0xFF00E676)
private val CardBg = Color(0xFF1A1D2E)
private val CardGlass = Color(0xFF1E2234)

/**
 * First-launch onboarding wizard with 4 screens.
 *
 * Screen 1: Welcome
 * Screen 2: AI Setup (skip-friendly)
 * Screen 3: Permissions (skip-friendly)
 * Screen 4: Quick Start (pick a feature to try)
 *
 * @param onComplete        Called when onboarding is finished (navigates to home)
 * @param onNavigateToRoute Called when user picks a feature on the last screen
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onNavigateToRoute: (String) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val onboardingPrefs = remember { OnboardingPreferences.getInstance(context) }

    fun finishOnboarding(navigateTo: String? = null) {
        onboardingPrefs.hasCompletedOnboarding = true
        if (navigateTo != null) {
            onNavigateToRoute(navigateTo)
        } else {
            onComplete()
        }
    }

    AuroraBackground(forceDark = true) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Page indicators
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(3) { index ->
                    val isActive = pagerState.currentPage == index
                    val width by animateDpAsState(
                        targetValue = if (isActive) 24.dp else 8.dp,
                        animationSpec = tween(300),
                        label = "indicator"
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .height(8.dp)
                            .width(width)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isActive) AccentPurple
                                else Color.White.copy(alpha = 0.2f)
                            )
                    )
                }
            }

            // Pages
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> AISetupPage()
                    2 -> QuickStartPage(
                        onPickFeature = { route -> finishOnboarding(route) },
                        onGoHome = { finishOnboarding() }
                    )
                }
            }

            // Bottom navigation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Skip
                if (pagerState.currentPage < 2) {
                    TextButton(onClick = { finishOnboarding() }) {
                        Text(
                            "Skip",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 14.sp
                        )
                    }
                } else {
                    Spacer(Modifier.width(64.dp))
                }

                // Next / Get Started
                if (pagerState.currentPage < 2) {
                    Button(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(AccentPurple, AccentBlue)
                                    ),
                                    RoundedCornerShape(14.dp)
                                )
                                .padding(horizontal = 28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (pagerState.currentPage == 0) "Get Started"
                                    else "Continue",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════
//  PAGE 1: WELCOME
// ═════════════════════════════════════════════════════════════

@Composable
private fun WelcomePage() {
    val infiniteTransition = rememberInfiniteTransition(label = "welcome")
    val iconScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "icon_pulse"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App icon
        Box(
            modifier = Modifier
                .size(96.dp)
                .scale(iconScale)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(AccentPurple, AccentBlue)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(Modifier.height(32.dp))

        Text(
            "Welcome to Autonion",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "AI-Powered Automation for Android",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(40.dp))

        // Benefits
        BenefitRow(Icons.Default.TouchApp, "Record & replay gestures across any app")
        Spacer(Modifier.height(16.dp))
        BenefitRow(Icons.Default.SmartToy, "Smart automation that assists with natural language tasks")
        Spacer(Modifier.height(16.dp))
        BenefitRow(Icons.Default.Devices, "Control your desktop from your phone")
    }
}

@Composable
private fun BenefitRow(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AccentPurple.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = AccentPurple,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

// ═════════════════════════════════════════════════════════════
//  PAGE 2: AI SETUP
// ═════════════════════════════════════════════════════════════

@Composable
private fun AISetupPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Psychology,
            contentDescription = null,
            tint = AccentPurple,
            modifier = Modifier.size(56.dp)
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "Power Up with AI",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Connecting to an AI model unlocks Omni-Chat,\nSemantic Automation, and smarter assistance.",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(Modifier.height(32.dp))

        // AI option cards (now neutral glassmorphic to avoid selected-state confusion)
        AIOptionCard(
            emoji = "☁️",
            title = "Cloud API",
            subtitle = "OpenAI, Groq, or any compatible service"
        )
        Spacer(Modifier.height(12.dp))
        AIOptionCard(
            emoji = "🖥️",
            title = "Ollama Server",
            subtitle = "Run models on your PC"
        )
        Spacer(Modifier.height(12.dp))
        AIOptionCard(
            emoji = "📱",
            title = "On-Device SLM",
            subtitle = "Run a small model directly on your phone"
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "You can configure this later in Omni-Chat settings ⚙",
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AIOptionCard(
    emoji: String,
    title: String,
    subtitle: String
) {
    Surface(
        color = CardGlass,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, fontSize = 24.sp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════
//  PAGE 4: QUICK START
// ═════════════════════════════════════════════════════════════

@Composable
private fun QuickStartPage(
    onPickFeature: (String) -> Unit,
    onGoHome: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "What do you want to do first?",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Pick a feature to explore, or jump to the home screen.",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        QuickStartCard(
            emoji = "🎯",
            title = "Record Gestures",
            subtitle = "Replay taps, swipes, and complex gestures",
            color = AccentBlue,
            onClick = { onPickFeature("feature/gesture_recording_playback") }
        )
        Spacer(Modifier.height(12.dp))
        QuickStartCard(
            emoji = "🔄",
            title = "Build Automation Flows",
            subtitle = "Visual drag-and-drop automation builder",
            color = AccentPurple,
            onClick = { onPickFeature("feature/flow_builder") }
        )
        Spacer(Modifier.height(12.dp))
        QuickStartCard(
            emoji = "🤖",
            title = "Try Semantic Automation",
            subtitle = "Automate with natural language commands",
            color = Color(0xFFFF9800),
            onClick = { onPickFeature("feature/semantic_automation") }
        )
        Spacer(Modifier.height(12.dp))
        QuickStartCard(
            emoji = "🖥️",
            title = "Control My Desktop",
            subtitle = "Send commands to your connected PC",
            color = AccentGreen,
            onClick = { onPickFeature("feature/cross_device_automation") }
        )

        Spacer(Modifier.height(24.dp))

        TextButton(onClick = onGoHome) {
            Text(
                "Go to Home →",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun QuickStartCard(
    emoji: String,
    title: String,
    subtitle: String,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, fontSize = 28.sp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════
//  UTILITY
// ═════════════════════════════════════════════════════════════

private fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
    val expectedComponentName = ComponentName(context, AutomationService::class.java)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)

    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledComponent = ComponentName.unflattenFromString(componentNameString)
        if (enabledComponent != null && enabledComponent == expectedComponentName) return true
    }
    return false
}
