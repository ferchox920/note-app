package com.noteapp

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noteapp.audio.AudioSessionWriter
import com.noteapp.audio.PcmFormat
import com.noteapp.domain.SessionStatus
import com.noteapp.security.AndroidKeystoreSessionArtifactStore
import com.noteapp.security.SessionArtifactStore
import com.noteapp.storage.FileSessionCheckpointStore
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two-phase physical reboot audit that never opens the microphone or starts ASR.
 *
 * The preparation method intentionally leaves one authenticated PCM segment outside
 * the checkpoint. The verification method must run after a real device reboot.
 */
@RunWith(AndroidJUnit4::class)
class SessionRebootRecoveryInstrumentedTest {
    @Test
    fun prepareSyntheticInterruptedSessionForReboot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = auditRoot(context)
        deleteAuditRoot(root)
        val store = AndroidKeystoreSessionArtifactStore.create(context, root)
        store.migrateAll()

        val writer = AudioSessionWriter(
            rootDirectory = root,
            sessionId = SESSION_ID,
            format = FORMAT,
            artifactStore = store,
        )
        writer.writeLifecycleEvent("STARTED", SessionStatus.RECORDING, "reboot-audit")
        writer.openSegment().write(listedPayload())
        writer.closeSegment()
        writer.writeCheckpoint(SessionStatus.RECORDING)

        val orphan = writer.openSegment()
        orphan.write(orphanPayload())
        writer.writeCheckpoint(SessionStatus.RECORDING)

