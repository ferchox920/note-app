package com.noteapp.asr

import kotlinx.collections.immutable.PersistentList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalMetricTimelineTest {
    @Test
    fun `forty five minute timeline keeps structural sharing`() {
        var timeline: List<IncrementalInferenceMetric> = emptyList()
        var earlySnapshot: List<IncrementalInferenceMetric>? = null

        repeat(FRAME_COUNT_45_MINUTES) { sequence ->
            val startMs = sequence * FRAME_MS
            timeline = timeline.appendMetric(
                IncrementalInferenceMetric(
                    sequence = sequence,
                    windowStartMs = startMs.toLong(),
                    windowEndMs = (startMs + FRAME_MS).toLong(),
                    final = false,
                    audioDurationMs = FRAME_MS.toLong(),
                    inferenceDurationMs = 5,
                    visibleLatencyMs = 1,
                    realTimeFactor = 0.05,
                ),
            )
            if (sequence == 99) earlySnapshot = timeline
        }

        assertTrue(timeline is PersistentList<*>)
        assertTrue(earlySnapshot is PersistentList<*>)
        assertEquals(100, earlySnapshot?.size)
        assertEquals(FRAME_COUNT_45_MINUTES, timeline.size)
        assertEquals(0, timeline.first().sequence)
        assertEquals(FRAME_COUNT_45_MINUTES - 1, timeline.last().sequence)
    }

    private companion object {
        const val FRAME_MS = 100
        const val FRAME_COUNT_45_MINUTES = 45 * 60 * 1_000 / FRAME_MS
    }
}
