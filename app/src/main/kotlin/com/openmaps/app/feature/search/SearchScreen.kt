package com.openmaps.app.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openmaps.app.R
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.geocoding.SearchResult

/**
 * Destination search: typing debounces into a geocoding lookup ([SearchViewModel]); the recent
 * list shows when the query is blank. Selection bookkeeping (clearing the query, recording the
 * recent) happens here; telling the map about the new destination and closing this screen is
 * the caller's job via [onResultSelected], same pattern as [onBack].
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onResultSelected: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun selectResult(result: SearchResult) {
        viewModel.onResultSelected(result)
        onResultSelected(result)
    }

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
                value = state.query,
                onValueChange = viewModel::onQueryChanged,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
                    .focusRequester(focusRequester),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_title)) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChanged("") }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.cd_clear_query),
                                tint = OpenMapsColors.TextSecondary,
                            )
                        }
                    }
                },
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

        Spacer(Modifier.height(16.dp))

        val currentError = state.error
        when {
            state.query.isBlank() -> RecentSearchesList(state.recentSearches, onSelect = ::selectResult)
            state.isSearching -> SearchStatusMessage { LoadingRow() }
            currentError != null -> SearchStatusMessage { ErrorRow(currentError) }
            state.hasNoResults -> SearchStatusMessage {
                Text(
                    text = stringResource(R.string.search_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextSecondary,
                )
            }
            else -> SearchResultsList(state.results, onSelect = ::selectResult)
        }
    }
}

@Composable
private fun SearchStatusMessage(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { content() }
}

@Composable
private fun LoadingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = OpenMapsColors.ElectricBlue,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.search_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = OpenMapsColors.TextSecondary,
        )
    }
}

@Composable
private fun ErrorRow(error: SearchError) {
    Text(
        text = stringResource(error.toMessageRes()),
        style = MaterialTheme.typography.bodyMedium,
        color = OpenMapsColors.TextSecondary,
    )
}

private fun SearchError.toMessageRes(): Int = when (this) {
    SearchError.NoNetwork -> R.string.search_error_no_network
    SearchError.Timeout -> R.string.search_error_timeout
    SearchError.RateLimited -> R.string.search_error_rate_limited
    SearchError.ServerError -> R.string.search_error_server
    SearchError.Malformed -> R.string.search_error_malformed
}

@Composable
private fun RecentSearchesList(recents: List<SearchResult>, onSelect: (SearchResult) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.search_recent_header),
            style = MaterialTheme.typography.labelMedium,
            color = OpenMapsColors.Cyan,
        )
        Spacer(Modifier.height(8.dp))
        if (recents.isEmpty()) {
            Text(
                text = stringResource(R.string.search_recent_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenMapsColors.TextSecondary,
            )
        } else {
            LazyColumn {
                items(recents, key = { it.id }) { result ->
                    SearchResultRow(result, leadingIcon = Icons.Filled.Place, onClick = { onSelect(result) })
                }
            }
        }
    }
}

@Composable
private fun SearchResultsList(results: List<SearchResult>, onSelect: (SearchResult) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth()) {
        items(results, key = { it.id }) { result ->
            SearchResultRow(result, leadingIcon = Icons.Filled.LocationOn, onClick = { onSelect(result) })
        }
    }
}

@Composable
private fun SearchResultRow(
    result: SearchResult,
    leadingIcon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(leadingIcon, contentDescription = null, tint = OpenMapsColors.ElectricBlue)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = result.name.ifBlank { result.displayName },
                style = MaterialTheme.typography.bodyLarge,
                color = OpenMapsColors.TextPrimary,
            )
            Text(
                text = result.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = OpenMapsColors.TextSecondary,
            )
        }
    }
}
