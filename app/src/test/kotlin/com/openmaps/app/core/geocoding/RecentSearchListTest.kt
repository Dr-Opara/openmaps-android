package com.openmaps.app.core.geocoding

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchListTest {

    @Test
    fun withRecorded_prependsNewest() {
        val current = listOf(result("a"), result("b"))
        val next = RecentSearchList.withRecorded(current, result("c"))
        assertEquals(listOf("c", "a", "b"), next.map { it.id })
    }

    @Test
    fun withRecorded_reselectingExisting_movesItToFrontWithoutDuplicating() {
        val current = listOf(result("a"), result("b"), result("c"))
        val next = RecentSearchList.withRecorded(current, result("b"))
        assertEquals(listOf("b", "a", "c"), next.map { it.id })
        assertEquals(3, next.size)
    }

    @Test
    fun withRecorded_capsAtLimit() {
        val current = (1..5).map { result(it.toString()) }
        val next = RecentSearchList.withRecorded(current, result("new"), limit = 3)
        assertEquals(3, next.size)
        assertEquals(listOf("new", "1", "2"), next.map { it.id })
    }

    private fun result(id: String) = SearchResult(
        id = id,
        name = "Place $id",
        displayName = "Place $id, USA",
        latitude = 0.0,
        longitude = 0.0,
    )
}
