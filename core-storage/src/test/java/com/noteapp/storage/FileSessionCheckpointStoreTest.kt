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
        writeCheckpoint("recover-me", "RECOVERING", 12_345, "AUDIO_CLIENT_SILENCED")
        writeCheckpoint("done", "COMPLETED", 99_000)

        val result = FileSessionCheckpointStore(temporaryFolder.root).findRecoverable()

        assertEquals(1, result.size)
        assertEquals("recover-me", result.single().id)
        assertEquals(12_345, result.single().durationMs)
        assertEquals("AUDIO_CLIENT_SILENCED", result.single().errorCode)
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

    private fun writeCheckpoint(
        id: String,
        status: String,
        durationMs: Long,
        errorCode: String? = null,
    ) {
        val directory = temporaryFolder.newFolder(id)
        val errorJson = errorCode?.let { "\"$it\"" } ?: "null"
        directory.resolve("checkpoint.json").writeText(
            """{"schemaVersion":1,"sessionId":"$id","status":"$status","durationMs":$durationMs,"errorCode":$errorJson}""",
        )
    }
}
