package com.noteapp.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileSessionCheckpointStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `finds interrupted recording but excludes terminal session`() = runBlocking {
        writeCheckpoint("recover-me", "RECORDING", 12_345)
        writeCheckpoint("done", "COMPLETED", 99_000)

        val result = FileSessionCheckpointStore(temporaryFolder.root).findRecoverable()

        assertEquals(1, result.size)
        assertEquals("recover-me", result.single().id)
        assertEquals(12_345, result.single().durationMs)
    }

    @Test
    fun `ignores malformed checkpoint`() = runBlocking {
        temporaryFolder.newFolder("broken").resolve("checkpoint.json").writeText("not-json")

        assertTrue(FileSessionCheckpointStore(temporaryFolder.root).findRecoverable().isEmpty())
    }

    @Test
    fun `finds completed sessions for post process laboratory`() = runBlocking {
        writeCheckpoint("interrupted", "RECORDING", 12_345)
        writeCheckpoint("completed", "COMPLETED", 99_000)

        val result = FileSessionCheckpointStore(temporaryFolder.root).findCompleted()

        assertEquals(1, result.size)
        assertEquals("completed", result.single().id)
        assertEquals(99_000, result.single().durationMs)
    }

    private fun writeCheckpoint(id: String, status: String, durationMs: Long) {
        val directory = temporaryFolder.newFolder(id)
        directory.resolve("checkpoint.json").writeText(
            """{"schemaVersion":1,"sessionId":"$id","status":"$status","durationMs":$durationMs}""",
        )
    }
}
