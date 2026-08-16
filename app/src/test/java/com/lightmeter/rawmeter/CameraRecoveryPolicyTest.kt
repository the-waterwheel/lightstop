package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CameraRecoveryPolicyTest {
    @Test
    fun cameraErrorCodesMapToAllKnownFailureKinds() {
        assertEquals(CameraFailureKind.IN_USE, CameraFailureKind.fromDeviceError(1))
        assertEquals(CameraFailureKind.RESOURCE_LIMIT, CameraFailureKind.fromDeviceError(2))
        assertEquals(CameraFailureKind.DISABLED, CameraFailureKind.fromDeviceError(3))
        assertEquals(CameraFailureKind.DEVICE, CameraFailureKind.fromDeviceError(4))
        assertEquals(CameraFailureKind.SERVICE, CameraFailureKind.fromDeviceError(5))
        assertEquals(CameraFailureKind.UNKNOWN, CameraFailureKind.fromDeviceError(99))
    }

    @Test
    fun compatibleModeNeverRequestsRaw() {
        val withTracking = CameraRecoveryPolicy.initialProfile(
            MeteringPipelineMode.COMPATIBLE,
            rawSupported = true,
            trackingSupported = true,
        )
        val withoutTracking = CameraRecoveryPolicy.initialProfile(
            MeteringPipelineMode.COMPATIBLE,
            rawSupported = true,
            trackingSupported = false,
        )

        assertEquals(CameraSessionProfile.COMPATIBLE, withTracking)
        assertEquals(CameraSessionProfile.PREVIEW_ONLY, withoutTracking)
        assertFalse(withTracking.usesRaw)
        assertFalse(withoutTracking.usesRaw)
    }

    @Test
    fun sessionProfilesDegradeInCompatibilityOrder() {
        val rawOnly = CameraRecoveryPolicy.nextProfile(
            CameraSessionProfile.FULL,
            rawSupported = true,
            trackingSupported = true,
        )
        val compatible = CameraRecoveryPolicy.nextProfile(
            rawOnly!!,
            rawSupported = true,
            trackingSupported = true,
        )
        val previewOnly = CameraRecoveryPolicy.nextProfile(
            compatible!!,
            rawSupported = true,
            trackingSupported = true,
        )

        assertEquals(CameraSessionProfile.RAW_ONLY, rawOnly)
        assertEquals(CameraSessionProfile.COMPATIBLE, compatible)
        assertEquals(CameraSessionProfile.PREVIEW_ONLY, previewOnly)
    }

    @Test
    fun openingDeviceFailureRetriesBeforeDroppingFeatures() {
        val first = CameraRecoveryPolicy.decide(
            failure = CameraFailureKind.DEVICE,
            stage = CameraFailureStage.OPENING,
            current = CameraSessionProfile.FULL,
            attempt = 1,
            rawSupported = true,
            trackingSupported = true,
        )
        val second = CameraRecoveryPolicy.decide(
            failure = CameraFailureKind.DEVICE,
            stage = CameraFailureStage.OPENING,
            current = CameraSessionProfile.FULL,
            attempt = 2,
            rawSupported = true,
            trackingSupported = true,
        )

        assertEquals(CameraRecoveryAction.RETRY, first.action)
        assertEquals(CameraSessionProfile.FULL, first.profile)
        assertEquals(CameraRecoveryAction.DOWNGRADE, second.action)
        assertEquals(CameraSessionProfile.RAW_ONLY, second.profile)
    }

    @Test
    fun occupiedAndDisabledCamerasDoNotDisableRaw() {
        val occupied = CameraRecoveryPolicy.decide(
            CameraFailureKind.IN_USE,
            CameraFailureStage.OPENING,
            CameraSessionProfile.FULL,
            attempt = 1,
            rawSupported = true,
            trackingSupported = true,
        )
        val disabled = CameraRecoveryPolicy.decide(
            CameraFailureKind.DISABLED,
            CameraFailureStage.OPENING,
            CameraSessionProfile.FULL,
            attempt = 1,
            rawSupported = true,
            trackingSupported = true,
        )

        assertEquals(CameraRecoveryAction.RETRY, occupied.action)
        assertEquals(CameraSessionProfile.FULL, occupied.profile)
        assertEquals(CameraRecoveryAction.STOP, disabled.action)
    }

    @Test
    fun repeatedResourceLimitDropsOptionalYuvOutputInCompatibleMode() {
        val first = CameraRecoveryPolicy.decide(
            CameraFailureKind.RESOURCE_LIMIT,
            CameraFailureStage.CONFIGURING,
            CameraSessionProfile.COMPATIBLE,
            attempt = 1,
            rawSupported = true,
            trackingSupported = true,
        )
        val second = CameraRecoveryPolicy.decide(
            CameraFailureKind.RESOURCE_LIMIT,
            CameraFailureStage.CONFIGURING,
            CameraSessionProfile.COMPATIBLE,
            attempt = 2,
            rawSupported = true,
            trackingSupported = true,
        )

        assertEquals(CameraRecoveryAction.RETRY, first.action)
        assertEquals(CameraSessionProfile.COMPATIBLE, first.profile)
        assertEquals(CameraRecoveryAction.DOWNGRADE, second.action)
        assertEquals(CameraSessionProfile.PREVIEW_ONLY, second.profile)
    }

    @Test
    fun runningDeviceFailureImmediatelyDropsOneStreamCombination() {
        val decision = CameraRecoveryPolicy.decide(
            CameraFailureKind.DEVICE,
            CameraFailureStage.RUNNING,
            CameraSessionProfile.FULL,
            attempt = 1,
            rawSupported = true,
            trackingSupported = true,
        )

        assertEquals(CameraRecoveryAction.DOWNGRADE, decision.action)
        assertEquals(CameraSessionProfile.RAW_ONLY, decision.profile)
    }
}
