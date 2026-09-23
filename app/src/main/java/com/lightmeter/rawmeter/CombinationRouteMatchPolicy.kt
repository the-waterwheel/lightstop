package com.lightmeter.rawmeter

/**
 * Pure route-matching rule for a persisted combination selection.
 *
 * A confirmation recorded on path A must not be inherited when the same lens is reached through a
 * different transport route or output configuration (path B). Legacy records carry no route
 * identity and are therefore never treated as already verified for the current path.
 */
internal object CombinationRouteMatchPolicy {
    /** Every system cache needs a real probe credential for the current route, including old RAW. */
    fun needsSystemRevalidation(selection: CombinationSelection?, currentRouteId: String): Boolean =
        selection != null &&
            (selection.origin != CombinationSelectionOrigin.SYSTEM_PROBE ||
                !matches(selection, currentRouteId))

    fun matches(selection: CombinationSelection?, currentRouteId: String): Boolean =
        selection != null &&
            selection.routeIdentity != null &&
            selection.routeIdentity == currentRouteId
}
