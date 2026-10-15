package com.openmaps.app.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openmaps.app.R
import com.openmaps.app.core.designsystem.GlassShapes
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
    /** Set when the home screen's mic was tapped: start capture as soon as this screen appears. */
    autoStartVoiceSearch: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // The recognizer is owned here (it needs an Activity + ActivityResult launcher), while its
    // outcome is reported into the ViewModel so the state stays testable without Android.
    val voiceLauncher = rememberVoiceSearchLauncher(
        onStarted = viewModel::onVoiceSearchStarted,
        onUnavailable = viewModel::onVoiceSearchUnavailable,
        onFailed = viewModel::onVoiceSearchFailed,
        onCancelled = viewModel::onVoiceSearchCancelled,
        onResult = viewModel::onVoiceSearchResult,
    )

    fun selectResult(result: SearchResult) {
        viewModel.onResultSelected(result)
        onResultSelected(result)
    }

    // Only when explicitly asked (the home mic), and only once per appearance.
    var voiceArmed by remember { mutableStateOf(autoStartVoiceSearch) }
    LaunchedEffect(voiceArmed) {
        if (voiceArmed) {
            voiceArmed = false
            voiceLauncher()
        }
    }

    // Mockup Search Results: white screen, rounded search field with the entered query and a clear X.
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(OpenMapsColors.Surface)
                .pointerInput(Unit) { detectTapGestures { } }
                .statusBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
        // Mockup: one borderless search pill, no second close button beside it — the system back
        // gesture/button (always available) is how this screen is left, same as [onBack] already
        // wired for it.
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChanged,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_title)) },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = OpenMapsColors.TextSecondary,
                )
            },
            trailingIcon = {
                // The microphone is a real control: it launches Android's speech recognizer and
                // every outcome (heard / cancelled / unavailable / error) is reported, so it can
                // never be a dead icon.
                IconButton(onClick = voiceLauncher) {
                    Icon(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = stringResource(R.string.cd_voice_search),
                        tint = if (state.voiceState is VoiceSearchState.Listening) {
                            OpenMapsColors.ElectricBlueFilled
                        } else {
                            OpenMapsColors.TextSecondary
                        },
                    )
                }
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
            shape = GlassShapes.Pill,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = OpenMapsColors.TextPrimary,
                unfocusedTextColor = OpenMapsColors.TextPrimary,
                focusedContainerColor = OpenMapsColors.SurfaceMuted,
                unfocusedContainerColor = OpenMapsColors.SurfaceMuted,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = OpenMapsColors.ElectricBlueFilled,
                focusedPlaceholderColor = OpenMapsColors.TextSecondary,
                unfocusedPlaceholderColor = OpenMapsColors.TextSecondary,
            ),
        )

        Spacer(Modifier.height(16.dp))

        // Honest voice state. Never swallowed: no recognizer on the device has to be visible.
        VoiceSearchBanner(state = state.voiceState, onShown = viewModel::onVoiceSearchMessageShown)

        SearchFilterTabs(
            selected = state.selectedTab,
            onSelected = viewModel::onTabSelected,
        )

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
            // A search that succeeded but has nothing in the selected bucket. Distinct from
            // "no results at all" so the user knows to switch tabs rather than retype.
            state.selectedTabIsEmpty -> SearchStatusMessage {
                Text(
                    text = stringResource(R.string.search_no_results_for_tab, state.selectedTab.label()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextSecondary,
                )
            }
            else -> SearchResultsList(state.visibleResults, onSelect = ::selectResult)
        }
    }
}

