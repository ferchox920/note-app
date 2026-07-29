package com.noteapp.audio

import com.noteapp.domain.SessionStatus
import com.noteapp.security.EncryptedSessionArtifactStore
import com.noteapp.security.PlaintextSessionArtifactStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec

class AudioSessionWriterTest {
    private lateinit var root: File
    private lateinit var artifacts: PlaintextSessionArtifactStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("audio-session-test").toFile()
        artifacts = PlaintextSessionArtifactStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `closing a segment stores byte count and sha256`() {
        val writer = AudioSessionWriter(root, "session-1", PcmFormat(16_000), artifactStore = artifacts)
        val bytes = byteArrayOf(1, 2, 3, 4)

        writer.openSegment().write(bytes, 0, bytes.size)
        val metadata = writer.closeSegment()!!

        assertEquals(4, metadata.byteCount)
        assertEquals(0, metadata.startByteOffset)
        assertEquals(4, metadata.endByteOffset)
        assertEquals(
            "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",
            metadata.sha256,
        )
        assertTrue(File(writer.sessionDirectory, metadata.fileName).exists())
    }

    @Test
    fun `checkpoint is replaced atomically and contains recoverable metadata`() {
        val writer = AudioSessionWriter(root, "session-2", PcmFormat(16_000), artifactStore = artifacts)
        writer.openSegment().apply { write(ByteArray(32_000), 0, 32_000) }
        writer.closeSegment()

        writer.writeCheckpoint(SessionStatus.PAUSED)

        val checkpoint = File(writer.sessionDirectory, AudioSessionWriter.CHECKPOINT_FILE)
        assertTrue(checkpoint.exists())
        assertFalse(File(writer.sessionDirectory, "${AudioSessionWriter.CHECKPOINT_FILE}.tmp").exists())
        assertTrue(checkpoint.readText().contains("\"status\":\"PAUSED\""))
        assertTrue(checkpoint.readText().contains("\"durationMs\":1000"))
    }

    @Test
    fun `recovery verifies listed PCM and adopts crash orphan before appending`() {
        val format = PcmFormat(16_000)
        val original = AudioSessionWriter(root, "session-3", format, artifactStore = artifacts)
        original.openSegment().apply { write(ByteArray(640) { 1 }, 0, 640) }
        original.closeSegment()
        original.writeCheckpoint(SessionStatus.RECORDING)
        File(original.sessionDirectory, "segment-0001.pcm").writeBytes(ByteArray(1_280) { 2 })

        val recovered = AudioSessionWriter.recover(root, "session-3", format, artifacts)

        assertEquals(2, recovered.completedSegments.size)
        assertEquals(1_920, recovered.totalBytes)
        val next = recovered.openSegment()
        next.write(ByteArray(640), 0, 640)
        assertEquals(2, recovered.closeSegment()!!.sequence)
    }

    @Test
    fun `recovery rejects modified listed PCM`() {
        val format = PcmFormat(16_000)
        val original = AudioSessionWriter(root, "session-4", format, artifactStore = artifacts)
        original.openSegment().apply { write(ByteArray(640) { 1 }, 0, 640) }
        val metadata = original.closeSegment()!!
        original.writeCheckpoint(SessionStatus.PAUSED)
        File(original.sessionDirectory, metadata.fileName).writeBytes(ByteArray(640) { 9 })

        assertThrows(IllegalArgumentException::class.java) {
            AudioSessionWriter.recover(root, "session-4", format, artifacts)
        }
    }

    @Test
    fun `recovery preserves capture quality counters`() {
        val format = PcmFormat(16_000)
        val original = AudioSessionWriter(root, "session-5", format, artifactStore = artifacts)
        original.writeCheckpoint(
            status = SessionStatus.RECORDING,
            metrics = AudioCaptureMetrics(
                readErrorCount = 2,
                discontinuityCount = 3,
                estimatedMissingFrames = 480,
            ),
        )

        val recovered = AudioSessionWriter.recover(root, "session-5", format, artifacts)

        assertEquals(2, recovered.checkpointMetrics.readErrorCount)
        assertEquals(3, recovered.checkpointMetrics.discontinuityCount)
        assertEquals(480, recovered.checkpointMetrics.estimatedMissingFrames)
    }

    @Test
    fun `recovery preserves native capture pipeline`() {
        val original = AudioSessionWriter(
            rootDirectory = root,
            sessionId = "session-6",
            format = PcmFormat(16_000),
            capturePipeline = CapturePipeline.NATIVE_48_KHZ_TO_16_KHZ,
            artifactStore = artifacts,
        )
        original.writeCheckpoint(SessionStatus.RECORDING)

        val recovered = AudioSessionWriter.recover(root, "session-6", PcmFormat(16_000), artifacts)

        assertEquals(CapturePipeline.NATIVE_48_KHZ_TO_16_KHZ, recovered.capturePipeline)
    }