        assertEquals(EXPECTED_TOTAL_BYTES, writer.totalBytes)
        assertTrue(store.isEncrypted(File(writer.sessionDirectory, "segment-0000.pcm")))
        assertTrue(store.isEncrypted(File(writer.sessionDirectory, "segment-0001.pcm")))
        sendResult(
            marker = "REBOOT_RECOVERY_FIXTURE_PREPARED",
            segmentCount = 2,
            totalBytes = EXPECTED_TOTAL_BYTES,
        )
    }

    @Test
    fun verifyRecoveryAfterRebootIsIdempotentAndCleanup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = auditRoot(context)
        try {
            assertTrue("REBOOT_RECOVERY_FIXTURE_MISSING", root.isDirectory)
            val store = AndroidKeystoreSessionArtifactStore.create(context, root)
            val checkpointStore = FileSessionCheckpointStore(root, store)
            val recoverable = runBlocking { checkpointStore.findRecoverable() }
            assertEquals("RECOVERABLE_SESSION_COUNT_INVALID", 1, recoverable.size)
            assertEquals(SESSION_ID, recoverable.single().id)
            assertEquals(SessionStatus.RECOVERING, recoverable.single().status)

            val sessionDirectory = File(root, SESSION_ID)
            val ciphertextBefore = ciphertextSnapshot(sessionDirectory)
            val first = recover(root, store)
            assertRecoveredPrefix(first, store)
            assertEquals(
                "RECOVERY_REWROTE_AUTHENTICATED_PREFIX",
                ciphertextBefore,
                ciphertextSnapshot(sessionDirectory),
            )

            val repeatedBeforeCheckpoint = recover(root, store)
            assertEquals(first.completedSegments, repeatedBeforeCheckpoint.completedSegments)
            first.writeCheckpoint(SessionStatus.RECOVERING)
            val repeatedAfterCheckpoint = recover(root, store)
            assertEquals(first.completedSegments, repeatedAfterCheckpoint.completedSegments)

            repeatedAfterCheckpoint.openSegment().write(resumedPayload())
            repeatedAfterCheckpoint.closeSegment()
            repeatedAfterCheckpoint.writeCheckpoint(SessionStatus.COMPLETED)
            val completed = recover(root, store)
            assertEquals(3, completed.completedSegments.size)
            assertEquals(EXPECTED_FINAL_BYTES, completed.totalBytes)
            assertEquals(
                SESSION_ID,
                runBlocking { checkpointStore.findCompleted() }.single().id,
            )

            val artifacts = root.walkTopDown().filter(File::isFile).toList()
            assertTrue("REBOOT_RECOVERY_ARTIFACTS_MISSING", artifacts.isNotEmpty())
            artifacts.forEach { file ->
                assertTrue("REBOOT_RECOVERY_PLAINTEXT_REMAINS", store.isEncrypted(file))
                assertFalse("REBOOT_RECOVERY_TEMPORARY_REMAINS", file.hasRecoverySuffix())
            }
            sendResult(
                marker = "REBOOT_RECOVERY_AUDIT_PASSED",
                segmentCount = completed.completedSegments.size,
                totalBytes = completed.totalBytes,
            )
        } finally {
            deleteAuditRoot(root)
        }
    }

    private fun recover(
        root: File,
        store: SessionArtifactStore,
    ): AudioSessionWriter = AudioSessionWriter.recover(
        rootDirectory = root,
        sessionId = SESSION_ID,
        expectedFormat = FORMAT,
        artifactStore = store,
    )

    private fun assertRecoveredPrefix(
        writer: AudioSessionWriter,
        store: SessionArtifactStore,
    ) {
        assertEquals(2, writer.completedSegments.size)
        assertEquals(EXPECTED_TOTAL_BYTES, writer.totalBytes)
        val listed = File(writer.sessionDirectory, "segment-0000.pcm")
        val orphan = File(writer.sessionDirectory, "segment-0001.pcm")
        assertArrayEquals(listedPayload(), store.readBytes(listed))
        assertArrayEquals(orphanPayload(), store.readBytes(orphan))
        assertEquals(sha256(listedPayload()), writer.completedSegments[0].sha256)
        assertEquals(sha256(orphanPayload()), writer.completedSegments[1].sha256)
    }

    private fun ciphertextSnapshot(directory: File): List<CiphertextMetadata> =
        directory.walkTopDown()
            .filter(File::isFile)
            .map { file ->
                CiphertextMetadata(
                    relativePath = directory.toPath().relativize(file.toPath()).toString(),
                    byteCount = file.length(),
                    sha256 = sha256(file.readBytes()),
                )
            }
            .sortedBy(CiphertextMetadata::relativePath)
            .toList()

    private fun sendResult(
        marker: String,
        segmentCount: Int,
        totalBytes: Long,
    ) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            STATUS_AUDIT_COMPLETE,
            Bundle().apply {
                putString("audit", marker)
                putInt("segmentCount", segmentCount)
                putLong("totalBytes", totalBytes)
                putBoolean("recordingStarted", false)
                putBoolean("transcriptionStarted", false)
                putBoolean("contentIncluded", false)
            },
        )
    }

    private fun auditRoot(context: Context): File =
        File(context.filesDir, AUDIT_ROOT_DIRECTORY).canonicalFile

    private fun deleteAuditRoot(root: File) {
        val filesDirectory = requireNotNull(root.parentFile).canonicalFile
        check(root.name == AUDIT_ROOT_DIRECTORY) { "REBOOT_AUDIT_ROOT_INVALID" }
        check(filesDirectory.name == "files") { "REBOOT_AUDIT_PARENT_INVALID" }
        if (root.exists()) {
            check(root.deleteRecursively()) { "REBOOT_AUDIT_CLEANUP_FAILED" }
        }
    }

    private fun listedPayload(): ByteArray = deterministicPayload(LISTED_BYTES, 17)

    private fun orphanPayload(): ByteArray = deterministicPayload(ORPHAN_BYTES, 53)

    private fun resumedPayload(): ByteArray = deterministicPayload(RESUMED_BYTES, 91)

    private fun deterministicPayload(size: Int, seed: Int): ByteArray =
        ByteArray(size) { index -> ((index * 31 + seed) % 251).toByte() }

    private fun sha256(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun File.hasRecoverySuffix(): Boolean =
        name.endsWith(".tmp") ||
            name.endsWith(".plaintext.backup") ||
            name.endsWith(".encrypted.tmp") ||
            name.endsWith(".secure-write.tmp")

    private data class CiphertextMetadata(
        val relativePath: String,
        val byteCount: Long,
        val sha256: String,
    )

    private companion object {
        const val AUDIT_ROOT_DIRECTORY = "sprint4-reboot-recovery-audit"
        const val SESSION_ID = "s4-reboot-recovery-audit"
        const val STATUS_AUDIT_COMPLETE = 2
        const val LISTED_BYTES = 32_000
        const val ORPHAN_BYTES = 48_000
        const val RESUMED_BYTES = 16_000
        const val EXPECTED_TOTAL_BYTES = (LISTED_BYTES + ORPHAN_BYTES).toLong()
        const val EXPECTED_FINAL_BYTES = EXPECTED_TOTAL_BYTES + RESUMED_BYTES
        val FORMAT = PcmFormat(16_000)
    }
}

private fun AudioSessionWriter.SegmentSink.write(content: ByteArray) {
    write(content, 0, content.size)
}