/** One line explaining the voice-search outcome. Renders nothing while idle. */
@Composable
private fun VoiceSearchBanner(state: VoiceSearchState, onShown: () -> Unit) {
    val message = when (state) {
        VoiceSearchState.Idle -> null
        VoiceSearchState.Listening -> stringResource(R.string.voice_search_listening)
        VoiceSearchState.Unavailable -> stringResource(R.string.voice_search_unavailable)
        is VoiceSearchState.Failed -> state.message
    }
    if (message == null) return
    LaunchedEffect(state) { onShown() }
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = if (state is VoiceSearchState.Listening) OpenMapsColors.ElectricBlueFilled else OpenMapsColors.TextSecondary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * The mockup's All / Places / Addresses / Categories row.
 *
 * Functional: each tab partitions the provider's own result list by what Nominatim actually
 * reported (see [SearchResultKind]), so a place never appears under Addresses and vice versa.
 */
@Composable
private fun SearchFilterTabs(
    selected: SearchTab,
    onSelected: (SearchTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SearchTab.entries.forEach { tab ->
                val isSelected = tab == selected
                Column(
                    modifier = Modifier
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            onClick = { onSelected(tab) },
                        )
                        .padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(tab.labelRes()),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) OpenMapsColors.ElectricBlueFilled else OpenMapsColors.TextSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    // Mockup: a short blue underline beneath only the selected tab.
                    Box(
                        Modifier
                            .height(2.dp)
                            .width(if (isSelected) 28.dp else 0.dp)
                            .background(OpenMapsColors.ElectricBlueFilled),
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(OpenMapsColors.SurfaceBorder),
        )
    }
}

private fun SearchTab.labelRes(): Int = when (this) {
    SearchTab.All -> R.string.search_filter_all
    SearchTab.Places -> R.string.search_filter_places
    SearchTab.Addresses -> R.string.search_filter_addresses
    SearchTab.Categories -> R.string.search_filter_categories
}

private fun SearchTab.label(): String = when (this) {
    SearchTab.All -> "all results"
    SearchTab.Places -> "places"
    SearchTab.Addresses -> "addresses"
    SearchTab.Categories -> "categories"
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
            color = OpenMapsColors.ElectricBlueFilled,
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
                    SearchResultRow(result, onClick = { onSelect(result) })
                }
            }
        }
    }
}

@Composable
private fun SearchResultsList(results: List<SearchResult>, onSelect: (SearchResult) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth()) {
        itemsIndexed(results, key = { _, result -> result.id }) { index, result ->
            SearchResultRow(result, onClick = { onSelect(result) })
            // Mockup: a hairline divider between rows, not after the last one.
            if (index != results.lastIndex) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(OpenMapsColors.SurfaceBorder))
            }
        }
    }
}

/**
 * A result row: semantic icon in a circle, name, supporting address/category, and a chevron
 * affordance.
 *
 * The icon circle distinguishes a business result (solid blue circle, white pin) from an address /
 * locality (muted circle, blue pin) using [SearchResult.hasBusinessCategory] — the one result-type
 * signal the provider actually supplies. The mockup also gives its very first row a distinct
 * "top match" red-pin treatment, which is not reproduced: Nominatim exposes no honest "this is the
 * best match" signal, and inventing one would be exactly the fabrication rule this screen follows
 * (see the no-rating note below).
 *
 * **No rating is rendered** — the geocoding provider returns none, and inventing one is exactly the
 * fabrication rule this screen follows.
 */
@Composable
private fun SearchResultRow(
    result: SearchResult,
    onClick: () -> Unit,
) {
    val isBusiness = result.hasBusinessCategory()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    if (isBusiness) OpenMapsColors.ElectricBlueFilled else OpenMapsColors.SurfaceMuted,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (isBusiness) R.drawable.ic_place else R.drawable.ic_my_location),
                contentDescription = null,
                tint = if (isBusiness) Color.White else OpenMapsColors.ElectricBlueFilled,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = result.name.ifBlank { result.displayName },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = OpenMapsColors.TextPrimary,
            )
            if (result.subtitle.isNotBlank()) {
                Text(
                    text = result.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Only ever the provider's own words — the raw Nominatim category/type. Mockup folds
            // this into the same gray metadata line rather than a separate blue label.
            result.category?.takeIf { it.isNotBlank() }?.let { category ->
                Text(
                    text = category.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                    color = OpenMapsColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = OpenMapsColors.TextSecondary,
        )
    }
}
