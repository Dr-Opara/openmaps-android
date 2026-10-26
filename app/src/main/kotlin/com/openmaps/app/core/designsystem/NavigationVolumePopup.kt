package com.openmaps.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The same dark, readable volume popup in Live camera and Map navigation.
 * It only appears after a deliberate tap on the single persistent volume icon.
 */
@Composable
fun NavigationVolumePopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    guidanceEnabled: Boolean,
    onLower: () -> Unit,
    onRaise: () -> Unit,
    onMuteToggle: () -> Unit,
) {
    val typography = MaterialTheme.typography
    MaterialTheme(
        colorScheme = darkColorScheme(
            surface = Color(0xFF0B1928),
            surfaceContainer = Color(0xFF10243B),
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFE0EAF6),
            primary = Color(0xFF6BA2FF),
        ),
        typography = typography,
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier
                .widthIn(min = 218.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF10243B)),
        ) {
            DropdownMenuItem(
                text = {
                    Row {
                        Text("−", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(16.dp))
                        Text("Volume down", style = MaterialTheme.typography.titleMedium)
                    }
                },
                onClick = onLower,
            )
            DropdownMenuItem(
                text = {
                    Row {
                        Text(if (guidanceEnabled) "Mute" else "Unmute",
                            style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(12.dp))
                        Text("guidance", style = MaterialTheme.typography.titleMedium)
                    }
                },
                onClick = onMuteToggle,
            )
            DropdownMenuItem(
                text = {
                    Row {
                        Text("+", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(16.dp))
                        Text("Volume up", style = MaterialTheme.typography.titleMedium)
                    }
                },
                onClick = onRaise,
            )
        }
    }
}
