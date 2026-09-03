package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    @Test
    fun meteringSectionOffersAccuracyStableAndCompatibilityModes() {
        val metering = SettingsCatalog.sections.single { it.key == SettingsSectionKey.METERING }
        val selection = metering.items.single { it.key == SettingKey.COMBINATION_SELECTION }
        val engine = metering.items.single { it.key == SettingKey.METERING_PIPELINE }

        assertEquals("测光组合选择", selection.label.resolve(MenuLanguage.CHINESE))
        assertEquals(
            "Metering combination selection",
            selection.label.resolve(MenuLanguage.ENGLISH),
        )
        assertEquals(
            listOf(
                MeteringCombinationSelectionMode.SYSTEM.name,
                MeteringCombinationSelectionMode.MANUAL.name,
            ),
            selection.options.map { it.value },
        )
        assertEquals("系统测光模式", engine.label.resolve(MenuLanguage.CHINESE))

        assertEquals(
            listOf(
                MeteringPipelineMode.AUTO.name,
                MeteringPipelineMode.ISOLATED.name,
                MeteringPipelineMode.FAST.name,
            ),
            engine.options.map { it.value },
        )
        assertEquals(
            listOf("高精度（推荐）", "稳定模式", "兼容模式"),
            engine.options.map { it.label.resolve(MenuLanguage.CHINESE) },
        )
        assertEquals(
            listOf(
                "High accuracy (recommended)",
                "Stable",
                "Compatibility mode",
            ),
            engine.options.map { it.label.resolve(MenuLanguage.ENGLISH) },
        )
    }

    @Test
    fun meteringAreaOffersCenterSpotAndAngleWithoutDuplicatingMethodLabel() {
        val metering = SettingsCatalog.sections.single { it.key == SettingsSectionKey.METERING }
        val area = metering.items.single { it.key == SettingKey.METERING_MODE }

        assertEquals("测光区域", area.label.resolve(MenuLanguage.CHINESE))
        assertEquals("Metering area", area.label.resolve(MenuLanguage.ENGLISH))
        assertEquals(
            listOf(
                MeteringMode.CENTER_WEIGHTED.name,
                MeteringMode.SPOT.name,
                MeteringMode.ANGLE.name,
            ),
            area.options.map { it.value },
        )
    }

    @Test
    fun meteringSectionOffersOptInExposurePreview() {
        val metering = SettingsCatalog.sections.single { it.key == SettingsSectionKey.METERING }
        val preview = metering.items.single { it.key == SettingKey.EXPOSURE_PREVIEW }

        assertEquals("曝光预览", preview.label.resolve(MenuLanguage.CHINESE))
        assertEquals("Exposure preview", preview.label.resolve(MenuLanguage.ENGLISH))
        assertEquals(
            listOf(ExposurePreviewMode.OFF.name, ExposurePreviewMode.ON.name),
            preview.options.map { it.value },
        )
    }

    @Test
    fun moreSectionOffersPreviewHealthInfoBarAndManualSafePreview() {
        val more = SettingsCatalog.sections.single { it.key == SettingsSectionKey.MORE }
        val health = more.items.single {
            it.key == SettingKey.PREVIEW_HEALTH_DETECTION
        }

        assertEquals(
            listOf(PreviewHealthDetectionMode.ON.name, PreviewHealthDetectionMode.OFF.name),
            health.options.map { it.value },
        )
        assertTrue(more.items.any { it.key == SettingKey.PREVIEW_INFORMATION_BAR })
        assertTrue(more.actions.any { it.key == SettingActionKey.USE_SAFE_PREVIEW })
        assertTrue(more.actions.any { it.key == SettingActionKey.MANAGE_OUTPUT_ASPECTS })
    }

    @Test
    fun cameraActionsFollowTheThreeExposureStepRows() {
        val metering = SettingsCatalog.sections.single { it.key == SettingsSectionKey.METERING }

        assertEquals(
            listOf(SettingActionKey.MANAGE_CAMERAS),
            metering.inlineActions.getValue(3).map { it.key },
        )
        assertEquals(listOf(SettingActionKey.SHOW_MORE_SETTINGS), metering.actions.map { it.key })
    }

    @Test
    fun oldCompatiblePreferenceMigratesToFastMode() {
        assertEquals(
            MeteringPipelineMode.FAST,
            MeteringPipelineMode.fromStored("COMPATIBLE"),
        )
        assertEquals(MeteringPipelineMode.AUTO, MeteringPipelineMode.fromStored("unknown"))
    }

    @Test
    fun calibrationKeepsAllHardwareSourcesIndependentOfRuntimeMode() {
        MeteringPipelineMode.entries.forEach { mode ->
            assertTrue(CalibrationStreamPolicy.includesRaw(mode, true))
        }
        MeteringPipelineMode.entries.forEach { mode ->
            assertEquals(false, CalibrationStreamPolicy.includesRaw(mode, false))
        }
    }


    @Test
    fun generalSectionExposesOnlyAboutAsItsInformationEntry() {
        val general = SettingsCatalog.sections.single { it.key == SettingsSectionKey.GENERAL }

        assertEquals(listOf(SettingActionKey.SHOW_ABOUT), general.actions.map { it.key })
    }

    @Test
    fun generalSectionOffersLowAndHighViewfinderFrameRates() {
        val general = SettingsCatalog.sections.single { it.key == SettingsSectionKey.GENERAL }
        val frameRate = general.items.single { it.key == SettingKey.PREVIEW_FRAME_RATE }

        assertEquals("取景帧率", frameRate.label.resolve(MenuLanguage.CHINESE))
        assertEquals("Viewfinder frame rate", frameRate.label.resolve(MenuLanguage.ENGLISH))
        assertEquals(
            listOf(PreviewFrameRateMode.LOW.name, PreviewFrameRateMode.HIGH.name),
            frameRate.options.map { it.value },
        )
        assertEquals(
            listOf("标准（最高 30 fps）", "流畅（最高 60 fps）"),
            frameRate.options.map { it.label.resolve(MenuLanguage.CHINESE) },
        )
        assertEquals(30, PreviewFrameRateMode.LOW.requestedCeiling)
        assertEquals(60, PreviewFrameRateMode.HIGH.requestedCeiling)
    }

    @Test
    fun informationActionsHaveBothLanguageLabels() {
        val actions = SettingsCatalog.sections
            .single { it.key == SettingsSectionKey.GENERAL }
            .actions

        actions.forEach { action ->
            assertTrue(action.label.resolve(MenuLanguage.CHINESE).isNotBlank())
            assertTrue(action.label.resolve(MenuLanguage.ENGLISH).isNotBlank())
        }
    }
}
