package com.openmaps.app.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.openmaps.app.R

/** The OpenMaps pin mark (decorative: the wordmark carries the name). */
@Composable
fun OpenMapsMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_openmaps_mark),
        contentDescription = null,
        modifier = modifier,
    )
}

/**
 * The wordmark, split into its two halves as the mockup shows it: "Open" in dark navy and "Maps" in
 * OpenMaps blue.
 *
 * Rendered as one accessibility label (`app_name`) rather than two fragments, so a screen reader
 * announces "OpenMaps" once instead of "Open Maps" with two separate stops.
 *
 * [onLight] is the only thing that changes between surfaces: on the light Map screens the navy "Open"
 * is right, while over Live's dark camera feed the whole mark has to invert or it disappears. There
 * is no all-white or all-blue wordmark.
 */
@Composable
fun OpenMapsWordmark(
    modifier: Modifier = Modifier,
    onLight: Boolean = true,
) {
    // One label for the whole mark, read once rather than as three fragments.
    val label = stringResource(R.string.app_name)
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenMapsMark(
            Modifier
                .height(20.dp)
                .width(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.brand_open),
            style = MaterialTheme.typography.labelLarge,
            color = if (onLight) OpenMapsColors.TextPrimary else OpenMapsColors.TextOnDark,
        )
        Text(
            text = stringResource(R.string.brand_maps),
            style = MaterialTheme.typography.labelLarge,
            color = OpenMapsColors.ElectricBlue,
        )
    }
}
