package com.autonion.automationcompanion.features.screen_understanding_ml.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.screen_understanding_ml.model.*
import com.autonion.automationcompanion.features.visual_trigger.ui.EditorChoice
import com.autonion.automationcompanion.features.visual_trigger.ui.EditorSettingsSheet
import com.autonion.automationcompanion.features.visual_trigger.ui.EditorTool
import com.autonion.automationcompanion.features.visual_trigger.ui.EditorToolAction
import com.autonion.automationcompanion.features.visual_trigger.ui.VisionEditorToolbar

internal val MlEditorAccent = Color(0xFF47E78B)
internal val ActionType.editorLabel: String get() = when (this) {
    ActionType.CLICK -> "Tap"
    ActionType.SCROLL_UP -> "Scroll up"
    ActionType.SCROLL_DOWN -> "Scroll down"
    ActionType.INPUT_TEXT -> "Input text"
    ActionType.WAIT -> "Wait"
    ActionType.FINISH -> "Finish"
    ActionType.FAIL -> "Fail"
}
internal val ActionType.editorDescription: String get() = when (this) {
    ActionType.CLICK -> "Tap this target when it appears."
    ActionType.SCROLL_UP -> "Scroll the page up when this target appears."
    ActionType.SCROLL_DOWN -> "Scroll the page down when this target appears."
    ActionType.INPUT_TEXT -> "Tap this field and enter your text."
    ActionType.WAIT -> "Pause for two seconds when this target appears."
    else -> editorLabel
}
internal val ActionType.editorIcon get() = when (this) {
    ActionType.CLICK -> Icons.Default.TouchApp
    ActionType.SCROLL_UP -> Icons.Default.ArrowUpward
    ActionType.SCROLL_DOWN -> Icons.Default.ArrowDownward
    ActionType.INPUT_TEXT -> Icons.Default.Keyboard
    ActionType.WAIT -> Icons.Default.Timer
    else -> Icons.Default.Check
}

