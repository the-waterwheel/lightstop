package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    @Test
    fun meteringSectionOffersAccuracyStableAndCompatibilityModes() {
        val metering = SettingsCatalog.sections.single { it.key == SettingsSectionKey.METERING }
        val engine = metering.items.single { it.key == SettingKey.METERING_PIPELINE }

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
            listOf("High accuracy (recommended)", "Stable", "Compatibility mode"),
            engine.options.map { it.label.resolve(MenuLanguage.ENGLISH) },
        )
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
    fun calibrationUsesRawInAccuracyAndStableButNeverCompatibilityMode() {
        assertTrue(CalibrationStreamPolicy.includesRaw(MeteringPipelineMode.AUTO, true))
        assertTrue(CalibrationStreamPolicy.includesRaw(MeteringPipelineMode.ISOLATED, true))
        assertEquals(
            false,
            CalibrationStreamPolicy.includesRaw(MeteringPipelineMode.FAST, true),
        )
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
