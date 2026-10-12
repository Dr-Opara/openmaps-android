package com.openmaps.app.core.spatial

/**
 * The tiny localization indicator shown in Live, as a provider-neutral enum.
 *
 * Kept separate from [SpatialLocalizationState] on purpose: that type is for the engine and carries
 * reasons and failures that mean nothing to a driver. This is what the UI shows, and mapping between
 * them is one pure function ([LiveLocalizationStatus.of]) which is the whole Phase 6 UI test surface.
 *
 * `None` means "don't show anything" — most of the time a driver should see no localization UI at
 * all, only the turn guidance. Clutter on a driving HUD is a safety issue, not a polish issue.
 */
enum class LiveLocalizationStatus {
    /** Localization is not applicable or unavailable; show nothing (Live just works without it). */
    None,

    /** Actively acquiring a fix ("Locating…"). */
    Locating,

    /** Has a fix but it is not yet trustworthy ("Improving accuracy…"). */
    Improving,

    /** Fix is good enough for guidance ("Ready"). */
    Ready,
}

object LiveLocalizationStatusMapper {

    /**
     * Maps engine state -> what to show.
     *
     * Unsupported / NotInstalled / PermissionRequired / Failed all collapse to [None] rather than to
     * an error message: AR is an enhancement, and telling a driver "ARCore isn't supported" while
     * their navigation works fine is noise at best and alarming at worst. Those states still reach
     * the debug diagnostics surface.
     */
    fun of(state: SpatialLocalizationState): LiveLocalizationStatus = when (state) {
        SpatialLocalizationState.Idle,
        SpatialLocalizationState.Unsupported,
        is SpatialLocalizationState.NotInstalled,
        SpatialLocalizationState.PermissionRequired,
        is SpatialLocalizationState.Failed,
        -> LiveLocalizationStatus.None

        SpatialLocalizationState.Initializing,
        is SpatialLocalizationState.Limited,
        -> LiveLocalizationStatus.Locating

        is SpatialLocalizationState.Tracking -> when (state.pose?.quality) {
            null -> LiveLocalizationStatus.Locating
            LocalizationQuality.Unavailable -> LiveLocalizationStatus.Locating
            LocalizationQuality.Acquiring -> LiveLocalizationStatus.Locating
            LocalizationQuality.Poor -> LiveLocalizationStatus.Improving
            LocalizationQuality.Fair,
            LocalizationQuality.Good,
            -> LiveLocalizationStatus.Ready
        }
    }
}