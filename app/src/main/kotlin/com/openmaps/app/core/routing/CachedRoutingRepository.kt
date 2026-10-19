package com.openmaps.app.core.routing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.*

/**
 * Local degraded-operation cache for the most recently successful route.
 *
 * Traffic fields are deliberately cleared on restore: cached geometry/maneuvers are useful offline,
 * but old traffic/ETA must never be presented as live. The cache is only eligible when both origin
 * and destination still match within conservative distance gates and the entry is fresh.
 */
class CachedRoutingRepository(
    private val store: RouteCacheStore,
    private val upstream: RoutingRepository,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : RoutingRepository {

    constructor(
        context: Context,
        upstream: RoutingRepository,
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    ) : this(
        store = EncryptedRouteCacheStore(context),
        upstream = upstream,
        maxAgeMillis = maxAgeMillis,
    )

    override suspend fun route(origin: Coordinate, destination: Coordinate): Result<RouteResult> {
        val live = upstream.route(origin, destination)
        if (live.isSuccess) {
            live.getOrNull()?.let { save(origin, destination, it) }
            return live
        }

        val failure = live.exceptionOrNull()
        val cacheEligible = failure is RoutingException.NoNetwork ||
            failure is RoutingException.Timeout ||
            failure is RoutingException.Server

        if (!cacheEligible) return live

        val cached = load(origin, destination) ?: return live
        return Result.success(cached)
    }

    private fun save(origin: Coordinate, destination: Coordinate, route: RouteResult) {
        val json = JSONObject()
            .put("savedAt", nowMillis())
            .put("origin", origin.toJson())
            .put("destination", destination.toJson())
            .put("route", route.toCacheJson())
            .toString()
        store.put(json)
    }

    private fun load(origin: Coordinate, destination: Coordinate): RouteResult? {
        val raw = store.get() ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val savedAt = root.getLong("savedAt")
            if (nowMillis() - savedAt > maxAgeMillis) return null

            val savedOrigin = root.getJSONObject("origin").toCoordinate()
            val savedDestination = root.getJSONObject("destination").toCoordinate()
            if (distanceMeters(origin, savedOrigin) > ORIGIN_MATCH_METERS) return null
            if (distanceMeters(destination, savedDestination) > DESTINATION_MATCH_METERS) return null

            root.getJSONObject("route").toRouteResult().copy(
                baseDurationSeconds = null,
                typicalDurationSeconds = null,
                trafficDelaySeconds = null,
                isTrafficAware = false,
                alternatives = emptyList(),
                isOfflineCached = true,
            )
        }.getOrNull()
    }

    private fun RouteResult.toCacheJson(): JSONObject = JSONObject()
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

    private fun distanceMeters(a: Coordinate, b: Coordinate): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * earthRadius * asin(sqrt(h))
    }

    private companion object {
        const val DEFAULT_MAX_AGE_MILLIS = 6 * 60 * 60 * 1000L
        const val ORIGIN_MATCH_METERS = 2_000.0
        const val DESTINATION_MATCH_METERS = 300.0
    }
}


interface RouteCacheStore {
    fun get(): String?
    fun put(value: String)
    fun clear()
}

class EncryptedRouteCacheStore(context: Context) : RouteCacheStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(): String? {
        val encoded = prefs.getString(KEY_ROUTE, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= IV_BYTES) error("invalid encrypted route cache")
            val iv = packed.copyOfRange(0, IV_BYTES)
            val ciphertext = packed.copyOfRange(IV_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrElse {
            clear()
            null
        }
    }

    override fun put(value: String) {
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val packed = cipher.iv + encrypted
            prefs.edit().putString(KEY_ROUTE, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
        }
    }

    override fun clear() {
        prefs.edit().remove(KEY_ROUTE).apply()
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
        const val PREFS_NAME = "openmaps_route_cache"
        const val KEY_ROUTE = "last_route"
        const val KEY_ALIAS = "openmaps_route_cache_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
