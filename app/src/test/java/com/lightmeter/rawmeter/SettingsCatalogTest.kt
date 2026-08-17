package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    @Test
    fun meteringSectionOffersAccuracyCompatibilityAndFastEngines() {
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
