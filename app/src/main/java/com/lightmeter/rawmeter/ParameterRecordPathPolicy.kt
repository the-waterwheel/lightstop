package com.lightmeter.rawmeter

import java.io.File
import java.util.UUID

/** Canonical-path checks for every destructive parameter-record file operation. */
internal object ParameterRecordPathPolicy {
    private val quarantineName = Regex(
        "^\\.delete-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})-" +
            "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$",
    )

    fun isIdentifier(value: String): Boolean = runCatching {
        UUID.fromString(value).toString() == value.lowercase()
    }.getOrDefault(false)

    fun categoryDirectory(root: File, categoryId: String): File? {
        if (!isIdentifier(categoryId)) return null
        val canonicalRoot = root.canonicalFile
        val directory = File(canonicalRoot, categoryId).canonicalFile
        return directory.takeIf { it.parentFile == canonicalRoot }
    }

    fun pendingFile(pendingRoot: File, recordId: String, extension: String): File? {
        if (!isIdentifier(recordId) || extension !in setOf("jpg", "dng")) return null
        val canonicalRoot = pendingRoot.canonicalFile
        val file = File(canonicalRoot, "$recordId.$extension").canonicalFile
        return file.takeIf { it.parentFile == canonicalRoot }
    }

    fun isDeleteQuarantine(root: File, candidate: File): Boolean {
        val canonicalRoot = root.canonicalFile
        val canonicalCandidate = candidate.canonicalFile
        return canonicalCandidate.isDirectory && canonicalCandidate.parentFile == canonicalRoot &&
            quarantineName.matches(canonicalCandidate.name)
    }

    fun isOwnedRecordFile(directory: File, candidate: File): Boolean {
        val canonicalDirectory = directory.canonicalFile
        val canonicalCandidate = candidate.canonicalFile
        if (!canonicalCandidate.isFile || canonicalCandidate.parentFile != canonicalDirectory) return false
        val separator = canonicalCandidate.name.lastIndexOf('.')
        if (separator <= 0) return false
        val id = canonicalCandidate.name.substring(0, separator)
        val extension = canonicalCandidate.name.substring(separator + 1)
        return isIdentifier(id) && extension in setOf("jpg", "dng")
    }

    fun validateCategoryContents(
        directory: File,
        category: ParameterRecordCategory,
    ): Set<File>? {
        val canonicalDirectory = directory.canonicalFile
        val expected = linkedSetOf<File>()
        for (record in category.records) {
            if (record.categoryId != category.id || !isIdentifier(record.id)) return null
            val preview = File(canonicalDirectory, "${record.id}.jpg").canonicalFile
            if (File(record.previewPath).canonicalFile != preview) return null
            expected += preview
            record.rawPath?.let { path ->
                val raw = File(canonicalDirectory, "${record.id}.dng").canonicalFile
                if (File(path).canonicalFile != raw) return null
                expected += raw
            }
        }
        val actual = canonicalDirectory.listFiles()?.mapTo(linkedSetOf()) { it.canonicalFile }.orEmpty()
        if (actual.any { it.parentFile != canonicalDirectory || it.isDirectory }) return null
        if (actual.any { it !in expected }) return null
        return expected
    }
}
