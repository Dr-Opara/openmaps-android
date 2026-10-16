package com.openmaps.app.core.scout

import com.openmaps.app.core.places.PlaceCategory

fun interface ScoutIntentParser {
    fun parse(input: String): ScoutIntent
}

class RuleBasedScoutIntentParser : ScoutIntentParser {
    override fun parse(input: String): ScoutIntent {
        val normalized = input.trim()
        val lower = normalized.lowercase()

        if (normalized.isBlank()) return ScoutIntent.Unknown
        if ("explain route" in lower || "why this route" in lower || "route summary" in lower) {
            return ScoutIntent.ExplainRoute
        }
        if ("parking" in lower || "where can i park" in lower) return ScoutIntent.FindParking

        categoryFor(lower)?.let { category ->
            if ("nearby" in lower || "near me" in lower || "find" in lower || "show" in lower) {
                return ScoutIntent.FindNearby(category)
            }
        }

        val arrivalPrefix = listOf(
            "plan my arrival to ",
            "plan arrival to ",
            "arrival plan for ",
            "get me to ",
        ).firstOrNull(lower::startsWith)
        if (arrivalPrefix != null) {
            return ScoutIntent.ArrivalPlan(normalized.drop(arrivalPrefix.length).trim())
        }

        val navigationPrefix = listOf(
            "navigate to ",
            "directions to ",
            "take me to ",
            "go to ",
        ).firstOrNull(lower::startsWith)
        if (navigationPrefix != null) {
            return ScoutIntent.NavigateTo(normalized.drop(navigationPrefix.length).trim())
        }

        return ScoutIntent.Unknown
    }

    private fun categoryFor(input: String): PlaceCategory? = when {
        "restaurant" in input || "food" in input -> PlaceCategory.Restaurant
        "coffee" in input || "cafe" in input -> PlaceCategory.Coffee
        "hotel" in input -> PlaceCategory.Hotel
        "pharmacy" in input -> PlaceCategory.Pharmacy
        "parking" in input -> PlaceCategory.Parking
        "gas" in input || "fuel" in input -> PlaceCategory.Gas
        "charging" in input || "ev charger" in input -> PlaceCategory.EvCharging
        "atm" in input -> PlaceCategory.Atm
        "bank" in input -> PlaceCategory.Bank
        "grocery" in input -> PlaceCategory.Grocery
        "medical" in input || "hospital" in input || "clinic" in input -> PlaceCategory.Medical
        else -> null
    }
}
