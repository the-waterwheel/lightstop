package com.lightmeter.rawmeter

import android.content.Context
import android.os.Build

/** Where a persisted combination choice came from, so migration can treat it honestly. */
internal enum class CombinationSelectionOrigin {
    /** The user explicitly accepted this workflow. */
    MANUAL,

    /** The user accepted a manual probe that actually succeeded for this workflow. */
    MANUAL_VERIFIED,

    /** This build's real system probe accepted this workflow. */
    SYSTEM_PROBE,

    /** Written by an older build that had no origin; it may be an automatic downgrade. */
    LEGACY_AUTO,
}

/** A persisted combination choice with its origin and the actual route it was verified on. */
internal data class CombinationSelection(
    val planId: String,
    val origin: CombinationSelectionOrigin,
    /** Actual HAL route identity at verification time; null for legacy records. */
    val routeIdentity: String? = null,
)

/** Persists a human-approved workflow only for the same camera route and OS build. */
internal class CameraCombinationSelectionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun selectedPlan(cameraRouteId: String): CombinationSelection? =
        readSelection(cameraRouteId, systemMode = null)

    fun selectedSystemPlan(
        cameraRouteId: String,
        mode: MeteringPipelineMode,
    ): CombinationSelection? = readSelection(cameraRouteId, systemMode = mode)

    fun save(cameraRouteId: String, planId: String, routeIdentity: String) {
        writeSelection(
            cameraRouteId,
            systemMode = null,
            planId = planId,
            origin = CombinationSelectionOrigin.MANUAL,
            routeIdentity = routeIdentity,
        )
    }

    fun saveVerified(cameraRouteId: String, planId: String, routeIdentity: String) {
        writeSelection(
            cameraRouteId,
            systemMode = null,
            planId = planId,
            origin = CombinationSelectionOrigin.MANUAL_VERIFIED,
            routeIdentity = routeIdentity,
        )
    }

    fun saveSystem(
        cameraRouteId: String,
        mode: MeteringPipelineMode,
        planId: String,
        routeIdentity: String,
    ) {
        writeSelection(
            cameraRouteId,
            systemMode = mode,
            planId = planId,
            origin = CombinationSelectionOrigin.SYSTEM_PROBE,
            routeIdentity = routeIdentity,
        )
    }

    fun clear(cameraRouteId: String) {
        if (cameraRouteId.isBlank()) return
        val prefix = keyPrefix(cameraRouteId)
        preferences.edit()
            .remove("${prefix}_schema")
            .remove("${prefix}_fingerprint")
            .remove("${prefix}_plan")
            .remove("${prefix}_origin")
            .remove("${prefix}_route")
            .apply()
    }

    fun clearSystem(cameraRouteId: String, mode: MeteringPipelineMode) {
        if (cameraRouteId.isBlank()) return
        val prefix = systemPrefix(cameraRouteId, mode)
        preferences.edit()
            .remove("${prefix}_schema")
            .remove("${prefix}_fingerprint")
            .remove("${prefix}_plan")
            .remove("${prefix}_origin")
            .remove("${prefix}_route")
            .apply()
    }

    private fun readSelection(
        cameraRouteId: String,
        systemMode: MeteringPipelineMode?,
    ): CombinationSelection? {
        if (cameraRouteId.isBlank()) return null
        val prefix = systemMode?.let { systemPrefix(cameraRouteId, it) } ?: keyPrefix(cameraRouteId)
        if (preferences.getInt("${prefix}_schema", 0) != SCHEMA_VERSION) return null
        if (preferences.getString("${prefix}_fingerprint", null) != environmentKey()) return null
        val planId = preferences.getString("${prefix}_plan", null)?.takeIf(String::isNotBlank)
            ?: return null
        val origin = preferences.getString("${prefix}_origin", null)
            ?.let { runCatching { CombinationSelectionOrigin.valueOf(it) }.getOrNull() }
            ?: CombinationSelectionOrigin.LEGACY_AUTO
        val routeIdentity = preferences.getString("${prefix}_route", null)?.takeIf(String::isNotBlank)
        return CombinationSelection(planId, origin, routeIdentity)
    }

    private fun writeSelection(
        cameraRouteId: String,
        systemMode: MeteringPipelineMode?,
        planId: String,
        origin: CombinationSelectionOrigin,
        routeIdentity: String,
    ) {
        if (cameraRouteId.isBlank() || planId.isBlank()) return
        val prefix = systemMode?.let { systemPrefix(cameraRouteId, it) } ?: keyPrefix(cameraRouteId)
        preferences.edit()
            .putInt("${prefix}_schema", SCHEMA_VERSION)
            .putString("${prefix}_fingerprint", environmentKey())
            .putString("${prefix}_plan", planId)
            .putString("${prefix}_origin", origin.name)
            .putString("${prefix}_route", routeIdentity)
            .apply()
    }

    private fun environmentKey(): String = Build.FINGERPRINT + "|" + COMPATIBILITY_POLICY_VERSION

    private fun keyPrefix(cameraRouteId: String): String =
        "combination_${cameraRouteId.hashCode().toUInt().toString(16)}"

    private fun systemPrefix(cameraRouteId: String, mode: MeteringPipelineMode): String =
        "${keyPrefix(cameraRouteId)}_system_${mode.name.lowercase()}"

    private companion object {
        private const val PREFERENCES = "camera_combination_selection"
        private const val SCHEMA_VERSION = 1
        private const val COMPATIBILITY_POLICY_VERSION = 1
    }
}
