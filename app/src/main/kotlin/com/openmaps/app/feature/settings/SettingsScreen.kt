package com.openmaps.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.openmaps.app.BuildConfig
import com.openmaps.app.R
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.location.LocationPermissionState
import com.openmaps.app.map.MapStyleSource

@Composable
fun SettingsScreen(
    permission: LocationPermissionState,
    styleSource: MapStyleSource,
    onBack: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OpenMapsColors.Navy900)
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = OpenMapsColors.TextPrimary,
                )
            }
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                color = OpenMapsColors.TextPrimary,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionHeader(stringResource(R.string.settings_section_location))
            SettingsRow(
                title = stringResource(R.string.settings_location_access),
                value = stringResource(permission.label()),
            )
            if (permission !is LocationPermissionState.Granted) {
                ActionRow(stringResource(R.string.settings_open_app_settings), onOpenAppSettings)
            }

            SectionHeader(stringResource(R.string.settings_section_map))
            SettingsRow(title = stringResource(R.string.settings_map_style), value = styleSource.name)
            if (styleSource.isDevelopmentOnly) {
                Text(
                    text = stringResource(R.string.settings_map_dev_only),
                    style = MaterialTheme.typography.labelMedium,
                    color = OpenMapsColors.Warning,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                text = styleSource.attribution,
                style = MaterialTheme.typography.bodyMedium,
                color = OpenMapsColors.TextSecondary,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            SectionHeader(stringResource(R.string.settings_section_about))
            SettingsRow(title = stringResource(R.string.app_name), value = stringResource(R.string.tagline))
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            SettingsRow(title = stringResource(R.string.settings_version), value = BuildConfig.VERSION_NAME)
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun LocationPermissionState.label(): Int = when (this) {
    LocationPermissionState.Unknown -> R.string.settings_perm_unknown
    LocationPermissionState.NotRequested -> R.string.settings_perm_not_requested
    is LocationPermissionState.Granted ->
        if (precise) R.string.settings_perm_precise else R.string.settings_perm_approximate
    LocationPermissionState.Denied -> R.string.settings_perm_denied
    LocationPermissionState.PermanentlyDenied -> R.string.settings_perm_blocked
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = OpenMapsColors.Cyan,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsRow(title: String, value: String) {
    Column(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, color = OpenMapsColors.TextPrimary)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = OpenMapsColors.TextSecondary)
    }
}

@Composable
private fun ActionRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = OpenMapsColors.ElectricBlue,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
    )
}
