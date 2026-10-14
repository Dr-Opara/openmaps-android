package com.openmaps.app.core.roadintelligence

import org.junit.Assert.assertEquals
import org.junit.Test

class RoadEventDeduplicatorTest {

    @Test
    fun distinctIds_areAllKept() {
        val events = listOf(hazard("a"), hazard("b"))
        assertEquals(events, RoadEventDeduplicator.dedupe(events))
    }

    @Test
    fun duplicateIds_collapseToOne_firstOccurrenceWins() {
        val first = hazard("a", title = "First")
        val duplicate = hazard("a", title = "Duplicate")
        val result = RoadEventDeduplicator.dedupe(listOf(first, duplicate))
        assertEquals(listOf(first), result)
    }

    @Test
    fun emptyInput_returnsEmpty() {
        assertEquals(emptyList<RoadEvent>(), RoadEventDeduplicator.dedupe(emptyList()))
    }

    private fun hazard(id: String, title: String = "Event") = RoadEvent(
        id = id,
        type = RoadEventType.Construction,
        latitude = 0.0,
        longitude = 0.0,
        severity = RoadEventSeverity.Moderate,
        title = title,
    )
}