    @Test
    fun `recovery preserves selected incremental model`() {
        val original = AudioSessionWriter(
            rootDirectory = root,
            sessionId = "session-7",
            format = PcmFormat(16_000),
            incrementalModelId = "whisper-base-multilingual-q5_1",
            artifactStore = artifacts,
        )
        original.writeCheckpoint(SessionStatus.RECORDING)

        val recovered = AudioSessionWriter.recover(root, "session-7", PcmFormat(16_000), artifacts)

        assertEquals("whisper-base-multilingual-q5_1", recovered.incrementalModelId)
    }

    @Test
    fun `lifecycle events are immutable ordered and recovered`() {
        val format = PcmFormat(16_000)
        val writer = AudioSessionWriter(root, "session-8", format, artifactStore = artifacts)
        writer.writeLifecycleEvent("STARTED", SessionStatus.RECORDING, "ui")
        writer.writeLifecycleEvent("PAUSED", SessionStatus.PAUSED, "notification")
        writer.writeCheckpoint(SessionStatus.PAUSED)

        val recovered = AudioSessionWriter.recover(root, "session-8", format, artifacts)
        recovered.writeLifecycleEvent("RESUMED", SessionStatus.RECORDING, "ui")

        val directory = File(writer.sessionDirectory, AudioSessionWriter.LIFECYCLE_EVENTS_DIRECTORY)
        assertEquals(
            listOf("event-0000.json", "event-0001.json", "event-0002.json"),
            directory.listFiles()!!.map(File::getName).sorted(),
        )
        assertTrue(directory.resolve("event-0000.json").readText().contains("\"event\":\"STARTED\""))
        assertTrue(directory.resolve("event-0001.json").readText().contains("\"source\":\"notification\""))
        assertTrue(directory.resolve("event-0002.json").readText().contains("\"event\":\"RESUMED\""))
    }

    @Test
    fun `encrypted writer streams PCM and recovers authenticated metadata`() {
        val artifactStore = EncryptedSessionArtifactStore(
            root,
            SecretKeySpec(ByteArray(32) { (it + 11).toByte() }, "AES"),
        )
        val format = PcmFormat(16_000)
        val writer = AudioSessionWriter(
            rootDirectory = root,
            sessionId = "encrypted-session",
            format = format,
            artifactStore = artifactStore,
        )
        val pcm = ByteArray(64_000) { (it % 181).toByte() }
        writer.openSegment().write(pcm, 0, pcm.size)
        writer.closeSegment()
        writer.writeCheckpoint(SessionStatus.PAUSED)

        val checkpoint = writer.sessionDirectory.resolve(AudioSessionWriter.CHECKPOINT_FILE)
        val segment = writer.sessionDirectory.resolve("segment-0000.pcm")
        assertTrue(artifactStore.isEncrypted(checkpoint))
        assertTrue(artifactStore.isEncrypted(segment))
        assertFalse(checkpoint.readText().contains("PAUSED"))

        val recovered = AudioSessionWriter.recover(
            rootDirectory = root,
            sessionId = "encrypted-session",
            expectedFormat = format,
            artifactStore = artifactStore,
        )
        assertEquals(64_000L, recovered.totalBytes)
        assertEquals(64_000L, artifactStore.plaintextSize(segment))
    }

    @Test
    fun `encrypted recovery adopts authenticated prefix after interrupted final frame`() {
        val artifactStore = EncryptedSessionArtifactStore(
            root,
            SecretKeySpec(ByteArray(32) { (it + 19).toByte() }, "AES"),
        )
        val format = PcmFormat(16_000)
        val writer = AudioSessionWriter(
            rootDirectory = root,
            sessionId = "encrypted-crash-session",
            format = format,
            artifactStore = artifactStore,
        )
        val retained = ByteArray(3_200) { (it % 173).toByte() }
        val interrupted = ByteArray(3_200) { (it % 149).toByte() }
        writer.openSegment().apply {
            write(retained, 0, retained.size)
            write(interrupted, 0, interrupted.size)
        }
        writer.writeCheckpoint(SessionStatus.RECORDING)
        writer.closeSegment()
        val segment = writer.sessionDirectory.resolve("segment-0000.pcm")
        RandomAccessFile(segment, "rw").use { randomAccess ->
            randomAccess.setLength(randomAccess.length() - 9)
        }

        val recovered = AudioSessionWriter.recover(
            rootDirectory = root,
            sessionId = "encrypted-crash-session",
            expectedFormat = format,
            artifactStore = artifactStore,
        )

        assertEquals(retained.size.toLong(), recovered.totalBytes)
        assertEquals(1, recovered.completedSegments.size)
        assertEquals(
            "f21a4ebabade404b8c0d2abceab8945b55df224e282aefdcf743fa0913c67920",
            recovered.completedSegments.single().sha256,
        )
    }
}
