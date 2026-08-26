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
    private val transactions = ParameterRecordTransaction(root)
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

    val needsPrivacyNotice: Boolean
        get() = preferences.getInt(KEY_PRIVACY_NOTICE_VERSION, 0) < PRIVACY_NOTICE_VERSION

    fun acknowledgePrivacyNotice() {
        preferences.edit().putInt(KEY_PRIVACY_NOTICE_VERSION, PRIVACY_NOTICE_VERSION).apply()
    }

    init {
        transactions.recover { categoryId, recordId ->
            categories.any { category ->
                category.id == categoryId && category.records.any { record -> record.id == recordId }
            }
        }
        cleanupQuarantinedDeletes()
    }

    @Synchronized
    fun categories(): List<ParameterRecordCategory> = categories
        .asSequence()
        .filter { it.records.isNotEmpty() }
        .sortedByDescending { it.startedAtEpochMs }
        .toList()

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
        if (current.records.isEmpty()) {
            synchronized(this) { categories.removeAll { it.id == id } }
            activeCategoryId = null
            preferences.edit().remove(KEY_ACTIVE_CATEGORY).apply()
            saveIndex()
            return null
        }
        val updated = current.copy(endedAtEpochMs = now)
        replaceCategory(updated)
        activeCategoryId = null
        preferences.edit().remove(KEY_ACTIVE_CATEGORY).apply()
        saveIndex()
        return updated
    }

    fun createPendingPreviewFile(id: String): File =
        requireNotNull(ParameterRecordPathPolicy.pendingFile(pending, id, "jpg"))

    fun createPendingRawFile(id: String): File =
        requireNotNull(ParameterRecordPathPolicy.pendingFile(pending, id, "dng"))

    fun applyActiveCategoryDefaults(draft: ParameterCaptureDraft): ParameterCaptureDraft {
        val selection = category(activeCategoryId)?.defaultFilm ?: return draft
        return draft.copy(
            filmId = selection.id,
            filmName = selection.name,
            filmIso = selection.iso,
            snapshot = selection.iso?.let { draft.snapshot.copy(ei = it) } ?: draft.snapshot,
        )
    }

    fun save(draft: ParameterCaptureDraft): ParameterRecordEntry {
        val category = startCategory()
        require(ParameterRecordPathPolicy.isIdentifier(draft.id)) { "Invalid parameter-record id" }
        val directory = requireNotNull(ParameterRecordPathPolicy.categoryDirectory(root, category.id))
            .apply { mkdirs() }
        val expectedPreview = requireNotNull(ParameterRecordPathPolicy.pendingFile(pending, draft.id, "jpg"))
        require(File(draft.previewTempPath).canonicalFile == expectedPreview) { "Preview is outside the pending record directory" }
        val marker = transactions.begin(category.id, draft.id)
        val previousCategories = categories.toMutableList()
        val movedFiles = mutableListOf<ParameterRecordTransaction.MovedFile>()
        try {
            val previewTarget = File(directory, "${draft.id}.jpg")
            val preview = moveInto(File(draft.previewTempPath), previewTarget)
            movedFiles += ParameterRecordTransaction.MovedFile(preview, expectedPreview)
            val raw = draft.rawTempPath?.let { source ->
                val expectedRaw = requireNotNull(ParameterRecordPathPolicy.pendingFile(pending, draft.id, "dng"))
                require(File(source).canonicalFile == expectedRaw) { "RAW is outside the pending record directory" }
                File(source).takeIf(File::exists)?.let { rawFile ->
                    val target = File(directory, "${draft.id}.dng")
                    moveInto(rawFile, target).also { moved ->
                        movedFiles += ParameterRecordTransaction.MovedFile(moved, expectedRaw)
                    }
                }
            }
            val entry = ParameterRecordEntry(
                id = draft.id,
                categoryId = category.id,
                capturedAtEpochMs = draft.capturedAtEpochMs,
                previewPath = preview.absolutePath,
                rawPath = raw?.absolutePath,
                cameraId = draft.cameraId,
                mode = draft.snapshot.mode,
                apertureCoordinate = draft.snapshot.apertureCoordinate,
                shutterCoordinate = draft.snapshot.shutterCoordinate,
                ei = draft.snapshot.ei,
                ev100 = draft.snapshot.ev100,
                filmId = draft.filmId,
                filmName = draft.filmName,
                filmIso = draft.filmIso,
                notes = draft.notes.take(MAX_NOTES),
                location = draft.location,
                zonePoints = draft.snapshot.zonePoints,
                rawGrid = draft.rawGrid,
            )
            val latestCategory = category(category.id) ?: category
            replaceCategory(latestCategory.copy(records = latestCategory.records + entry))
            saveIndex()
            transactions.complete(marker)
            return entry
        } catch (error: Exception) {
            categories = previousCategories
            if (transactions.rollback(movedFiles)) transactions.complete(marker)
            throw error
        }
    }

    fun discard(draft: ParameterCaptureDraft) {
        deletePending(draft.previewTempPath)
        draft.rawTempPath?.let(::deletePending)
    }

    /** The history screen may edit RAW-derived points, but never captured exposure or file paths. */
    fun updateZonePoints(
        categoryId: String,
        recordId: String,
        points: List<RecordedZonePoint>,
    ): ParameterRecordEntry? {
        val category = category(categoryId) ?: return null
        val existing = category.records.firstOrNull { it.id == recordId } ?: return null
        if (existing.rawPath == null || existing.rawGrid == null) return null
        val validated = points
            .take(MAX_RAW_POINTS)
            .takeIf { values ->
                values.map(RecordedZonePoint::id).distinct().size == values.size &&
                    values.all { point ->
                        point.id > 0 && point.normalizedX.isFinite() && point.normalizedX in 0f..1f &&
                            point.normalizedY.isFinite() && point.normalizedY in 0f..1f &&
                            (point.ev100 == null || point.ev100.isFinite())
                    }
            } ?: return null
        val updated = existing.copy(zonePoints = validated)
        replaceCategory(
            category.copy(records = category.records.map { if (it.id == recordId) updated else it }),
        )
        saveIndex()
        return updated
    }

    /** Called only after confirmation; refuses unknown files, subdirectories, or path escapes. */
    @Synchronized
    fun deleteCategory(id: String): Boolean {
        val category = category(id) ?: return false
        val directory = ParameterRecordPathPolicy.categoryDirectory(root, category.id) ?: return false
        if (directory.exists() && ParameterRecordPathPolicy.validateCategoryContents(directory, category) == null) {
            return false
        }
        val quarantine = File(root.canonicalFile, ".delete-${category.id}-${UUID.randomUUID()}").canonicalFile
        if (quarantine.parentFile != root.canonicalFile) return false
        if (directory.exists() && !directory.renameTo(quarantine)) return false
        val previousCategories = categories.toList()
        val previousActive = activeCategoryId
        return try {
            categories.removeAll { it.id == id }
            if (previousActive == id) activeCategoryId = null
            persistActiveCategory()
            saveIndex()
            deleteQuarantine(quarantine)
            true
        } catch (_: Exception) {
            categories = previousCategories.toMutableList()
            activeCategoryId = previousActive
            persistActiveCategory()
            if (quarantine.exists() && !directory.exists()) quarantine.renameTo(directory)
            false
        }
    }

    private fun moveInto(source: File, target: File): File {
        require(source.exists()) { "Pending record file is missing" }
        require(!target.exists()) { "A parameter-record file with this id already exists" }
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
            val target = file.canonicalFile
            val name = target.name
            val separator = name.lastIndexOf('.')
            if (separator <= 0) return@runCatching
            val expected = ParameterRecordPathPolicy.pendingFile(
                pending,
                name.substring(0, separator),
                name.substring(separator + 1),
            )
            if (expected == target) target.delete()
        }
    }

    private fun persistActiveCategory() {
        val editor = preferences.edit()
        if (activeCategoryId == null) editor.remove(KEY_ACTIVE_CATEGORY)
        else editor.putString(KEY_ACTIVE_CATEGORY, activeCategoryId)
        editor.apply()
    }

    private fun cleanupQuarantinedDeletes() {
        root.listFiles().orEmpty()
            .filter { ParameterRecordPathPolicy.isDeleteQuarantine(root, it) }
            .forEach(::deleteQuarantine)
    }

    private fun deleteQuarantine(directory: File) {
        if (!directory.exists() || directory.parentFile?.canonicalFile != root.canonicalFile) return
        val children = directory.listFiles() ?: return
        if (children.any { !ParameterRecordPathPolicy.isOwnedRecordFile(directory, it) }) return
        children.forEach { it.delete() }
        if (directory.listFiles().orEmpty().isEmpty()) directory.delete()
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
            writer.write(JSONObject().put("version", 2).put("categories", array).toString())
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
        .put("cameraId", cameraId)
        .put("mode", mode.name)
        .put("aperture", apertureCoordinate)
        .put("shutter", shutterCoordinate)
        .put("ei", ei)
        .put("ev100", ev100)
        .put("filmId", filmId)
        .put("filmName", filmName)
        .put("filmIso", filmIso)
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
            cameraId = nullableString("cameraId"),
            mode = runCatching { ParameterRecordMode.valueOf(optString("mode")) }.getOrDefault(ParameterRecordMode.NORMAL),
            apertureCoordinate = optDouble("aperture"),
            shutterCoordinate = optDouble("shutter"),
            ei = optInt("ei", 100),
            ev100 = nullableDouble("ev100"),
            filmId = nullableString("filmId"),
            filmName = nullableString("filmName"),
            filmIso = nullableInt("filmIso"),
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

    private fun JSONObject.nullableInt(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key).takeIf { it > 0 }

    private companion object {
        const val MAX_NOTES = 10
        const val MAX_RAW_POINTS = 100
        const val KEY_ACTIVE_CATEGORY = "active_category"
        const val KEY_PRIVACY_NOTICE_VERSION = "parameter_record_privacy_notice_version"
        const val KEY_GPS = "record_gps"
        const val KEY_TIME = "record_time"
        const val KEY_RAW = "record_raw"
        const val KEY_RAW_WARNING = "suppress_raw_warning"
        const val PRIVACY_NOTICE_VERSION = 1
    }
}
