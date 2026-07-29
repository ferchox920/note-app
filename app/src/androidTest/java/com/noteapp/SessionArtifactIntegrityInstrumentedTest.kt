package com.noteapp

import android.content.Context
import android.os.Bundle
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noteapp.security.AndroidKeystoreSessionArtifactStore
import com.noteapp.security.SessionArtifactStore
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Audits existing app-private sessions in place.
 *
 * This test deliberately never deletes app data, records audio, starts ASR, or emits
 * decrypted content. It is intended for the physical Sprint 4 migration gate.
 */
@RunWith(AndroidJUnit4::class)
class SessionArtifactIntegrityInstrumentedTest {
    @Test
    fun migrateAndVerifyExistingSessionsWithoutExposingContent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.filesDir, "recordings")
        val store = AndroidKeystoreSessionArtifactStore.create(context)

        store.migrateAll()
        assertTrue("ARTIFACT_ROOT_MISSING", root.isDirectory)
        verifyOwnerOnlyDirectory(root)
        val keyMarker = File(context.filesDir, "security/session-artifacts.v1")
        assertTrue("ARTIFACT_KEY_MARKER_MISSING", keyMarker.isFile)
        verifyOwnerOnlyFile(keyMarker)
        verifyOwnerOnlyDirectory(requireNotNull(keyMarker.parentFile))
        val firstSnapshot = metadataSnapshot(root)
        store.migrateAll()
        assertEquals("ARTIFACT_MIGRATION_NOT_IDEMPOTENT", firstSnapshot, metadataSnapshot(root))

        val artifacts = root.walkTopDown().filter(File::isFile).toList()
        assertTrue("NO_SESSION_ARTIFACTS", artifacts.isNotEmpty())
        artifacts.forEach { file ->
            assertFalse("RECOVERY_TEMPORARY_REMAINS", file.hasRecoverySuffix())
            assertTrue("PLAINTEXT_SESSION_ARTIFACT_REMAINS", store.isEncrypted(file))
            verifyOwnerOnlyFile(file)
            store.openInput(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (input.read(buffer) >= 0) Unit
            }
        }

        val sessionDirectories = root.listFiles(File::isDirectory)
            .orEmpty()
            .filter { directory -> File(directory, CHECKPOINT_FILE).isFile }
            .sortedBy(File::getName)
        assertTrue("NO_PERSISTED_SESSIONS", sessionDirectories.isNotEmpty())

        var pcmSegmentCount = 0
        var plaintextBytes = 0L
        sessionDirectories.forEach { directory ->
            val result = verifyCheckpointAndPcm(directory, store)
            pcmSegmentCount += result.segmentCount
            plaintextBytes += result.totalBytes
        }

        InstrumentationRegistry.getInstrumentation().sendStatus(
            STATUS_AUDIT_COMPLETE,
            Bundle().apply {
                putString("audit", "SESSION_ARTIFACT_AUDIT_PASSED")
                putInt("sessionCount", sessionDirectories.size)
                putInt("artifactCount", artifacts.size)
                putInt("pcmSegmentCount", pcmSegmentCount)
                putLong("plaintextBytes", plaintextBytes)
                putBoolean("contentIncluded", false)
            },
        )
    }

    private fun verifyCheckpointAndPcm(
        directory: File,
        store: SessionArtifactStore,
    ): SessionAuditResult {
        val checkpoint = JSONObject(store.readText(File(directory, CHECKPOINT_FILE)))
        assertEquals("CHECKPOINT_SESSION_ID_MISMATCH", directory.name, checkpoint.getString("sessionId"))
        assertEquals("CHECKPOINT_SCHEMA_UNSUPPORTED", 1, checkpoint.getInt("schemaVersion"))
        assertTrue(
            "CHECKPOINT_STATUS_INVALID",
            checkpoint.getString("status") in VALID_STATUSES,
        )

        val segments = checkpoint.getJSONArray("segments")
        var expectedOffset = 0L
        for (index in 0 until segments.length()) {
            val segment = segments.getJSONObject(index)
            assertEquals("PCM_SEQUENCE_NOT_CONTIGUOUS", index, segment.getInt("sequence"))
            val expectedName = "segment-${index.toString().padStart(4, '0')}.pcm"
            assertEquals("PCM_FILE_NAME_INVALID", expectedName, segment.getString("fileName"))
            assertEquals("PCM_START_OFFSET_INVALID", expectedOffset, segment.getLong("startByteOffset"))
            val byteCount = segment.getLong("byteCount")
            assertTrue("PCM_BYTE_COUNT_INVALID", byteCount > 0L && byteCount % 2L == 0L)
            expectedOffset += byteCount
            assertEquals("PCM_END_OFFSET_INVALID", expectedOffset, segment.getLong("endByteOffset"))

            val pcm = File(directory, expectedName)
            assertTrue("PCM_SEGMENT_MISSING", pcm.isFile)
            assertEquals("PCM_PLAINTEXT_SIZE_MISMATCH", byteCount, store.plaintextSize(pcm))
            assertEquals(
                "PCM_CHECKSUM_MISMATCH",
                segment.getString("sha256"),
                sha256(pcm, store),
            )
        }
        assertEquals("CHECKPOINT_TOTAL_BYTES_INVALID", expectedOffset, checkpoint.getLong("totalBytes"))
        return SessionAuditResult(segments.length(), expectedOffset)
    }

    private fun sha256(file: File, store: SessionArtifactStore): String {
        val digest = MessageDigest.getInstance("SHA-256")
        store.openInput(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun metadataSnapshot(root: File): List<ArtifactMetadata> =
        root.walkTopDown()
            .filter(File::isFile)
            .map { file ->
                ArtifactMetadata(
                    relativePath = root.toPath().relativize(file.toPath()).toString(),
                    ciphertextBytes = file.length(),
                    modifiedAtEpochMs = file.lastModified(),
                )
            }
            .sortedBy(ArtifactMetadata::relativePath)
            .toList()

    private fun verifyOwnerOnlyFile(file: File) {
        assertEquals("ARTIFACT_FILE_PERMISSIONS_INVALID", OWNER_READ_WRITE, permissionBits(file))
    }

    private fun verifyOwnerOnlyDirectory(directory: File) {
        directory.walkTopDown().filter(File::isDirectory).forEach { candidate ->
            assertEquals(
                "ARTIFACT_DIRECTORY_PERMISSIONS_INVALID",
                OWNER_READ_WRITE_EXECUTE,
                permissionBits(candidate),
            )
        }
    }

    private fun permissionBits(file: File): Int = Os.stat(file.path).st_mode and PERMISSION_MASK

    private fun File.hasRecoverySuffix(): Boolean =
        name.endsWith(".tmp") ||
            name.endsWith(".plaintext.backup") ||
            name.endsWith(".encrypted.tmp") ||
            name.endsWith(".secure-write.tmp")

    private data class SessionAuditResult(
        val segmentCount: Int,
        val totalBytes: Long,
    )

    private data class ArtifactMetadata(
        val relativePath: String,
        val ciphertextBytes: Long,
        val modifiedAtEpochMs: Long,
    )

    private companion object {
        const val CHECKPOINT_FILE = "checkpoint.json"
        const val STATUS_AUDIT_COMPLETE = 2
        const val PERMISSION_MASK = 0b111_111_111
        const val OWNER_READ_WRITE = 0b110_000_000
        const val OWNER_READ_WRITE_EXECUTE = 0b111_000_000
        val VALID_STATUSES = setOf(
            "NEW",
            "RECORDING",
            "PAUSED",
            "RECOVERING",
            "COMPLETED",
            "FAILED",
            "ABORTED",
        )
    }
}
