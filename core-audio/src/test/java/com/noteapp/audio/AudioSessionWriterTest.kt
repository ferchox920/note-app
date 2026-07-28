package com.noteapp.audio

import com.noteapp.domain.SessionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AudioSessionWriterTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("audio-session-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `closing a segment stores byte count and sha256`() {
        val writer = AudioSessionWriter(root, "session-1", PcmFormat(16_000))
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
        val writer = AudioSessionWriter(root, "session-2", PcmFormat(16_000))
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
        val original = AudioSessionWriter(root, "session-3", format)
        original.openSegment().apply { write(ByteArray(640) { 1 }, 0, 640) }
        original.closeSegment()
        original.writeCheckpoint(SessionStatus.RECORDING)
        File(original.sessionDirectory, "segment-0001.pcm").writeBytes(ByteArray(1_280) { 2 })

        val recovered = AudioSessionWriter.recover(root, "session-3", format)

        assertEquals(2, recovered.completedSegments.size)
        assertEquals(1_920, recovered.totalBytes)
        val next = recovered.openSegment()
        next.write(ByteArray(640), 0, 640)
        assertEquals(2, recovered.closeSegment()!!.sequence)
    }

    @Test
    fun `recovery rejects modified listed PCM`() {
        val format = PcmFormat(16_000)
        val original = AudioSessionWriter(root, "session-4", format)
        original.openSegment().apply { write(ByteArray(640) { 1 }, 0, 640) }
        val metadata = original.closeSegment()!!
        original.writeCheckpoint(SessionStatus.PAUSED)
        File(original.sessionDirectory, metadata.fileName).writeBytes(ByteArray(640) { 9 })

        assertThrows(IllegalArgumentException::class.java) {
            AudioSessionWriter.recover(root, "session-4", format)
        }
    }

    @Test
    fun `recovery preserves capture quality counters`() {
        val format = PcmFormat(16_000)
        val original = AudioSessionWriter(root, "session-5", format)
        original.writeCheckpoint(
            status = SessionStatus.RECORDING,
            metrics = AudioCaptureMetrics(
                readErrorCount = 2,
                discontinuityCount = 3,
                estimatedMissingFrames = 480,
            ),
        )

        val recovered = AudioSessionWriter.recover(root, "session-5", format)

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
        )
        original.writeCheckpoint(SessionStatus.RECORDING)

        val recovered = AudioSessionWriter.recover(root, "session-6", PcmFormat(16_000))

        assertEquals(CapturePipeline.NATIVE_48_KHZ_TO_16_KHZ, recovered.capturePipeline)
    }

    @Test
    fun `recovery preserves selected incremental model`() {
        val original = AudioSessionWriter(
            rootDirectory = root,
            sessionId = "session-7",
            format = PcmFormat(16_000),
            incrementalModelId = "whisper-base-multilingual-q5_1",
        )
        original.writeCheckpoint(SessionStatus.RECORDING)

        val recovered = AudioSessionWriter.recover(root, "session-7", PcmFormat(16_000))

        assertEquals("whisper-base-multilingual-q5_1", recovered.incrementalModelId)
    }

    @Test
    fun `lifecycle events are immutable ordered and recovered`() {
        val format = PcmFormat(16_000)
        val writer = AudioSessionWriter(root, "session-8", format)
        writer.writeLifecycleEvent("STARTED", SessionStatus.RECORDING, "ui")
        writer.writeLifecycleEvent("PAUSED", SessionStatus.PAUSED, "notification")
        writer.writeCheckpoint(SessionStatus.PAUSED)

        val recovered = AudioSessionWriter.recover(root, "session-8", format)
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
}
