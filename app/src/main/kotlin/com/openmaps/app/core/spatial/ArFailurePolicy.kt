package com.openmaps.app.core.spatial

/**
 * What to do about an AR failure. This replaces "any `Failed` permanently hands the camera to CameraX",
 * which turned a single transient hiccup into AR being gone for the whole drive, and equally never
 * told anyone when AR was gone for a *permanent* reason (credentials, project config, unsupported device).
 *
 *  - [Retry]: the cause may clear by itself. Rebuild the AR session after a backoff, up to
 *    [ArFailurePolicy.MAX_RETRIES] times, while staying on the AR preview.
 *  - [Fallback]: AR cannot work in this session. Hand the camera to CameraX and say exactly why.
 *    [Fallback.permanentConfiguration] marks causes that no retry or restart can fix and that need a
 *    human (build key, Google Cloud project, device support).
 */
sealed interface ArRecovery {
    data class Retry(val delayMillis: Long) : ArRecovery
    data class Fallback(val permanentConfiguration: Boolean) : ArRecovery
}

object ArFailurePolicy {
    const val MAX_RETRIES = 3
    private const val BASE_BACKOFF_MILLIS = 1_000L
    private const val QUOTA_BACKOFF_MILLIS = 10_000L

    /** True when retrying can never help: the build, the Google Cloud project, or the device is the cause. */
    fun isPermanentConfiguration(failure: SpatialFailure): Boolean = when (failure) {
        SpatialFailure.EarthUnavailable, // ERROR_NOT_AUTHORIZED: key missing/restricted/API not enabled
        SpatialFailure.GeospatialDisabled,
        SpatialFailure.GeospatialUnsupported,
        -> true
        SpatialFailure.SessionCreationFailed,
        SpatialFailure.ArCoreError,
        SpatialFailure.CameraUnavailable,
        SpatialFailure.ResourceExhausted,
        SpatialFailure.EarthInternal,
        -> false
    }

    /** Failures worth rebuilding the session for. Session *creation* failing is not: it did not run at all. */
    private fun isRetryable(failure: SpatialFailure): Boolean = when (failure) {
        SpatialFailure.CameraUnavailable,
        SpatialFailure.ArCoreError,
        SpatialFailure.ResourceExhausted,
        SpatialFailure.EarthInternal,
        -> true
        SpatialFailure.SessionCreationFailed,
        SpatialFailure.EarthUnavailable,
        SpatialFailure.GeospatialDisabled,
        SpatialFailure.GeospatialUnsupported,
        -> false
    }

    /** [attemptsSoFar] is how many retries this session has already used. */
    fun decide(failure: SpatialFailure, attemptsSoFar: Int): ArRecovery {
        if (!isRetryable(failure) || attemptsSoFar >= MAX_RETRIES) {
            return ArRecovery.Fallback(permanentConfiguration = isPermanentConfiguration(failure))
        }
        val base = if (failure == SpatialFailure.ResourceExhausted) QUOTA_BACKOFF_MILLIS else BASE_BACKOFF_MILLIS
        return ArRecovery.Retry(delayMillis = base shl attemptsSoFar) // 1s, 2s, 4s (10s, 20s, 40s for quota)
    }

    /**
     * Plain-language cause for logs. [keyCompiledIn] is only whether a Geospatial key was present at
     * build time; the key itself is never passed in, logged, or returned.
     */
    fun explanation(failure: SpatialFailure, keyCompiledIn: Boolean): String = when (failure) {
        SpatialFailure.EarthUnavailable ->
            if (keyCompiledIn) {
                "ERROR_NOT_AUTHORIZED: a key is compiled in but ARCore rejected it. Check that the ARCore API " +
                    "is enabled in the Google Cloud project, the key allows this package name and signing " +
                    "SHA-1 (debug and release signatures differ), and billing/quota is active."
            } else {
                "ERROR_NOT_AUTHORIZED: this build has no ARCORE_API_KEY (CI and local builds only include it " +
                    "when ARCORE_API_KEY is supplied). Geospatial cannot authorize without it."
            }
        SpatialFailure.GeospatialDisabled -> "Geospatial mode is disabled in the session configuration."
        SpatialFailure.GeospatialUnsupported -> "This device / ARCore version does not support Geospatial mode."
        SpatialFailure.ResourceExhausted -> "Geospatial quota or rate limit reached; will retry with backoff."
        SpatialFailure.EarthInternal -> "ARCore reported a transient internal Earth error; will retry."
        SpatialFailure.CameraUnavailable -> "The rear camera was lost or is in use by another app."
        SpatialFailure.ArCoreError -> "ARCore threw an unexpected error while updating a frame."
        SpatialFailure.SessionCreationFailed -> "The ARCore session could not be created."
    }
}
