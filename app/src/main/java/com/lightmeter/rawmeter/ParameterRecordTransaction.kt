package com.lightmeter.rawmeter

import java.io.File
import java.util.UUID

/**
 * Crash-recovery journal for one parameter-record file move and index commit. A marker exists
 * before any pending JPEG/DNG is moved. On next launch a marker whose record is absent from the
 * atomic index authorizes deletion of only that exact record's final files; a committed marker is
 * simply removed. This keeps cleanup narrowly scoped and never scans unrelated app files.
 */
internal class ParameterRecordTransaction(private val root: File) {
    data class MovedFile(val finalFile: File, val pendingFile: File)

    fun begin(categoryId: String, recordId: String): File {
        require(ParameterRecordPathPolicy.isIdentifier(categoryId)) { "Invalid category id" }
        require(ParameterRecordPathPolicy.isIdentifier(recordId)) { "Invalid record id" }
        val marker = File(root, "$MARKER_PREFIX$recordId.json").canonicalFile
        require(marker.parentFile == root.canonicalFile) { "Transaction marker escaped root" }
        val temporary = File(root, "$MARKER_PREFIX$recordId.${UUID.randomUUID()}.tmp").canonicalFile
        require(temporary.parentFile == root.canonicalFile) { "Transaction temporary escaped root" }
        temporary.writer().use { writer ->
            writer.write("$categoryId\n$recordId\n")
            writer.flush()
        }
        if (!temporary.renameTo(marker)) {
            temporary.inputStream().use { input -> marker.outputStream().use(input::copyTo) }
            temporary.delete()
        }
        return marker
    }

    fun complete(marker: File) {
        if (marker.parentFile?.canonicalFile == root.canonicalFile) marker.delete()
    }

    fun rollback(movedFiles: List<MovedFile>): Boolean = movedFiles.asReversed().all { moved ->
        if (!moved.finalFile.exists()) return@all true
        moved.pendingFile.parentFile?.mkdirs()
        if (moved.finalFile.renameTo(moved.pendingFile)) {
            true
        } else {
            runCatching {
                moved.finalFile.inputStream().use { input ->
                    moved.pendingFile.outputStream().use(input::copyTo)
                }
                moved.finalFile.delete()
            }.isSuccess
        }
    }

    fun recover(isCommitted: (categoryId: String, recordId: String) -> Boolean) {
        root.listFiles().orEmpty()
            .filter { file -> file.name.startsWith(MARKER_PREFIX) && file.name.endsWith(".json") }
            .forEach { marker ->
                val data = runCatching { marker.readLines() }.getOrNull().orEmpty()
                val categoryId = data.getOrNull(0).orEmpty()
                val recordId = data.getOrNull(1).orEmpty()
                if (!ParameterRecordPathPolicy.isIdentifier(categoryId) ||
                    !ParameterRecordPathPolicy.isIdentifier(recordId)
                ) {
                    marker.delete()
                    return@forEach
                }
                if (!isCommitted(categoryId, recordId)) deleteUncommittedFiles(categoryId, recordId)
                marker.delete()
            }
    }

    private fun deleteUncommittedFiles(categoryId: String, recordId: String) {
        val directory = ParameterRecordPathPolicy.categoryDirectory(root, categoryId) ?: return
        listOf("jpg", "dng").forEach { extension ->
            val file = File(directory, "$recordId.$extension")
            if (file.parentFile?.canonicalFile == directory.canonicalFile && file.isFile) file.delete()
        }
        if (directory.listFiles().orEmpty().isEmpty()) directory.delete()
    }

    private companion object {
        private const val MARKER_PREFIX = ".record-transaction-"
    }
}
