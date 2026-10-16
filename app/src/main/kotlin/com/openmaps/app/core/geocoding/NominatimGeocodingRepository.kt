package com.openmaps.app.core.geocoding

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
 * [GeocodingRepository] backed by the public OSM Nominatim instance
 * (https://nominatim.openstreetmap.org) — free, open, no API key, consistent with the
 * OpenFreeMap tile source already used for the basemap (see ARCHITECTURE.md).
 *
 * Development-only constraints (Nominatim's usage policy,
 * https://operations.osmfoundation.org/policies/nominatim/):
 *  - hard cap of ~1 request/second from a single IP, enforced server-side;
 *  - a descriptive User-Agent identifying the application is required (set below);
 *  - no heavy/bulk use, no server-side caching/redistribution of results.
 * This instance has no SLA and is NOT appropriate for production-scale traffic. Before
 * shipping, move to a self-hosted Nominatim/Photon instance or a contracted provider — the
 * same decision already flagged for the tile source.
 *
 * This fallback is worldwide. Current location is used only as an unbounded ranking bias; it never
 * limits the query to one country or region.
 */
class NominatimGeocodingRepository(
    private val userAgent: String,
    private val baseUrl: String = "https://nominatim.openstreetmap.org/search",
) : GeocodingRepository {

    override suspend fun search(
        query: String,
        context: GeocodingSearchContext,
    ): Result<List<SearchResult>> = withContext(Dispatchers.IO) {
        try {
            Result.success(fetch(query, context))
        } catch (e: CancellationException) {
            throw e // Never swallow cancellation: the caller relies on it to drop stale searches.
        } catch (e: GeocodingException) {
            Result.failure(e)
        } catch (e: SocketTimeoutException) {
            Result.failure(GeocodingException.Timeout)
        } catch (e: JSONException) {
            Result.failure(GeocodingException.Malformed)
        } catch (e: IOException) {
            Result.failure(GeocodingException.NoNetwork)
        }
    }

    private fun fetch(query: String, context: GeocodingSearchContext): List<SearchResult> {
        val connection = buildUrl(query, context).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "application/json")
        try {
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    NominatimResponseParser.parse(body)
                }
                429 -> throw GeocodingException.RateLimited
                else -> throw GeocodingException.Server(code)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun buildUrl(query: String, context: GeocodingSearchContext): URL {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val params = mutableListOf(
            "q=$encoded",
            "format=jsonv2",
            "addressdetails=1",
            "limit=$RESULT_LIMIT",
        )
        val lat = context.latitude
        val lon = context.longitude
        if (lat != null && lon != null) {
            val delta = 0.75
            params += "viewbox=" + listOf(lon - delta, lat + delta, lon + delta, lat - delta).joinToString(",")
            params += "bounded=0"
        }
        context.localeTag?.takeIf { it.isNotBlank() }?.let {
            params += "accept-language=" + URLEncoder.encode(it, "UTF-8")
        }
        return URL("$baseUrl?" + params.joinToString("&"))
    }

    private companion object {
        const val TIMEOUT_MILLIS = 8_000
        const val RESULT_LIMIT = 20
    }
}
