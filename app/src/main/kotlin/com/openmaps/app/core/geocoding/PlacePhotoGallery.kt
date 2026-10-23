package com.openmaps.app.core.geocoding

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.cos

/** Photos from two distinct sources. A street image must never be described as the venue itself. */
enum class GalleryPhotoKind { Destination, NearbyStreet }

data class GalleryPhoto(
    val bitmap: Bitmap,
    val author: String,
    val license: String,
    val sourceUrl: String,
    val kind: GalleryPhotoKind,
)

data class PlacePhotoGallery(
    val destinationPhotos: List<GalleryPhoto> = emptyList(),
    val nearbyStreetViews: List<GalleryPhoto> = emptyList(),
)

/**
 * Small, bounded, best-effort public photo source. No user location is transmitted:
 * only the coordinate of a destination already selected for public map lookup.
 * All queries execute on Dispatchers.IO; the UI treats an absent result as no verified image.
 */
object PlacePhotoGalleryRepository {
    private const val USER_AGENT = "OpenMapsAndroid/1.0 (https://openmaps.tech; photo gallery)"
    private val GENERIC_WORDS = setOf("airport", "international", "intercontinental", "terminal", "center", "centre", "station", "building", "restaurant", "hotel", "store", "shop")

    /** Unit-testable identity check. Avoid photos of a same-name chain at another location. */
    internal fun matchesCommonsTitle(name: String, title: String, locality: String?): Boolean {
        val words = name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 3 && it !in GENERIC_WORDS }
        if (words.isEmpty()) return false
        val normalizedTitle = title.lowercase(Locale.ROOT).replace('_', ' ')
        if (!words.all { normalizedTitle.contains(it) }) return false
        if (locality != null && !normalizedTitle.contains(locality.lowercase(Locale.ROOT))) return false
        // A single ambiguous chain name must never be matched without its city.
        return locality != null || words.size >= 2
    }

    suspend fun load(
        name: String,
        latitude: Double,
        longitude: Double,
        locality: String?,
        mapillaryClientToken: String = "",
    ): PlacePhotoGallery = withContext(Dispatchers.IO) {
        if (name.isBlank() || !latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) return@withContext PlacePhotoGallery()
        val placePhotos = runCatching { commons(name, locality) }.getOrDefault(emptyList())
        // Mapillary requires an opt-in client access token. This is a client-visible token,
        // NOT a server-side application secret; never give the mobile app private credentials.
        val street = if (mapillaryClientToken.isBlank()) emptyList() else
            runCatching { mapillary(latitude, longitude, mapillaryClientToken) }.getOrDefault(emptyList())
        PlacePhotoGallery(placePhotos, street)
    }

    private fun commons(name: String, locality: String?): List<GalleryPhoto> {
        val queryName = name.replace(Regex("\\([^)]*\\)"), "").trim().take(140)
        if (queryName.length < 5) return emptyList()
        val encoded = URLEncoder.encode("\"$queryName\"", "UTF-8")
        val endpoint = "https://commons.wikimedia.org/w/api.php?action=query&generator=search" +
            "&gsrsearch=$encoded&gsrnamespace=6&gsrlimit=16&prop=imageinfo" +
            "&iiprop=url%7Csize%7Cextmetadata&iiurlwidth=740&format=json"
        val pages = json(endpoint).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val candidates = mutableListOf<GalleryPhoto>()
        val keys = pages.keys()
        while (keys.hasNext() && candidates.size < 4) {
            val page = pages.optJSONObject(keys.next()) ?: continue
            if (!matchesCommonsTitle(queryName, page.optString("title"), locality)) continue
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            if (info.optInt("width") < 740 || info.optInt("height") < 420) continue
            val metadata = info.optJSONObject("extmetadata") ?: continue
            val license = metadata.optJSONObject("LicenseShortName")?.optString("value").orEmpty()
            if (!allowedCommonsLicense(license)) continue
            val imageUrl = info.optString("thumburl")
            val pageUrl = info.optString("descriptionurl")
            if (!onHost(imageUrl, "upload.wikimedia.org") ||
                !onHost(pageUrl, "commons.wikimedia.org")) continue
            val artist = metadata.optJSONObject("Artist")?.optString("value").orEmpty()
                .replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(130)
            val bitmap = fetchBitmap(imageUrl) ?: continue
            candidates += GalleryPhoto(
                bitmap = bitmap,
                author = artist.ifBlank { "Wikimedia Commons contributor" },
                license = license,
                sourceUrl = pageUrl,
                kind = GalleryPhotoKind.Destination,
            )
        }
        return candidates
    }

    internal fun allowedCommonsLicense(license: String): Boolean =
        license.startsWith("CC BY ") || license.startsWith("CC BY-SA ") ||
            license == "CC0" || license == "Public domain"

    private fun mapillary(lat: Double, lon: Double, token: String): List<GalleryPhoto> {
        // Keep bounding boxes intentionally small: large Mapillary bounding boxes can time out.
        val halfLat = 120.0 / 111_320.0
        val halfLon = halfLat / cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        val bbox = listOf(lon - halfLon, lat - halfLat, lon + halfLon, lat + halfLat)
            .joinToString(",") { String.format(Locale.US, "%.6f", it) }
        val endpoint = "https://graph.mapillary.com/images?bbox=$bbox&limit=9" +
            "&fields=id,thumb_1024_url,computed_geometry,creator&access_token=" +
            URLEncoder.encode(token, "UTF-8")
        val entries = json(endpoint).optJSONArray("data") ?: return emptyList()
        val photos = mutableListOf<GalleryPhoto>()
        for (index in 0 until entries.length()) {
            if (photos.size >= 4) break
            val entry = entries.optJSONObject(index) ?: continue
            val id = entry.optString("id").takeIf { it.matches(Regex("[0-9]+")) } ?: continue
            val coordinates = entry.optJSONObject("computed_geometry")?.optJSONArray("coordinates") ?: continue
            if (coordinates.length() < 2) continue
            val imageLon = coordinates.optDouble(0, Double.NaN)
            val imageLat = coordinates.optDouble(1, Double.NaN)
            if (!imageLon.isFinite() || !imageLat.isFinite() ||
                approximateMeters(lat, lon, imageLat, imageLon) > 170.0) continue
            val thumbnail = entry.optString("thumb_1024_url")
            if (!onHostSuffix(thumbnail, ".fbcdn.net") && !onHostSuffix(thumbnail, ".mapillary.com")) continue
            val bitmap = fetchBitmap(thumbnail) ?: continue
            val author = entry.optJSONObject("creator")?.optString("username").orEmpty()
            photos += GalleryPhoto(
                bitmap = bitmap,
                author = author.ifBlank { "Mapillary contributor" },
                license = "CC BY-SA",
                sourceUrl = "https://www.mapillary.com/app/?pKey=$id",
                kind = GalleryPhotoKind.NearbyStreet,
            )
        }
        return photos
    }

    private fun approximateMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 111_320.0
        val dx = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians(lat1))
        return kotlin.math.hypot(dx, dy)
    }

    private fun onHost(url: String, host: String): Boolean = runCatching {
        val parsed = URL(url)
        parsed.protocol == "https" && parsed.host.equals(host, ignoreCase = true)
    }.getOrDefault(false)

    private fun onHostSuffix(url: String, suffix: String): Boolean = runCatching {
        val parsed = URL(url)
        parsed.protocol == "https" &&
            (parsed.host.endsWith(suffix, ignoreCase = true) ||
                parsed.host.equals(suffix.removePrefix("."), ignoreCase = true))
    }.getOrDefault(false)

    private fun json(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return try {
            if (connection.responseCode != 200) return JSONObject()
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchBitmap(url: String): Bitmap? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return try {
            if (connection.responseCode != 200) return null
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            connection.inputStream.use { BitmapFactory.decodeStream(it, null, options) }
        } finally {
            connection.disconnect()
        }
    }
}
