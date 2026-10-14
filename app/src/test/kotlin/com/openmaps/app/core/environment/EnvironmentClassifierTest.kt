package com.openmaps.app.core.environment

import org.junit.Assert.assertEquals
import org.junit.Test

class EnvironmentClassifierTest {

    private val config = EnvironmentClassifierConfig()

    @Test
    fun motorway_classifiesAsHighway_regardlessOfSpeedOrDensity() {
        val signals = EnvironmentSignals(
            roadClass = RoadClass.Motorway,
            currentSpeedKmh = 15.0, // crawling in traffic — still a motorway
            averageManeuverSpacingMeters = 50.0, // would otherwise read as City
            nearbyPlaceCount = 20,
        )
        assertEquals(DrivingEnvironment.Highway, EnvironmentClassifier.classify(signals, config))
    }

    @Test
    fun motorwayLinkAndTrunkAndTrunkLink_allClassifyAsHighway() {
        listOf(RoadClass.MotorwayLink, RoadClass.Trunk, RoadClass.TrunkLink).forEach { roadClass ->
            val signals = EnvironmentSignals(roadClass = roadClass)
            assertEquals(roadClass.name, DrivingEnvironment.Highway, EnvironmentClassifier.classify(signals, config))
        }
    }

    @Test
    fun highSpeedAloneWithoutHighwayRoadClass_doesNotClassifyAsHighway() {
        // Section B2: "should not be triggered merely because current speed is high."
        val signals = EnvironmentSignals(
            roadClass = RoadClass.Secondary,
            currentSpeedKmh = 110.0,
            speedLimitKmh = 100.0,
        )
        assertEquals(DrivingEnvironment.Suburb, EnvironmentClassifier.classify(signals, config))
    }

    @Test
    fun denseShortSpacingAndHighPlaceDensity_classifiesAsCity() {
        val signals = EnvironmentSignals(
            roadClass = RoadClass.Residential,
            averageManeuverSpacingMeters = 150.0,
            nearbyPlaceCount = 10,
        )
        assertEquals(DrivingEnvironment.City, EnvironmentClassifier.classify(signals, config))
    }

    @Test
    fun residentialRoadClassAlone_classifiesAsCityEvenWithoutOtherEvidence() {
        val signals = EnvironmentSignals(roadClass = RoadClass.Residential)
        assertEquals(DrivingEnvironment.City, EnvironmentClassifier.classify(signals, config))
    }

    @Test
    fun longSpacingAndLowDensity_classifiesAsRural() {
        val signals = EnvironmentSignals(
            roadClass = RoadClass.Tertiary,
            averageManeuverSpacingMeters = 3_000.0,
            nearbyPlaceCount = 0,
        )
        assertEquals(DrivingEnvironment.Rural, EnvironmentClassifier.classify(signals, config))
    }

    @Test
    fun longSpacingButStillSomeNearbyPlaces_doesNotClassifyAsRural() {
        // Both long spacing AND low density are required together — one noisy signal alone must not
        // flip the mode.
        val signals = EnvironmentSignals(
            averageManeuverSpacingMeters = 3_000.0,
            nearbyPlaceCount = 5,
        )
        assertEquals(DrivingEnvironment.Suburb, EnvironmentClassifier.classify(signals, config))
    }

    @Test
    fun moderateEvidence_classifiesAsSuburb() {
        val signals = EnvironmentSignals(
            roadClass = RoadClass.Secondary,
            averageManeuverSpacingMeters = 800.0,
            nearbyPlaceCount = 3,
        )
        assertEquals(DrivingEnvironment.Suburb, EnvironmentClassifier.classify(signals, config))
    }

    @Test
    fun noSignalAtAll_classifiesAsUnknown() {
        assertEquals(DrivingEnvironment.Unknown, EnvironmentClassifier.classify(EnvironmentSignals()))
    }

    @Test
    fun onlySpeedLimitKnown_isEnoughToAvoidUnknown() {
        val signals = EnvironmentSignals(speedLimitKmh = 50.0)
        // Not enough to be clearly City or Rural, so Suburb (the balanced middle) — but critically
        // not Unknown, since a real signal (the speed limit) is present.
        assertEquals(DrivingEnvironment.Suburb, EnvironmentClassifier.classify(signals))
    }

    @Test
    fun customThresholds_areRespected() {
        val looseConfig = EnvironmentClassifierConfig(cityManeuverSpacingMeters = 1_000.0, cityPlaceDensityMin = 2)
        val signals = EnvironmentSignals(averageManeuverSpacingMeters = 900.0, nearbyPlaceCount = 1)
        assertEquals(DrivingEnvironment.City, EnvironmentClassifier.classify(signals, looseConfig))
    }
}
