package com.openmaps.app.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.roadintelligence.RoadIntelligenceState
import com.openmaps.app.core.roadintelligence.RouteAlert

/**
 * Map mode's road-intelligence surface (section 11 of the Phase 10 brief): a density-limited
 * single row, never more than the top-priority alert plus a speed readout, placed below the
 * search bar/navigation banner so it never covers the route or destination.
 */
@Composable
fun RoadAlertBanner(roadState: RoadIntelligenceState, modifier: Modifier = Modifier) {
    val topAlert = roadState.activeAlerts.firstOrNull { it !is RouteAlert.SpeedLimit }
    val speedLimitAlert = roadState.activeAlerts.filterIsInstance<RouteAlert.SpeedLimit>().firstOrNull()
    val currentSpeed = roadState.currentSpeedMetersPerSecond
    if (topAlert == null && speedLimitAlert == null) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        topAlert?.let { alert ->
            Chip(background = OpenMapsColors.Warning) {
                val distance = RoadAlertPresentation.distanceAheadLabel(alert)
                Text(
                    text = if (distance != null) "${RoadAlertPresentation.headline(alert)} $distance" else RoadAlertPresentation.headline(alert),
                    style = MaterialTheme.typography.labelMedium,
                    color = OpenMapsColors.Navy900,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        speedLimitAlert?.let { alert ->
            Chip(background = OpenMapsColors.Navy800) {
                val text = if (currentSpeed != null) {
                    "${RoadAlertPresentation.formatCurrentSpeed(currentSpeed, alert.info.unit)} / ${RoadAlertPresentation.headline(alert)}"
                } else {
                    RoadAlertPresentation.headline(alert)
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
