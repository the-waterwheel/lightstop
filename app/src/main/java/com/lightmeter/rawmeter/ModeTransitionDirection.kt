package com.lightmeter.rawmeter

/**
 * Keeps the horizontal Normal/Zone transition mirrored with the handed layout.
 *
 * In landscape, the transition handles sit on opposite sides for left- and right-handed users.
 * A positive distance always means that the user is dragging toward the requested mode, regardless
 * of the physical screen direction.
 */
internal object ModeTransitionDirection {
    fun zoneEntrySign(isLeftHanded: Boolean): Float = if (isLeftHanded) 1f else -1f

    fun normalEntrySign(isLeftHanded: Boolean): Float = -zoneEntrySign(isLeftHanded)

    fun zoneEntryDistance(startX: Float, currentX: Float, isLeftHanded: Boolean): Float =
        (currentX - startX) * zoneEntrySign(isLeftHanded)

    fun normalEntryDistance(startX: Float, currentX: Float, isLeftHanded: Boolean): Float =
        (currentX - startX) * normalEntrySign(isLeftHanded)
}
