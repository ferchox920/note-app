package com.noteapp.asr

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalAsrCoordinatorTest {
    @Test
    fun `emits a partial then finalizes the segment without blocking capture input`() = runBlocking {
        var calls = 0
        val coordinator = IncrementalAsrCoordinator(
            scope = this,
            sampleRateHz = 10,
            transcriber = IncrementalPcmTranscriber { _, _ ->
                calls++
                IncrementalInferenceResult(
                    text = if (calls == 1) "hola cómo" else "hola cómo estás",
                    inferenceDurationMs = 20,
                    realTimeFactor = 0.1,
                )
            },
        )

        coordinator.onPcm16(pcm(2), speechActive = false, endpointDetected = false, streamEndMs = 200)
        coordinator.onPcm16(pcm(30), speechActive = true, endpointDetected = false, streamEndMs = 3_200)
        val partial = withTimeout(2_000) { coordinator.state.first { it.partialCount == 1 } }

        assertEquals("hola cómo", partial.unstableText)
        assertNotNull(partial.timeToFirstTextMs)

        coordinator.endSegment(streamEndMs = 3_200)
        val final = withTimeout(2_000) { coordinator.state.first { it.finalizedSegments.size == 1 } }

        assertEquals("hola cómo estás", final.stableText)
        assertEquals("", final.unstableText)
        assertEquals(0, final.queueDepth)
        assertEquals(2, final.inferenceMetrics.size)
        assertEquals(false, final.inferenceMetrics.first().final)
        assertEquals(true, final.inferenceMetrics.last().final)
        coordinator.shutdown(drain = true)
    }

    @Test
    fun `reuses an exact partial window when finalizing without new audio`() = runBlocking {
        var calls = 0
        val coordinator = IncrementalAsrCoordinator(
            scope = this,
            sampleRateHz = 10,
            transcriber = IncrementalPcmTranscriber { _, _ ->
                calls++
                IncrementalInferenceResult(
                    text = "hola mundo",
                    inferenceDurationMs = 200,
                    realTimeFactor = 0.2,
                )
            },
        )

        coordinator.onPcm16(
            pcm16 = pcm(30),
            speechActive = true,
            endpointDetected = false,
            streamEndMs = 3_000,
        )
        withTimeout(2_000) { coordinator.state.first { it.partialCount == 1 } }

        coordinator.endSegment(streamEndMs = 3_000)
        val final = withTimeout(2_000) { coordinator.state.first { it.finalizedSegments.size == 1 } }

        assertEquals(1, calls)
        assertEquals("hola mundo", final.stableText)
        assertEquals(2, final.inferenceMetrics.size)
        assertTrue(final.inferenceMetrics.last().reusedResult)
        assertEquals(0L, final.inferenceMetrics.last().inferenceDurationMs)
        assertEquals(0.0, final.inferenceMetrics.last().realTimeFactor, 0.0)
        coordinator.shutdown(drain = true)
    }

    @Test
    fun `coalesces a short pause and finalizes only after sustained silence`() = runBlocking {
        var calls = 0
        val coordinator = IncrementalAsrCoordinator(
            scope = this,
            sampleRateHz = 10,
            endpointFinalizationGraceMs = 700,
            transcriber = IncrementalPcmTranscriber { _, _ ->
                calls++
                IncrementalInferenceResult(
                    text = "una frase continua",
                    inferenceDurationMs = 20,
                    realTimeFactor = 0.1,
                )
            },
        )

        coordinator.onPcm16(pcm(10), speechActive = true, endpointDetected = false, streamEndMs = 1_000)
        coordinator.onPcm16(pcm(3), speechActive = false, endpointDetected = true, streamEndMs = 1_300)
        coordinator.onPcm16(pcm(6), speechActive = false, endpointDetected = false, streamEndMs = 1_900)
        assertEquals(0, coordinator.state.value.finalizedSegments.size)

        coordinator.onPcm16(pcm(1), speechActive = true, endpointDetected = false, streamEndMs = 2_000)
        coordinator.onPcm16(pcm(10), speechActive = true, endpointDetected = false, streamEndMs = 3_000)
        coordinator.onPcm16(pcm(3), speechActive = false, endpointDetected = true, streamEndMs = 3_300)
        coordinator.onPcm16(pcm(7), speechActive = false, endpointDetected = false, streamEndMs = 4_000)

        val final = withTimeout(2_000) { coordinator.state.first { it.finalizedSegments.size == 1 } }
        assertEquals("una frase continua", final.stableText)
        assertEquals(1, calls)
        assertEquals(4_000L, final.inferenceMetrics.last().audioDurationMs)
        coordinator.shutdown(drain = true)
    }

    private fun pcm(samples: Int): ByteArray = ByteArray(samples * 2) { index ->
        if (index % 2 == 0) (index / 2).toByte() else 0
    }
}
