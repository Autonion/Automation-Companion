package com.autonion.automationcompanion.ui.components

import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import kotlin.random.Random
import com.autonion.automationcompanion.ui.theme.DarkBackground

@SuppressLint("UnrememberedMutableState")
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    
    // Config for gradients
    val bgBase = if (isDark) Color(0xFF0F1115) else Color(0xFFF3F6FD) // Dark: Rich Black, Light: Pale Blue-White
    
    // Top Right Orb — slightly stronger alpha so motion is more visible
    val topRightColor = if (isDark) Color(0xFF7C4DFF).copy(alpha = 0.32f) else Color(0xFFE1BEE7).copy(alpha = 0.65f)
    // Top Left Orb
    val topLeftColor = if (isDark) Color(0xFF00B0FF).copy(alpha = 0.26f) else Color(0xFFD1E4FF).copy(alpha = 0.65f)

    val noiseBitmap = remember {
        generateNoiseBitmap(100, 100) 
    }
    
    // Battery Saver Optimization
    val context = androidx.compose.ui.platform.LocalContext.current
    val powerManager = remember { context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager }
    var isPowerSaveMode by remember { androidx.compose.runtime.mutableStateOf(powerManager.isPowerSaveMode) }

    // Listen for power save mode changes
    androidx.compose.runtime.DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                isPowerSaveMode = powerManager.isPowerSaveMode
            }
        }
        val filter = android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        context.registerReceiver(receiver, filter)
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Animation State
    // If power save is on, we stop the infinite transition by not using it for values
    val infiniteTransition = rememberInfiniteTransition(label = "Aurora")
    
    val smoothEasing = FastOutSlowInEasing
    val movePx = 120f
    val durationFast = 2800
    val durationSlow = 3400

    // Use static values if in power save mode
    val topRightX by if (isPowerSaveMode) androidx.compose.runtime.mutableFloatStateOf(0f) else infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = movePx,
        animationSpec = infiniteRepeatable(
            animation = tween(durationFast, easing = smoothEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "TR_X"
    )
    val topRightY by if (isPowerSaveMode) androidx.compose.runtime.mutableFloatStateOf(0f) else infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = movePx * 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationSlow, easing = smoothEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "TR_Y"
    )

    val topLeftX by if (isPowerSaveMode) androidx.compose.runtime.mutableFloatStateOf(0f) else infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -movePx * 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationSlow, easing = smoothEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "TL_X"
    )
    val topLeftY by if (isPowerSaveMode) androidx.compose.runtime.mutableFloatStateOf(0f) else infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = movePx * 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationFast + 200, easing = smoothEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "TL_Y"
    )

    Box(modifier = modifier.fillMaxSize().background(bgBase)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 1. Base Background
            drawRect(color = bgBase)

            // 2. Large Soft Gradients matches reference
            // Top Right (Huge Pink/Purple Glow)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(topRightColor, Color.Transparent),
                    center = Offset(size.width * 0.85f + topRightX, size.height * 0.15f + topRightY),
                    radius = size.width * 0.9f
                ),
                center = Offset(size.width * 0.85f + topRightX, size.height * 0.15f + topRightY),
                radius = size.width * 0.9f
            )

            // Top Left (Blue Glow) - Slightly smaller/subtler
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(topLeftColor, Color.Transparent),
                    center = Offset(size.width * 0.1f + topLeftX, size.height * 0.05f + topLeftY),
                    radius = size.width * 0.7f
                ),
                center = Offset(size.width * 0.1f + topLeftX, size.height * 0.05f + topLeftY),
                radius = size.width * 0.7f
            )

            // 3. Subtle Noise Texture Overlay (Optional, keep very subtle)
            drawIntoCanvas { canvas ->
                val paint = Paint().apply {
                    alpha = 10 // Reduced alpha for even subtler effect
                    shader = android.graphics.BitmapShader(
                        noiseBitmap,
                        android.graphics.Shader.TileMode.REPEAT,
                        android.graphics.Shader.TileMode.REPEAT
                    )
                }
                canvas.nativeCanvas.drawPaint(paint)
            }
        }
        
        // Content on top
        content()
    }
}

private fun generateNoiseBitmap(width: Int, height: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(width * height)
    
    for (i in pixels.indices) {
        val alpha = Random.nextInt(256)
        val gray = Random.nextInt(256)
        pixels[i] = AndroidColor.argb(alpha, gray, gray, gray)
    }
    
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}
