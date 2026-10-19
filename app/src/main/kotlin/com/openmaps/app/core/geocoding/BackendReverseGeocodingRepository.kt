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

class BackendReverseGeocodingRepository(
    private val endpointUrl: String,
    private val publishableKey: String,
    private val clientId: String? = null,
) : ReverseGeocodingRepository {

    override suspend fun reverse(
        latitude: Double,
        longitude: Double,
        localeTag: String?,
    ): Result<SearchResult?> = withContext(Dispatchers.IO) {
        if (endpointUrl.isBlank() || publishableKey.isBlank()) {
            return@withContext Result.failure(GeocodingException.Server(CONFIGURATION_ERROR_CODE))
        }

        try {
            Result.success(fetch(latitude, longitude, localeTag))
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

    private fun fetch(latitude: Double, longitude: Double, localeTag: String?): SearchResult? {
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
            .put("latitude", latitude)
            .put("longitude", longitude)
            .apply {
                localeTag?.takeIf { it.isNotBlank() }?.let { put("localeTag", it) }
            }
            .toString()

        try {
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    BackendReverseGeocodingResponseParser.parse(response)
                }
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

internal object BackendReverseGeocodingResponseParser {
    fun parse(body: String): SearchResult? {
        val root = JSONObject(body)
        if (!root.has("result") || root.isNull("result")) return null
        val item = root.getJSONObject("result")
        return SearchResult(
            id = item.getString("id"),
            name = item.getString("name"),
            displayName = item.getString("displayName"),
            latitude = item.getDouble("latitude"),
            longitude = item.getDouble("longitude"),
            category = item.nullableString("category"),
            address = item.nullableString("address"),
            city = item.nullableString("city"),
            state = item.nullableString("state"),
            country = item.nullableString("country"),
        )
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }
}
