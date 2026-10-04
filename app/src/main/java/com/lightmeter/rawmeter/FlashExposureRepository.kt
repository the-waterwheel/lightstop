package com.lightmeter.rawmeter

import android.content.Context

internal class FlashExposureRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun selected(normalIso: Int): FlashConfiguration = read(CONFIG_PREFIX, normalIso)

    fun saveSelected(value: FlashConfiguration) {
        write(CONFIG_PREFIX, value.normalized())
    }

    fun applied(normalIso: Int): FlashConfiguration? = if (preferences.getBoolean(KEY_APPLIED, false)) {
        read(APPLIED_PREFIX, normalIso)
    } else {
        null
    }

    fun apply(value: FlashConfiguration) {
        val normalized = value.normalized()
        saveSelected(normalized)
        write(APPLIED_PREFIX, normalized)
        preferences.edit().putBoolean(KEY_APPLIED, true).apply()
    }

    fun clearApplied() {
        preferences.edit().putBoolean(KEY_APPLIED, false).apply()
    }

    private fun read(prefix: String, normalIso: Int): FlashConfiguration {
        val auto = preferences.getBoolean("${prefix}auto_distance", true)
        return FlashConfiguration(
            guideNumber = preferences.getFloat("${prefix}guide_number", 100f).toDouble(),
            guideNumberReferenceIso = preferences.getInt("${prefix}guide_number_reference_iso", 100),
            iso = preferences.getInt("${prefix}iso", normalIso),
            powerDenominator = preferences.getInt("${prefix}power_denominator", 1),
            lossStops = preferences.getFloat("${prefix}loss_stops", 0f).toDouble(),
            distanceMeters = if (auto) null else {
                preferences.getFloat("${prefix}distance_meters", 2f).toDouble()
            },
        ).normalized()
    }

    private fun write(prefix: String, value: FlashConfiguration) {
        preferences.edit()
            .putFloat("${prefix}guide_number", value.guideNumber.toFloat())
            .putInt("${prefix}guide_number_reference_iso", value.guideNumberReferenceIso)
            .putInt("${prefix}iso", value.iso)
            .putInt("${prefix}power_denominator", value.powerDenominator)
            .putFloat("${prefix}loss_stops", value.lossStops.toFloat())
            .putBoolean("${prefix}auto_distance", value.isAutoDistance)
            .putFloat("${prefix}distance_meters", (value.distanceMeters ?: 2.0).toFloat())
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "flash_exposure_state"
        const val KEY_APPLIED = "applied"
        const val CONFIG_PREFIX = "selected_"
        const val APPLIED_PREFIX = "applied_"
    }
}
