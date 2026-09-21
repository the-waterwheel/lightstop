package com.lightmeter.rawmeter

/**
 * Pure bookkeeping for one parameter-record capture and its save. It prevents a stale background
 * completion from reopening an editor after the user cancelled, and prevents a duplicate save from
 * a double tap.
 */
internal class ParameterCaptureGuard {
    private var generation = 0
    private var activeDraftId: String? = null
    private var saving = false

    val isActive: Boolean get() = activeDraftId != null
    val isSaving: Boolean get() = saving

    fun begin(draftId: String): Int {
        generation += 1
        activeDraftId = draftId
        saving = false
        return generation
    }

    fun isCurrent(token: Int): Boolean = token == generation && activeDraftId != null

    /** Reserves the save slot; returns false when a save is already in flight. */
    fun beginSave(token: Int): Boolean {
        if (token != generation || activeDraftId == null || saving) return false
        saving = true
        return true
    }

    fun finishSave() {
        saving = false
    }

    fun complete() {
        generation += 1
        activeDraftId = null
        saving = false
    }

    fun cancel() {
        complete()
    }
}
