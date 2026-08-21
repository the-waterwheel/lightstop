package com.lightmeter.rawmeter

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Owns app-private record files and an atomic JSON index. */
internal class ParameterRecordRepository(context: Context) {
    private val root = File(context.filesDir, "parameter_records").apply { mkdirs() }
    private val pending = File(context.cacheDir, "parameter_record_pending").apply { mkdirs() }
    private val index = AtomicFile(File(root, "index.json"))
    private val preferences = context.getSharedPreferences("parameter_record_settings", Context.MODE_PRIVATE)
    private var categories = loadCategories().toMutableList()
    var activeCategoryId: String? = preferences.getString(KEY_ACTIVE_CATEGORY, null)
        private set

    var options: ParameterRecordOptions
        get() = ParameterRecordOptions(
            recordGps = preferences.getBoolean(KEY_GPS, false),
            recordTime = preferences.getBoolean(KEY_TIME, true),
            recordRaw = preferences.getBoolean(KEY_RAW, false),
        )
        set(value) {
            preferences.edit()
                .putBoolean(KEY_GPS, value.recordGps)
                .putBoolean(KEY_TIME, value.recordTime)
                .putBoolean(KEY_RAW, value.recordRaw)
                .apply()
        }

    var suppressRawWarning: Boolean
        get() = preferences.getBoolean(KEY_RAW_WARNING, false)
        set(value) { preferences.edit().putBoolean(KEY_RAW_WARNING, value).apply() }

    @Synchronized
    fun categories(): List<ParameterRecordCategory> = categories.sortedByDescending { it.startedAtEpochMs }

    @Synchronized
    fun category(id: String?): ParameterRecordCategory? = categories.firstOrNull { it.id == id }

    fun record(categoryId: String, recordId: String): ParameterRecordEntry? =
        category(categoryId)?.records?.firstOrNull { it.id == recordId }

    fun startCategory(now: Long = System.currentTimeMillis()): ParameterRecordCategory {
        category(activeCategoryId)?.takeIf { it.endedAtEpochMs == null }?.let { return it }
        val category = ParameterRecordCategory(
            id = UUID.randomUUID().toString(),
            startedAtEpochMs = now,
            endedAtEpochMs = null,
            records = emptyList(),
        )
        categories += category
        activeCategoryId = category.id
        preferences.edit().putString(KEY_ACTIVE_CATEGORY, category.id).apply()
        saveIndex()
        return category
    }

    fun finishActiveCategory(now: Long = System.currentTimeMillis()): ParameterRecordCategory? {
        val id = activeCategoryId ?: return null
        val current = category(id) ?: return null
        val updated = current.copy(endedAtEpochMs = now)
        replaceCategory(updated)
        activeCategoryId = null
        preferences.edit().remove(KEY_ACTIVE_CATEGORY).apply()
        saveIndex()
        return updated
    }

    fun createPendingPreviewFile(id: String): File = File(pending, "$id.jpg")

    fun createPendingRawFile(id: String): File = File(pending, "$id.dng")

    fun save(draft: ParameterCaptureDraft): ParameterRecordEntry {
        val category = startCategory()
        val directory = File(root, category.id).apply { mkdirs() }
        val preview = moveInto(File(draft.previewTempPath), File(directory, "${draft.id}.jpg"))
        val raw = draft.rawTempPath?.let { source ->
            File(source).takeIf(File::exists)?.let { moveInto(it, File(directory, "${draft.id}.dng")) }
        }
        val entry = ParameterRecordEntry(
            id = draft.id,
            categoryId = category.id,
            capturedAtEpochMs = draft.capturedAtEpochMs,
            previewPath = preview.absolutePath,
            rawPath = raw?.absolutePath,
            mode = draft.snapshot.mode,
            apertureCoordinate = draft.snapshot.apertureCoordinate,
            shutterCoordinate = draft.snapshot.shutterCoordinate,
            ei = draft.snapshot.ei,
            ev100 = draft.snapshot.ev100,
            filmId = draft.filmId,
            filmName = draft.filmName,
            notes = draft.notes.take(MAX_NOTES),
            location = draft.location,
            zonePoints = draft.snapshot.zonePoints,
            rawGrid = draft.rawGrid,
        )
        val latestCategory = category(category.id) ?: category
        replaceCategory(latestCategory.copy(records = latestCategory.records + entry))
        saveIndex()
        return entry
    }