@Composable
internal fun CaptureEditorScreen(
    model: CaptureEditorViewModel, onCancel: () -> Unit, onSaved: () -> Unit, onRecapture: (String?) -> Unit
) {
    var showTargets by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showRunSettings by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var fitRevision by remember { mutableIntStateOf(0) }
    fun cancel() { if (model.dirty) confirmDiscard = true else onCancel() }
    BackHandler(enabled = !model.saving) { cancel() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF101216)).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val landscape = maxWidth > maxHeight
        val toolbarHeight = if (model.pages.size > 1) 144.dp else 96.dp
        Column(Modifier.fillMaxSize().padding(end = if (landscape) 104.dp else 0.dp,
            bottom = if (landscape) 0.dp else toolbarHeight)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Center) {
                listOf(EditorDisplayMode.ELEMENTS, EditorDisplayMode.TEXT, EditorDisplayMode.SELECTED)
                    .filterNot { model.pageAccessibilityOnly && it == EditorDisplayMode.TEXT }.forEach { mode ->
                        FilterChip(selected = model.mode == mode,
                            onClick = { model.chooseMode(mode) }, enabled = !model.saving,
                            modifier = Modifier.padding(horizontal = 3.dp),
                            label = { Text(when (mode) {
                                EditorDisplayMode.ELEMENTS -> "Elements"
                                EditorDisplayMode.TEXT -> "Text"
                                EditorDisplayMode.SELECTED -> "Selected (${model.steps.size})"
                            }, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MlEditorAccent.copy(alpha = 0.16f), selectedLabelColor = MlEditorAccent,
                                containerColor = Color.Transparent, labelColor = Color.LightGray))
                    }
            }
            Text(if (model.scanning) "Finding ${if (model.mode == EditorDisplayMode.TEXT) "text" else "elements"}…"
                else "Tap a target to select · Pinch to zoom · Hold for settings",
                color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp))
            if (model.mode == EditorDisplayMode.ELEMENTS && model.missingAccessibilityData) {
                Text("Additional accessibility elements weren't saved with this capture. Recapture to include them.",
                    color = Color.LightGray, fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val bitmap = model.bitmap
                if (bitmap != null) CaptureCanvas(bitmap, model, fitRevision, onSettings = { showSettings = true })
                else if (!model.loading) {
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ImageNotSupported, null, tint = Color.Gray, modifier = Modifier.size(40.dp))
                        Text("No saved snapshot", color = Color.White, modifier = Modifier.padding(top = 12.dp))
                        Text("Your selected targets and actions can still be edited.", color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { showTargets = true }) { Text("View selected elements", color = MlEditorAccent) }
                    }
                }
                if (model.loading || model.scanning) CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(32.dp), color = MlEditorAccent)
            }
        }
        val primary = listOf(
            EditorToolAction("Cancel", Icons.Default.Close, enabled = !model.saving) { cancel() },
            EditorToolAction("Undo", Icons.AutoMirrored.Filled.Undo, enabled = model.canUndo && !model.saving) { model.undo() },
            EditorToolAction("Recapture", Icons.Default.CameraAlt, enabled = !model.saving && !model.loading) { model.recapture(onRecapture) },
            EditorToolAction("Fit image", Icons.Default.FitScreen, enabled = model.bitmap != null) { fitRevision++ },
            EditorToolAction("Run behavior", Icons.Default.Settings, enabled = !model.saving) { showRunSettings = true },
            EditorToolAction("Save", Icons.Default.Check, enabled = model.canSave, tint = MlEditorAccent) { model.save(onSaved) }
        )
        VisionEditorToolbar(
            modifier = if (landscape) Modifier.align(Alignment.CenterEnd).width(104.dp).fillMaxHeight()
                else Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(toolbarHeight),
            landscape = landscape, primary = primary,
            target = listOf(
                EditorToolAction("Selected elements", Icons.Default.FormatListNumbered) { showTargets = true },
                EditorToolAction("Target settings", Icons.Default.Tune, enabled = model.selected != null && !model.saving) { showSettings = true }
            ),
            targetLabel = model.selected?.let { "Target #${model.steps.indexOf(it) + 1}" } ?: "${model.steps.size} targets selected",
            searchLabel = model.selected?.actionType?.editorLabel ?: "Select a target to configure",
            page = model.pageIndex, pages = model.pages.size,
            onPage = { model.select(null); model.navigate(it) }
        )
    }
    if (showTargets) {
        EditorSettingsSheet("Selected elements (${model.steps.size})", { showTargets = false }) {
            if (model.steps.isEmpty()) Text("Tap an element or text in the snapshot to add a target.", color = Color.LightGray)
            model.steps.forEachIndexed { index, step ->
                Surface(onClick = { model.select(step.id); showTargets = false; showSettings = true },
                    color = Color.White.copy(alpha = 0.06f), shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", color = MlEditorAccent, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                        MlTargetPreview(step, Modifier.size(56.dp), crop = true)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(step.anchor.text?.takeIf { it.isNotBlank() } ?: step.label, maxLines = 2)
                            Text(step.actionType.editorLabel + if (step.isOptional) " · Skip if missing" else "",
                                color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Default.ChevronRight, "Edit target #${index + 1}", tint = Color.LightGray)
                    }
                }
            }
        }
    }
    if (showSettings && model.selected != null) MlTargetSettings(model, onDismiss = { showSettings = false })
    if (showRunSettings) {
        EditorSettingsSheet("Run behavior", { showRunSettings = false }) {
            if (!model.flowMode) {
                OutlinedTextField(value = model.name, onValueChange = model::rename, label = { Text("Preset name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                Text("Follow a sequence", fontWeight = FontWeight.SemiBold)
                Text("Missing target", style = MaterialTheme.typography.titleSmall)
                EditorChoice("Wait", model.executionMode == ExecutionMode.STRICT) { model.updateExecutionMode(ExecutionMode.STRICT) }
                EditorChoice("Skip", model.executionMode == ExecutionMode.FLEXIBLE) { model.updateExecutionMode(ExecutionMode.FLEXIBLE) }
            } else Text("Targets follow the Flow's sequence. Set missing-target behavior in each target's settings.")
            Text("Use the arrows in Target settings to change the order.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard changes?") }, text = { Text("Your unsaved target changes will be lost.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onCancel() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } })
    model.error?.let { message ->
        AlertDialog(onDismissRequest = { model.error = null }, title = { Text("Unable to complete") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { model.error = null }) { Text("OK") } })
    }
    if (model.saving) androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
        Surface(shape = RoundedCornerShape(8.dp)) {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text("Saving preset…", modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}

@Composable
private fun MlTargetSettings(model: CaptureEditorViewModel, onDismiss: () -> Unit) {
    val step = model.selected ?: return
    val number = model.steps.indexOf(step) + 1
    var choosingAction by rememberSaveable(step.id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(step.id) { mutableStateOf(false) }
    val hasText = remember(step.id) { step.anchor.text != null || step.anchor.source == "ocr" || step.anchor.label.equals("Text", true) }
    val allowsInput = step.actionType == ActionType.INPUT_TEXT || step.anchor.label.contains("Input", true) || step.anchor.label.contains("Edit", true)
    EditorSettingsSheet("Target #$number", onDismiss) {
        Text("Action", style = MaterialTheme.typography.titleSmall)
        Surface(onClick = { choosingAction = !choosingAction }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Choose action" },
            color = Color.White.copy(alpha = 0.06f), shape = RoundedCornerShape(6.dp)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(step.actionType.editorIcon, null, tint = MlEditorAccent)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(step.actionType.editorLabel, fontWeight = FontWeight.SemiBold)
                    Text(step.actionType.editorDescription, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.ExpandMore, null)
            }
        }
        if (choosingAction) listOf(ActionType.CLICK, ActionType.SCROLL_UP, ActionType.SCROLL_DOWN, ActionType.INPUT_TEXT, ActionType.WAIT)
            .filter { it != ActionType.INPUT_TEXT || allowsInput }.forEach {
            EditorChoice(it.editorLabel, step.actionType == it) {
                model.update(step.copy(actionType = it, inputText = if (it == ActionType.INPUT_TEXT) step.inputText ?: "" else null))
                choosingAction = false
            }
        }
        if (step.actionType == ActionType.INPUT_TEXT) OutlinedTextField(
            value = step.inputText.orEmpty(), onValueChange = { model.update(step.copy(inputText = it)) },
            label = { Text("Text to enter") }, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
        if (hasText) {
            OutlinedTextField(value = step.anchor.text.orEmpty(), onValueChange = { model.update(step.copy(anchor = step.anchor.copy(text = it))) },
                label = { Text("Text to find") }, modifier = Modifier.fillMaxWidth(), isError = step.anchor.text.isNullOrBlank(),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Skip if missing", modifier = Modifier.weight(1f))
            Switch(checked = step.isOptional, onCheckedChange = { model.update(step.copy(isOptional = it)) })
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Order: $number of ${model.steps.size}", modifier = Modifier.weight(1f))
            EditorTool(EditorToolAction("Move earlier", Icons.Default.ArrowUpward, enabled = number > 1) { model.move(step.id, -1) })
            EditorTool(EditorToolAction("Move later", Icons.Default.ArrowDownward, enabled = number < model.steps.size) { model.move(step.id, 1) })
        }
        TextButton(onClick = { confirmDelete = true }) {
            Icon(Icons.Default.Delete, null, tint = Color(0xFFFF7777))
            Text("Delete target", color = Color(0xFFFF7777), modifier = Modifier.padding(start = 8.dp))
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete target?") },
        text = { Text("This target will be removed. You can undo this in the editor.") },
        confirmButton = { TextButton(onClick = { model.remove(step.id); onDismiss() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun CaptureCanvas(bitmap: Bitmap, model: CaptureEditorViewModel, fitRevision: Int, onSettings: () -> Unit) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember(bitmap, fitRevision) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap, fitRevision) { mutableStateOf(Offset.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val fit = minOf(canvasSize.width.toFloat() / bitmap.width, canvasSize.height.toFloat() / bitmap.height).coerceAtLeast(0.0001f)
    val scale = fit * zoom
    val origin = Offset((canvasSize.width - bitmap.width * scale) / 2f, (canvasSize.height - bitmap.height * scale) / 2f) + pan
    val transform by rememberUpdatedState(origin to scale)
    val candidates = when (model.mode) {
        EditorDisplayMode.ELEMENTS -> model.elements
        EditorDisplayMode.TEXT -> model.textElements
        EditorDisplayMode.SELECTED -> emptyList()
    }
    val currentCandidates by rememberUpdatedState(candidates)
    fun hit(offset: Offset): UIElement? {
        val (start, factor) = transform
        val point = (offset - start) / factor
        return (model.pageSteps.map { it.anchor } + currentCandidates).distinctBy { it.id }
            .filter { it.bounds.contains(point.x, point.y) }.minByOrNull { it.bounds.width() * it.bounds.height() }
    }
    Canvas(Modifier.fillMaxSize().onSizeChanged { canvasSize = it }
        .semantics {
            contentDescription = "Captured screen"
            stateDescription = "${model.pageSteps.size} selected targets; zoom $zoom"
            customActions = model.pageSteps.map { step ->
                CustomAccessibilityAction("Select target #${model.steps.indexOf(step) + 1}") { model.select(step.id); true }
            }
        }
        .pointerInput(bitmap, fitRevision) {
            detectTapGestures(onTap = { offset -> hit(offset)?.let { model.add(it) } ?: model.select(null) },
                onLongPress = { offset -> hit(offset)?.let { model.add(it); onSettings() } })
        }
        .pointerInput(bitmap, fitRevision, canvasSize) {
            detectTransformGestures { centroid, delta, zoomChange, _ ->
                val oldScale = fit * zoom
                val oldOrigin = Offset((canvasSize.width - bitmap.width * oldScale) / 2f, (canvasSize.height - bitmap.height * oldScale) / 2f) + pan
                val point = (centroid - oldOrigin) / oldScale
                zoom = (zoom * zoomChange).coerceIn(1f, 6f)
                val newScale = fit * zoom
                val centered = Offset((canvasSize.width - bitmap.width * newScale) / 2f, (canvasSize.height - bitmap.height * newScale) / 2f)
                val next = centroid + delta - point * newScale - centered
                val maxX = ((bitmap.width * newScale - canvasSize.width) / 2f).coerceAtLeast(0f) + canvasSize.width * 0.1f
                val maxY = ((bitmap.height * newScale - canvasSize.height) / 2f).coerceAtLeast(0f) + canvasSize.height * 0.1f
                pan = Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
            }
        }) {
        if (canvasSize.width == 0 || canvasSize.height == 0) return@Canvas
        clipRect {
            withTransform({ translate(origin.x, origin.y); scale(scale, scale, Offset.Zero) }) {
                drawImage(image)
                candidates.forEach { element ->
                    if (model.pageSteps.none { it.anchor.id == element.id }) {
                        val b = element.bounds
                        val position = Offset(b.left, b.top)
                        val size = Size(b.width(), b.height())
                        // Both edges remain visible regardless of the captured app's theme or zoom.
                        drawRect(Color(0xFF15171C), position, size, style = Stroke(2.5.dp.toPx() / scale))
                        drawRect(Color.White, position, size, style = Stroke(1.dp.toPx() / scale))
                    }
                }
                model.pageSteps.forEach { step ->
                    val b = step.anchor.bounds
                    val focused = model.selectedId == step.id
                    val position = Offset(b.left, b.top)
                    val size = Size(b.width().coerceAtLeast(1f), b.height().coerceAtLeast(1f))
                    drawRect(MlEditorAccent.copy(alpha = if (focused) 0.22f else 0.08f), position, size)
                    if (focused) drawRect(Color.White, position, size, style = Stroke(5f / scale))
                    drawRect(MlEditorAccent, position, size, style = Stroke((if (focused) 3f else 2f) / scale,
                        pathEffect = if (step.isOptional) PathEffect.dashPathEffect(floatArrayOf(8f / scale, 5f / scale)) else null))
                    val radius = minOf(12.dp.toPx() / scale, minOf(bitmap.width, bitmap.height) / 2f)
                    val center = Offset((b.left + radius).coerceIn(radius, bitmap.width - radius), b.top.coerceIn(radius, bitmap.height - radius))
                    drawCircle(MlEditorAccent, radius, center)
                    drawContext.canvas.nativeCanvas.drawText("${model.steps.indexOf(step) + 1}", center.x, center.y + 4.sp.toPx() / scale,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 12.sp.toPx() / scale; textAlign = Paint.Align.CENTER; isFakeBoldText = true })
                }
            }
        }
    }
}
