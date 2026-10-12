package com.openmaps.app.feature.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openmaps.app.R
import com.openmaps.app.core.designsystem.GlassShapes
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.designsystem.glass
import com.openmaps.app.map.MapMode
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/** Map | Live segmented toggle, shared by the Map and Live screens. */
@Composable
fun MapModeToggle(
    selected: MapMode,
    onSelected: (MapMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .glass(GlassShapes.Pill)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        MapMode.entries.forEach { mode ->
            val isSelected = mode == selected
            Box(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(CircleShape)
                    .drawBehind { if (isSelected) drawRect(OpenMapsColors.ElectricBlueFilled) }
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelected(mode) })
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(if (mode == MapMode.Map) R.string.mode_map else R.string.mode_live),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) Color.White else OpenMapsColors.TextSecondary,
                )
            }
        }
    }
}

/** "Where are you going?" entry point. Opens the placeholder search screen. */
@Composable
fun MapSearchBar(
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .glass(GlassShapes.Pill)
            .clickable(role = Role.Button, onClick = onSearchClick)
            .padding(start = 18.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = OpenMapsColors.ElectricBlue)
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.map_search_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = OpenMapsColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSettingsClick) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = stringResource(R.string.cd_settings),
                tint = OpenMapsColors.TextPrimary,
            )
        }
    }
}

@Composable
fun RecenterButton(
    hasLocation: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(52.dp)
            .glass(GlassShapes.Circle),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_my_location),
            contentDescription = stringResource(R.string.cd_recenter),
            tint = if (hasLocation) OpenMapsColors.ElectricBlue else OpenMapsColors.TextSecondary,
        )
    }
}

/**
 * Compass, visible only while the map is rotated. Collects the live bearing itself so only
 * this small composable recomposes while the user rotates the map.
 */
@Composable
fun CompassButton(
    bearing: StateFlow<Float>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val degrees by bearing.collectAsStateWithLifecycle()
    AnimatedVisibility(
        visible = abs(degrees) > COMPASS_VISIBLE_THRESHOLD_DEGREES,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier,
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(48.dp)
                .glass(GlassShapes.Circle),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_compass),
                contentDescription = stringResource(R.string.cd_compass),
                tint = Color.Unspecified,
                modifier = Modifier.graphicsLayer { rotationZ = -degrees },
            )
        }
    }
}

private const val COMPASS_VISIBLE_THRESHOLD_DEGREES = 1f

/**
 * Collapsed bottom panel placeholder. Intentionally shows no ETA, distance or route: nothing
 * is fabricated before routing exists.
 */
@Composable
fun BottomPanel(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glass(GlassShapes.Card)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(CircleShape)
                .drawBehind { drawRect(Color.White.copy(alpha = 0.28f)) },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.panel_title),
            style = MaterialTheme.typography.titleMedium,
            color = OpenMapsColors.TextPrimary,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}

/** One card for every location situation; copy and primary action depend on [prompt]. */
@Composable
fun LocationPromptCard(
    prompt: LocationPrompt,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (prompt == LocationPrompt.Loading) {
        Row(
            modifier = modifier
                .glass(GlassShapes.Pill)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = OpenMapsColors.ElectricBlue,
            )
            Text(
                text = stringResource(R.string.location_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenMapsColors.TextPrimary,
            )
        }
        return
    }

    val copy = prompt.copy()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glass(GlassShapes.Card)
            .padding(start = 18.dp, top = 14.dp, end = 6.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                Text(
                    text = stringResource(copy.title),
                    style = MaterialTheme.typography.titleMedium,
                    color = OpenMapsColors.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(copy.body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextSecondary,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_dismiss),
                    tint = OpenMapsColors.TextSecondary,
                )
            }
        }
        TextButton(onClick = onAction, modifier = Modifier.align(Alignment.Start)) {
            Text(
                text = stringResource(copy.action),
                style = MaterialTheme.typography.labelLarge,
                color = OpenMapsColors.ElectricBlue,
            )
        }
    }
}

private data class PromptCopy(val title: Int, val body: Int, val action: Int)

private fun LocationPrompt.copy(): PromptCopy = when (this) {
    LocationPrompt.AllowLocation -> PromptCopy(
        R.string.location_allow_title, R.string.location_allow_body, R.string.location_allow_action,
    )
    LocationPrompt.Denied -> PromptCopy(
        R.string.location_denied_title, R.string.location_denied_body, R.string.location_allow_action,
    )
    LocationPrompt.Blocked -> PromptCopy(
        R.string.location_blocked_title, R.string.location_blocked_body, R.string.location_blocked_action,
    )
    LocationPrompt.ServicesOff -> PromptCopy(
        R.string.location_services_off_title, R.string.location_services_off_body, R.string.location_services_off_action,
    )
    LocationPrompt.Unavailable -> PromptCopy(
        R.string.location_unavailable_title, R.string.location_unavailable_body, R.string.location_unavailable_action,
    )
    // Loading is rendered as a pill above and never reaches here.
    LocationPrompt.Loading -> PromptCopy(
        R.string.location_loading, R.string.location_loading, R.string.location_loading,
    )
}

/** Full-screen failure state: "Map unavailable" + retry. Browsing is blocked only by this. */
@Composable
fun MapUnavailableOverlay(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .drawBehind { drawRect(OpenMapsColors.Navy900) }
            // Swallow touches so gestures do not reach the (failed) map underneath.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.map_unavailable_title),
                style = MaterialTheme.typography.titleLarge,
                color = OpenMapsColors.TextPrimary,
            )
            Text(
                text = stringResource(R.string.map_unavailable_body),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenMapsColors.TextSecondary,
            )
            Button(onClick = onRetry) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}
