package com.lightmeter.rawmeter

import java.security.MessageDigest

/** Independent calibration domains; a change in one must never invalidate the others. */
enum class CalibrationDomain {
    METERING_RAW,
    METERING_YUV,
    METERING_ISP,
    EXPOSURE_PREVIEW,
    VIGNETTING,
    ;

    companion object {
        fun forSource(source: MeteringSource): CalibrationDomain = when (source) {
            MeteringSource.RAW -> METERING_RAW
            MeteringSource.YUV_PREVIEW -> METERING_YUV
            MeteringSource.ISP_PREVIEW -> METERING_ISP
        }
    }
}

/** Whether a stored calibration may be applied to the current capture context. */
enum class CalibrationSignatureState {
    /** Stored signature matches the current route, output and algorithm. */
    VALID,

    /** Pre-signature data; retained for history but never auto-applied. */
    LEGACY_UNVERIFIED,

    /** Same route, but the build/algorithm changed and the value must be re-verified. */
    NEEDS_REVALIDATION,

    /** A different route or output geometry; the stored value does not describe this capture. */
    ROUTE_MISMATCH,

    /** No calibration is stored for this camera and source. */
    NONE,
    ;

    val isApplicable: Boolean get() = this == VALID
}

/**
 * Immutable identity of one calibration's capture context.
 *
 * The identity is deliberately separated from the correction value: a correction may remain
 * visible as history while its signature is no longer valid for the current capture. The
 * confirmed physical id is informational and never makes a transient logical-session reopen look
 * like a lens change.
 */
data class CalibrationSignature(
    val schemaVersion: Int,
    val algorithmVersion: Int,
    val domain: CalibrationDomain,
    val buildFingerprintHash: String,
    val cameraInfoVersion: String?,
    val selectionRouteId: String,
    val logicalCameraId: String,
    val configuredPhysicalCameraId: String?,
    val confirmedPhysicalCameraId: String?,
    val routeKind: CameraRouteKind,
    val outputWidth: Int,
    val outputHeight: Int,
    val outputFormat: Int,
) {
    fun serialize(): String = listOf(
        schemaVersion,
        algorithmVersion,
        domain.name,
        buildFingerprintHash,
        cameraInfoVersion.orEmpty(),
        selectionRouteId,
        logicalCameraId,
        configuredPhysicalCameraId.orEmpty(),
        confirmedPhysicalCameraId.orEmpty(),
        routeKind.name,
        outputWidth,
        outputHeight,
        outputFormat,
    ).joinToString(FIELD_SEPARATOR)

    fun stableHash(): String = sha256(serialize())

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val CURRENT_ALGORITHM_VERSION = CameraCalibrationRecord.CURRENT_SCHEMA_VERSION
        const val FIELD_SEPARATOR = "|"

        fun parse(value: String?): CalibrationSignature? {
            if (value.isNullOrBlank()) return null
            val parts = value.split(FIELD_SEPARATOR)
            if (parts.size != FIELD_COUNT) return null
            return runCatching {
                CalibrationSignature(
                    schemaVersion = parts[0].toInt(),
                    algorithmVersion = parts[1].toInt(),
                    domain = CalibrationDomain.valueOf(parts[2]),
                    buildFingerprintHash = parts[3],
                    cameraInfoVersion = parts[4].ifEmpty { null },
                    selectionRouteId = parts[5],
                    logicalCameraId = parts[6],
                    configuredPhysicalCameraId = parts[7].ifEmpty { null },
                    confirmedPhysicalCameraId = parts[8].ifEmpty { null },
                    routeKind = CameraRouteKind.valueOf(parts[9]),
                    outputWidth = parts[10].toInt(),
                    outputHeight = parts[11].toInt(),
                    outputFormat = parts[12].toInt(),
                )
            }.getOrNull()
        }

        private const val FIELD_COUNT = 13
    }
}

