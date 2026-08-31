package com.lightmeter.rawmeter

import android.content.Context
import android.os.Build

/** Persists a human-approved workflow only for the same camera route and OS build. */
internal class CameraCombinationSelectionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun selectedPlanId(cameraRouteId: String): String? {
        if (cameraRouteId.isBlank()) return null
        val prefix = keyPrefix(cameraRouteId)
        if (preferences.getInt("${prefix}_schema", 0) != SCHEMA_VERSION) return null
        if (preferences.getString("${prefix}_fingerprint", null) != Build.FINGERPRINT) return null
        return preferences.getString("${prefix}_plan", null)?.takeIf(String::isNotBlank)
    }

    fun save(cameraRouteId: String, planId: String) {
        if (cameraRouteId.isBlank() || planId.isBlank()) return
        val prefix = keyPrefix(cameraRouteId)
        preferences.edit()
            .putInt("${prefix}_schema", SCHEMA_VERSION)
            .putString("${prefix}_fingerprint", Build.FINGERPRINT)
            .putString("${prefix}_plan", planId)
            .apply()
    }

    fun clear(cameraRouteId: String) {
        if (cameraRouteId.isBlank()) return
        val prefix = keyPrefix(cameraRouteId)
        preferences.edit()
            .remove("${prefix}_schema")
            .remove("${prefix}_fingerprint")
            .remove("${prefix}_plan")
            .apply()
    }

    fun selectedSystemPlanId(
        cameraRouteId: String,
        mode: MeteringPipelineMode,
    ): String? {
        if (cameraRouteId.isBlank()) return null
        val prefix = "${keyPrefix(cameraRouteId)}_system_${mode.name.lowercase()}"
        if (preferences.getInt("${prefix}_schema", 0) != SCHEMA_VERSION) return null
        if (preferences.getString("${prefix}_fingerprint", null) != Build.FINGERPRINT) return null
        return preferences.getString("${prefix}_plan", null)?.takeIf(String::isNotBlank)
    }

    fun saveSystem(
        cameraRouteId: String,
        mode: MeteringPipelineMode,
        planId: String,
    ) {
        if (cameraRouteId.isBlank() || planId.isBlank()) return
        val prefix = "${keyPrefix(cameraRouteId)}_system_${mode.name.lowercase()}"
        preferences.edit()
            .putInt("${prefix}_schema", SCHEMA_VERSION)
            .putString("${prefix}_fingerprint", Build.FINGERPRINT)
            .putString("${prefix}_plan", planId)
            .apply()
    }

    fun clearSystem(cameraRouteId: String, mode: MeteringPipelineMode) {
        if (cameraRouteId.isBlank()) return
        val prefix = "${keyPrefix(cameraRouteId)}_system_${mode.name.lowercase()}"
        preferences.edit()
            .remove("${prefix}_schema")
            .remove("${prefix}_fingerprint")
            .remove("${prefix}_plan")
            .apply()
    }

    private fun keyPrefix(cameraRouteId: String): String =
        "combination_${cameraRouteId.hashCode().toUInt().toString(16)}"

    private companion object {
        private const val PREFERENCES = "camera_combination_selection"
        private const val SCHEMA_VERSION = 1
    }
}
