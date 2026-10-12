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

/** Subtle top-of-map branding: small mark + wordmark. */
@Composable
fun OpenMapsWordmark(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        OpenMapsMark(Modifier.height(20.dp).width(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.labelLarge,
            color = OpenMapsColors.TextPrimary,
        )
    }
}
