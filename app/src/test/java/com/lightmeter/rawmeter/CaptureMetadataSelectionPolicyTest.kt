package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureMetadataSelectionPolicyTest {
    private fun input(
        routeKind: CameraRouteKind,
        requestedPhysicalCameraId: String?,
        selectedResultPresent: Boolean = true,
        physicalTimestampNs: Long? = 100L,
        logicalTimestampNs: Long? = 100L,
        requiredFieldsPresent: Boolean = true,
    ) = CaptureMetadataInput(
        routeKind = routeKind,
        requestedPhysicalCameraId = requestedPhysicalCameraId,
        selectedResultPresent = selectedResultPresent,
        physicalTimestampNs = physicalTimestampNs,
        logicalTimestampNs = logicalTimestampNs,
        requiredFieldsPresent = requiredFieldsPresent,
    )

    @Test
    fun `logical auto route uses the logical result`() {
        val selection = CaptureMetadataSelectionPolicy.select(
            input(CameraRouteKind.LOGICAL_AUTO, null),
        )
        assertEquals(
            CaptureMetadataSelection.Selected(CaptureMetadataSource.LOGICAL_RESULT, 100L),
            selection,
        )
    }

    @Test
    fun `public direct route uses the logical result without a physical map`() {
        val selection = CaptureMetadataSelectionPolicy.select(
            input(CameraRouteKind.PUBLIC_DIRECT, null, selectedResultPresent = true),
        )
        assertTrue(selection is CaptureMetadataSelection.Selected)
    }

    @Test
    fun `fixed physical route uses the requested physical result`() {
        val selection = CaptureMetadataSelectionPolicy.select(
            input(CameraRouteKind.FIXED_PHYSICAL, "2"),
        )
        assertEquals(
            CaptureMetadataSelection.Selected(CaptureMetadataSource.PHYSICAL_RESULT, 100L),
            selection,
        )
    }

    @Test
    fun `fixed physical route without its result is rejected instead of borrowing logical`() {
        val selection = CaptureMetadataSelectionPolicy.select(
            input(
                CameraRouteKind.FIXED_PHYSICAL,
                "2",
                selectedResultPresent = false,
                physicalTimestampNs = null,
                logicalTimestampNs = 100L,
            ),
        )
        assertEquals(
            CaptureMetadataSelection.Rejected(CaptureMetadataFailure.MISSING_PHYSICAL_RESULT),
            selection,
        )
    }

    @Test
    fun `physical result without a timestamp may borrow the same-capture logical timestamp`() {
        val selection = CaptureMetadataSelectionPolicy.select(
            input(
                CameraRouteKind.FIXED_PHYSICAL,
                "2",
                physicalTimestampNs = null,
                logicalTimestampNs = 123L,
            ),
        )
        assertEquals(
            CaptureMetadataSelection.Selected(CaptureMetadataSource.PHYSICAL_RESULT, 123L),
            selection,
        )
    }

    @Test
    fun `physical result with neither timestamp is rejected`() {
        val selection = CaptureMetadataSelectionPolicy.select(
            input(
                CameraRouteKind.FIXED_PHYSICAL,
                "2",
                physicalTimestampNs = null,
                logicalTimestampNs = null,
            ),
        )
        assertEquals(
            CaptureMetadataSelection.Rejected(CaptureMetadataFailure.MISSING_PHYSICAL_TIMESTAMP),
            selection,
        )
    }

    @Test
    fun `missing required fields is rejected on every route`() {
        assertEquals(
            CaptureMetadataSelection.Rejected(CaptureMetadataFailure.MISSING_REQUIRED_FIELDS),
            CaptureMetadataSelectionPolicy.select(
                input(CameraRouteKind.LOGICAL_AUTO, null, requiredFieldsPresent = false),
            ),
        )
        assertEquals(
            CaptureMetadataSelection.Rejected(CaptureMetadataFailure.MISSING_REQUIRED_FIELDS),
            CaptureMetadataSelectionPolicy.select(
                input(CameraRouteKind.FIXED_PHYSICAL, "2", requiredFieldsPresent = false),
            ),
        )
    }

    @Test
    fun `route kind and physical id must agree`() {
        assertEquals(
            CaptureMetadataSelection.Rejected(CaptureMetadataFailure.IDENTITY_CONFLICT),
            CaptureMetadataSelectionPolicy.select(
                input(CameraRouteKind.LOGICAL_AUTO, "2"),
            ),
        )
        assertEquals(
            CaptureMetadataSelection.Rejected(CaptureMetadataFailure.IDENTITY_CONFLICT),
            CaptureMetadataSelectionPolicy.select(
                input(CameraRouteKind.FIXED_PHYSICAL, null),
            ),
        )
    }
}
