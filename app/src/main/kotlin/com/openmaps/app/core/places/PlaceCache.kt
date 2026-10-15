package com.openmaps.app.core.places

import com.openmaps.app.core.routing.Coordinate

/**
 * In-memory nearby-places cache.
 *
 * Keyed on a **location bucket + category set + radius bucket**, so re-tapping the same category, or
 * drifting a few tens of metres, is a hit rather than a network request.
 *
 * Two read modes matter for reliability:
 *  - [get] — fresh hit only.
 *  - [getStale] — any un-expired-softly entry, used **only** when every provider has failed, so a
 *    transient Overpass outage degrades to slightly old data instead of an error screen. The caller
 *    flags it as stale in the UI; a stale list is never presented as fresh.
 *
 * Pure Kotlin and clock-injectable, so all of this is testable without wall-clock flakiness.
 */
class PlaceCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val staleTtlMillis: Long = DEFAULT_STALE_TTL_MILLIS,
    private val bucketDegrees: Double = DEFAULT_BUCKET_DEGREES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Key(
        val latBucket: Int,
        val lonBucket: Int,
        val categories: Set<PlaceCategory>,
        val radiusBucket: Int,
    )

    private data class Entry(val places: List<Place>, val storedAtMillis: Long)

    private val entries = mutableMapOf<Key, Entry>()

    /** A fresh hit, or null on a miss/expiry. */
    fun get(center: Coordinate, radiusMeters: Double, categories: Set<PlaceCategory>): List<Place>? {
        val entry = entries[keyFor(center, radiusMeters, categories)] ?: return null
        if (now() - entry.storedAtMillis >= ttlMillis) return null
        return entry.places
    }

    /**
     * The most recent entry for this key within the wider stale window, or null. Used as the last
     * resort when the provider chain has failed outright.
     */
    fun getStale(center: Coordinate, radiusMeters: Double, categories: Set<PlaceCategory>): List<Place>? {
        val entry = entries[keyFor(center, radiusMeters, categories)] ?: return null
        if (now() - entry.storedAtMillis >= staleTtlMillis) {
            entries.remove(keyFor(center, radiusMeters, categories))
            return null
        }
        return entry.places
    }

    fun put(center: Coordinate, radiusMeters: Double, categories: Set<PlaceCategory>, places: List<Place>) {
        entries[keyFor(center, radiusMeters, categories)] = Entry(places, now())
    }

    fun clear() = entries.clear()

    private fun keyFor(center: Coordinate, radiusMeters: Double, categories: Set<PlaceCategory>): Key = Key(
        latBucket = Math.round(center.latitude / bucketDegrees).toInt(),
        lonBucket = Math.round(center.longitude / bucketDegrees).toInt(),
        categories = categories,
        radiusBucket = Math.round(radiusMeters / RADIUS_BUCKET_METERS).toInt(),
    )

    internal companion object {
        /** Fresh window: long enough to absorb repeated location ticks over the same area. */
        const val DEFAULT_TTL_MILLIS = 10 * 60 * 1000L

        /**
         * Stale window: how long a cached answer may still rescue a total provider outage. Long
         * enough to cover a bad cell/connection, short enough that "open nearby" is not misleading.
         */
        const val DEFAULT_STALE_TTL_MILLIS = 60 * 60 * 1000L

        /** ~550 m at mid US latitudes — small enough that a bucket never masks real movement. */
        const val DEFAULT_BUCKET_DEGREES = 0.005

        const val RADIUS_BUCKET_METERS = 250.0
    }
}