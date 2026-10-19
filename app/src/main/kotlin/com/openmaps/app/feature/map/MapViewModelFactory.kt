package com.openmaps.app.feature.map

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openmaps.app.AppContainer

/** Builds [MapViewModel] from the app container (no DI framework). */
fun mapViewModelFactory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        MapViewModel(
            locationRepository = container.locationRepository,
            routingRepository = container.routingRepository,
            placesRepository = container.placesRepository,
            roadIntelligenceRepository = container.roadIntelligenceRepository,
            roadClassRepository = container.roadClassRepository,
            laneGuidanceRepository = container.laneGuidanceRepository,
            reverseGeocodingRepository = container.reverseGeocodingRepository,
            driverReportRepository = container.driverReportRepository,
            tripShareRepository = container.tripShareRepository,
            tripShareRecoveryStore = container.tripShareRecoveryStore,
            voiceGuidanceEngine = container.voiceGuidanceEngine,
            voiceGuidancePrefs = container.voiceGuidancePrefs,
            localizationPrefs = container.localizationPrefs,
            reliabilityReporter = container.reliabilityReporter,
            navigationForegroundController = container.navigationForegroundController,
            navigationRecoveryStore = container.navigationRecoveryStore,
            offlineMapCorridorCache = container.offlineMapCorridorCache,
        )
    }
}
