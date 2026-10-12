package com.openmaps.app.feature.search

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openmaps.app.AppContainer

/** Builds [SearchViewModel] from the app container (no DI framework). */
fun searchViewModelFactory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        SearchViewModel(
            geocodingRepository = container.geocodingRepository,
            recentSearchStore = container.recentSearchStore,
        )
    }
}
