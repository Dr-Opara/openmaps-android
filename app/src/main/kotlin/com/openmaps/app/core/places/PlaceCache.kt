package com.openmaps.app.core.places

import com.openmaps.app.core.routing.Coordinate

/**
 * Lightweight in-memory nearby-places cache (Phase 9). Avoids re-querying the same area for the
 * same category set within [ttlMillis] — no database, no persistence across process death; this is
 * a Phase-sized concern kept Phase-sized, the same reasoning behind
 * [com.openmaps.app.core.geocoding.RecentSearchStore] staying a flat `SharedPreferences` list.
 *
 * The cache key buckets the center to [bucketDegrees] so two queries a few tens of meters apart —
 * a user drifting slightly, two camera-idle events in a row — hit the same entry instead of each
 * firing a fresh network request. Pure Kotlin and clock-injectable, so it is unit-testable without
 * `System.currentTimeMillis()` wall-clock flakiness.
 */
class PlaceCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val bucketDegrees: Double = DEFAULT_BUCKET_DEGREES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Key(
        val latBucket: Int,
        val lonBucket: Int,
        val categories: Set<PlaceCategory>,
        val radiusBucket: Int,
    )

    private data class Entry(val places: List<Place>, val expiresAtMillis: Long)

    private val entries = mutableMapOf<Key, Entry>()

    /** Cached places for this query, or null when there is no fresh entry (a miss or an expired one). */
    fun get(center: Coordinate, radiusMeters: Double, categories: Set<PlaceCategory>): List<Place>? {
        val key = keyFor(center, radiusMeters, categories)
        val entry = entries[key] ?: return null
        if (now() >= entry.expiresAtMillis) {
            entries.remove(key)
            return null
        }
        return entry.places
    }

    fun put(center: Coordinate, radiusMeters: Double, categories: Set<PlaceCategory>, places: List<Place>) {
        entries[keyFor(center, radiusMeters, categories)] = Entry(places, now() + ttlMillis)
    }

    /** Drops every entry. Tests only; production code has no reason to flush this by hand. */
    fun clear() = entries.clear()

    private fun keyFor(center: Coordinate, radiusMeters: Double, categories: Set<PlaceCategory>): Key = Key(
        latBucket = Math.round(center.latitude / bucketDegrees).toInt(),
        lonBucket = Math.round(center.longitude / bucketDegrees).toInt(),
        categories = categories,
        radiusBucket = Math.round(radiusMeters / RADIUS_BUCKET_METERS).toInt(),
    )

    private companion object {
        /** Long enough to absorb repeated camera-idle/location ticks over the same area. */
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L

        /** ~550 m at mid US latitudes — small enough that the bucket never masks real movement. */
        const val DEFAULT_BUCKET_DEGREES = 0.005

        const val RADIUS_BUCKET_METERS = 250.0
    }
}
