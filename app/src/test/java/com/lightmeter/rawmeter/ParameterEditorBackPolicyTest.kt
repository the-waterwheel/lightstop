package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test

class ParameterEditorBackPolicyTest {
    @Test
    fun `closed editor does not consume back`() {
        assertEquals(
            ParameterEditorBackAction.NOT_HANDLED,
            ParameterEditorBackPolicy.decide(isOpen = false, hasDraft = false, saving = false),
        )
        assertEquals(
            ParameterEditorBackAction.NOT_HANDLED,
            ParameterEditorBackPolicy.decide(isOpen = false, hasDraft = true, saving = true),
        )
    }

    @Test
    fun `stale open editor closes without touching files`() {
        assertEquals(
            ParameterEditorBackAction.CLOSE_STALE,
            ParameterEditorBackPolicy.decide(isOpen = true, hasDraft = false, saving = false),
        )
    }

    @Test
    fun `saving consumes back without cancelling`() {
        assertEquals(
            ParameterEditorBackAction.CONSUME_SAVING,
            ParameterEditorBackPolicy.decide(isOpen = true, hasDraft = true, saving = true),
        )
    }

    @Test
    fun `live draft is cancelled and discarded`() {
        assertEquals(
            ParameterEditorBackAction.CLOSE_AND_DISCARD,
            ParameterEditorBackPolicy.decide(isOpen = true, hasDraft = true, saving = false),
        )
    }
}
