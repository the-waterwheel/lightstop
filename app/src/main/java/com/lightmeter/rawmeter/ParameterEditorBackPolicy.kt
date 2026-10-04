package com.lightmeter.rawmeter

/** What the back gesture should do with the parameter-record editor. */
internal enum class ParameterEditorBackAction {
    /** The editor is not open; back navigation continues to the next handler. */
    NOT_HANDLED,

    /** A save transaction owns the files; consume back without cancelling it. */
    CONSUME_SAVING,

    /** An open editor has no live draft; close it without touching pending files. */
    CLOSE_STALE,

    /** A live, non-saving draft may be cancelled and its pending files discarded. */
    CLOSE_AND_DISCARD,
}

/**
 * Pure decision for the parameter-record editor's back handling. Keeping it outside the View makes
 * the "stale editor keeps eating back" regression testable without Android.
 */
internal object ParameterEditorBackPolicy {
    fun decide(isOpen: Boolean, hasDraft: Boolean, saving: Boolean): ParameterEditorBackAction =
        when {
            !isOpen -> ParameterEditorBackAction.NOT_HANDLED
            !hasDraft -> ParameterEditorBackAction.CLOSE_STALE
            saving -> ParameterEditorBackAction.CONSUME_SAVING
            else -> ParameterEditorBackAction.CLOSE_AND_DISCARD
        }
}
