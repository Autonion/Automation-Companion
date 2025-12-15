package com.example.automationcompanion.features.gesture_recording_playback.ui.presets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Simple, accessible preset item replacing item_preset.xml
 *
 * caller handles play/delete actions (and confirmation if needed).
 */
@Composable
fun PresetCard(
    name: String,
    subtitle: String = "",
    onClick: () -> Unit = {},
    onPlay: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                if (subtitle.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2
                    )
                }
            }

            IconButton(
                onClick = { onPlay(name) },
                modifier = Modifier.size(44.dp)
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play preset")
            }

            IconButton(
                onClick = { onDelete(name) },
                modifier = Modifier.size(44.dp)
            ) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete preset")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PresetCardPreview() {
    PresetCard(name = "Morning", subtitle = "Open music, set brightness")
}
