package com.lightmeter.rawmeter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class ParameterRecordPathPolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `category and pending paths reject traversal and non UUID ids`() {
        val root = temporaryFolder.newFolder("root")

        assertNull(ParameterRecordPathPolicy.categoryDirectory(root, "../outside"))
        assertNull(ParameterRecordPathPolicy.pendingFile(root, "not-an-id", "jpg"))
        assertNull(ParameterRecordPathPolicy.pendingFile(root, UUID.randomUUID().toString(), "txt"))
    }

    @Test
    fun `delete validation accepts only the category owned capture files`() {
        val root = temporaryFolder.newFolder("root")
        val categoryId = UUID.randomUUID().toString()
        val recordId = UUID.randomUUID().toString()
        val directory = requireNotNull(ParameterRecordPathPolicy.categoryDirectory(root, categoryId)).apply { mkdirs() }
        val preview = File(directory, "$recordId.jpg").apply { writeText("preview") }
        val raw = File(directory, "$recordId.dng").apply { writeText("raw") }
        val category = category(categoryId, recordId, preview, raw)

        assertNotNull(ParameterRecordPathPolicy.validateCategoryContents(directory, category))
        val unknown = File(directory, "unrelated.txt").apply { writeText("keep") }
        assertNull(ParameterRecordPathPolicy.validateCategoryContents(directory, category))
        assertTrue(unknown.exists())
    }

    @Test
    fun `quarantine cleanup accepts only UUID capture files`() {
        val root = temporaryFolder.newFolder("root")
        val categoryId = UUID.randomUUID().toString()
        val transactionId = UUID.randomUUID().toString()
        val quarantine = File(root, ".delete-$categoryId-$transactionId").apply { mkdirs() }
        val owned = File(quarantine, "${UUID.randomUUID()}.jpg").apply { writeText("preview") }

        assertTrue(ParameterRecordPathPolicy.isDeleteQuarantine(root, quarantine))
        assertTrue(ParameterRecordPathPolicy.isOwnedRecordFile(quarantine, owned))
        assertFalse(ParameterRecordPathPolicy.isOwnedRecordFile(quarantine, File(quarantine, "notes.txt").apply { writeText("keep") }))
    }

    private fun category(
        categoryId: String,
        recordId: String,
        preview: File,
        raw: File,
    ) = ParameterRecordCategory(
        id = categoryId,
        startedAtEpochMs = 1L,
        endedAtEpochMs = null,
        records = listOf(
            ParameterRecordEntry(
                id = recordId,
                categoryId = categoryId,
                capturedAtEpochMs = 1L,
                previewPath = preview.absolutePath,
                rawPath = raw.absolutePath,
                mode = ParameterRecordMode.NORMAL,
                apertureCoordinate = 0.0,
                shutterCoordinate = 0.0,
                ei = 100,
                ev100 = null,
                filmId = null,
                filmName = null,
                filmIso = null,
                notes = emptyList(),
                location = null,
                zonePoints = emptyList(),
                rawGrid = null,
            ),
        ),
    )
}
