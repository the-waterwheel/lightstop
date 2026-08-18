package com.lightmeter.rawmeter

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal data class CalibrationEnvironmentChange(val eventId: String)

/**
 * Detects restore-to-device and camera-catalog changes without relying on a hardware identifier.
 *
 * The installation id lives in noBackupFilesDir, while the last observed id and camera signature
 * are backed up. A restore therefore produces a mismatch. Old calibration files remain on disk
 * for forensic safety, but a timestamp cutoff makes both active values and history unavailable.
 */
internal class CalibrationEnvironmentStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun evaluate(
        cameras: List<CameraDescriptor>,
        hasCalibrationArtifacts: Boolean,
    ): CalibrationEnvironmentChange? {
        // A temporarily unavailable camera service is not evidence that hardware changed.
        if (cameras.isEmpty()) return null
        val installationId = currentInstallationId()
        val signature = cameraEnvironmentSignature(cameras)
        val previousInstallationId = preferences.getString(KEY_INSTALLATION_ID, null)
        val previousSignature = preferences.getString(KEY_CAMERA_SIGNATURE, null)
        val pendingEventId = preferences.getString(KEY_PENDING_EVENT_ID, null)

        if (previousInstallationId == null || previousSignature == null) {
            preferences.edit()
                .putString(KEY_INSTALLATION_ID, installationId)
                .putString(KEY_CAMERA_SIGNATURE, signature)
                .apply()
            return null
        }
        if (previousInstallationId == installationId && previousSignature == signature) {
            return pendingEventId
                ?.takeUnless { preferences.getString(KEY_PROMPTED_EVENT_ID, null) == it }
                ?.let(::CalibrationEnvironmentChange)
        }

        val eventId = sha256(
            "$previousInstallationId|$previousSignature->$installationId|$signature",
        )
        val shouldPrompt = hasCalibrationArtifacts || pendingEventId != null
        preferences.edit().apply {
            putString(KEY_INSTALLATION_ID, installationId)
            putString(KEY_CAMERA_SIGNATURE, signature)
            if (hasCalibrationArtifacts) {
                val previousCutoff = preferences.getLong(KEY_MINIMUM_VALID_TIMESTAMP, 0L)
                putLong(
                    KEY_MINIMUM_VALID_TIMESTAMP,
                    maxOf(System.currentTimeMillis(), previousCutoff + 1L),
                )
            }
            if (shouldPrompt) putString(KEY_PENDING_EVENT_ID, eventId)
        }.apply()
        if (!shouldPrompt || preferences.getString(KEY_PROMPTED_EVENT_ID, null) == eventId) {
            return null
        }
        return CalibrationEnvironmentChange(eventId)
    }

    fun markPrompted(change: CalibrationEnvironmentChange) {
        preferences.edit()
            .putString(KEY_PROMPTED_EVENT_ID, change.eventId)
            .remove(KEY_PENDING_EVENT_ID)
            .apply()
    }

    private fun currentInstallationId(): String {
        val file = File(appContext.noBackupFilesDir, INSTALLATION_ID_FILE)
        val existing = runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull()
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        file.parentFile?.mkdirs()
        runCatching { file.writeText(generated, Charsets.UTF_8) }
        return generated
    }

    private fun cameraEnvironmentSignature(cameras: List<CameraDescriptor>): String {
        val payload = buildString {
            append(Build.MANUFACTURER).append('|')
            append(Build.MODEL).append('|')
            append(Build.DEVICE).append('|')
            append(Build.HARDWARE).append('|')
            cameras.asSequence()
                .filter { it.lensFacing != CameraCharacteristics.LENS_FACING_EXTERNAL }
                .sortedBy { it.cameraId }
                .forEach { camera ->
                    append(camera.cameraId).append(':')
                    append(camera.logicalCameraId).append(':')
                    append(camera.physicalCameraId.orEmpty()).append(':')
                    append(camera.lensFacing).append(':')
                    append(camera.rawAvailable).append(':')
                    append(camera.manualSensorAvailable).append(':')
                    append(camera.sensorOrientationDegrees).append(':')
                    append(camera.focalLengthsMm.joinToString(",")).append(':')
                    append(camera.apertures.joinToString(",")).append(';')
                }
        }
        return sha256(payload)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        private const val PREFERENCES = "calibration_environment"
        private const val INSTALLATION_ID_FILE = "calibration-installation-id"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val KEY_CAMERA_SIGNATURE = "camera_signature"
        private const val KEY_PROMPTED_EVENT_ID = "prompted_event_id"
        private const val KEY_PENDING_EVENT_ID = "pending_event_id"
        private const val KEY_MINIMUM_VALID_TIMESTAMP = "minimum_valid_timestamp"

        fun isCalibrationTimestampValid(context: Context, timestamp: Long): Boolean {
            val cutoff = context.applicationContext
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getLong(KEY_MINIMUM_VALID_TIMESTAMP, 0L)
            return cutoff <= 0L || timestamp >= cutoff
        }
    }
}
