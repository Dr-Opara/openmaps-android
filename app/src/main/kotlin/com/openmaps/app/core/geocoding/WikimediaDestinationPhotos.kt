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

/** Opt-in public Wikimedia Commons images, matched conservatively to the destination name.
 * Images may not exist for a place. Never fall back to unrelated nearby or stock imagery.
 */
data class LicensedDestinationPhoto(
    val bitmap: Bitmap,
    val attribution: String,
    val license: String,
    val commonsPage: String,
)

object WikimediaDestinationPhotos {
    suspend fun forPlace(name: String): LicensedDestinationPhoto? = withContext(Dispatchers.IO) {
        val queryName = name.replace(Regex("\\([^)]*\\)"), "").trim().take(140)
        if (queryName.length < 5) return@withContext null
        val query = URLEncoder.encode("\"$queryName\"", "UTF-8")
        val endpoint = "https://commons.wikimedia.org/w/api.php?action=query&generator=search" +
            "&gsrsearch=$query&gsrnamespace=6&gsrlimit=15&prop=imageinfo" +
            "&iiprop=url%7Cextmetadata&iiurlwidth=960&format=json"
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 7_000
        conn.setRequestProperty("User-Agent", "OpenMapsAndroid/1.0 (openmaps.tech; Wikimedia Commons image lookup)")
        val body = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return@withContext null
        } finally {
            conn.disconnect()
        }
        val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return@withContext null
        val normalizedName = queryName.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+")).filter { it.length > 3 && it !in setOf("airport", "intercontinental", "international", "station", "center", "terminal") }
        if (normalizedName.isEmpty()) return@withContext null
        var candidate: Triple<String, String, Pair<String,String>>? = null
        val keys = pages.keys()
        while (keys.hasNext()) {
            val page = pages.optJSONObject(keys.next()) ?: continue
            val title = page.optString("title").lowercase(Locale.ROOT).replace('_',' ')
            // A strong destination-specific name match is required, not just generic place terms.
            if (!normalizedName.all { title.contains(it) }) continue
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val metadata = info.optJSONObject("extmetadata") ?: continue
            val license = metadata.optJSONObject("LicenseShortName")?.optString("value").orEmpty()
            if (!(license.startsWith("CC BY ") || license.startsWith("CC BY-SA ") || license == "CC0" || license == "Public domain")) continue
            val imageUrl = info.optString("thumburl").ifBlank { info.optString("url") }
            val pageUrl = info.optString("descriptionurl")
            val fileHost = runCatching { URL(imageUrl).host }.getOrNull()
            if (fileHost != "upload.wikimedia.org" || !pageUrl.startsWith("https://commons.wikimedia.org/")) continue
            val artist = metadata.optJSONObject("Artist")?.optString("value").orEmpty()
                .replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(120)
            candidate = Triple(imageUrl, pageUrl, artist to license)
            break
        }
        val picked = candidate ?: return@withContext null
        val imageConn = URL(picked.first).openConnection() as HttpURLConnection
        imageConn.connectTimeout = 5_000
        imageConn.readTimeout = 8_000
        val bitmap = try { imageConn.inputStream.use { BitmapFactory.decodeStream(it) } }
            catch (_: Exception) { null } finally { imageConn.disconnect() }
        bitmap?.let { LicensedDestinationPhoto(it, picked.third.first.ifBlank { "Wikimedia Commons contributor" }, picked.third.second, picked.second) }
    }
}
