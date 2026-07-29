package com.noteapp.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SherpaStreamingLabRunnerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun decodeLoopKeepsStateAcrossFramesAndCommitsEndpoints() {
        val decoder = FakeStreamingDecoder()
        var clockMs = 0L

        val result = StreamingDecodeLoop.run(
            pcm16 = ByteArray(2_000 * 2),
            decoder = decoder,
            sampleRateHz = 1_000,
            frameMs = 100,
            nanoTime = {
                val value = clockMs * 1_000_000L
                clockMs += 5
                value
            },
        )

        assertEquals("hola mundo", result.transcript)
        assertEquals(2_000, result.audioDurationMs)
        assertEquals(5L, result.timeToFirstTextMs)
        assertEquals(500L, result.firstTextAudioMs)
        assertEquals(2, result.partialUpdateCount)
        assertEquals(2, result.endpointCount)
        assertEquals(21, result.decodePassCount)
        assertEquals(listOf("hola", "mundo"), result.finalizedTexts)
    }

    @Test
    fun decodeLoopRejectsTruncatedPcm16() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamingDecodeLoop.run(
                pcm16 = ByteArray(3),
                decoder = FakeStreamingDecoder(),
            )
        }
    }

    @Test
    fun verifierReportsTheSpecificInvalidArtifact() {
        val directory = temporaryFolder.newFolder("model")
        val encoder = File(directory, "encoder.onnx").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val tokens = File(directory, "tokens.txt").apply { writeText("a 1") }
        val descriptor = SherpaStreamingModelDescriptor(
            id = "test",
            directoryName = "test",
            language = "es",
            modelType = "zipformer2",
            license = "test",
            sourceRevision = "test",
            artifacts = listOf(
                artifactFor(encoder),
                artifactFor(tokens),
            ),
        )

        assertNull(SherpaStreamingModelVerifier.verify(directory, descriptor))

        tokens.writeText("corrupt")
        val failure = requireNotNull(
            SherpaStreamingModelVerifier.verify(directory, descriptor),
        )
        assertEquals("tokens.txt", failure.artifact.fileName)
        assertEquals(
            ModelVerificationResult.InvalidSize(
                expected = descriptor.artifact("tokens.txt").expectedBytes,
                actual = tokens.length(),
            ),
            failure.result,
        )
    }

    private fun artifactFor(file: File) = SherpaModelArtifact(
        fileName = file.name,
        expectedBytes = file.length(),
        sha256 = WhisperModelVerifier.sha256(file),
    )

    private class FakeStreamingDecoder : StreamingDecoder {
        private var segmentIndex = 0
        private var segmentSamples = 0

        override fun acceptWaveform(samples: FloatArray, sampleRateHz: Int) {
            segmentSamples += samples.size
        }

        override fun decodeAvailable(): Int = 1

        override fun text(): String = when {
            segmentSamples < 500 -> ""
            segmentIndex == 0 -> "hola"
            else -> "mundo"
        }

        override fun isEndpoint(): Boolean = segmentSamples >= 1_000

        override fun reset() {
            segmentIndex++
            segmentSamples = 0
        }

        override fun inputFinished() = Unit

        override fun close() = Unit
    }
}
