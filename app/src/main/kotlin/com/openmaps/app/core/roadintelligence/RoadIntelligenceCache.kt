package com.openmaps.app.core.roadintelligence

import com.openmaps.app.core.routing.Coordinate

/**
 * Three small TTL caches, one per Phase 10 data type, each bucketed by location so nearby repeat
 * queries (a user drifting slightly, two ticks in a row) hit the same entry instead of firing a
 * fresh request — the same shape as [com.openmaps.app.core.places.PlaceCache], just split by
 * concern because section 19 of the Phase 10 brief calls for a **different refresh cadence per
 * type**: hazards/incidents relatively fresh, weather slower, speed limits long-cacheable.
 *
 * Pure Kotlin and clock-injectable like [com.openmaps.app.core.places.PlaceCache], for the same
 * reason: unit-testable without `System.currentTimeMillis()` wall-clock flakiness.
 */
class RoadIntelligenceCache(private val now: () -> Long = System::currentTimeMillis) {

    private data class Key(val latBucket: Int, val lonBucket: Int)
    private data class Entry<T>(val value: T, val expiresAtMillis: Long)

    private class BucketedCache<T>(
        private val ttlMillis: Long,
        private val bucketDegrees: Double,
        private val now: () -> Long,
    ) {
        private val entries = mutableMapOf<Key, Entry<T>>()

        fun get(center: Coordinate): T? {
            val key = keyFor(center)
            val entry = entries[key] ?: return null
            if (now() >= entry.expiresAtMillis) {
                entries.remove(key)
                return null
            }
            return entry.value
        }

        fun put(center: Coordinate, value: T) {
            entries[keyFor(center)] = Entry(value, now() + ttlMillis)
        }

        fun clear() = entries.clear()

        private fun keyFor(center: Coordinate): Key = Key(
            latBucket = Math.round(center.latitude / bucketDegrees).toInt(),
            lonBucket = Math.round(center.longitude / bucketDegrees).toInt(),
        )
    }

    private val hazards = BucketedCache<List<RoadEvent>>(HAZARD_TTL_MILLIS, HAZARD_BUCKET_DEGREES, now)
    private val weather = BucketedCache<WeatherCondition?>(WEATHER_TTL_MILLIS, WEATHER_BUCKET_DEGREES, now)
    private val speedLimit = BucketedCache<SpeedLimitInfo?>(SPEED_LIMIT_TTL_MILLIS, SPEED_LIMIT_BUCKET_DEGREES, now)

    fun getHazards(center: Coordinate): List<RoadEvent>? = hazards.get(center)
    fun putHazards(center: Coordinate, events: List<RoadEvent>) = hazards.put(center, events)

    /** Null return means "no cache entry" (a miss or expired one) — not "cached as no weather". */
    fun getWeather(center: Coordinate): WeatherCondition? = weather.get(center)
    fun putWeather(center: Coordinate, condition: WeatherCondition?) = weather.put(center, condition)

    /** Null return means "no cache entry" (a miss or expired one) — not "cached as no speed limit". */
    fun getSpeedLimit(center: Coordinate): SpeedLimitInfo? = speedLimit.get(center)
    fun putSpeedLimit(center: Coordinate, info: SpeedLimitInfo?) = speedLimit.put(center, info)

    /** Drops every entry across all three caches. Tests only. */
    fun clear() {
        hazards.clear()
        weather.clear()
        speedLimit.clear()
    }

    private companion object {
        /** Hazards/incidents: "relatively fresh" per section 19 — short TTL, small bucket. */
        const val HAZARD_TTL_MILLIS = 3 * 60 * 1000L
        const val HAZARD_BUCKET_DEGREES = 0.005 // ~550 m

        /** Weather: "slower cadence" per section 19 — changes over tens of minutes, not metres. */
        const val WEATHER_TTL_MILLIS = 20 * 60 * 1000L
        const val WEATHER_BUCKET_DEGREES = 0.05 // ~5.5 km — weather doesn't vary block to block

        /** Speed limits: "cacheable" per section 19 — tied to the road segment, not the clock. */
        const val SPEED_LIMIT_TTL_MILLIS = 30 * 60 * 1000L
        const val SPEED_LIMIT_BUCKET_DEGREES = 0.0015 // ~165 m — tight enough to change at intersections
    }
}
