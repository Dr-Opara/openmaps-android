package com.openmaps.app.core.navigation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.ManeuverType
import com.openmaps.app.core.routing.RouteBounds
import com.openmaps.app.core.routing.RouteManeuver
import com.openmaps.app.core.routing.RouteResult
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class NavigationRecoverySnapshot(
    val route: RouteResult,
    val destination: SearchResult,
    val savedAtMillis: Long,
)

interface NavigationRecoveryStore {
    fun load(nowMillis: Long = System.currentTimeMillis()): NavigationRecoverySnapshot?
    fun save(route: RouteResult, destination: SearchResult, nowMillis: Long = System.currentTimeMillis())
    fun clear()
}

object NoopNavigationRecoveryStore : NavigationRecoveryStore {
    override fun load(nowMillis: Long): NavigationRecoverySnapshot? = null
    override fun save(route: RouteResult, destination: SearchResult, nowMillis: Long) = Unit
    override fun clear() = Unit
}

class EncryptedNavigationRecoveryStore(
    context: Context,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
) : NavigationRecoveryStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(nowMillis: Long): NavigationRecoverySnapshot? {
        val encoded = prefs.getString(KEY_ACTIVE_NAVIGATION, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= IV_BYTES) error("invalid encrypted navigation recovery record")
            val iv = packed.copyOfRange(0, IV_BYTES)
            val ciphertext = packed.copyOfRange(IV_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            val root = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            val savedAt = root.getLong("savedAt")
            if (nowMillis - savedAt !in 0..maxAgeMillis) {
                clear()
                return null
            }
            NavigationRecoverySnapshot(
                route = root.getJSONObject("route").toRouteResult(),
                destination = root.getJSONObject("destination").toSearchResult(),
                savedAtMillis = savedAt,
            )
        }.getOrElse {
            clear()
            null
        }
    }

    override fun save(route: RouteResult, destination: SearchResult, nowMillis: Long) {
        val plaintext = JSONObject()
            .put("savedAt", nowMillis)
            .put("route", route.toJson())
            .put("destination", destination.toJson())
            .toString()
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintext)
        val packed = cipher.iv + encrypted
        prefs.edit().putString(
            KEY_ACTIVE_NAVIGATION,
            Base64.encodeToString(packed, Base64.NO_WRAP),
        ).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_ACTIVE_NAVIGATION).apply()
    }

    private fun RouteResult.toJson(): JSONObject = JSONObject()
        .put("geometry", JSONArray().apply { geometry.forEach { put(it.toJson()) } })
        .put("distanceMeters", distanceMeters)
        .put("durationSeconds", durationSeconds)
        .put("maneuvers", JSONArray().apply { maneuvers.forEach { put(it.toJson()) } })
        .put("id", id)
        .put("label", label)

    private fun JSONObject.toRouteResult(): RouteResult {
        val geometryArray = getJSONArray("geometry")
        val geometry = buildList(geometryArray.length()) {
            for (index in 0 until geometryArray.length()) add(geometryArray.getJSONObject(index).toCoordinate())
        }
        val maneuverArray = optJSONArray("maneuvers")
        val maneuvers = buildList {
            if (maneuverArray != null) {
                for (index in 0 until maneuverArray.length()) add(maneuverArray.getJSONObject(index).toManeuver())
            }
        }
        return RouteResult(
            geometry = geometry,
            distanceMeters = getDouble("distanceMeters"),
            durationSeconds = getDouble("durationSeconds"),
            bounds = geometry.bounds(),
            maneuvers = maneuvers,
            id = optString("id").ifBlank { null },
            label = optString("label").ifBlank { null },
            isOfflineCached = true,
        )
    }

    private fun RouteManeuver.toJson(): JSONObject = JSONObject()
        .put("instruction", instruction)
        .put("verbalInstruction", verbalInstruction)
        .put("type", type.name)
        .put("streetName", streetName)
        .put("distanceMeters", distanceMeters)
        .put("beginShapeIndex", beginShapeIndex)
        .put("endShapeIndex", endShapeIndex)
        .put("exitSign", exitSign)
        .put("signpost", signpost)

    private fun JSONObject.toManeuver(): RouteManeuver = RouteManeuver(
        instruction = getString("instruction"),
        verbalInstruction = optNullableString("verbalInstruction"),
        type = ManeuverType.entries.firstOrNull { it.name == optString("type") } ?: ManeuverType.Unknown,
        streetName = optNullableString("streetName"),
        distanceMeters = getDouble("distanceMeters"),
        beginShapeIndex = getInt("beginShapeIndex"),
        endShapeIndex = getInt("endShapeIndex"),
        exitSign = optNullableString("exitSign"),
        signpost = optNullableString("signpost"),
    )

    private fun SearchResult.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("displayName", displayName)
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("category", category)
        .put("address", address)
        .put("city", city)
        .put("state", state)
        .put("country", country)

    private fun JSONObject.toSearchResult(): SearchResult = SearchResult(
        id = getString("id"),
        name = getString("name"),
        displayName = getString("displayName"),
        latitude = getDouble("latitude"),
        longitude = getDouble("longitude"),
        category = optNullableString("category"),
        address = optNullableString("address"),
        city = optNullableString("city"),
        state = optNullableString("state"),
        country = optNullableString("country"),
    )

    private fun Coordinate.toJson(): JSONObject =
        JSONObject().put("latitude", latitude).put("longitude", longitude)

    private fun JSONObject.toCoordinate(): Coordinate =
        Coordinate(getDouble("latitude"), getDouble("longitude"))

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }

    private fun List<Coordinate>.bounds(): RouteBounds? {
        if (isEmpty()) return null
        return RouteBounds(
            south = minOf { it.latitude },
            west = minOf { it.longitude },
            north = maxOf { it.latitude },
            east = maxOf { it.longitude },
        )
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "openmaps_navigation_recovery"
        const val KEY_ACTIVE_NAVIGATION = "active_navigation"
        const val DEFAULT_MAX_AGE_MILLIS = 6 * 60 * 60 * 1000L
        const val KEY_ALIAS = "openmaps_navigation_recovery_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
