package com.lightmeter.rawmeter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class ParameterRecordTransactionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun recoveryDeletesOnlyFilesFromAnUncommittedJournal() {
        val root = temporaryFolder.newFolder("records")
        val categoryId = UUID.randomUUID().toString()
        val recordId = UUID.randomUUID().toString()
        val directory = requireNotNull(ParameterRecordPathPolicy.categoryDirectory(root, categoryId))
            .apply { mkdirs() }
        val preview = File(directory, "$recordId.jpg").apply { writeText("preview") }
        val transaction = ParameterRecordTransaction(root)
        transaction.begin(categoryId, recordId)

        transaction.recover { _, _ -> false }

        assertFalse(preview.exists())
    }

    @Test
    fun recoveryKeepsARecordWhoseIndexWasAlreadyCommitted() {
        val root = temporaryFolder.newFolder("records")
        val categoryId = UUID.randomUUID().toString()
        val recordId = UUID.randomUUID().toString()
        val directory = requireNotNull(ParameterRecordPathPolicy.categoryDirectory(root, categoryId))
            .apply { mkdirs() }
        val preview = File(directory, "$recordId.jpg").apply { writeText("preview") }
        val transaction = ParameterRecordTransaction(root)
        transaction.begin(categoryId, recordId)

        transaction.recover { category, record -> category == categoryId && record == recordId }

        assertTrue(preview.exists())
    }
}
