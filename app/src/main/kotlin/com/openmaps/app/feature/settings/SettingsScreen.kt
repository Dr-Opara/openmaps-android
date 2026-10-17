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
import com.openmaps.app.core.localization.MeasurementSystem
import com.openmaps.app.core.voice.VoiceLanguage
import com.openmaps.app.map.MapStyleSource
import java.util.Locale

@Composable
fun SettingsScreen(
    permission: LocationPermissionState,
    styleSource: MapStyleSource,
    onBack: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** Delta-spec section 24. Defaulted so every other existing caller/preview keeps compiling. */
    voiceLanguage: VoiceLanguage = VoiceLanguage.EnglishUs,
    onVoiceLanguageChanged: (VoiceLanguage) -> Unit = {},
    measurementSystem: MeasurementSystem = MeasurementSystem.Auto,
    onMeasurementSystemChanged: (MeasurementSystem) -> Unit = {},
    onOpenLanguageSettings: () -> Unit = {},
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

            SectionHeader(stringResource(R.string.settings_section_navigation))
            SettingsRow(
                title = stringResource(R.string.settings_app_language),
                value = Locale.getDefault().getDisplayName(Locale.getDefault()),
            )
            ActionRow(stringResource(R.string.settings_change_app_language), onOpenLanguageSettings)

            Text(
                text = stringResource(R.string.settings_units),
                style = MaterialTheme.typography.bodyLarge,
                color = OpenMapsColors.TextPrimary,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            MeasurementSystem.entries.forEach { system ->
                val isSelected = system == measurementSystem
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.RadioButton) { onMeasurementSystemChanged(system) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(system.labelRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) OpenMapsColors.ElectricBlue else OpenMapsColors.TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) {
                        Text(
                            text = stringResource(R.string.settings_voice_language_selected),
                            style = MaterialTheme.typography.labelMedium,
                            color = OpenMapsColors.ElectricBlue,
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.settings_voice_language),
                style = MaterialTheme.typography.bodyLarge,
                color = OpenMapsColors.TextPrimary,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            Text(
                text = stringResource(R.string.settings_voice_ai_disclosure),
                style = MaterialTheme.typography.bodySmall,
                color = OpenMapsColors.TextSecondary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            VoiceLanguage.entries.forEach { language ->
                val isSelected = language == voiceLanguage
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.RadioButton) { onVoiceLanguageChanged(language) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(language.labelRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) OpenMapsColors.ElectricBlue else OpenMapsColors.TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) {
                        Text(
                            text = stringResource(R.string.settings_voice_language_selected),
                            style = MaterialTheme.typography.labelMedium,
                            color = OpenMapsColors.ElectricBlue,
                        )
                    }
                }
            }

            SectionHeader(stringResource(R.string.settings_section_about))
            SettingsRow(title = stringResource(R.string.app_name), value = stringResource(R.string.tagline))
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            SettingsRow(title = stringResource(R.string.settings_version), value = BuildConfig.VERSION_NAME)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Delta-spec section 18: the five initial language/voice options. */
private fun VoiceLanguage.labelRes(): Int = when (this) {
    VoiceLanguage.EnglishUs -> R.string.settings_voice_language_en_us
    VoiceLanguage.EnglishUk -> R.string.settings_voice_language_en_gb
    VoiceLanguage.Spanish -> R.string.settings_voice_language_es
    VoiceLanguage.French -> R.string.settings_voice_language_fr
    VoiceLanguage.NigerianPidgin -> R.string.settings_voice_language_pidgin
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


private fun MeasurementSystem.labelRes(): Int = when (this) {
    MeasurementSystem.Auto -> R.string.settings_units_auto
    MeasurementSystem.Imperial -> R.string.settings_units_imperial
    MeasurementSystem.Metric -> R.string.settings_units_metric
}
