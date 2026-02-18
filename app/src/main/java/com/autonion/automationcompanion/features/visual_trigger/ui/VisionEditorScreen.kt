package com.autonion.automationcompanion.features.visual_trigger.ui

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autonion.automationcompanion.features.visual_trigger.models.VisionAction
import com.autonion.automationcompanion.features.visual_trigger.models.ScrollDirection
import kotlin.math.abs

private enum class DragMode { NONE, DRAW, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }

@Composable
fun VisionEditorScreen(
    imagePath: String,
    presetId: String? = null,
    presetName: String = "New Automation",
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    onRecapture: () -> Unit,
    viewModel: VisionEditorViewModel = viewModel()
) {
    val initialized = remember { mutableStateOf(false) }
    if (!initialized.value) {
        if (presetId != null) {
            viewModel.loadExistingPreset(presetId) { success ->
                if (!success) viewModel.loadImage(imagePath)
            }
        } else {
            viewModel.loadImage(imagePath)
        }
        initialized.value = true
    }

    val bitmap by viewModel.imageBitmap.collectAsState()
    val regions by viewModel.regions.collectAsState()

    // Drawing / editing state
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var dragMode by remember { mutableStateOf(DragMode.NONE) }
    var selectedRegionId by remember { mutableStateOf<Int?>(null) }
    var editingRect by remember { mutableStateOf<Rect?>(null) } // live rect during move/resize

    // Region detail dialog
    var dialogRegion by remember { mutableStateOf<VisionEditorViewModel.TempRegion?>(null) }
    var showRegionDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val handleRadius = 14f // pixels in canvas coords

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D1A))
    ) {
        if (bitmap != null) {
            val imageBitmap = bitmap!!.asImageBitmap()
            val imageWidth = bitmap!!.width
            val imageHeight = bitmap!!.height

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { canvasSize = it.size }
                    .pointerInput(canvasSize, regions) {
                        if (canvasSize == IntSize.Zero) return@pointerInput
                        val scaleX = canvasSize.width.toFloat() / imageWidth
                        val scaleY = canvasSize.height.toFloat() / imageHeight
                        val scale = minOf(scaleX, scaleY)

                        detectDragGestures(
                            onDragStart = { pos ->
                                val imgX = (pos.x / scale).toInt()
                                val imgY = (pos.y / scale).toInt()

                                // Check if near a corner of the selected region
                                val sel = if (selectedRegionId != null) regions.find { it.id == selectedRegionId } else null
                                if (sel != null) {
                                    val r = sel.rect
                                    val corners = listOf(
                                        DragMode.RESIZE_TL to Offset(r.left * scale, r.top * scale),
                                        DragMode.RESIZE_TR to Offset(r.right * scale, r.top * scale),
                                        DragMode.RESIZE_BL to Offset(r.left * scale, r.bottom * scale),
                                        DragMode.RESIZE_BR to Offset(r.right * scale, r.bottom * scale),
                                    )
                                    val hitCorner = corners.find {
                                        val dx = pos.x - it.second.x
                                        val dy = pos.y - it.second.y
                                        dx * dx + dy * dy < (handleRadius * 3) * (handleRadius * 3)
                                    }
                                    if (hitCorner != null) {
                                        dragMode = hitCorner.first
                                        editingRect = Rect(r)
                                        dragStart = pos
                                        return@detectDragGestures
                                    }
                                    // Check if inside the selected region → move
                                    if (r.contains(imgX, imgY)) {
                                        dragMode = DragMode.MOVE
                                        editingRect = Rect(r)
                                        dragStart = pos
                                        return@detectDragGestures
                                    }
                                }

                                // Check if touching any other region → select it
                                val hit = regions.find { it.rect.contains(imgX, imgY) }
                                if (hit != null) {
                                    selectedRegionId = hit.id
                                    dragMode = DragMode.MOVE
                                    editingRect = Rect(hit.rect)
                                    dragStart = pos
                                    return@detectDragGestures
                                }

                                // Otherwise → draw new region
                                selectedRegionId = null
                                editingRect = null
                                dragMode = DragMode.DRAW
                                dragStart = pos
                            },
                            onDrag = { change, _ ->
                                dragCurrent = change.position
                                val s = if (canvasSize.width > 0) minOf(canvasSize.width.toFloat() / imageWidth, canvasSize.height.toFloat() / imageHeight) else 1f

                                if (dragMode == DragMode.MOVE && editingRect != null && dragStart != null) {
                                    val origRect = regions.find { it.id == selectedRegionId }?.rect ?: return@detectDragGestures
                                    val dx = ((change.position.x - dragStart!!.x) / s).toInt()
                                    val dy = ((change.position.y - dragStart!!.y) / s).toInt()
                                    editingRect = Rect(
                                        origRect.left + dx, origRect.top + dy,
                                        origRect.right + dx, origRect.bottom + dy
                                    )
                                } else if (dragMode in listOf(DragMode.RESIZE_TL, DragMode.RESIZE_TR, DragMode.RESIZE_BL, DragMode.RESIZE_BR) && editingRect != null) {
                                    val origRect = regions.find { it.id == selectedRegionId }?.rect ?: return@detectDragGestures
                                    val dx = ((change.position.x - dragStart!!.x) / s).toInt()
                                    val dy = ((change.position.y - dragStart!!.y) / s).toInt()
                                    val newRect = Rect(origRect)
                                    when (dragMode) {
                                        DragMode.RESIZE_TL -> { newRect.left = origRect.left + dx; newRect.top = origRect.top + dy }
                                        DragMode.RESIZE_TR -> { newRect.right = origRect.right + dx; newRect.top = origRect.top + dy }
                                        DragMode.RESIZE_BL -> { newRect.left = origRect.left + dx; newRect.bottom = origRect.bottom + dy }
                                        DragMode.RESIZE_BR -> { newRect.right = origRect.right + dx; newRect.bottom = origRect.bottom + dy }
                                        else -> {}
                                    }
                                    // Ensure min size
                                    if (newRect.width() > 10 && newRect.height() > 10) {
                                        editingRect = newRect
                                    }
                                }
                            },
                            onDragEnd = {
                                val s = if (canvasSize.width > 0) minOf(canvasSize.width.toFloat() / imageWidth, canvasSize.height.toFloat() / imageHeight) else 1f

                                when (dragMode) {
                                    DragMode.DRAW -> {
                                        if (dragStart != null && dragCurrent != null) {
                                            val start = dragStart!!
                                            val end = dragCurrent!!
                                            val left = minOf(start.x, end.x).toInt()
                                            val top = minOf(start.y, end.y).toInt()
                                            val right = maxOf(start.x, end.x).toInt()
                                            val bottom = maxOf(start.y, end.y).toInt()

                                            if (right - left > 10 && bottom - top > 10) {
                                                val scaledRect = Rect(
                                                    (left / s).toInt(), (top / s).toInt(),
                                                    (right / s).toInt(), (bottom / s).toInt()
                                                )
                                                scaledRect.intersect(0, 0, imageWidth, imageHeight)
                                                if (scaledRect.width() > 5 && scaledRect.height() > 5) {
                                                    viewModel.addRegion(scaledRect)
                                                }
                                            }
                                        }
                                    }
                                    DragMode.MOVE, DragMode.RESIZE_TL, DragMode.RESIZE_TR, DragMode.RESIZE_BL, DragMode.RESIZE_BR -> {
                                        if (selectedRegionId != null && editingRect != null) {
                                            val clamped = Rect(editingRect!!)
                                            clamped.intersect(0, 0, imageWidth, imageHeight)
                                            if (clamped.width() > 5 && clamped.height() > 5) {
                                                viewModel.updateRegionRect(selectedRegionId!!, clamped)
                                            }
                                        }
                                    }
                                    DragMode.NONE -> {}
                                }
                                dragStart = null
                                dragCurrent = null
                                editingRect = null
                                dragMode = DragMode.NONE
                            }
                        )
                    }
                    .pointerInput(canvasSize, regions) {
                        if (canvasSize == IntSize.Zero) return@pointerInput
                        val scaleX = canvasSize.width.toFloat() / imageWidth
                        val scaleY = canvasSize.height.toFloat() / imageHeight
                        val scale = minOf(scaleX, scaleY)

                        detectTapGestures(
                            onTap = { offset ->
                                val x = (offset.x / scale).toInt()
                                val y = (offset.y / scale).toInt()
                                val hit = regions.find { it.rect.contains(x, y) }
                                selectedRegionId = hit?.id // select or deselect
                            },
                            onLongPress = { offset ->
                                val x = (offset.x / scale).toInt()
                                val y = (offset.y / scale).toInt()
                                val region = regions.find { it.rect.contains(x, y) }
                                if (region != null) {
                                    dialogRegion = region
                                    showRegionDialog = true
                                }
                            }
                        )
                    }
            ) {
                val viewWidth = size.width
                val viewHeight = size.height
                val scaleX = viewWidth / imageWidth.toFloat()
                val scaleY = viewHeight / imageHeight.toFloat()
                val scale = minOf(scaleX, scaleY)
                val drawWidth = (imageWidth * scale).toInt()
                val drawHeight = (imageHeight * scale).toInt()

                drawImage(
                    image = imageBitmap,
                    dstSize = androidx.compose.ui.unit.IntSize(drawWidth, drawHeight)
                )

                // Draw regions
                regions.forEachIndexed { index, region ->
                    // Use live editing rect during drag
                    val r = if (region.id == selectedRegionId && editingRect != null) editingRect!! else region.rect
                    val isSelected = region.id == selectedRegionId

                    val topLeft = Offset(r.left * scale, r.top * scale)
                    val rectSize = Size(r.width() * scale, r.height() * scale)

                    drawRect(color = Color(region.color), topLeft = topLeft, size = rectSize, alpha = if (isSelected) 0.35f else 0.25f)
                    drawRect(color = Color(region.color), topLeft = topLeft, size = rectSize, style = Stroke(width = if (isSelected) 4f else 2.5f))

                    // Sequence number badge (#1, #2, ...)
                    val seqText = "#${index + 1}"
                    val badgePaint = android.graphics.Paint().apply { color = 0xDD000000.toInt(); isAntiAlias = true }
                    val seqPaint = android.graphics.Paint().apply {
                        color = 0xFFFFFFFF.toInt(); textSize = 13f * scale
                        typeface = android.graphics.Typeface.DEFAULT_BOLD; isAntiAlias = true
                    }
                    val seqWidth = seqPaint.measureText(seqText)
                    val badgeCx = topLeft.x + rectSize.width / 2
                    val badgeCy = topLeft.y + rectSize.height / 2
                    val badgeW = seqWidth + 16f
                    val badgeH = seqPaint.textSize + 10f
                    drawContext.canvas.nativeCanvas.drawRoundRect(
                        badgeCx - badgeW / 2, badgeCy - badgeH / 2,
                        badgeCx + badgeW / 2, badgeCy + badgeH / 2,
                        8f, 8f, badgePaint
                    )
                    drawContext.canvas.nativeCanvas.drawText(seqText, badgeCx - seqWidth / 2, badgeCy + seqPaint.textSize / 3, seqPaint)

                    // Action label at top
                    val actionText = when (region.action) {
                        is VisionAction.Click -> "TAP"
                        is VisionAction.LongClick -> "LONG"
                        is VisionAction.Scroll -> "SCROLL ${(region.action as VisionAction.Scroll).direction.name}"
                    }
                    val actionPaint = android.graphics.Paint().apply {
                        color = region.color; textSize = 10f * scale
                        typeface = android.graphics.Typeface.DEFAULT_BOLD; isAntiAlias = true
                    }
                    val actionWidth = actionPaint.measureText(actionText)
                    val aBadgeLeft = topLeft.x
                    val aBadgeTop = topLeft.y - actionPaint.textSize - 8f
                    if (aBadgeTop > 0) {
                        drawContext.canvas.nativeCanvas.drawRoundRect(
                            aBadgeLeft, aBadgeTop,
                            aBadgeLeft + actionWidth + 12f, topLeft.y - 2f,
                            6f, 6f, badgePaint
                        )
                        drawContext.canvas.nativeCanvas.drawText(actionText, aBadgeLeft + 6f, topLeft.y - 6f, actionPaint)
                    }

                    // Corner handles when selected
                    if (isSelected) {
                        val corners = listOf(
                            Offset(topLeft.x, topLeft.y),
                            Offset(topLeft.x + rectSize.width, topLeft.y),
                            Offset(topLeft.x, topLeft.y + rectSize.height),
                            Offset(topLeft.x + rectSize.width, topLeft.y + rectSize.height),
                        )
                        corners.forEach { corner ->
                            drawCircle(color = Color.White, radius = handleRadius, center = corner)
                            drawCircle(color = Color(region.color), radius = handleRadius - 3f, center = corner)
                        }
                    }
                }

                // Current drag rectangle (drawing new region)
                if (dragMode == DragMode.DRAW && dragStart != null && dragCurrent != null) {
                    val start = dragStart!!
                    val end = dragCurrent!!
                    val tl = Offset(minOf(start.x, end.x), minOf(start.y, end.y))
                    val sz = Size(abs(end.x - start.x), abs(end.y - start.y))
                    drawRect(color = Color.White, topLeft = tl, size = sz, style = Stroke(width = 3f))
                    drawRect(color = Color.White.copy(alpha = 0.15f), topLeft = tl, size = sz)
                }
            }

            // Instruction overlay when no regions
            if (regions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = Color.Black.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Text(
                            text = "Draw rectangles around UI elements to track",
                            color = Color.White, fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                }
            }

            // Selected region hint
            if (selectedRegionId != null && regions.any { it.id == selectedRegionId }) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = Color.Black.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Text(
                            text = "Drag corners to resize • Drag center to move • Tap outside to deselect",
                            color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }

        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF00C853))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Loading capture...", color = Color.White.copy(alpha = 0.7f))
                }
            }
        }

        // --- Floating action buttons at top ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallFloatingActionButton(
                onClick = onCancel,
                containerColor = Color(0xAA000000),
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(20.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallFloatingActionButton(
                    onClick = onRecapture,
                    containerColor = Color(0xAA000000),
                    contentColor = Color.White,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Recapture", modifier = Modifier.size(20.dp))
                }

                if (regions.isNotEmpty()) {
                    SmallFloatingActionButton(
                        onClick = { viewModel.undoLastRegion() },
                        containerColor = Color(0xAA000000),
                        contentColor = Color.White,
                        shape = CircleShape
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // --- Save FAB ---
        if (regions.isNotEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                FloatingActionButton(
                    onClick = { viewModel.savePreset(presetName) { onSaved() } },
                    containerColor = Color(0xFF00C853),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Check, "Save")
                }
            }
        }
    }

    // --- Region Detail Dialog ---
    if (showRegionDialog && dialogRegion != null) {
        val region = dialogRegion!!
        AlertDialog(
            onDismissRequest = { showRegionDialog = false; dialogRegion = null },
            containerColor = Color(0xFF22252B),
            titleContentColor = Color.White,
            title = { Text("Region #${regions.indexOfFirst { it.id == region.id } + 1}", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Select action for this region:", color = Color.White.copy(alpha = 0.7f))

                    val actions = listOf(
                        "Tap" to VisionAction.Click,
                        "Long Press" to VisionAction.LongClick,
                        "Scroll Up" to VisionAction.Scroll(ScrollDirection.UP),
                        "Scroll Down" to VisionAction.Scroll(ScrollDirection.DOWN),
                    )

                    actions.forEach { (label, action) ->
                        val isSelected = when {
                            action is VisionAction.Click && region.action is VisionAction.Click -> true
                            action is VisionAction.LongClick && region.action is VisionAction.LongClick -> true
                            action is VisionAction.Scroll && region.action is VisionAction.Scroll ->
                                (action as VisionAction.Scroll).direction == (region.action as VisionAction.Scroll).direction
                            else -> false
                        }

                        Surface(
                            onClick = {
                                viewModel.updateRegionAction(region.id, action)
                                dialogRegion = dialogRegion!!.copy(action = action)
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) Color(0xFF00C853).copy(alpha = 0.2f) else Color.Transparent,
                            border = if (isSelected) {
                                androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00C853))
                            } else {
                                androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color(0xFF00C853) else Color.White,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Surface(
                        onClick = { showDeleteConfirm = true },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFF1744).copy(alpha = 0.1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF1744).copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF1744), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Delete Region", color = Color(0xFFFF1744), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRegionDialog = false; dialogRegion = null }) {
                    Text("Done", color = Color(0xFF00C853))
                }
            }
        )
    }

    // --- Delete confirmation ---
    if (showDeleteConfirm && dialogRegion != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = Color(0xFF22252B),
            title = { Text("Delete Region?", color = Color.White) },
            text = { Text("This region will be removed.", color = Color.White.copy(alpha = 0.7f)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeRegion(dialogRegion!!.id)
                    showDeleteConfirm = false; showRegionDialog = false; dialogRegion = null
                    selectedRegionId = null
                }) { Text("Delete", color = Color(0xFFFF1744)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }
}
