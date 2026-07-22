package com.noteapp.vad

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

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
        VadTimelineStore().write(
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
}

