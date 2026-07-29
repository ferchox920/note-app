package com.noteapp.vad

import com.noteapp.security.EncryptedSessionArtifactStore
import com.noteapp.security.PlaintextSessionArtifactStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec

class VadTimelineStoreTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("vad-timeline-test").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `timeline stores timestamps and master audio offsets atomically`() {
        VadTimelineStore(PlaintextSessionArtifactStore(directory)).write(
            sessionDirectory = directory,
            sessionId = "session-vad",
            segments = listOf(VadSpeechSegment(0, 200, 800, 6_400, 25_600)),
            processedDurationMs = 1_000,
        )

        val timeline = File(directory, VadTimelineStore.FILE_NAME)
        val json = timeline.readText()
        assertTrue(json.contains("\"startMs\":200"))
        assertTrue(json.contains("\"endByteOffset\":25600"))
        assertFalse(File(directory, "${VadTimelineStore.FILE_NAME}.tmp").exists())
    }

    @Test
    fun `timeline remains readable through encrypted artifact store`() {
        val root = directory.resolve("recordings").apply { mkdirs() }
        val session = root.resolve("session-vad").apply { mkdirs() }
        val artifacts = EncryptedSessionArtifactStore(
            root,
            SecretKeySpec(ByteArray(32) { (it + 3).toByte() }, "AES"),
        )
        val store = VadTimelineStore(artifacts)

        store.write(
            sessionDirectory = session,
            sessionId = "session-vad",
            segments = listOf(VadSpeechSegment(0, 100, 500, 3_200, 16_000)),
            processedDurationMs = 500,
        )

        val file = session.resolve(VadTimelineStore.FILE_NAME)
        assertTrue(artifacts.isEncrypted(file))
        assertFalse(file.readText().contains("session-vad"))
        assertTrue(store.read(session).segments.single().endMs == 500L)
    }
}
