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
            listOf("高精度（推荐）", "稳定模式（YUV）", "兼容模式（ISP）"),
            engine.options.map { it.label.resolve(MenuLanguage.CHINESE) },
        )
        assertEquals(
            listOf(
                "High accuracy (recommended)",
                "Stable (YUV)",
                "Compatibility mode (ISP)",
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
    fun meteringSectionOffersPreviewHealthControlAndManualSafePreview() {
        val metering = SettingsCatalog.sections.single { it.key == SettingsSectionKey.METERING }
        val health = metering.items.single {
            it.key == SettingKey.PREVIEW_HEALTH_DETECTION
        }

        assertEquals(
            listOf(PreviewHealthDetectionMode.ON.name, PreviewHealthDetectionMode.OFF.name),
            health.options.map { it.value },
        )
        assertTrue(metering.actions.any { it.key == SettingActionKey.USE_SAFE_PREVIEW })
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
