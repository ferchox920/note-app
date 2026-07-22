package com.noteapp.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadSegmenterTest {
    @Test
    fun `arbitrary audio chunks are reassembled into twenty millisecond frames`() {
        val detector = ScriptedDetector(List(4) { false })
        val segmenter = VadSegmenter(detector)
        val audio = ByteArray(4 * detector.frameSizeSamples * 2)

        segmenter.process(audio, 0, 333)
        segmenter.process(audio, 333, audio.size - 333)

        assertEquals(4, detector.framesReceived)
        assertEquals(80, segmenter.processedDurationMs)
    }

    @Test
    fun `speech segment includes preroll and conservative hangover`() {
        val decisions = buildList {
            repeat(20) { add(false) }
            repeat(5) { add(true) }
            repeat(15) { add(false) }
        }
        val detector = ScriptedDetector(decisions)
        val segmenter = VadSegmenter(detector)
        val audio = ByteArray(decisions.size * detector.frameSizeSamples * 2)

        val result = segmenter.process(audio, 0, audio.size)

        assertEquals(1, result.closedSegments.size)
        val segment = result.closedSegments.single()
        assertEquals(200, segment.startMs)
        assertEquals(800, segment.endMs)
        assertEquals(6_400, segment.startByteOffset)
        assertEquals(25_600, segment.endByteOffset)
        assertFalse(result.speechActive)
    }

    @Test
    fun `stream end closes active speech without inventing trailing audio`() {
        val decisions = List(3) { true }
        val detector = ScriptedDetector(decisions)
        val segmenter = VadSegmenter(detector)
        val audio = ByteArray(decisions.size * detector.frameSizeSamples * 2)

        val processing = segmenter.process(audio, 0, audio.size)
        val ended = segmenter.endCurrentStream()

        assertTrue(processing.speechActive)
        assertEquals(1, ended.closedSegments.size)
        assertEquals(60, ended.closedSegments.single().endMs)
        assertFalse(ended.speechActive)
    }

    @Test
    fun `preroll never overlaps the previously closed segment`() {
        val decisions = buildList {
            repeat(3) { add(true) }
            repeat(15) { add(false) }
            repeat(3) { add(true) }
            repeat(15) { add(false) }
        }
        val detector = ScriptedDetector(decisions)
        val segmenter = VadSegmenter(detector)
        val audio = ByteArray(decisions.size * detector.frameSizeSamples * 2)

        val result = segmenter.process(audio, 0, audio.size)

        assertEquals(2, result.closedSegments.size)
        assertEquals(result.closedSegments[0].endMs, result.closedSegments[1].startMs)
    }

    @Test
    fun `recovered segmenter continues timeline and sequence`() {
        val detector = ScriptedDetector(List(3) { true })
        val segmenter = VadSegmenter(
            detector = detector,
            initialProcessedFrames = 100,
            initialSequence = 4,
            initialLastClosedEndFrame = 90,
        )

        segmenter.process(ByteArray(3 * 320 * 2), 0, 3 * 320 * 2)
        val segment = segmenter.endCurrentStream().closedSegments.single()

        assertEquals(4, segment.sequence)
        assertEquals(1_800, segment.startMs)
        assertEquals(2_060, segment.endMs)
        assertEquals(57_600, segment.startByteOffset)
    }

    private class ScriptedDetector(private val decisions: List<Boolean>) : FrameVoiceDetector {
        override val sampleRateHz = 16_000
        override val frameSizeSamples = 320
        var framesReceived = 0

        override fun isSpeech(pcm16LittleEndian: ByteArray): Boolean =
            decisions.getOrElse(framesReceived++) { false }

        override fun close() = Unit
    }
}
