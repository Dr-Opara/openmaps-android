package com.openmaps.app.anchor

import android.util.Log
import com.google.ar.core.Earth
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.openmaps.app.core.anchor.AnchorBackend
import com.openmaps.app.core.anchor.AnchorTrackingState
import com.openmaps.app.core.anchor.WorldPose
import com.openmaps.app.core.anchor.WorldTransform

/**
 * The only file that calls ARCore's real anchor APIs.
 *
 * Uses genuine geospatial anchors: `Earth.createAnchor(latitude, longitude, altitude, quaternion)`.
 * These are true ARCore world anchors — ARCore refines them over time and `Anchor.getPose()`
 * returns a stable world-space transform that does not move when the phone does. That is the entire
 * point, and it is why this cannot be faked with a lat/lng wrapper.
 *
 * Keys are the manager's absolute anchor-grid indices (metres along the route / grid size). They are
 * stable for the life of a route, which is what lets the manager create, reuse and prune anchors
 * deterministically instead of rebuilding the set on every position update.
 * Every method is defensive: this is reached only when Earth is tracking, but anchor creation can
 * still fail transiently and must never propagate as a crash into Live.
 */
@Suppress("LogNotTimber")
class ArCoreAnchorBackend(
    private val sessionProvider: () -> Session?,
    private val cameraTransformProvider: () -> WorldTransform?,
) : AnchorBackend {

    private val anchors = HashMap<Long, com.google.ar.core.Anchor>()

    private fun earth(): Earth? = try {
        sessionProvider()?.earth
    } catch (t: Throwable) {
        Log.w(TAG, "Earth unavailable", t)
        null
    }

    override fun isAvailable(): Boolean {
        val earth = earth() ?: return false
        return try {
            earth.trackingState == TrackingState.TRACKING && earth.earthState == Earth.EarthState.ENABLED
        } catch (t: Throwable) {
            false
        }
    }

    override fun createAnchor(
        key: Long,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double,
        quaternion: FloatArray,
    ): Boolean {
        // Reuse: an existing anchor for this key is never recreated, which is what keeps the arrows
        // spatially stable and stops per-frame churn.
        if (anchors.containsKey(key)) return true
        val earth = earth() ?: return false
        return try {
            val anchor = earth.createAnchor(latitude, longitude, altitudeMeters, quaternion)
            anchors[key] = anchor
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Anchor creation failed for key $key", t)
            false
        }
    }

    override fun worldPose(key: Long): WorldPose? {
        val anchor = anchors[key] ?: return null
        return try {
            if (anchor.trackingState != TrackingState.TRACKING) return null
            val pose = anchor.pose
            WorldPose(pose.tx().toDouble(), pose.ty().toDouble(), pose.tz().toDouble())
        } catch (t: Throwable) {
            null
        }
    }

    override fun trackingState(key: Long): AnchorTrackingState {
        val anchor = anchors[key] ?: return AnchorTrackingState.Stopped
        return when (anchor.trackingState) {
            TrackingState.TRACKING -> AnchorTrackingState.Tracking
            TrackingState.PAUSED -> AnchorTrackingState.Paused
            else -> AnchorTrackingState.Stopped
        }
    }

    override fun detach(key: Long) {
        val anchor = anchors.remove(key) ?: return
        try {
            anchor.detach()
        } catch (t: Throwable) {
            Log.w(TAG, "Anchor detach failed for key $key", t)
        }
    }

    override fun detachAll() {
        anchors.keys.toList().forEach { detach(it) }
        anchors.clear()
    }

    /**
     * The camera transform comes from the engine, not from the session: ARCore only exposes a
     * `Camera` on the per-frame `Frame`, which is stale the moment it is returned. The engine
     * publishes the pose each frame, which is what keeps the view-relative transform consistent
     * with the anchors created in that same session.
     */
    override fun cameraTransform(): WorldTransform? = try {
        cameraTransformProvider()
    } catch (t: Throwable) {
        null
    }

    private companion object {
        const val TAG = "ArCoreAnchorBackend"
    }
}