/** Actual output geometry used by one metering source in the configured session. */
data class CalibrationOutputGeometry(
    val width: Int,
    val height: Int,
    val format: Int,
)

/**
 * Capture context of the currently configured route/output. A single context produces one
 * [CalibrationSignature] per metering source, because RAW, YUV and ISP use different outputs.
 */
data class CalibrationCaptureContext(
    val buildFingerprintHash: String,
    val cameraInfoVersion: String?,
    val selectionRouteId: String,
    val logicalCameraId: String,
    val configuredPhysicalCameraId: String?,
    val confirmedPhysicalCameraId: String?,
    val routeKind: CameraRouteKind,
    val outputs: Map<MeteringSource, CalibrationOutputGeometry>,
) {
    /** A source absent from this session has no calibration evidence; never manufacture 0x0 output. */
    fun sampleSignature(source: MeteringSource): CalibrationSignature? =
        outputs[source]?.let { signature(source) }

    fun signature(source: MeteringSource): CalibrationSignature {
        val output = outputs[source] ?: CalibrationOutputGeometry(0, 0, 0)
        return CalibrationSignature(
            schemaVersion = CalibrationSignature.CURRENT_SCHEMA_VERSION,
            algorithmVersion = CalibrationSignature.CURRENT_ALGORITHM_VERSION,
            domain = CalibrationDomain.forSource(source),
            buildFingerprintHash = buildFingerprintHash,
            cameraInfoVersion = cameraInfoVersion,
            selectionRouteId = selectionRouteId,
            logicalCameraId = logicalCameraId,
            configuredPhysicalCameraId = configuredPhysicalCameraId,
            confirmedPhysicalCameraId = confirmedPhysicalCameraId,
            routeKind = routeKind,
            outputWidth = output.width,
            outputHeight = output.height,
            outputFormat = output.format,
        )
    }

    /** Vignetting is a RAW-only coordinate-domain correction. */
    fun vignettingSignature(): CalibrationSignature =
        signature(MeteringSource.RAW).copy(domain = CalibrationDomain.VIGNETTING)
}

/**
 * Pure classifier for a stored signature against the current capture context. It performs no I/O
 * and no Android access, so migration behavior is unit-testable.
 */
internal object CalibrationSignaturePolicy {
    fun classify(
        stored: CalibrationSignature?,
        storedPresent: Boolean,
        current: CalibrationSignature,
    ): CalibrationSignatureState {
        if (stored == null) {
            return if (storedPresent) {
                CalibrationSignatureState.LEGACY_UNVERIFIED
            } else {
                CalibrationSignatureState.NONE
            }
        }
        if (stored.domain != current.domain) return CalibrationSignatureState.ROUTE_MISMATCH
        if (stored.schemaVersion != current.schemaVersion ||
            stored.algorithmVersion != current.algorithmVersion ||
            stored.buildFingerprintHash != current.buildFingerprintHash ||
            stored.cameraInfoVersion != current.cameraInfoVersion
        ) {
            return CalibrationSignatureState.NEEDS_REVALIDATION
        }
        if (stored.selectionRouteId != current.selectionRouteId ||
            stored.logicalCameraId != current.logicalCameraId ||
            stored.configuredPhysicalCameraId != current.configuredPhysicalCameraId ||
            stored.routeKind != current.routeKind ||
            stored.outputWidth != current.outputWidth ||
            stored.outputHeight != current.outputHeight ||
            stored.outputFormat != current.outputFormat
        ) {
            return CalibrationSignatureState.ROUTE_MISMATCH
        }
        // A transient null confirmed id during a logical-session reopen is not a lens change.
        val storedConfirmed = stored.confirmedPhysicalCameraId
        val currentConfirmed = current.confirmedPhysicalCameraId
        if (storedConfirmed != null && currentConfirmed != null &&
            storedConfirmed != currentConfirmed
        ) {
            return CalibrationSignatureState.ROUTE_MISMATCH
        }
        return CalibrationSignatureState.VALID
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
