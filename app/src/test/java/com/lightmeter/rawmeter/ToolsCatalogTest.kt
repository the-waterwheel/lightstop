package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolsCatalogTest {
    @Test
    fun futureToolsKeepIdsButAreHiddenFromCurrentGrid() {
        val visibleIds = ToolsCatalog.tools.map(ToolSpec::id)

        assertEquals(
            listOf(
                ToolId.DEPTH_OF_FIELD,
                ToolId.LATITUDE,
                ToolId.PARAMETER_LOG,
                ToolId.RECIPROCITY,
                ToolId.COLOR_TEMPERATURE,
            ),
            visibleIds,
        )
        assertFalse(ToolId.FLASH_INDEX in visibleIds)
        assertFalse(ToolId.EXPOSURE_CORRECTION in visibleIds)
    }
}
