package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolsCatalogTest {
    @Test
    fun currentToolsExposeFlashButKeepFutureExposureCorrectionHidden() {
        val visibleIds = ToolsCatalog.tools.map(ToolSpec::id)

        assertEquals(
            listOf(
                ToolId.DEPTH_OF_FIELD,
                ToolId.LATITUDE,
                ToolId.PARAMETER_LOG,
                ToolId.RECIPROCITY,
                ToolId.FLASH_INDEX,
                ToolId.COLOR_TEMPERATURE,
            ),
            visibleIds,
        )
        assertFalse(ToolId.EXPOSURE_CORRECTION in visibleIds)
    }
}
