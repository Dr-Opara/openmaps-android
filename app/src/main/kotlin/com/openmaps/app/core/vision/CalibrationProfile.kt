package com.openmaps.app.core.vision

import kotlin.math.abs

/** The physical device the prototype is calibrated and benchmarked against first. */
object DeviceTargets {
    const val PRIMARY_MODEL = "SM-S948U1"
    const val PRIMARY_NAME = "Samsung Galaxy S26 Ultra"

    fun isPrimary(model: String?): Boolean = model?.trim().equals(PRIMARY_MODEL, ignoreCase = true)
}

/**
 * How one phone sits in one vehicle, as entered and confirmed **by a person**.
 *
 * Nothing here has a default that could pass for a measurement: until [lensHeightMeters] is
 * entered the ground grid is not drawn at all, and until [verifiedOnDevice] is set the gate reports
 * the calibration as unverified.
 */
data class CalibrationProfile(
    /** `Build.MODEL` of the phone this profile was made on. */
    val deviceModel: String,
    /** Camera2 id of the camera it was made with. */
    val cameraId: String?,
    /** Display rotation it was made in (0 = portrait). */
    val displayRotationDegrees: Int,
    val mountDescription: String = "",
    /** Lens centre above the road, tape-measured, in metres. Null until entered. */
    val lensHeightMeters: Double? = null,
    /** Manual corrections added to the sensor's pitch/roll so the horizon line sits on the real horizon. */
    val pitchTrimDegrees: Double = 0.0,
    val rollTrimDegrees: Double = 0.0,
    /** Sensor pitch/roll (before trim) when the mount angle was captured. Null until captured. */
    val referencePitchDownDegrees: Double? = null,
    val referenceRollDegrees: Double? = null,
    /** A person confirmed the grid against a tape-measured mark on this phone and mount. */
    val verifiedOnDevice: Boolean = false,
    val savedAtEpochMillis: Long = 0L,
) {
    val hasLensHeight: Boolean get() = lensHeightMeters != null
    val hasMountAngle: Boolean get() = referencePitchDownDegrees != null && referenceRollDegrees != null

    /** True when this profile describes the phone, camera and orientation currently in use. */
    fun appliesTo(model: String, cameraId: String?, displayRotationDegrees: Int): Boolean =
        deviceModel == model &&
            this.displayRotationDegrees == displayRotationDegrees &&
            (this.cameraId == null || cameraId == null || this.cameraId == cameraId)

    /** The attitude used for projection: sensor attitude plus this profile's trims. */
    fun corrected(attitude: CameraAttitude): CameraAttitude = attitude.copy(
        pitchDownRadians = attitude.pitchDownRadians + Math.toRadians(pitchTrimDegrees),
        rollRadians = attitude.rollRadians + Math.toRadians(rollTrimDegrees),
    )

    fun toMap(): Map<String, String> = buildMap {
        put(KEY_MODEL, deviceModel)
        cameraId?.let { put(KEY_CAMERA, it) }
        put(KEY_ROTATION, displayRotationDegrees.toString())
        put(KEY_MOUNT, mountDescription)
        lensHeightMeters?.let { put(KEY_HEIGHT, it.toString()) }
        put(KEY_PITCH_TRIM, pitchTrimDegrees.toString())
        put(KEY_ROLL_TRIM, rollTrimDegrees.toString())
        referencePitchDownDegrees?.let { put(KEY_REF_PITCH, it.toString()) }
        referenceRollDegrees?.let { put(KEY_REF_ROLL, it.toString()) }
        put(KEY_VERIFIED, verifiedOnDevice.toString())
        put(KEY_SAVED_AT, savedAtEpochMillis.toString())
    }

    companion object {
        const val MIN_LENS_HEIGHT_METERS = 0.5
        const val MAX_LENS_HEIGHT_METERS = 3.5
        const val MAX_TRIM_DEGREES = 15.0

        private const val KEY_MODEL = "deviceModel"
        private const val KEY_CAMERA = "cameraId"
        private const val KEY_ROTATION = "displayRotationDegrees"
        private const val KEY_MOUNT = "mountDescription"
        private const val KEY_HEIGHT = "lensHeightMeters"
        private const val KEY_PITCH_TRIM = "pitchTrimDegrees"
        private const val KEY_ROLL_TRIM = "rollTrimDegrees"
        private const val KEY_REF_PITCH = "referencePitchDownDegrees"
        private const val KEY_REF_ROLL = "referenceRollDegrees"
        private const val KEY_VERIFIED = "verifiedOnDevice"
        private const val KEY_SAVED_AT = "savedAtEpochMillis"

        /** A plausible lens height typed by a person, or null. Rejects anything a car mount cannot be. */
        fun parseLensHeight(text: String): Double? =
            text.trim().replace(',', '.').toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in MIN_LENS_HEIGHT_METERS..MAX_LENS_HEIGHT_METERS }

        /** Null when the stored values are missing or damaged; a bad profile is never half-used. */
        fun fromMap(map: Map<String, String>): CalibrationProfile? {
            val model = map[KEY_MODEL]?.takeIf { it.isNotBlank() } ?: return null
            val rotation = map[KEY_ROTATION]?.toIntOrNull() ?: return null
            val height = map[KEY_HEIGHT]?.let { parseLensHeight(it) ?: return null }
            val pitchTrim = map[KEY_PITCH_TRIM]?.toDoubleOrNull() ?: 0.0
            val rollTrim = map[KEY_ROLL_TRIM]?.toDoubleOrNull() ?: 0.0
            if (!pitchTrim.isFinite() || !rollTrim.isFinite()) return null
            if (abs(pitchTrim) > MAX_TRIM_DEGREES || abs(rollTrim) > MAX_TRIM_DEGREES) return null
            return CalibrationProfile(
                deviceModel = model,
                cameraId = map[KEY_CAMERA],
                displayRotationDegrees = rotation,
                mountDescription = map[KEY_MOUNT].orEmpty(),
                lensHeightMeters = height,
                pitchTrimDegrees = pitchTrim,
                rollTrimDegrees = rollTrim,
                referencePitchDownDegrees = map[KEY_REF_PITCH]?.toDoubleOrNull()?.takeIf { it.isFinite() },
                referenceRollDegrees = map[KEY_REF_ROLL]?.toDoubleOrNull()?.takeIf { it.isFinite() },
                verifiedOnDevice = map[KEY_VERIFIED] == "true",
                savedAtEpochMillis = map[KEY_SAVED_AT]?.toLongOrNull() ?: 0L,
            )
        }
    }
}

