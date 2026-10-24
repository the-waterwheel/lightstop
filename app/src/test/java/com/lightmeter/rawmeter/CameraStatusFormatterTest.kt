package com.lightmeter.rawmeter

import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStatusFormatterTest {
    private val english = CameraStatusFormatter { _, text -> text }
    private val chinese = CameraStatusFormatter { text, _ -> text }

    @Test fun isolatedRawStillReportsHighAccuracyWhenResidentPreviewHasNoRawReader() {
        assertTrue(ready(transientRaw = true).endsWith("High-accuracy metering"))
        assertTrue(ready().endsWith("Preview-stream metering"))
    }

    @Test fun explicitModesKeepTheirOwnLabelsDespiteHardwareRawSupport() {
        assertTrue(ready(MeteringPipelineMode.ISOLATED, raw = true).endsWith("Stable mode"))
        assertTrue(ready(MeteringPipelineMode.FAST, raw = true).endsWith("Compatibility mode"))
    }

    @Test fun lowLightHintTakesPrecedenceAndDistinguishesAnActualFrameRateReduction() {
        assertTrue(ready(raw = true, lowLight = true, reduced = true, safe = true)
            .endsWith("Low light; viewfinder frame rate reduced"))
        assertTrue(ready(lowLight = true).endsWith("Ambient light is too low"))
    }

    @Test fun manualSafePreviewRemainsVisibleOutsideLowLight() {
        assertTrue(ready(raw = true, safe = true).endsWith("Manual safe preview"))
    }

    @Test fun busyCameraRetryAndTerminalFailureHaveDifferentInstructionsInBothLanguages() {
        assertTrue(english.recovery(CameraFailureKind.IN_USE).endsWith("Retrying"))
        assertTrue(english.failure(CameraFailureKind.IN_USE).contains("Close it"))
        assertTrue(chinese.recovery(CameraFailureKind.IN_USE).contains("正在重试"))
        assertTrue(chinese.failure(CameraFailureKind.IN_USE).contains("关闭其他相机应用"))
    }

    private fun ready(
        mode: MeteringPipelineMode = MeteringPipelineMode.AUTO,
        raw: Boolean = false,
        transientRaw: Boolean = false,
        lowLight: Boolean = false,
        reduced: Boolean = false,
        safe: Boolean = false,
    ) = english.ready(CameraUiInfo(rawAvailable = raw), mode, lowLight, reduced, safe, transientRaw)
}
