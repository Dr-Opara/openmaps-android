package com.openmaps.app.map

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.openmaps.app.core.routing.RouteResult
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

interface OfflineMapCorridorCache {
    fun prefetch(route: RouteResult)
    fun clear()
}

object NoopOfflineMapCorridorCache : OfflineMapCorridorCache {
    override fun prefetch(route: RouteResult) = Unit
    override fun clear() = Unit
}

/**
 * Maintains one best-effort MapLibre offline region for the active route.
 *
 * The current region is replaced whenever a new route/reroute is accepted. Downloads stop at the
 * configured byte ceiling and completed regions become inactive but remain available to MapLibre.
 */
class MapLibreOfflineMapCorridorCache(
    context: Context,
    private val styleProvider: MapStyleProvider,
    private val enabled: Boolean,
    private val maxBytes: Long,
) : OfflineMapCorridorCache {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var requestGeneration = 0L

    override fun prefetch(route: RouteResult) {
        if (!enabled || maxBytes <= 0L) return
        val source = styleProvider.resolve() as? MapStyleSource.Url ?: return
        if (source.isDevelopmentOnly || !source.url.startsWith(STADIA_STYLE_PREFIX)) return
        val plan = OfflineCorridorPolicy.plan(route) ?: return

        mainHandler.post {
            val generation = ++requestGeneration
            val manager = OfflineManager.getInstance(appContext)
            manager.setMaximumAmbientCacheSize(maxBytes, null)
            deleteOwnedRegions(manager) {
                if (generation == requestGeneration) {
                    createRouteRegion(manager, source.url, plan, generation)
                }
            }
        }
    }

    override fun clear() {
        if (!enabled) return
        mainHandler.post {
            requestGeneration++
            deleteOwnedRegions(OfflineManager.getInstance(appContext), onComplete = {})
        }
    }

    private fun createRouteRegion(
        manager: OfflineManager,
        styleUrl: String,
        plan: OfflineCorridorPlan,
        generation: Long,
    ) {
        val b = plan.bounds
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            LatLngBounds.from(b.north, b.east, b.south, b.west),
            plan.minZoom,
            plan.maxZoom,
            appContext.resources.displayMetrics.density.coerceIn(1f, 2f),
            true,
        )
        val metadata = JSONObject()
            .put(META_OWNER, META_OWNER_VALUE)
            .put(META_KIND, META_KIND_ACTIVE_ROUTE)
            .put(META_CREATED_AT, System.currentTimeMillis())
            .toString()
            .toByteArray(Charsets.UTF_8)

        manager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    if (generation != requestGeneration) {
                        offlineRegion.delete(
                            object : OfflineRegion.OfflineRegionDeleteCallback {
                                override fun onDelete() = Unit
                                override fun onError(error: String) = Unit
                            },
                        )
                        return
                    }
                    offlineRegion.setObserver(
                        object : OfflineRegion.OfflineRegionObserver {
                            override fun onStatusChanged(status: OfflineRegionStatus) {
                                if (status.completedResourceSize >= maxBytes || status.isComplete) {
                                    offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                }
                            }

                            override fun onError(error: OfflineRegionError) {
                                // Best-effort degraded support only; never block navigation.
                            }

                            override fun mapboxTileCountLimitExceeded(limit: Long) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            }
                        },
                    )
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    // Best-effort degraded support only; normal online MapLibre rendering continues.
                }
            },
        )
    }

    private fun deleteOwnedRegions(manager: OfflineManager, onComplete: () -> Unit) {
        manager.listOfflineRegions(
            object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    val owned = offlineRegions.orEmpty().filter(::isOwnedRegion)
                    deleteNext(owned, 0, onComplete)
                }

                override fun onError(error: String) {
                    onComplete()
                }
            },
        )
    }

    private fun deleteNext(regions: List<OfflineRegion>, index: Int, onComplete: () -> Unit) {
        if (index >= regions.size) {
            onComplete()
            return
        }
        regions[index].delete(
            object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() {
                    deleteNext(regions, index + 1, onComplete)
                }

                override fun onError(error: String) {
                    deleteNext(regions, index + 1, onComplete)
                }
            },
        )
    }

    private fun isOwnedRegion(region: OfflineRegion): Boolean = runCatching {
        JSONObject(String(region.metadata, Charsets.UTF_8)).optString(META_OWNER) == META_OWNER_VALUE
    }.getOrDefault(false)

    private companion object {
        const val STADIA_STYLE_PREFIX = "https://tiles.stadiamaps.com/styles/"
        const val META_OWNER = "owner"
        const val META_OWNER_VALUE = "openmaps"
        const val META_KIND = "kind"
        const val META_KIND_ACTIVE_ROUTE = "active_route"
        const val META_CREATED_AT = "createdAt"
    }
}
