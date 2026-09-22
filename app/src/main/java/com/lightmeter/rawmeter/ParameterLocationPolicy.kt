package com.lightmeter.rawmeter

/** Permission level actually granted for the current location fix. */
enum class LocationPermissionQuality {
    FINE,
    COARSE,
    NONE,
}

/** What the parameter-record UI should tell the user about the current fix. */
enum class ParameterLocationDisplayState {
    DISABLED,
    REQUESTING,
    FINE_FIX,
    COARSE_FIX,
    NO_FIX,
}

/**
 * One validated location candidate. The age is measured against the monotonic clock so it can be
 * compared with Camera2 sensor timestamps without mixing wall-clock time.
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val provider: String?,
    val ageMs: Long,
    val permissionQuality: LocationPermissionQuality,
) {
    val isCoarse: Boolean get() = permissionQuality == LocationPermissionQuality.COARSE
}

enum class LocationRejection {
    INVALID_COORDINATES,
    INVALID_ACCURACY,
    STALE,
    PERMISSION_MISSING,
}

/**
 * Pure freshness/accuracy policy for a parameter-record location. The thresholds are engineering
 * starting values pending device testing; they are not an accuracy guarantee.
 */
object ParameterLocationPolicy {
    /** A fix older than this is not attached to a new record. */
    const val FRESH_MAX_AGE_MS = 60_000L

    /** Upper bound for a single fix request before it is abandoned. */
    const val REQUEST_TIMEOUT_MS = 8_000L
    const val PREFETCH_AGE_MS = 45_000L
    const val RETRY_MIN_INTERVAL_MS = 10_000L

    /** Reference accuracy for a fine fix; coarser fixes are labelled, not rejected. */
    const val FINE_ACCURACY_REFERENCE_METERS = 100.0

    /** Clearly implausible accuracy is rejected regardless of permission level. */
    const val MAX_ACCEPTABLE_ACCURACY_METERS = 10_000.0

    fun evaluate(fix: LocationFix): LocationRejection? {
        if (fix.permissionQuality == LocationPermissionQuality.NONE) {
            return LocationRejection.PERMISSION_MISSING
        }
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite() ||
            fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0
        ) {
            return LocationRejection.INVALID_COORDINATES
        }
        val accuracy = fix.accuracyMeters
        if (accuracy != null &&
            (!accuracy.isFinite() || accuracy <= 0f || accuracy > MAX_ACCEPTABLE_ACCURACY_METERS)
        ) {
            return LocationRejection.INVALID_ACCURACY
        }
        if (fix.ageMs < 0L || fix.ageMs > FRESH_MAX_AGE_MS) {
            return LocationRejection.STALE
        }
        return null
    }

    fun isAcceptable(fix: LocationFix): Boolean = evaluate(fix) == null

    fun shouldRequestRefresh(
        cachedAgeMs: Long?,
        permission: LocationPermissionQuality,
        foreground: Boolean,
        enabled: Boolean,
        inFlight: Boolean,
        sinceLastFailureMs: Long?,
    ): Boolean = foreground && enabled && permission != LocationPermissionQuality.NONE && !inFlight &&
        (cachedAgeMs == null || cachedAgeMs >= PREFETCH_AGE_MS) &&
        (sinceLastFailureMs == null || sinceLastFailureMs >= RETRY_MIN_INTERVAL_MS)
}
