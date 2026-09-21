package com.autonion.automationcompanion.features.screen_understanding_ml.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autonion.automationcompanion.core.util.BitmapUtils
import com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationPreset
import com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val PreviewDecodeDispatcher = Dispatchers.IO.limitedParallelism(2)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScreenMlPresetDetailSheet(
    preset: AutomationPreset, onDismiss: () -> Unit, onEdit: () -> Unit, onRun: () -> Unit, onDelete: () -> Unit
) {
    val steps = remember(preset) { preset.steps.sortedBy { it.orderIndex } }
    var enlarged by remember { mutableStateOf<AutomationStep?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(preset.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("${steps.size} selected targets · ${steps.mapNotNull { it.captureImagePath }.distinct().size} saved snapshots",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onEdit, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                        Text("Edit", modifier = Modifier.padding(start = 6.dp))
                    }
                    FilledTonalButton(onClick = onRun, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Text("Run", modifier = Modifier.padding(start = 6.dp))
                    }
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete preset") }
                }
            }
            item {
                Text("Selected elements", style = MaterialTheme.typography.titleMedium)
                Text("Tap a target to view its snapshot and saved action.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(steps, key = { _, step -> step.id }) { index, step ->
                Surface(onClick = { enlarged = step }, shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        MlTargetPreview(step, Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)), crop = true)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("#${index + 1} · ${step.anchor.text?.takeIf { it.isNotBlank() } ?: step.label}",
                                maxLines = 2, fontWeight = FontWeight.SemiBold)
                            Text(step.actionType.editorLabel, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary)
                            if (step.isOptional) Text("Skip if missing", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Default.ChevronRight, "View target #${index + 1}")
                    }
                }
            }
        }
    }
    enlarged?.let { step ->
        Dialog(onDismissRequest = { enlarged = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Target #${steps.indexOf(step) + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { enlarged = null }) { Icon(Icons.Default.Close, "Close preview") }
                    }
                    MlTargetPreview(step, Modifier.weight(1f).fillMaxWidth(), crop = false)
                    Text(step.anchor.text?.takeIf { it.isNotBlank() } ?: step.label,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                    Text(step.actionType.editorDescription, style = MaterialTheme.typography.bodyMedium)
                    step.inputText?.let { Text("Text to enter: $it", style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { enlarged = null; onEdit() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Edit preset") }
                }
            }
        }
    }
}

@Composable
internal fun MlTargetPreview(step: AutomationStep, modifier: Modifier, crop: Boolean) {
    val bounds = step.anchor.bounds
    val bitmap by produceState<Bitmap?>(null, step.captureImagePath, bounds, crop) {
        value = withContext(PreviewDecodeDispatcher) {
            val path = step.captureImagePath ?: return@withContext null
            if (!File(path).isFile) return@withContext null
            val image = BitmapUtils.decodeSampledBitmapFromFile(path, if (crop) 400 else 1000, if (crop) 700 else 1600)
                ?: return@withContext null
            if (!crop) return@withContext image
            val sx = image.width / step.captureScreenWidth.takeIf { it > 0f }.let { it ?: image.width.toFloat() }
            val sy = image.height / step.captureScreenHeight.takeIf { it > 0f }.let { it ?: image.height.toFloat() }
            val left = (bounds.left * sx).roundToInt().coerceIn(0, image.width - 1)
            val top = (bounds.top * sy).roundToInt().coerceIn(0, image.height - 1)
            val right = (bounds.right * sx).roundToInt().coerceIn(left + 1, image.width)
            val bottom = (bounds.bottom * sy).roundToInt().coerceIn(top + 1, image.height)
            Bitmap.createBitmap(image, left, top, right - left, bottom - top).also {
                if (it !== image) image.recycle()
            }
        }
    }
    Box(modifier.background(Color(0xFF181B20)), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                Icon(Icons.Default.ImageNotSupported, null, tint = Color.Gray)
                Text("No snapshot", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
            }
        } else {
            Image(image.asImageBitmap(), if (crop) "Selected element preview" else "Saved snapshot with selected target",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            if (!crop) Canvas(Modifier.fillMaxSize()) {
                val fit = minOf(size.width / image.width, size.height / image.height)
                val origin = Offset((size.width - image.width * fit) / 2f, (size.height - image.height * fit) / 2f)
                val sx = image.width / (step.captureScreenWidth.takeIf { it > 0f } ?: image.width.toFloat()) * fit
                val sy = image.height / (step.captureScreenHeight.takeIf { it > 0f } ?: image.height.toFloat()) * fit
                val start = origin + Offset(bounds.left * sx, bounds.top * sy)
                val targetSize = Size(bounds.width() * sx, bounds.height() * sy)
                drawRect(MlEditorAccent.copy(alpha = 0.2f), start, targetSize)
                drawRect(Color.White, start, targetSize, style = Stroke(5f))
                drawRect(MlEditorAccent, start, targetSize, style = Stroke(3f))
            }
        }
    }
}
