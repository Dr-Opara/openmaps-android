package com.openmaps.app.core.environment

import com.openmaps.app.core.routing.Coordinate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/**
 * [RoadClassRepository] backed by the public Overpass API — the same free, keyless, no-SLA public
 * instance `OverpassPlaceRepository`/`DefaultRoadIntelligenceRepository` already call (see
 * ARCHITECTURE.md). A separate class and a separate query from Phase 10's speed-limit lookup (not a
 * shared method) so Phase 10's seam stays untouched; the minor cost is a second small Overpass
 * request at the same cadence `MapViewModel` already throttles speed-limit lookups to.
 *
 * **Do not ship to the public on this.** Same open production decision as every other Overpass use
 * in this codebase.
 */
class OverpassRoadClassRepository(
    private val userAgent: String,
    private val baseUrl: String = "https://overpass-api.de/api/interpreter",
    private val cache: RoadClassCache = RoadClassCache(),
) : RoadClassRepository {

    override suspend fun roadClassNear(location: Coordinate): Result<RoadClass?> = withContext(Dispatchers.IO) {
        try {
            val cached = cache.get(location)
            val result = cached ?: fetch(location).also { cache.put(location, it) }
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RoadClassException) {
            Result.failure(e)
        } catch (e: SocketTimeoutException) {
            Result.failure(RoadClassException.Timeout)
        } catch (e: JSONException) {
            Result.failure(RoadClassException.Malformed)
        } catch (e: IOException) {
            Result.failure(RoadClassException.NoNetwork)
        }
    }

    private fun fetch(location: Coordinate): RoadClass? {
        val query = """
            [out:json][timeout:$TIMEOUT_SECONDS];
            (
              way["highway"](around:$RADIUS_METERS,${location.latitude},${location.longitude});
            );
            out body center $MAX_ELEMENTS;
        """.trimIndent()
        val connection = URL(baseUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.doOutput = true
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            val body = "data=" + URLEncoder.encode(query, "UTF-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                    OverpassRoadClassParser.parseNearestRoadClass(responseBody, location)
                }
                else -> throw RoadClassException.Server(code)
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000
        const val TIMEOUT_SECONDS = 15
        const val RADIUS_METERS = 60
        const val MAX_ELEMENTS = 10
    }
}

/**
 * Bucketed TTL cache for road-class lookups, same shape as `RoadIntelligenceCache`'s speed-limit
 * cache (30 min TTL, ~165 m bucket — a road's classification changes at intersections, not by the
 * metre, and essentially never changes over a 30-minute window).
 */
class RoadClassCache(private val now: () -> Long = System::currentTimeMillis) {
    private data class Key(val latBucket: Int, val lonBucket: Int)
    private data class Entry(val value: RoadClass?, val expiresAtMillis: Long)

    private val entries = mutableMapOf<Key, Entry>()

    /** Null means "no cache entry" (a miss or an expired one) — not "cached as no road class". */
    fun get(center: Coordinate): RoadClass? {
        val key = keyFor(center)
        val entry = entries[key] ?: return null
        if (now() >= entry.expiresAtMillis) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    fun put(center: Coordinate, value: RoadClass?) {
        entries[keyFor(center)] = Entry(value, now() + TTL_MILLIS)
    }

    fun clear() = entries.clear()

    private fun keyFor(center: Coordinate): Key = Key(
        latBucket = Math.round(center.latitude / BUCKET_DEGREES).toInt(),
        lonBucket = Math.round(center.longitude / BUCKET_DEGREES).toInt(),
    )

    private companion object {
        const val TTL_MILLIS = 30 * 60 * 1000L
        const val BUCKET_DEGREES = 0.0015
    }
}