    fun discard(draft: ParameterCaptureDraft) {
        deletePending(draft.previewTempPath)
        draft.rawTempPath?.let(::deletePending)
    }

    fun updateRecord(updated: ParameterRecordEntry) {
        val category = category(updated.categoryId) ?: return
        if (category.records.none { it.id == updated.id }) return
        replaceCategory(
            category.copy(records = category.records.map { if (it.id == updated.id) updated else it }),
        )
        saveIndex()
    }

    /** Called only after the UI's destructive-delete confirmation. */
    fun deleteCategory(id: String): Boolean {
        val category = category(id) ?: return false
        val directory = File(root, category.id)
        val rootPath = root.canonicalFile.toPath()
        val directoryPath = directory.canonicalFile.toPath()
        if (!directoryPath.startsWith(rootPath) || directoryPath == rootPath) return false
        if (directory.exists() && !directory.deleteRecursively()) return false
        synchronized(this) {
            categories.removeAll { it.id == id }
            if (activeCategoryId == id) {
                activeCategoryId = null
                preferences.edit().remove(KEY_ACTIVE_CATEGORY).apply()
            }
            saveIndex()
        }
        return true
    }

    private fun moveInto(source: File, target: File): File {
        require(source.exists()) { "Pending record file is missing" }
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            source.inputStream().use { input -> target.outputStream().use(input::copyTo) }
            source.delete()
        }
        return target
    }

    private fun deletePending(path: String) {
        val file = File(path)
        runCatching {
            val pendingPath = pending.canonicalFile.toPath()
            val target = file.canonicalFile.toPath()
            if (target.startsWith(pendingPath) && target != pendingPath) file.delete()
        }
    }

    private fun replaceCategory(value: ParameterRecordCategory) {
        val index = categories.indexOfFirst { it.id == value.id }
        if (index >= 0) categories[index] = value else categories += value
    }

    private fun loadCategories(): List<ParameterRecordCategory> = try {
        val text = index.openRead().bufferedReader().use { it.readText() }
        val array = JSONObject(text).optJSONArray("categories") ?: JSONArray()
        buildList(array.length()) {
            for (i in 0 until array.length()) array.optJSONObject(i)?.toCategory()?.let(::add)
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun saveIndex() {
        val output = index.startWrite()
        try {
            val writer = output.bufferedWriter()
            val array = JSONArray()
            categories.forEach { array.put(it.toJson()) }
            writer.write(JSONObject().put("version", 1).put("categories", array).toString())
            writer.flush()
            index.finishWrite(output)
        } catch (error: Exception) {
            index.failWrite(output)
            throw error
        }
    }

    private fun ParameterRecordCategory.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("startedAt", startedAtEpochMs)
        .put("endedAt", endedAtEpochMs)
        .put("records", JSONArray().also { values -> records.forEach { values.put(it.toJson()) } })

    private fun ParameterRecordEntry.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("categoryId", categoryId)
        .put("capturedAt", capturedAtEpochMs)
        .put("previewPath", previewPath)
        .put("rawPath", rawPath)
        .put("mode", mode.name)
        .put("aperture", apertureCoordinate)
        .put("shutter", shutterCoordinate)
        .put("ei", ei)
        .put("ev100", ev100)
        .put("filmId", filmId)
        .put("filmName", filmName)
        .put("notes", JSONArray(notes))
        .put("location", location?.toJson())
        .put("zonePoints", JSONArray().also { values -> zonePoints.forEach { values.put(it.toJson()) } })
        .put("rawGrid", rawGrid?.toJson())

    private fun RecordedLocation.toJson(): JSONObject = JSONObject()
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("accuracy", accuracyMeters)

    private fun RecordedZonePoint.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("x", normalizedX.toDouble())
        .put("y", normalizedY.toDouble())
        .put("ev100", ev100)
        .put("source", source.name)

    private fun RecordedRawGrid.toJson(): JSONObject = JSONObject()
        .put("width", width)
        .put("height", height)
        .put("reference", referenceLuma.toDouble())
        .put("screenToSensorRotation", screenToSensorRotationDegrees)
        .put("cropLeft", cropLeft.toDouble())
        .put("cropTop", cropTop.toDouble())
        .put("cropRight", cropRight.toDouble())
        .put("cropBottom", cropBottom.toDouble())
        .put("values", JSONArray().also { array -> values.forEach { array.put(it.toDouble()) } })

    private fun JSONObject.toCategory(): ParameterRecordCategory? {
        val id = optString("id")
        if (id.isBlank()) return null
        val records = optJSONArray("records") ?: JSONArray()
        return ParameterRecordCategory(
            id = id,
            startedAtEpochMs = optLong("startedAt"),
            endedAtEpochMs = nullableLong("endedAt"),
            records = buildList(records.length()) {
                for (i in 0 until records.length()) records.optJSONObject(i)?.toRecord()?.let(::add)
            },
        )
    }

    private fun JSONObject.toRecord(): ParameterRecordEntry? {
        val id = optString("id")
        val categoryId = optString("categoryId")
        val preview = optString("previewPath")
        if (id.isBlank() || categoryId.isBlank() || preview.isBlank()) return null
        return ParameterRecordEntry(
            id = id,
            categoryId = categoryId,
            capturedAtEpochMs = nullableLong("capturedAt"),
            previewPath = preview,
            rawPath = nullableString("rawPath"),
            mode = runCatching { ParameterRecordMode.valueOf(optString("mode")) }.getOrDefault(ParameterRecordMode.NORMAL),
            apertureCoordinate = optDouble("aperture"),
            shutterCoordinate = optDouble("shutter"),
            ei = optInt("ei", 100),
            ev100 = nullableDouble("ev100"),
            filmId = nullableString("filmId"),
            filmName = nullableString("filmName"),
            notes = optJSONArray("notes").strings(MAX_NOTES),
            location = optJSONObject("location")?.toLocation(),
            zonePoints = optJSONArray("zonePoints").zonePoints(),
            rawGrid = optJSONObject("rawGrid")?.toRawGrid(),
        )
    }

    private fun JSONObject.toLocation(): RecordedLocation = RecordedLocation(
        optDouble("latitude"),
        optDouble("longitude"),
        nullableDouble("accuracy")?.toFloat(),
    )

    private fun JSONObject.toRawGrid(): RecordedRawGrid? {
        val width = optInt("width")
        val height = optInt("height")
        val values = optJSONArray("values") ?: return null
        if (width <= 0 || height <= 0 || values.length() != width * height) return null
        return RecordedRawGrid(
            width,
            height,
            FloatArray(values.length()) { values.optDouble(it).toFloat() },
            optDouble("reference").toFloat(),
            optInt("screenToSensorRotation", 0),
            optDouble("cropLeft", 0.0).toFloat(),
            optDouble("cropTop", 0.0).toFloat(),
            optDouble("cropRight", 1.0).toFloat(),
            optDouble("cropBottom", 1.0).toFloat(),
        )
    }

    private fun JSONArray?.strings(limit: Int): List<String> = if (this == null) emptyList() else buildList {
        for (i in 0 until minOf(length(), limit)) optString(i).takeIf(String::isNotBlank)?.let(::add)
    }

    private fun JSONArray?.zonePoints(): List<RecordedZonePoint> = if (this == null) emptyList() else buildList {
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            add(
                RecordedZonePoint(
                    id = item.optInt("id"),
                    normalizedX = item.optDouble("x", 0.5).toFloat(),
                    normalizedY = item.optDouble("y", 0.5).toFloat(),
                    ev100 = item.nullableDouble("ev100"),
                    source = runCatching { MeteringSource.valueOf(item.optString("source")) }.getOrDefault(MeteringSource.RAW),
                ),
            )
        }
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun JSONObject.nullableDouble(key: String): Double? =
        if (isNull(key) || !has(key)) null else optDouble(key).takeIf(Double::isFinite)

    private fun JSONObject.nullableLong(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key)

    private companion object {
        const val MAX_NOTES = 10
        const val KEY_ACTIVE_CATEGORY = "active_category"
        const val KEY_GPS = "record_gps"
        const val KEY_TIME = "record_time"
        const val KEY_RAW = "record_raw"
        const val KEY_RAW_WARNING = "suppress_raw_warning"
    }
}
