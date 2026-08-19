@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.autonion.automationcompanion.features.flow_automation.ui.editor.panels

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.flow_automation.model.FlowNodeType
import com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas.NodeColors
import com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas.flowEditorColors

/**
 * Data representation for an item in the node palette.
 */
private data class NodeTypeItem(
    val nodeType: FlowNodeType,
    val label: String,
    val category: String,
    val description: String,
    val icon: ImageVector,
    val color: Color
)

/**
 * Grid palette showing available node types to add.
 * Includes interactive (i) info badges, clean vector icons, and an on-demand detail banner.
 */
@Composable
fun NodePalette(
    onAddNode: (FlowNodeType) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nodeTypes = remember {
        listOf(
            NodeTypeItem(
                nodeType = FlowNodeType.GESTURE,
                label = "Gesture",
                category = "Action",
                description = "Simulates taps, long presses, swipes, and drag gestures at coordinates or UI targets.",
                icon = Icons.Rounded.TouchApp,
                color = NodeColors.GestureBlue
            ),
            NodeTypeItem(
                nodeType = FlowNodeType.LAUNCH_APP,
                label = "Launch App",
                category = "Action",
                description = "Opens a target installed application by package name or launcher intent.",
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                color = NodeColors.LaunchAppTeal
            ),
            NodeTypeItem(
                nodeType = FlowNodeType.INPUT,
                label = "Input",
                category = "Action",
                description = "Types text into active input fields, handles key presses, or submits form data.",
                icon = Icons.Rounded.Keyboard,
                color = NodeColors.InputPink
            ),
            NodeTypeItem(
                nodeType = FlowNodeType.VISUAL_TRIGGER,
                label = "Image Match",
                category = "Detection",
                description = "Scans the screen for an image template or icon and returns match coordinates.",
                icon = Icons.Rounded.ImageSearch,
                color = NodeColors.VisualTriggerPurple
            ),
            NodeTypeItem(
                nodeType = FlowNodeType.SCREEN_ML,
                label = "Screen ML",
                category = "Detection",
                description = "Uses on-device AI for OCR text recognition, screen parsing, and element detection.",
                icon = Icons.Rounded.Psychology,
                color = NodeColors.ScreenMLAmber
            ),
            NodeTypeItem(
                nodeType = FlowNodeType.CLIPBOARD,
                label = "Clipboard",
                category = "Data",
                description = "Reads, writes, or clears system clipboard text to pass between automation steps.",
                icon = Icons.Rounded.ContentPaste,
                color = NodeColors.ClipboardBrown
            ),
            NodeTypeItem(
                nodeType = FlowNodeType.DELAY,
                label = "Delay",
                category = "Logic",
                description = "Pauses flow execution for a specified duration in milliseconds or seconds.",
                icon = Icons.Rounded.HourglassEmpty,
                color = NodeColors.DelayGrey
            ),
            NodeTypeItem(
                nodeType = FlowNodeType.REPEAT,
                label = "Repeat",
                category = "Logic",
                description = "Loops a connected block of nodes for a set count or until a condition is met.",
                icon = Icons.Rounded.Repeat,
                color = NodeColors.RepeatOrange
            )
        )
    }

    var activeInfoNode by remember { mutableStateOf<NodeTypeItem?>(null) }
    val editorColors = flowEditorColors()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    // Dynamic grid height constraint based on screen size and active banner state
    val maxGridHeight = if (activeInfoNode != null) {
        (screenHeight * 0.38f).coerceAtLeast(180.dp)
    } else {
        (screenHeight * 0.54f).coerceAtLeast(260.dp)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .navigationBarsPadding(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = editorColors.panelBg),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        elevation = CardDefaults.cardElevation(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // ── Header Row ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Add Node",
                        color = editorColors.panelText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )

                    // Header discoverability hint
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White.copy(alpha = 0.06f),
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = editorColors.panelDimText,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "Tap ⓘ for info",
                                color = editorColors.panelDimText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close Palette",
                        tint = editorColors.panelDimText,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ── Contextual Info Banner ──
            AnimatedVisibility(
                visible = activeInfoNode != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                activeInfoNode?.let { node ->
                    AnimatedContent(
                        targetState = node,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "node_info_content"
                    ) { targetNode ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = targetNode.color.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, targetNode.color.copy(alpha = 0.35f))
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clip(CircleShape)
                                                .background(targetNode.color.copy(alpha = 0.25f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = targetNode.icon,
                                                contentDescription = null,
                                                tint = targetNode.color,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }

                                        Text(
                                            text = targetNode.label,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )

                                        // Category Tag
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = targetNode.color.copy(alpha = 0.2f)
                                        ) {
                                            Text(
                                                text = targetNode.category,
                                                color = targetNode.color,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = { activeInfoNode = null },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Close info",
                                            tint = editorColors.panelDimText,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                Spacer(Modifier.height(6.dp))

                                Text(
                                    text = targetNode.description,
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )

                                Spacer(Modifier.height(10.dp))

                                Button(
                                    onClick = {
                                        onAddNode(targetNode.nodeType)
                                        onDismiss()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = targetNode.color,
                                        contentColor = Color(0xFF0D0F12)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .align(Alignment.End)
                                        .height(30.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "Add to Flow",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Grid of Node Types ──
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = maxGridHeight)
            ) {
                items(nodeTypes) { item ->
                    val isSelected = activeInfoNode?.nodeType == item.nodeType

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.95f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (isSelected) item.color.copy(alpha = 0.16f)
                                else Color.White.copy(alpha = 0.04f)
                            )
                            .border(
                                BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) item.color else Color.White.copy(alpha = 0.07f)
                                ),
                                RoundedCornerShape(18.dp)
                            )
                            .combinedClickable(
                                onClick = { onAddNode(item.nodeType) },
                                onLongClick = {
                                    activeInfoNode = if (isSelected) null else item
                                }
                            )
                    ) {
                        // Isolated (i) info button in top right
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(30.dp)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = true, radius = 15.dp)
                                ) {
                                    activeInfoNode = if (isSelected) null else item
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = "Info for ${item.label}",
                                tint = if (isSelected) item.color else editorColors.panelDimText.copy(alpha = 0.5f),
                                modifier = Modifier.size(15.dp)
                            )
                        }

                        // Center content: Vector icon badge + crisp title
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(item.color.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label,
                                    tint = item.color,
                                    modifier = Modifier.size(23.dp)
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            Text(
                                text = item.label,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