/**
 * Decides whether the phone has moved in its mount since the mount angle was captured.
 *
 * Pitch and roll are measured against gravity, so hills, braking and cornering also change them.
 * Those are temporary; a moved mount is not. The detector therefore smooths the readings and only
 * reports a move after the deviation has persisted for [sustainNanos]. It **latches**: once moved,
 * it stays moved until [reset] (a person recalibrating), because guidance must not silently resume
 * on a stale calibration. A long, steep grade can trip it too; that errs on the safe side.
 */
class MountDriftDetector(
    private val thresholdDegrees: Double = 4.0,
    private val sustainNanos: Long = 20_000_000_000L,
    private val smoothingNanos: Long = 2_000_000_000L,
) {
    private var smoothedPitch = Double.NaN
    private var smoothedRoll = Double.NaN
    private var lastTimestamp = 0L
    private var deviatingSince = -1L

    var moved: Boolean = false
        private set

    /** Largest smoothed deviation from the reference seen in the latest update, in degrees. */
    var deviationDegrees: Double = 0.0
        private set

    fun reset() {
        smoothedPitch = Double.NaN
        smoothedRoll = Double.NaN
        lastTimestamp = 0L
        deviatingSince = -1L
        moved = false
        deviationDegrees = 0.0
    }

    /** @return [moved] after taking this reading into account. */
    fun update(timestampNanos: Long, pitchDownDegrees: Double, rollDegrees: Double, profile: CalibrationProfile): Boolean {
        val referencePitch = profile.referencePitchDownDegrees
        val referenceRoll = profile.referenceRollDegrees
        if (referencePitch == null || referenceRoll == null) return moved
        if (!pitchDownDegrees.isFinite() || !rollDegrees.isFinite()) return moved
        if (smoothedPitch.isNaN() || timestampNanos <= lastTimestamp) {
            smoothedPitch = pitchDownDegrees
            smoothedRoll = rollDegrees
        } else {
            val alpha = ((timestampNanos - lastTimestamp).toDouble() / smoothingNanos).coerceIn(0.0, 1.0)
            smoothedPitch += alpha * (pitchDownDegrees - smoothedPitch)
            smoothedRoll += alpha * angleDelta(rollDegrees, smoothedRoll)
        }
        lastTimestamp = timestampNanos
        deviationDegrees = maxOf(abs(smoothedPitch - referencePitch), abs(angleDelta(smoothedRoll, referenceRoll)))
        if (deviationDegrees > thresholdDegrees) {
            if (deviatingSince < 0) deviatingSince = timestampNanos
            if (timestampNanos - deviatingSince >= sustainNanos) moved = true
        } else {
            deviatingSince = -1L
        }
        return moved
    }

    private fun angleDelta(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }
}
