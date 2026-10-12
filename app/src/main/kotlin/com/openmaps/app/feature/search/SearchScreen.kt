package com.openmaps.app.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.openmaps.app.R
import com.openmaps.app.core.designsystem.OpenMapsColors

/**
 * Phase 1 placeholder: "Search destinations". There is deliberately no geocoder, no Places
 * API and no fake backend; typing shows an honest "not available yet" message and the
 * Recent list is empty until real history exists.
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OpenMapsColors.Navy900)
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = OpenMapsColors.TextPrimary,
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
                    .focusRequester(focusRequester),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_title)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = MaterialTheme.shapes.extraLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = OpenMapsColors.TextPrimary,
                    unfocusedTextColor = OpenMapsColors.TextPrimary,
                    focusedContainerColor = OpenMapsColors.Navy800,
                    unfocusedContainerColor = OpenMapsColors.Navy800,
                    focusedBorderColor = OpenMapsColors.ElectricBlue,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                    cursorColor = OpenMapsColors.ElectricBlue,
                    focusedPlaceholderColor = OpenMapsColors.TextSecondary,
                    unfocusedPlaceholderColor = OpenMapsColors.TextSecondary,
                ),
            )
        }

        Spacer(Modifier.height(24.dp))

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (query.isBlank()) {
                Text(
                    text = stringResource(R.string.search_recent_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = OpenMapsColors.Cyan,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.search_recent_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextSecondary,
                )
            } else {
                Text(
                    text = stringResource(R.string.search_not_available),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextSecondary,
                )
            }
        }
    }
}
