package com.lightmeter.rawmeter

import android.annotation.TargetApi
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.MandatoryStreamCombination
import android.os.Build
import android.util.Size

internal data class CameraCombinationMatrixSnapshot(
    val capabilities: CameraCombinationCapabilities,
    val mandatoryGuaranteedProfiles: Set<CameraSessionProfile>,
)

/** Reads the framework's stream matrix without treating an absent entry as a rejection. */
internal object CameraCombinationMatrix {
    fun inspect(
        characteristics: CameraCharacteristics,
        previewSize: Size,
        rawSize: Size?,
        yuvSize: Size?,
    ): CameraCombinationMatrixSnapshot {
        val capabilities = CameraCombinationCapabilities(
            rawAvailable = rawSize != null,
            yuvAvailable = yuvSize != null,
            maxRawOutputs = characteristics.get(
                CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW,
            ) ?: if (rawSize != null) 1 else 0,
            maxProcessedOutputs = characteristics.get(
                CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC,
            ) ?: if (yuvSize != null) 2 else 1,
        )
        val combinations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            characteristics.get(CameraCharacteristics.SCALER_MANDATORY_STREAM_COMBINATIONS)
                ?.toList()
                .orEmpty()
        } else {
            emptyList()
        }
        val guaranteed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            CameraSessionProfile.entries.filterTo(mutableSetOf()) { profile ->
                isGuaranteed(
                    profile = profile,
                    combinations = combinations,
                    previewSize = previewSize,
                    rawSize = rawSize,
                    yuvSize = yuvSize,
                )
            }
        } else {
            emptySet()
        }
        return CameraCombinationMatrixSnapshot(capabilities, guaranteed)
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun isGuaranteed(
        profile: CameraSessionProfile,
        combinations: List<MandatoryStreamCombination>,
        previewSize: Size,
        rawSize: Size?,
        yuvSize: Size?,
    ): Boolean {
        val requested = buildList {
            if (profile.usesPreview) add(ImageFormat.PRIVATE to previewSize)
            if (profile.usesRaw) rawSize?.let { add(ImageFormat.RAW_SENSOR to it) } ?: return false
            if (profile.usesTracking) {
                yuvSize?.let { add(ImageFormat.YUV_420_888 to it) } ?: return false
            }
        }
        return combinations.any { combination ->
            if (combination.isReprocessable) return@any false
            val advertised = combination.streamsInformation.filterNot { it.isInput }
            // Keep GUARANTEED conservative. A non-exact table entry remains UNKNOWN and is still
            // passed to the HAL's real session query/configuration later.
            advertised.size == requested.size && matchEveryRequestedStream(
                requested = requested,
                advertised = advertised,
                used = BooleanArray(advertised.size),
                requestIndex = 0,
            )
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun matchEveryRequestedStream(
        requested: List<Pair<Int, Size>>,
        advertised: List<MandatoryStreamCombination.MandatoryStreamInformation>,
        used: BooleanArray,
        requestIndex: Int,
    ): Boolean {
        if (requestIndex >= requested.size) return true
        val (format, size) = requested[requestIndex]
        for (index in advertised.indices) {
            if (used[index]) continue
            val stream = advertised[index]
            if (stream.format != format || size !in stream.availableSizes) continue
            used[index] = true
            if (matchEveryRequestedStream(requested, advertised, used, requestIndex + 1)) {
                return true
            }
            used[index] = false
        }
        return false
    }
}
