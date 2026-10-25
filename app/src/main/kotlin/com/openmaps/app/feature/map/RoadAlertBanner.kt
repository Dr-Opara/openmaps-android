package com.openmaps.app.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmaps.app.R
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.roadintelligence.LaneDirection
import com.openmaps.app.core.localization.LocalAppLocale
import com.openmaps.app.core.localization.LocalMeasurementSystem
import com.openmaps.app.core.roadintelligence.LaneGuidance
import com.openmaps.app.core.roadintelligence.LaneGuidanceReleaseGate
import com.openmaps.app.core.roadintelligence.RoadIntelligenceState
import com.openmaps.app.core.roadintelligence.RouteAlert

/**
 * Map mode's road-intelligence surface (section 11 of the Phase 10 brief): a density-limited
 * single row, never more than the top-priority alert plus a speed readout, placed below the
 * search bar/navigation banner so it never covers the route or destination.
 */
@Composable
fun RoadAlertBanner(
    roadState: RoadIntelligenceState,
    onCommunityConfirm: (String) -> Unit = {},
    onCommunityDismiss: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val topAlert = roadState.activeAlerts.firstOrNull {
        it !is RouteAlert.SpeedLimit && (it !is RouteAlert.Lane || LaneGuidanceReleaseGate.mayDisplay(it.guidance))
    }
    val speedLimitAlert = roadState.activeAlerts.filterIsInstance<RouteAlert.SpeedLimit>().firstOrNull()
    val measurementSystem = LocalMeasurementSystem.current
    val locale = LocalAppLocale.current
    val currentSpeed = roadState.currentSpeedMetersPerSecond
    if (topAlert == null && speedLimitAlert == null) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        topAlert?.let { alert ->
            if (alert is RouteAlert.Lane) {
                LaneGuidanceStrip(
                    guidance = alert.guidance,
                    modifier = Modifier,
                )
            } else {
                Column {
                    Chip(background = OpenMapsColors.Warning) {
                        val distance = RoadAlertPresentation.distanceAheadLabel(alert, measurementSystem, locale)
                        Text(
                            text = if (distance != null) {
                                "${RoadAlertPresentation.headline(alert, measurementSystem, locale)} $distance"
                            } else {
                                RoadAlertPresentation.headline(alert, measurementSystem, locale)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = OpenMapsColors.Navy900,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    alert.communityReportId()?.let { reportId ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onCommunityConfirm(reportId) }) {
                                Text(stringResource(R.string.community_still_there), style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { onCommunityDismiss(reportId) }) {
                                Text(stringResource(R.string.community_not_there), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        speedLimitAlert?.let { alert ->
            Chip(background = OpenMapsColors.Navy800) {
                val text = if (currentSpeed != null) {
                    "${RoadAlertPresentation.formatCurrentSpeed(currentSpeed, alert.info.unit, measurementSystem, locale)} / " +
                        RoadAlertPresentation.headline(alert, measurementSystem, locale)
                } else {
                    RoadAlertPresentation.headline(alert, measurementSystem, locale)
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = OpenMapsColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * LaneLock: renders exactly the physical lanes the provider returned, left-to-right.
 * Recommended lanes are strongly emphasized; every other lane remains visible but muted.
 * Nothing renders when the provider could not identify a trustworthy recommendation.
 */
@Composable
fun LaneGuidanceStrip(
    guidance: LaneGuidance,
    modifier: Modifier = Modifier,
    darkSurface: Boolean = false,
) {
    if (!LaneGuidanceReleaseGate.mayDisplay(guidance)) return

    val container = if (darkSurface) {
        OpenMapsColors.Navy950.copy(alpha = 0.82f)
    } else {
        OpenMapsColors.Surface
    }
    val labelColor = if (darkSurface) OpenMapsColors.TextOnDarkSecondary else OpenMapsColors.TextSecondary

    Column(
        modifier = modifier
            .background(container, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            text = stringResource(R.string.lane_use_lane),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            guidance.lanes.forEachIndexed { index, lane ->
                val recommended = index in guidance.recommendedLaneIndexes && lane.isRecommended
                val glyph = lane.directions
                    .map { it.laneGlyph() }
                    .distinct()
                    .joinToString("")
                    .ifBlank { "·" }
                Text(
                    text = glyph,
                    style = MaterialTheme.typography.titleLarge,
                    color = when {
                        recommended -> OpenMapsColors.ElectricBlueFilled
                        darkSurface -> OpenMapsColors.TextOnDarkSecondary.copy(alpha = 0.48f)
                        else -> OpenMapsColors.TextSecondary.copy(alpha = 0.48f)
                    },
                    modifier = Modifier
                        .background(
                            if (recommended) OpenMapsColors.ElectricBlueFilled.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private fun LaneDirection.laneGlyph(): String = when (this) {
    LaneDirection.Left -> "←"
    LaneDirection.SlightLeft -> "↖"
    LaneDirection.Straight -> "↑"
    LaneDirection.SlightRight -> "↗"
    LaneDirection.Right -> "→"
    LaneDirection.UTurn -> "↶"
    LaneDirection.Unknown -> "·"
}

@Composable
private fun Chip(background: androidx.compose.ui.graphics.Color, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .background(background, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        content()
    }
}


private fun RouteAlert.communityReportId(): String? = when (this) {
    is RouteAlert.Hazard -> event.metadata["communityReportId"]
    is RouteAlert.Informational -> event.metadata["communityReportId"]
    else -> null
}
