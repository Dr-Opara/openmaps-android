package com.openmaps.app.feature.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.openmaps.app.R
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.designsystem.OpenMapsTheme
import com.openmaps.app.core.roadintelligence.Lane
import com.openmaps.app.core.roadintelligence.LaneDirection
import com.openmaps.app.core.roadintelligence.LaneGuidance

/**
 * Isolated reconstruction of the reference's lower-left instrument cluster: the circular speed
 * dial and the posted-limit sign **side by side**, bottom-aligned — see
 * `docs/LIVE_MOCKUP_VISUAL_SPEC.md` section 2.1. Not wired into [LiveScreen]; its private
 * `LiveSpeedBlock` stacks the sign above the dial instead.
 *
 * Visual-only: takes already-formatted strings and a precomputed [arcFraction] rather than a
 * provider/location type, so this file adds no new reading of navigation state. [arcFraction] is
 * passed in verbatim (not recomputed here) because what the ring should actually measure — a fixed
 * design scale vs. a speed/limit ratio — is flagged in the spec doc as a product decision, not
 * settled by this component.
 */
@Composable
fun LiveSpeedRow(
    speedValue: String?,
    speedUnit: String,
    arcFraction: Float,
    limitValue: String?,
    modifier: Modifier = Modifier,
) {
    if (speedValue == null && limitValue == null) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (speedValue != null) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Color(0xE3101B2B)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(5.dp)) {
                    val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                    drawArc(
                        color = Color.White.copy(alpha = 0.2f),
                        startAngle = 132f,
                        sweepAngle = 276f,
                        useCenter = false,
                        style = stroke,
                    )
                    drawArc(
                        color = OpenMapsColors.ElectricBlueFilled,
                        startAngle = 132f,
                        sweepAngle = 276f * arcFraction.coerceIn(0f, 1f),
                        useCenter = false,
                        style = stroke,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(speedValue, style = MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 1)
                    Text(speedUnit, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
                }
            }
        }
        if (limitValue != null) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(2.dp, OpenMapsColors.Navy900, RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.live_speed_limit_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = OpenMapsColors.Navy900,
                )
                Text(limitValue, style = MaterialTheme.typography.headlineSmall, color = OpenMapsColors.Navy900, maxLines = 1)
            }
        }
    }
}

/**
 * Richer lane-guidance card matching the reference's two-line header + bar-style lane segments —
 * see spec section 2.7. Deliberately does **not** derive [headline]/[subtitle] from [guidance]
 * itself: generating "Keep right" / "Use either of the 2 right lanes" from lane data is
 * presentation logic this isolated, visual-only file has no authority to invent, exactly like the
 * existing [com.openmaps.app.feature.map.LaneGuidanceStrip] leaves its own caption to its caller.
 * Not wired anywhere; `LaneGuidanceStrip` remains the one actually shown in [LiveScreen].
 */
@Composable
fun LaneGuidanceCard(
    headline: String,
    subtitle: String?,
    guidance: LaneGuidance,
    modifier: Modifier = Modifier,
) {
    if (guidance.lanes.isEmpty()) return
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(OpenMapsColors.Navy900.copy(alpha = 0.86f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(headline, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = OpenMapsColors.TextOnDarkSecondary, maxLines = 1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            guidance.lanes.forEachIndexed { index, lane ->
                val recommended = index in guidance.recommendedLaneIndexes && lane.isRecommended
                Box(
                    modifier = Modifier
                        .size(width = 28.dp, height = 40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (recommended) OpenMapsColors.ElectricBlueFilled else Color.Transparent)
                        .border(
                            1.dp,
                            if (recommended) Color.Transparent else Color.White.copy(alpha = 0.25f),
                            RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = lane.directions.firstOrNull()?.laneArrowGlyph() ?: "·",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (recommended) Color.White else Color.White.copy(alpha = 0.45f),
                    )
                }
            }
        }
    }
}

private fun LaneDirection.laneArrowGlyph(): String = when (this) {
    LaneDirection.Left -> "←"
    LaneDirection.SlightLeft -> "↖"
    LaneDirection.Straight -> "↑"
    LaneDirection.SlightRight -> "↗"
    LaneDirection.Right -> "→"
    LaneDirection.UTurn -> "↶"
    LaneDirection.Unknown -> "·"
}

// ---- Previews: debug-only, clearly-labeled fixtures. Not production data. ----

@Preview(name = "Live speed row (isolated)", showBackground = true, backgroundColor = 0xFF0A1224)
@Composable
private fun LiveSpeedRowPreview() {
    OpenMapsTheme {
        LiveSpeedRow(
            speedValue = "24",
            speedUnit = "mph",
            arcFraction = 0.95f,
            limitValue = "25",
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Lane guidance card (isolated)", showBackground = true, backgroundColor = 0xFF0A1224, widthDp = 360)
@Composable
private fun LaneGuidanceCardPreview() {
    OpenMapsTheme {
        LaneGuidanceCard(
            headline = "Keep right",
            subtitle = "Use either of the 2 right lanes",
            guidance = LaneGuidance(
                lanes = listOf(
                    Lane(directions = listOf(LaneDirection.Straight), isRecommended = false),
                    Lane(directions = listOf(LaneDirection.Straight, LaneDirection.SlightRight), isRecommended = true),
                    Lane(directions = listOf(LaneDirection.Right), isRecommended = true),
                    Lane(directions = listOf(LaneDirection.Right), isRecommended = false),
                ),
                recommendedLaneIndexes = listOf(1, 2),
                maneuverRelation = null,
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}
