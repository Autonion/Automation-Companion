package com.autonion.automationcompanion.ui.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.R
import kotlinx.coroutines.delay

/**
 * Animated Compose-based Splash Screen.
 * Shows the "ANi" logo and "Autonion" typography with a smooth fade-in.
 */
@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }
    val alphaAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "fade"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
        delay(1500)
        onTimeout()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1115)), // Matches the app's rich black theme
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.alpha(alphaAnim)
        ) {
            // "ANi" Logo Foreground Image (Reuses exact custom PNG from your assets)
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                contentDescription = "Autonion Logo",
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(24.dp))
            )
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Clean, Futuristic Typography
            Text(
                text = "Autonion",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 2.sp
            )
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Text(
                text = "AUTOMATION COMPANION",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp
            )
        }
    }
}
