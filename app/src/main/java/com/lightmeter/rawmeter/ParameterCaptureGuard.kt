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

    fun isCurrent(token: Int, draftId: String? = null): Boolean =
        token == generation && activeDraftId != null && (draftId == null || activeDraftId == draftId)

    /** Reserves the save slot; returns false when a save is already in flight. */
    fun beginSave(token: Int, draftId: String): Boolean {
        if (!isCurrent(token, draftId) || saving) return false
        saving = true
        return true
    }

    /** Returns false for a stale completion so it cannot unlock a newer draft. */
    fun finishSave(token: Int, draftId: String): Boolean {
        if (!isCurrent(token, draftId) || !saving) return false
        saving = false
        return true
    }

    /** Completes only the draft which reserved the save slot. */
    fun complete(token: Int, draftId: String): Boolean {
        if (!isCurrent(token, draftId) || !saving) return false
        generation += 1
        activeDraftId = null
        saving = false
        return true
    }

    /** Saving is intentionally non-cancellable: its file transaction owns the pending files. */
    fun cancel(token: Int, draftId: String): Boolean {
        if (!isCurrent(token, draftId) || saving) return false
        generation += 1
        activeDraftId = null
        return true
    }

    fun cancel() {
        if (!saving) {
            generation += 1
            activeDraftId = null
        }
    }
}
