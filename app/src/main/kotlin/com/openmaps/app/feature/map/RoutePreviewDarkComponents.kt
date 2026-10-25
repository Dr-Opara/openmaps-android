package com.openmaps.app.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.openmaps.app.R
import com.openmaps.app.core.designsystem.GlassShapes
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.designsystem.OpenMapsTheme
import com.openmaps.app.core.designsystem.SheetDragHandle

/**
 * Dark-sheet reconstruction of Route Preview, per `docs/LIVE_MOCKUP_VISUAL_SPEC.md` section 2.3:
 * every reference frame shows this screen as a dark navy sheet (white figures, dark cards), while
 * the live `BottomPanel` (`MapControls.kt`) wraps Route Preview in the same opaque-white
 * `.surfaceCard(...)` it uses for Home/Search. That shared container is left untouched here — no
 * reference frame for Search/Destination Details was available to confirm those should go dark too
 * — so this file is a self-contained, **not wired**, dark card set for Route Preview specifically.
 *
 * Card fill uses [OpenMapsColors.Navy800] (already defined, one step lighter than the
 * [OpenMapsColors.Navy900] sheet background these are meant to sit on) rather than a new color.
 */
private val RoutePreviewCardFill = OpenMapsColors.Navy800

@Composable
fun RoutePreviewDarkMetricCard(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(RoutePreviewCardFill, GlassShapes.Card)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = OpenMapsColors.TextOnDarkSecondary,
            maxLines = 1,
        )
    }
}

@Composable
fun RoutePreviewDarkMetricsRow(
    durationText: String,
    distanceText: String,
    arrivalText: String,
    statusText: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoutePreviewDarkMetricCard(
                value = durationText,
                label = stringResource(R.string.route_preview_duration),
                modifier = Modifier.weight(1f),
            )
            RoutePreviewDarkMetricCard(
                value = distanceText,
                label = stringResource(R.string.route_preview_distance),
                modifier = Modifier.weight(1f),
            )
            RoutePreviewDarkMetricCard(
                value = arrivalText,
                label = stringResource(R.string.route_preview_arrival),
                modifier = Modifier.weight(1f),
            )
        }
        statusText?.let {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(OpenMapsColors.Warning))
                Text(it, style = MaterialTheme.typography.bodySmall, color = OpenMapsColors.TextOnDarkSecondary)
            }
        }
    }
}

/** Alternate-route row with a real "Use" button, per section 2.4 (current code uses a bare text link). */
@Composable
fun RoutePreviewDarkAlternateRow(
    label: String,
    detail: String,
    onUseClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(RoutePreviewCardFill, GlassShapes.Row)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = OpenMapsColors.TextOnDarkSecondary)
        }
        Text(
            text = stringResource(R.string.route_use_action),
            style = MaterialTheme.typography.labelLarge,
            color = OpenMapsColors.Navy900,
            modifier = Modifier
                .clip(GlassShapes.Pill)
                .background(Color.White.copy(alpha = 0.92f))
                .clickable(role = Role.Button, onClick = onUseClick)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/**
 * "Choose navigation view" selector card, per section 2.5: icon + title + caption + an explicit
 * radio circle, selected = outline + filled radio (never a full-bleed blue fill, unlike the current
 * `NavigationModeCard` in `NavigationControls.kt`). [icon] is optional and nullable rather than
 * reaching for a placeholder glyph: no camera-style drawable exists in `res/drawable` yet, so the
 * "Live Camera" fixture below renders without one instead of inventing a new asset.
 */
@Composable
fun NavigationViewOptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
) {
    val borderColor = if (selected) OpenMapsColors.ElectricBlueFilled else Color.White.copy(alpha = 0.16f)
    Column(
        modifier = modifier
            .heightIn(min = 104.dp)
            .clip(GlassShapes.Card)
            .background(RoutePreviewCardFill)
            .border(if (selected) 2.dp else 1.dp, borderColor, GlassShapes.Card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            icon?.let {
                Icon(painter = it, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (selected) OpenMapsColors.ElectricBlueFilled else Color.White.copy(alpha = 0.4f), CircleShape)
                    .background(if (selected) OpenMapsColors.ElectricBlueFilled else Color.Transparent),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = OpenMapsColors.TextOnDarkSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Start + Steps side by side in one row, per section 2.6 (current code stacks Steps below Start). */
@Composable
fun RoutePreviewCtaRow(
    startLabel: String,
    onStart: () -> Unit,
    stepsLabel: String,
    onSteps: () -> Unit,
    modifier: Modifier = Modifier,
    startEnabled: Boolean = true,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .weight(0.7f)
                .heightIn(min = 54.dp)
                .clip(GlassShapes.Pill)
                .background(
                    if (startEnabled) OpenMapsColors.ElectricBlueFilled else OpenMapsColors.ElectricBlueFilled.copy(alpha = 0.4f),
                )
                .clickable(enabled = startEnabled, role = Role.Button, onClick = onStart)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(startLabel, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(
            modifier = Modifier
                .weight(0.3f)
                .heightIn(min = 54.dp)
                .clip(GlassShapes.Pill)
                .border(1.dp, Color.White.copy(alpha = 0.25f), GlassShapes.Pill)
                .clickable(role = Role.Button, onClick = onSteps)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stepsLabel, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- Previews: debug-only, clearly-labeled fixtures. Not production data/strings. ----

@Preview(
    name = "Route Preview — dark sheet (isolated, not wired)",
    showBackground = true,
    backgroundColor = 0xFF0A1224,
    widthDp = 392,
    heightDp = 760,
)
@Composable
private fun RoutePreviewDarkSheetPreview() {
    OpenMapsTheme {
        Column(
            modifier = Modifier
                .background(OpenMapsColors.Navy900)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SheetDragHandle(modifier = Modifier.align(Alignment.CenterHorizontally))
            Text("Route preview", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                text = "Review your trip and choose how you want to navigate.",
                style = MaterialTheme.typography.bodySmall,
                color = OpenMapsColors.TextOnDarkSecondary,
            )
            RoutePreviewDarkMetricsRow(
                durationText = "18 min",
                distanceText = "6.4 mi",
                arrivalText = "7:42 PM",
                statusText = "Heavy traffic",
            )
            Text("Other routes", style = MaterialTheme.typography.labelLarge, color = Color.White)
            RoutePreviewDarkAlternateRow(label = "22 min · Moderate traffic", detail = "7.1 mi", onUseClick = {})
            RoutePreviewDarkAlternateRow(label = "26 min · Light traffic", detail = "8.3 mi", onUseClick = {})
            Text("Choose navigation view", style = MaterialTheme.typography.labelLarge, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NavigationViewOptionCard(
                    title = "Map Navigation",
                    subtitle = "Traditional map view",
                    selected = true,
                    onClick = {},
                    icon = painterResource(R.drawable.ic_map),
                    modifier = Modifier.weight(1f),
                )
                NavigationViewOptionCard(
                    title = "Live Camera",
                    subtitle = "AR navigation view",
                    selected = false,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
            }
            RoutePreviewCtaRow(
                startLabel = "Start Map Navigation",
                onStart = {},
                stepsLabel = "Steps",
                onSteps = {},
            )
        }
    }
}
