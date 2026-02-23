package com.autonion.automationcompanion.features.flow_automation.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.flow_automation.model.FlowNodeType
import com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas.NodeColors
import com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas.drawNodeIcon
import com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas.flowEditorColors

/**
 * Grid palette showing available node types to add.
 * Uses a 3-column grid with uniform card sizes.
 */
@Composable
fun NodePalette(
    onAddNode: (FlowNodeType) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nodeTypes = listOf(
        NodeTypeItem(FlowNodeType.GESTURE, "Gesture", "👆", NodeColors.GestureBlue),
        NodeTypeItem(FlowNodeType.VISUAL_TRIGGER, "Image Match", "🔍", NodeColors.VisualTriggerPurple),
        NodeTypeItem(FlowNodeType.SCREEN_ML, "Screen ML", "🧠", NodeColors.ScreenMLAmber),
        NodeTypeItem(FlowNodeType.DELAY, "Delay", "⏱", NodeColors.DelayGrey),
        NodeTypeItem(FlowNodeType.LAUNCH_APP, "Launch App", "🚀", NodeColors.LaunchAppTeal)
    )

    val editorColors = flowEditorColors()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .navigationBarsPadding(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = editorColors.panelBg),
        elevation = CardDefaults.cardElevation(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Add Node",
                    color = editorColors.panelText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                TextButton(onClick = onDismiss) {
                    Text("✕", color = editorColors.panelDimText, fontSize = 20.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = 300.dp)
            ) {
                items(nodeTypes) { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.9f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(item.color.copy(alpha = 0.12f))
                            .clickable { onAddNode(item.nodeType) }
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.size(44.dp)) {
                            val cX = size.width / 2f
                            val cY = size.height / 2f
                            val circleR = size.width / 2f - 2f
                            // Outer glow ring
                            drawCircle(item.color.copy(alpha = 0.2f), radius = circleR + 3f, center = Offset(cX, cY))
                            // Solid accent circle
                            drawCircle(item.color, radius = circleR, center = Offset(cX, cY))
                            // Inner highlight
                            drawCircle(Color.White.copy(alpha = 0.12f), radius = circleR - 2f, center = Offset(cX, cY))
                            // Icon (large scale for clarity)
                            drawNodeIcon(
                                nodeType = item.nodeType,
                                iconCenterX = cX,
                                iconCenterY = cY,
                                iconColor = Color.White,
                                accent = item.color,
                                scale = 1.8f
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            item.label,
                            color = item.color,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

private data class NodeTypeItem(
    val nodeType: FlowNodeType,
    val label: String,
    val emoji: String,
    val color: Color
)
