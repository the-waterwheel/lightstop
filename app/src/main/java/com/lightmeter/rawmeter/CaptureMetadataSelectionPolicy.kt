package com.lightmeter.rawmeter

/** The Camera2 result object that legally carries one frame's exposure metadata. */
internal enum class CaptureMetadataSource {
    /** The logical/public camera's own TotalCaptureResult. */
    LOGICAL_RESULT,

    /** The requested physical camera's result-map entry. */
    PHYSICAL_RESULT,
}

/** Why a frame's metadata could not be used for a formal measurement. */
internal enum class CaptureMetadataFailure {
    /** A fixed physical output was requested but the result map has no entry for it. */
    MISSING_PHYSICAL_RESULT,

    /** Neither the physical result nor its same-capture TotalCaptureResult carried a timestamp. */
    MISSING_PHYSICAL_TIMESTAMP,

    /** The selected result lacks fields this consumer requires. */
    MISSING_REQUIRED_FIELDS,

    /** The declared route kind and the requested physical id disagree. */
    IDENTITY_CONFLICT,
}

/** Immutable, Camera2-free inputs for one metadata selection decision. */
internal data class CaptureMetadataInput(
    val routeKind: CameraRouteKind,
    val requestedPhysicalCameraId: String?,
    val selectedResultPresent: Boolean,
    val physicalTimestampNs: Long?,
    val logicalTimestampNs: Long?,
    val requiredFieldsPresent: Boolean,
)

internal sealed interface CaptureMetadataSelection {
    data class Selected(
        val source: CaptureMetadataSource,
        val pairedTimestampNs: Long?,
    ) : CaptureMetadataSelection

    data class Rejected(
        val failure: CaptureMetadataFailure,
    ) : CaptureMetadataSelection
}

/**
 * Pure, Camera2-free rule for choosing the result that legally carries a frame's exposure
 * metadata.
 *
 * A fixed physical output must never borrow the logical [android.hardware.camera2.TotalCaptureResult]:
 * identical sensor timestamps do not prove identical exposure, ISO or colour data. Only a
 * same-capture physical result that omits its own timestamp may borrow the logical timestamp,
 * because both then describe the same frame. Exposure, ISO, aperture, AWB and colour matrices are
 * never joined across results.
 */
internal object CaptureMetadataSelectionPolicy {
    fun select(input: CaptureMetadataInput): CaptureMetadataSelection {
        val requestedPhysicalId = input.requestedPhysicalCameraId
        val expectsPhysical = input.routeKind == CameraRouteKind.FIXED_PHYSICAL
        if (expectsPhysical != (requestedPhysicalId != null)) {
            return CaptureMetadataSelection.Rejected(CaptureMetadataFailure.IDENTITY_CONFLICT)
        }
        if (requestedPhysicalId == null) {
            if (!input.requiredFieldsPresent) {
                return CaptureMetadataSelection.Rejected(
                    CaptureMetadataFailure.MISSING_REQUIRED_FIELDS,
                )
            }
            return CaptureMetadataSelection.Selected(
                source = CaptureMetadataSource.LOGICAL_RESULT,
                pairedTimestampNs = input.logicalTimestampNs,
            )
        }
        if (!input.selectedResultPresent) {
            return CaptureMetadataSelection.Rejected(
                CaptureMetadataFailure.MISSING_PHYSICAL_RESULT,
            )
        }
        if (!input.requiredFieldsPresent) {
            return CaptureMetadataSelection.Rejected(
                CaptureMetadataFailure.MISSING_REQUIRED_FIELDS,
            )
        }
        val timestamp = input.physicalTimestampNs ?: input.logicalTimestampNs
        if (timestamp == null || timestamp <= 0L) {
            return CaptureMetadataSelection.Rejected(
                CaptureMetadataFailure.MISSING_PHYSICAL_TIMESTAMP,
            )
        }
        return CaptureMetadataSelection.Selected(
            source = CaptureMetadataSource.PHYSICAL_RESULT,
            pairedTimestampNs = timestamp,
        )
    }
}
