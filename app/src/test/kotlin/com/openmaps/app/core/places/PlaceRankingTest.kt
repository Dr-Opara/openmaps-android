package com.openmaps.app.core.places

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceRankingTest {

    private fun place(id: String, category: PlaceCategory, distanceMeters: Double) = Place(
        id = id,
        name = id,
        category = category,
        latitude = 0.0,
        longitude = 0.0,
        distanceMeters = distanceMeters,
    )

    @Test
    fun rank_closerPlaceOfSameCategoryWinsWhenBrowsing() {
        val near = place("near", PlaceCategory.Coffee, 50.0)
        val far = place("far", PlaceCategory.Coffee, 2_000.0)
        val ranked = PlaceRanking.rank(listOf(far, near), PlaceRanking.Context(isNavigating = false))
        assertEquals(listOf("near", "far"), ranked.map { it.id })
    }

    @Test
    fun rank_whileNavigating_utilityCategoryOutranksGeneralAtEqualDistance() {
        val gas = place("gas", PlaceCategory.Gas, 500.0)
        val coffee = place("coffee", PlaceCategory.Coffee, 500.0)
        val ranked = PlaceRanking.rank(listOf(coffee, gas), PlaceRanking.Context(isNavigating = true))
        assertEquals(listOf("gas", "coffee"), ranked.map { it.id })
    }

    @Test
    fun rank_whileBrowsing_utilityDoesNotOutrankGeneralAtEqualDistance() {
        val gas = place("gas", PlaceCategory.Gas, 500.0)
        val coffee = place("coffee", PlaceCategory.Coffee, 500.0)
        val ranked = PlaceRanking.rank(listOf(gas, coffee), PlaceRanking.Context(isNavigating = false))
        // Equal weight and equal distance: order is unaffected by category while just browsing.
        assertEquals(setOf("gas", "coffee"), ranked.map { it.id }.toSet())
    }

    @Test
    fun rank_whileNavigating_aheadPlaceOutranksBehindPlaceOfSameCategoryAndDistance() {
        val ahead = place("ahead", PlaceCategory.Coffee, 500.0)
        val behind = place("behind", PlaceCategory.Coffee, 500.0)
        val ranked = PlaceRanking.rank(
            listOf(behind, ahead),
            PlaceRanking.Context(isNavigating = true, aheadPlaceIds = setOf("ahead")),
        )
        assertEquals(listOf("ahead", "behind"), ranked.map { it.id })
    }

    @Test
    fun rank_routeDetourMeters_preferredOverStraightLineDistance() {
        // Small air distance but a large route detour (badly off the corridor) ...
        val nearByAirFarByDetour = Place(
            id = "near-air-far-detour",
            name = "near-air-far-detour",
            category = PlaceCategory.Coffee,
            latitude = 0.0,
            longitude = 0.0,
            distanceMeters = 50.0,
            routeDetourMeters = 5_000.0,
        )
        // ... versus a large air distance but a small route detour (right on the corridor).
        val farByAirNearByDetour = nearByAirFarByDetour.copy(
            id = "far-air-near-detour",
            distanceMeters = 5_000.0,
            routeDetourMeters = 50.0,
        )
        val ranked = PlaceRanking.rank(
            listOf(nearByAirFarByDetour, farByAirNearByDetour),
            PlaceRanking.Context(isNavigating = false),
        )
        assertEquals(listOf("far-air-near-detour", "near-air-far-detour"), ranked.map { it.id })
    }

    @Test
    fun rank_defaultMultipliers_reproduceThePrePhase11Order() {
        // Phase 11's `utilityWeightMultiplier`/`generalWeightMultiplier` default to 1.0, so a
        // Context with neither set must rank identically to before Phase 11 existed.
        val gas = place("gas", PlaceCategory.Gas, 500.0)
        val coffee = place("coffee", PlaceCategory.Coffee, 500.0)
        val withDefaults = PlaceRanking.rank(listOf(coffee, gas), PlaceRanking.Context(isNavigating = true))
        val withExplicitOnes = PlaceRanking.rank(
            listOf(coffee, gas),
            PlaceRanking.Context(isNavigating = true, utilityWeightMultiplier = 1.0, generalWeightMultiplier = 1.0),
        )
        assertEquals(withDefaults.map { it.id }, withExplicitOnes.map { it.id })
    }

    @Test
    fun rank_highUtilityMultiplier_widensTheGapOverGeneralCategories() {
        // Gas is far enough behind coffee that coffee wins with default (1.0/1.0) weights...
        val gas = place("gas", PlaceCategory.Gas, 1_500.0)
        val coffee = place("coffee", PlaceCategory.Coffee, 100.0)
        val defaultOrder = PlaceRanking.rank(listOf(coffee, gas), PlaceRanking.Context(isNavigating = true))
        assertEquals("sanity check: coffee wins with default weights", listOf("coffee", "gas"), defaultOrder.map { it.id })

        // ...but a Highway-style utility boost closes that gap and flips the order.
        val boosted = PlaceRanking.rank(
            listOf(coffee, gas),
            PlaceRanking.Context(isNavigating = true, utilityWeightMultiplier = 3.0),
        )
        assertEquals(listOf("gas", "coffee"), boosted.map { it.id })
    }

    @Test
    fun rank_lowGeneralMultiplier_suppressesGeneralCategoriesRelativeToUtility() {
        val gas = place("gas", PlaceCategory.Gas, 500.0)
        val coffee = place("coffee", PlaceCategory.Coffee, 500.0)
        val suppressed = PlaceRanking.rank(
            listOf(coffee, gas),
            PlaceRanking.Context(isNavigating = true, generalWeightMultiplier = 0.1),
        )
        assertEquals(listOf("gas", "coffee"), suppressed.map { it.id })
    }
}
