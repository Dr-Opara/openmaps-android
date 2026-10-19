package com.openmaps.app.core.geocoding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Production client for the OpenMaps geocoding gateway.
 *
 * Provider credentials and provider selection stay on the backend. The Android client sends only
 * the query, optional proximity bias and locale, then receives provider-neutral [SearchResult]s.
 */
class BackendGeocodingRepository(
    private val endpointUrl: String,
    private val publishableKey: String,
    private val clientId: String? = null,
) : GeocodingRepository {

    val isConfigured: Boolean
        get() = endpointUrl.isNotBlank() && publishableKey.isNotBlank()

    override suspend fun search(
        query: String,
        context: GeocodingSearchContext,
    ): Result<List<SearchResult>> = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext Result.failure(GeocodingException.Server(CONFIGURATION_ERROR_CODE))
        }
        try {
            Result.success(fetch(query, context))
        } catch (e: CancellationException) {
            throw e
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
        val connection = URL(endpointUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.doOutput = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("apikey", publishableKey)
        clientId?.takeIf { it.isNotBlank() }?.let {
            connection.setRequestProperty("x-openmaps-client-id", it)
        }

        val body = JSONObject()
            .put("query", query)
            .put(
                "context",
                JSONObject().apply {
                    context.latitude?.let { put("latitude", it) }
                    context.longitude?.let { put("longitude", it) }
                    context.localeTag?.takeIf { it.isNotBlank() }?.let { put("localeTag", it) }
                },
            )
            .toString()

        try {
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    BackendGeocodingResponseParser.parse(response)
                }
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN -> throw GeocodingException.Server(code)
                429 -> throw GeocodingException.RateLimited
                in 500..599 -> throw GeocodingException.Server(code)
                else -> throw GeocodingException.Server(code)
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 8_000
        const val CONFIGURATION_ERROR_CODE = 599
    }
}

/** Pure parser for the OpenMaps backend's provider-neutral geocoding response. */
internal object BackendGeocodingResponseParser {
    fun parse(body: String): List<SearchResult> {
        val root = JSONObject(body)
        val results = root.getJSONArray("results")
        return buildList(results.length()) {
            for (index in 0 until results.length()) {
                add(results.getJSONObject(index).toSearchResult())
            }
        }
    }

    private fun JSONObject.toSearchResult(): SearchResult = SearchResult(
        id = getString("id"),
        name = getString("name"),
        displayName = getString("displayName"),
        latitude = getDouble("latitude"),
        longitude = getDouble("longitude"),
        category = nullableString("category"),
        address = nullableString("address"),
        city = nullableString("city"),
        state = nullableString("state"),
        country = nullableString("country"),
    )

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }
}
