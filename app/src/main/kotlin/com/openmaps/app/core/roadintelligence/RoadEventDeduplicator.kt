package com.openmaps.app.core.roadintelligence

/**
 * Collapses duplicate [RoadEvent]s from a provider response down to one per [RoadEvent.id] (section
 * 15 of the Phase 10 brief: "duplicate provider events"). This is deliberately a separate, tiny pass
 * from [RoadAlertHysteresis] — hysteresis stabilizes *which ids are active across ticks*; this
 * stabilizes *one response not containing the same real-world event twice* (e.g. a construction zone
 * mapped as more than one OSM way, or a provider that double-reports an incident near a tile
 * boundary). The first occurrence wins, so a stable, meaningful id (see
 * `DefaultRoadIntelligenceRepository`'s OSM-element ids) also determines which copy's fields survive.
 */
object RoadEventDeduplicator {
    fun dedupe(events: List<RoadEvent>): List<RoadEvent> = events.distinctBy { it.id }
}
