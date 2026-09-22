package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParameterLocationPolicyTest {
    private fun fix(
        latitude: Double = 37.0,
        longitude: Double = 122.0,
        accuracy: Float? = 20f,
        ageMs: Long = 1_000L,
        quality: LocationPermissionQuality = LocationPermissionQuality.FINE,
    ) = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        provider = "gps",
        ageMs = ageMs,
        permissionQuality = quality,
    )

    @Test
    fun `fresh fine fix is accepted`() {
        assertNull(ParameterLocationPolicy.evaluate(fix()))
        assertTrue(ParameterLocationPolicy.isAcceptable(fix()))
    }

    @Test
    fun `coarse fix is accepted and labelled`() {
        val coarse = fix(accuracy = 800f, quality = LocationPermissionQuality.COARSE)
        assertTrue(ParameterLocationPolicy.isAcceptable(coarse))
        assertTrue(coarse.isCoarse)
    }

    @Test
    fun `stale fix is rejected`() {
        assertEquals(
            LocationRejection.STALE,
            ParameterLocationPolicy.evaluate(fix(ageMs = ParameterLocationPolicy.FRESH_MAX_AGE_MS + 1)),
        )
        assertEquals(
            LocationRejection.STALE,
            ParameterLocationPolicy.evaluate(fix(ageMs = -1L)),
        )
    }

    @Test
    fun `invalid coordinates are rejected`() {
        assertEquals(
            LocationRejection.INVALID_COORDINATES,
            ParameterLocationPolicy.evaluate(fix(latitude = 91.0)),
        )
        assertEquals(
            LocationRejection.INVALID_COORDINATES,
            ParameterLocationPolicy.evaluate(fix(longitude = 181.0)),
        )
        assertEquals(
            LocationRejection.INVALID_COORDINATES,
            ParameterLocationPolicy.evaluate(fix(latitude = Double.NaN)),
        )
    }

    @Test
    fun `implausible accuracy is rejected but absent accuracy is allowed`() {
        assertEquals(
            LocationRejection.INVALID_ACCURACY,
            ParameterLocationPolicy.evaluate(fix(accuracy = 20_000f)),
        )
        assertEquals(
            LocationRejection.INVALID_ACCURACY,
            ParameterLocationPolicy.evaluate(fix(accuracy = 0f)),
        )
        assertTrue(ParameterLocationPolicy.isAcceptable(fix(accuracy = null)))
    }

    @Test
    fun `missing permission is rejected`() {
        assertEquals(
            LocationRejection.PERMISSION_MISSING,
            ParameterLocationPolicy.evaluate(fix(quality = LocationPermissionQuality.NONE)),
        )
        assertFalse(
            ParameterLocationPolicy.isAcceptable(fix(quality = LocationPermissionQuality.NONE)),
        )
    }

    @Test
    fun `refresh is foreground gated deduplicated and rate limited`() {
        assertTrue(
            ParameterLocationPolicy.shouldRequestRefresh(
                cachedAgeMs = 61_000L,
                permission = LocationPermissionQuality.FINE,
                foreground = true,
                enabled = true,
                inFlight = false,
                sinceLastFailureMs = null,
            ),
        )
        assertFalse(
            ParameterLocationPolicy.shouldRequestRefresh(
                cachedAgeMs = 61_000L,
                permission = LocationPermissionQuality.FINE,
                foreground = true,
                enabled = true,
                inFlight = true,
                sinceLastFailureMs = null,
            ),
        )
        assertFalse(
            ParameterLocationPolicy.shouldRequestRefresh(
                cachedAgeMs = null,
                permission = LocationPermissionQuality.FINE,
                foreground = false,
                enabled = true,
                inFlight = false,
                sinceLastFailureMs = null,
            ),
        )
    }
}
