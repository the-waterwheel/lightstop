package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class PreviewFramePresentationTest {
    @Test fun displayTimestampFindsTheSameCameraResultInsteadOfTheNewerExposure() {
        val cameraTimestamp = 9_123_456_789_012L
        val results = TimestampedCaptureResultStore<String>()
        results.put(cameraTimestamp, "ISO 100")
        results.put(cameraTimestamp + 33_333_333L, "ISO 800")
        var presentedTimestamp = 0L
        val calls = mutableListOf<String>()
        assertTrue(presentPreviewFrame(cameraTimestamp,
            setPresentationTime = { presentedTimestamp = it; calls.add("timestamp") },
            swapBuffers = {
                calls.add("swap")
                assertEquals("ISO 100", results.takeExact(presentedTimestamp))
            },
        ))
        assertEquals(listOf("timestamp", "swap"), calls)
        assertEquals(cameraTimestamp, presentedTimestamp)
        assertEquals("ISO 800", results.takeExact(cameraTimestamp + 33_333_333L))
    }

    @Test fun settingsRedrawKeepsTheLatchedFrameIdentityUntilAnotherCameraFrameArrives() {
        val timestamps = mutableListOf<Long>()
        var pending = 0L
        for (latched in listOf(1_000_000L, 1_000_000L, 1_000_000L, 34_333_333L)) {
            presentPreviewFrame(latched, { pending = it }, { timestamps.add(pending) })
        }
        assertEquals(listOf(1_000_000L, 1_000_000L, 1_000_000L, 34_333_333L), timestamps)
    }

    @Test fun missingTimestampDoesNotPublishAnUnboundBuffer() {
        for (timestamp in listOf(0L, -1L)) {
            assertFalse(presentPreviewFrame(timestamp, { fail("must not bind") }, { fail("must not swap") }))
        }
    }

    @Test fun failedTimestampBindingCannotSwapOrReportSuccess() {
        val failure = IllegalStateException("EGL timestamp binding failed")
        val caught = runCatching {
            presentPreviewFrame(123L, { throw failure }, { fail("must not swap") })
        }.exceptionOrNull()
        assertSame(failure, caught)
    }

    @Test fun failedSwapIsPropagatedToTheRendererFailureHandler() {
        var bound = false
        val failure = IllegalStateException("EGL swap failed")
        val caught = runCatching {
            presentPreviewFrame(123L, { bound = true }, { throw failure })
        }.exceptionOrNull()
        assertTrue(bound)
        assertSame(failure, caught)
    }
}
