package com.noteapp.storage

import com.noteapp.security.EncryptedSessionArtifactStore
import com.noteapp.security.PlaintextSessionArtifactStore
import kotlinx.coroutines.runBlocking
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileSessionCheckpointStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val plaintextArtifacts = PlaintextSessionArtifactStore()

    @Test
    fun `finds interrupted recording but excludes terminal session`() = runBlocking {
        writeCheckpoint("recover-me", "RECOVERING", 12_345, "AUDIO_CLIENT_SILENCED")
        writeCheckpoint("done", "COMPLETED", 99_000)

        val result = FileSessionCheckpointStore(
            temporaryFolder.root,
            plaintextArtifacts,
        ).findRecoverable()

        assertEquals(1, result.size)
        assertEquals("recover-me", result.single().id)
        assertEquals(12_345, result.single().durationMs)
        assertEquals("AUDIO_CLIENT_SILENCED", result.single().errorCode)
    }

    @Test
    fun `ignores malformed checkpoint`() = runBlocking {
        temporaryFolder.newFolder("broken").resolve("checkpoint.json").writeText("not-json")

        assertTrue(
            FileSessionCheckpointStore(
                temporaryFolder.root,
                plaintextArtifacts,
            ).findRecoverable().isEmpty(),
        )
    }

    @Test
    fun `finds completed sessions for post process laboratory`() = runBlocking {
        writeCheckpoint("interrupted", "RECORDING", 12_345)
        writeCheckpoint("completed", "COMPLETED", 99_000)

        val result = FileSessionCheckpointStore(
            temporaryFolder.root,
            plaintextArtifacts,
        ).findCompleted()

        assertEquals(1, result.size)
        assertEquals("completed", result.single().id)
        assertEquals(99_000, result.single().durationMs)
    }

    @Test
    fun `reads finalized transcript segments and model from artifact`() {
        writeCheckpoint("indexed", "COMPLETED", 8_000)
        temporaryFolder.root.resolve("indexed").resolve("incremental-transcript.json").writeText(
            """
            {
              "schemaVersion": 5,
              "modelId": "sherpa-es",
              "partialCount": 14,
              "lastRealTimeFactor": 0.09,
              "segments": [
                {"startMs": 0, "endMs": 2500, "text": "primera frase"},
                {"startMs": 2500, "endMs": 8000, "text": "texto con \"comillas\""}
              ]
            }
            """.trimIndent(),
        )

        val snapshot = FileSessionArtifactReader(
            temporaryFolder.root,
            plaintextArtifacts,
        ).readAll().single()

        assertEquals("sherpa-es", snapshot.transcriptModelId)
        assertEquals(2, snapshot.transcriptSegments.size)
        assertEquals(2_500, snapshot.transcriptSegments[1].startMs)
        assertEquals("texto con \"comillas\"", snapshot.transcriptSegments[1].text)
        assertEquals(
            listOf("LAST_REAL_TIME_FACTOR", "PARTIAL_COUNT"),
            snapshot.transcriptMetrics.map { it.name },
        )
        assertEquals(
            setOf("incremental_summary"),
            snapshot.transcriptMetrics.mapNotNull { it.phase }.toSet(),
        )
    }

    @Test
    fun `encrypted index preserves sessions and rejects authenticated tampering`() {
        writeCheckpoint("encrypted", "COMPLETED", 4_000)
        val artifactStore = EncryptedSessionArtifactStore(
            temporaryFolder.root,
            SecretKeySpec(ByteArray(32) { (it + 7).toByte() }, "AES"),
        )
        artifactStore.migrateAll()

        val result = runBlocking {
            FileSessionCheckpointStore(temporaryFolder.root, artifactStore).findCompleted()
        }
        assertEquals("encrypted", result.single().id)

        val checkpoint = temporaryFolder.root.resolve("encrypted/checkpoint.json")
        checkpoint.writeBytes(checkpoint.readBytes().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        })
        assertThrows(SecurityException::class.java) {
            runBlocking {
                FileSessionCheckpointStore(temporaryFolder.root, artifactStore).findCompleted()
            }
        }
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
