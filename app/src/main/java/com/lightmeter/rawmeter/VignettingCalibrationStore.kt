package com.lightmeter.rawmeter

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Base64
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class VignettingCalibrationMap(
    val gridWidth: Int,
    val gridHeight: Int,
    val activeLeft: Float,
    val activeTop: Float,
    val activeRight: Float,
    val activeBottom: Float,
    val gains: FloatArray,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    /** RAW route/output signature; null for pre-signature files. */
    val signature: CalibrationSignature? = null,
) {
    init {
        require(gridWidth >= 2 && gridHeight >= 2)
        require(gains.size == gridWidth * gridHeight)
    }

    val maximumGain: Float get() = gains.maxOrNull() ?: 1f
    val minimumGain: Float get() = gains.minOrNull() ?: 1f
}

data class VignettingCalibrationInfo(
    val gridWidth: Int,
    val gridHeight: Int,
    val minimumGain: Float,
    val maximumGain: Float,
    val createdAtEpochMs: Long,
    val previewWidth: Int,
    val previewHeight: Int,
    val previewGrayscale: ByteArray,
)

internal class VignettingCalibrationStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, DIRECTORY_NAME)
    private val cache = mutableMapOf<String, VignettingCalibrationMap>()
    private val historyInfoCache = mutableMapOf<String, List<VignettingCalibrationInfo>>()

    @Volatile
    private var activeContext: CalibrationCaptureContext? = null

    fun setActiveContext(context: CalibrationCaptureContext?) {
        activeContext = context
    }

    /** Whether the stored vignetting map describes the active RAW route/output. */
    @Synchronized
    fun signatureState(cameraId: String): CalibrationSignatureState {
        val stored = load(cameraId)?.signature
        val storedPresent = load(cameraId) != null
        val current = activeContext?.vignettingSignature()
            ?: stored?.copy(
                buildFingerprintHash = CalibrationEnvironmentStore.buildFingerprintHash(),
            )
            ?: return if (storedPresent) {
                CalibrationSignatureState.LEGACY_UNVERIFIED
            } else {
                CalibrationSignatureState.NONE
            }
        return CalibrationSignaturePolicy.classify(stored, storedPresent, current)
    }

    @Synchronized
    fun save(cameraId: String, calibration: VignettingCalibrationMap) {
        if (cameraId.isBlank()) return
        directory.mkdirs()
        val stamped = calibration.copy(signature = activeContext?.vignettingSignature())
        val previous = historyMaps(cameraId)
        val updatedHistory = (listOf(stamped) + previous)
            .distinctBy { it.createdAtEpochMs }
            .take(HISTORY_LIMIT)
        writeMap(fileFor(cameraId), stamped)
        updatedHistory.forEachIndexed { index, map ->
            writeMap(historyFileFor(cameraId, index), map)
        }
        (updatedHistory.size until HISTORY_LIMIT).forEach { index ->
            historyFileFor(cameraId, index).delete()
        }
        cache[cameraId] = stamped
        historyInfoCache[cameraId] = updatedHistory.map(::toInfo)
    }

    private fun writeMap(destination: File, calibration: VignettingCalibrationMap) {
        val temporary = File(directory, "${destination.name}.tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
            output.writeInt(FILE_MAGIC)
            output.writeInt(FILE_VERSION)
            output.writeInt(calibration.gridWidth)
            output.writeInt(calibration.gridHeight)
            output.writeFloat(calibration.activeLeft)
            output.writeFloat(calibration.activeTop)
            output.writeFloat(calibration.activeRight)
            output.writeFloat(calibration.activeBottom)
            output.writeLong(calibration.createdAtEpochMs)
            calibration.gains.forEach(output::writeFloat)
            output.writeUTF(calibration.signature?.serialize() ?: "")
        }
        if (!temporary.renameTo(destination)) {
            FileInputStream(temporary).use { input ->
                FileOutputStream(destination).use(input::copyTo)
            }
            temporary.delete()
        }
    }

    @Synchronized
    fun load(cameraId: String): VignettingCalibrationMap? {
        if (cameraId.isBlank()) return null
        cache[cameraId]?.let { cached ->
            if (isValid(cached.createdAtEpochMs)) return cached
            cache.remove(cameraId)
        }
        val file = fileFor(cameraId)
        val calibration = readMap(file)
        calibration?.let { cache[cameraId] = it }
        return calibration
    }

    private fun readMap(file: File): VignettingCalibrationMap? {
        if (!file.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != FILE_MAGIC) return null
                val version = input.readInt()
                if (version != 1 && version != FILE_VERSION) return null
                val width = input.readInt()
                val height = input.readInt()
                if (width !in 2..MAX_GRID_EDGE || height !in 2..MAX_GRID_EDGE) return null
                val left = input.readFloat()
                val top = input.readFloat()
                val right = input.readFloat()
                val bottom = input.readFloat()
                val createdAt = input.readLong()
                if (!isValid(createdAt)) return null
                val gains = FloatArray(width * height) { input.readFloat() }
                if (gains.any { !it.isFinite() || it !in MIN_STORED_GAIN..MAX_STORED_GAIN } ||
                    left !in 0f..1f || top !in 0f..1f || right !in 0f..1f ||
                    bottom !in 0f..1f || right <= left || bottom <= top
                ) return null
                val signature = if (version >= 2) {
                    CalibrationSignature.parse(input.readUTF())
                } else {
                    null
                }
                VignettingCalibrationMap(
                    width,
                    height,
                    left,
                    top,
                    right,
                    bottom,
                    gains,
                    createdAt,
                    signature,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun history(cameraId: String): List<VignettingCalibrationInfo> {
        historyInfoCache[cameraId]?.let { cached ->
            val valid = cached.filter { isValid(it.createdAtEpochMs) }
            if (valid.size == cached.size) return valid
            historyInfoCache[cameraId] = valid
        }
        return historyMaps(cameraId).map(::toInfo).also { historyInfoCache[cameraId] = it }
    }

    fun hasCalibrationArtifacts(): Boolean =
        directory.listFiles()?.any { it.isFile && it.name.startsWith("vignette_") } == true

    @Synchronized
    fun restore(cameraId: String, createdAtEpochMs: Long): VignettingCalibrationInfo? {
        val selected = historyMaps(cameraId).firstOrNull {
            it.createdAtEpochMs == createdAtEpochMs
        } ?: return null
        directory.mkdirs()
        writeMap(fileFor(cameraId), selected)
        cache[cameraId] = selected
        return toInfo(selected)
    }

    @Synchronized
    fun reset(cameraId: String) {
        if (cameraId.isBlank()) return
        val retainedHistory = historyMaps(cameraId).take(HISTORY_LIMIT)
        directory.mkdirs()
        retainedHistory.forEachIndexed { index, map ->
            writeMap(historyFileFor(cameraId, index), map)
        }
        cache.remove(cameraId)
        historyInfoCache[cameraId] = retainedHistory.map(::toInfo)
        fileFor(cameraId).delete()
    }

    @Synchronized
    fun invalidate(cameraId: String) {
        cache.remove(cameraId)
        historyInfoCache.remove(cameraId)
    }

    @Synchronized
    fun info(cameraId: String): VignettingCalibrationInfo? = load(cameraId)?.let { map ->
        history(cameraId).firstOrNull { it.createdAtEpochMs == map.createdAtEpochMs }
            ?: toInfo(map)
    }

    private fun toInfo(map: VignettingCalibrationMap): VignettingCalibrationInfo {
        val grayscale = ByteArray(map.gains.size) { index ->
            val relativeBrightness = (1f / map.gains[index]).coerceIn(0f, 1f)
            val displayValue = (relativeBrightness.pow(1f / 2.2f) * 255f)
                .roundToInt()
                .coerceIn(0, 255)
            displayValue.toByte()
        }
        return VignettingCalibrationInfo(
            gridWidth = map.gridWidth,
            gridHeight = map.gridHeight,
            minimumGain = map.minimumGain,
            maximumGain = map.maximumGain,
            createdAtEpochMs = map.createdAtEpochMs,
            previewWidth = map.gridWidth,
            previewHeight = map.gridHeight,
            previewGrayscale = grayscale,
        )
    }

    private fun historyMaps(cameraId: String): List<VignettingCalibrationMap> {
        if (cameraId.isBlank()) return emptyList()
        val stored = (0 until HISTORY_LIMIT).mapNotNull { index ->
            readMap(historyFileFor(cameraId, index))
        }
        if (stored.isNotEmpty()) return stored.sortedByDescending { it.createdAtEpochMs }
        return listOfNotNull(load(cameraId))
    }

    private fun isValid(timestamp: Long): Boolean =
        CalibrationEnvironmentStore.isCalibrationTimestampValid(appContext, timestamp)

    fun gainAt(
        cameraId: String,
        sensorX: Float,
        sensorY: Float,
        imageWidth: Int,
        imageHeight: Int,
    ): Double {
        val map = load(cameraId) ?: return 1.0
        if (activeContext != null && !signatureState(cameraId).isApplicable) return 1.0
        if (imageWidth <= 0 || imageHeight <= 0) return 1.0
        val imageX = sensorX / imageWidth
        val imageY = sensorY / imageHeight
        val normalizedX = ((imageX - map.activeLeft) / (map.activeRight - map.activeLeft))
            .coerceIn(0f, 1f)
        val normalizedY = ((imageY - map.activeTop) / (map.activeBottom - map.activeTop))
            .coerceIn(0f, 1f)
        val gridX = normalizedX * (map.gridWidth - 1)
        val gridY = normalizedY * (map.gridHeight - 1)
        val left = floor(gridX).toInt().coerceIn(0, map.gridWidth - 1)
        val top = floor(gridY).toInt().coerceIn(0, map.gridHeight - 1)
        val right = (left + 1).coerceAtMost(map.gridWidth - 1)
        val bottom = (top + 1).coerceAtMost(map.gridHeight - 1)
        val fractionX = gridX - left
        val fractionY = gridY - top
        fun gain(column: Int, row: Int): Float = map.gains[row * map.gridWidth + column]
        val topGain = gain(left, top) * (1f - fractionX) + gain(right, top) * fractionX
        val bottomGain =
            gain(left, bottom) * (1f - fractionX) + gain(right, bottom) * fractionX
        return (topGain * (1f - fractionY) + bottomGain * fractionY)
            .coerceIn(MIN_APPLIED_GAIN, MAX_APPLIED_GAIN)
            .toDouble()
    }

    private fun fileFor(cameraId: String): File {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(cameraId.toByteArray(Charsets.UTF_8))
        return File(directory, "vignette_$encoded.bin")
    }

    private fun historyFileFor(cameraId: String, index: Int): File {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(cameraId.toByteArray(Charsets.UTF_8))
        return File(directory, "vignette_${encoded}_history_$index.bin")
    }

    private companion object {
        private const val DIRECTORY_NAME = "vignetting-calibration"
        private const val HISTORY_LIMIT = 3
        private const val FILE_MAGIC = 0x5649474E
        private const val FILE_VERSION = 2
        private const val MAX_GRID_EDGE = 256
        private const val MIN_STORED_GAIN = 0.25f
        private const val MAX_STORED_GAIN = 6f
        private const val MIN_APPLIED_GAIN = 0.5f
        private const val MAX_APPLIED_GAIN = 4f
    }
}
