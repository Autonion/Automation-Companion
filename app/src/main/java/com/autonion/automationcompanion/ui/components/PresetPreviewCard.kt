package com.autonion.automationcompanion.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.core.util.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ThumbnailDispatcher = Dispatchers.IO.limitedParallelism(2)

/** Shared dashboard card for presets configured from captured screens. */
@Composable
internal fun PresetPreviewCard(
    name: String,
    summary: String,
    captureImagePath: String?,
    isDark: Boolean,
    onClick: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit,
    primary: Color = MaterialTheme.colorScheme.primary,
    useVerticalLayout: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (isPressed) 0.97f else 1f, label = "scale")
    var thumbnail by remember(captureImagePath) { mutableStateOf<Bitmap?>(null) }
    var thumbnailLoaded by remember(captureImagePath) { mutableStateOf(false) }
    LaunchedEffect(captureImagePath) {
        thumbnail = withContext(ThumbnailDispatcher) {
            captureImagePath?.let { BitmapUtils.decodeSampledBitmapFromFile(it, 320, 480) }
        }
        thumbnailLoaded = true
    }

    val preview: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.background(if (isDark) Color(0xFF15171C) else Color(0xFFE8E8EC)),
            contentAlignment = Alignment.Center) {
            val image = thumbnail
            // The screenshot's intrinsic height must not determine the card height.
            if (image != null) Image(image.asImageBitmap(), contentDescription = "Capture preview",
                modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            else if (thumbnailLoaded) Icon(Icons.Default.BrokenImage, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                modifier = Modifier.size(if (useVerticalLayout) 28.dp else 24.dp))
        }
    }
    val content: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(summary, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onRun, shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = primary.copy(alpha = 0.12f), contentColor = primary),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Run", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(RoundedCornerShape(20.dp))
        .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = if (isDark) BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)) else null) {
        if (useVerticalLayout) {
            Column(Modifier.fillMaxWidth()) {
                preview(Modifier.fillMaxWidth().height(120.dp))
                content(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                preview(Modifier.width(80.dp).fillMaxHeight().heightIn(min = 90.dp))
                content(Modifier.weight(1f))
            }
        }
    }
}
