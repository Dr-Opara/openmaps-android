package com.openmaps.app.feature.live

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openmaps.app.R
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.designsystem.OpenMapsMark
import com.openmaps.app.core.designsystem.OpenMapsWordmark
import com.openmaps.app.feature.map.MapModeToggle
import com.openmaps.app.map.MapMode

/**
 * Phase 1 placeholder for the camera-first Live experience. No camera, no AR.
 * Opaque so it fully covers the map underneath and swallows its touches.
 */
@Composable
fun LiveScreen(
    onModeSelected: (MapMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(OpenMapsColors.Navy800, OpenMapsColors.Navy950)))
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpenMapsWordmark(Modifier.padding(start = 4.dp))
            MapModeToggle(selected = MapMode.Live, onSelected = onModeSelected)
        }

        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OpenMapsMark(Modifier.height(72.dp).width(53.dp))
            Text(
                text = stringResource(R.string.live_title),
                style = MaterialTheme.typography.titleLarge,
                color = OpenMapsColors.TextPrimary,
            )
            Text(
                text = stringResource(R.string.live_body),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenMapsColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
