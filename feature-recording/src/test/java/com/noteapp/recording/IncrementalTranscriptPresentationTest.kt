package com.noteapp.recording

import com.noteapp.asr.IncrementalTranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalTranscriptPresentationTest {
    @Test
    fun `finalized prefix is not repeated as provisional text`() {
        val segments = listOf(
            IncrementalTranscriptSegment(0, 1_000, "primer bloque"),
            IncrementalTranscriptSegment(1_000, 2_000, "segundo bloque"),
        )

        val presentation = incrementalTranscriptPresentation(
            stableText = "primer bloque segundo bloque cola estable",
            unstableText = "cola cambiante",
            finalizedSegments = segments,
        )

        assertEquals("cola estable", presentation.provisionalStableText)
        assertEquals("cola cambiante", presentation.provisionalUnstableText)
        assertTrue(presentation.hasProvisionalText)
    }

    @Test
    fun `fully finalized transcript has no provisional duplicate`() {
        val segments = listOf(
            IncrementalTranscriptSegment(0, 2_000, "texto final"),
        )

        val presentation = incrementalTranscriptPresentation(
            stableText = "texto final",
            unstableText = "",
            finalizedSegments = segments,
        )

        assertEquals("", presentation.provisionalStableText)
        assertFalse(presentation.hasProvisionalText)
    }

    @Test
    fun `mismatched stable text is preserved instead of discarded`() {
        val presentation = incrementalTranscriptPresentation(
            stableText = "texto recuperado distinto",
            unstableText = "",
            finalizedSegments = listOf(
                IncrementalTranscriptSegment(0, 1_000, "segmento"),
            ),
        )

        assertEquals("texto recuperado distinto", presentation.provisionalStableText)
    }

    @Test
    fun `timestamp uses hours for long sessions`() {
        assertEquals("59:59", formatTranscriptTimestamp(3_599_000))
        assertEquals("01:00:00", formatTranscriptTimestamp(3_600_000))
        assertEquals("01:30:05", formatTranscriptTimestamp(5_405_000))
    }
}
