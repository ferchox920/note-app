package com.noteapp.asr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class SherpaStreamingIncrementalSessionTest {
    @Test
    fun drainsContinuousFramesAndFinalizesEndpoint() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val decoder = EndpointDecoder()
        val clock = AtomicLong()
        val session = SherpaStreamingIncrementalSession.createForTest(
            scope = scope,
            decoder = decoder,
            sampleRateHz = 1_000,
            nanoTime = { clock.getAndAdd(1_000_000L) },
        )

        session.onPcm16(
            pcm16 = ByteArray(1_000),
            length = 1_000,
            speechActive = false,
            endpointDetected = false,
            streamEndMs = 500,
        )
        session.onPcm16(
            pcm16 = ByteArray(1_000),
            length = 1_000,
            speechActive = false,
            endpointDetected = false,
            streamEndMs = 1_000,
        )
        session.shutdown(drain = true)

        val state = session.state.value
        assertFalse(state.enabled)
        assertEquals("hola", state.stableText)
        assertEquals("", state.unstableText)
        assertEquals(1, state.finalizedSegments.size)
        assertEquals(0L, state.finalizedSegments.single().startMs)
        assertEquals(1_000L, state.finalizedSegments.single().endMs)
        assertEquals(10, state.inferenceMetrics.size)
        assertEquals(500L, state.timeToFirstTextMs)
        assertEquals(0, state.queueDepth)
        assertNull(state.errorCode)
        assertEquals(1, decoder.resetCount)
        assertEquals(1, decoder.closeCount)
        scope.cancel()
    }

    @Test
    fun coalescesCaptureReadsIntoOneHundredMillisecondFrames() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val decoder = EndpointDecoder()
        val session = SherpaStreamingIncrementalSession.createForTest(
            scope = scope,
            decoder = decoder,
            sampleRateHz = 1_000,
        )

        repeat(10) { index ->
            session.onPcm16(
                pcm16 = ByteArray(40),
                length = 40,
                speechActive = false,
                endpointDetected = false,
                streamEndMs = (index + 1) * 20L,
            )
        }
        session.shutdown(drain = true)

        assertEquals(2, session.state.value.inferenceMetrics.size)
        assertEquals(
            listOf(100L, 200L),
            session.state.value.inferenceMetrics.map { it.windowEndMs },
        )
        scope.cancel()
    }

    @Test
    fun recoveredSessionStartsNewSegmentsAtRecoveredAudioEnd() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = SherpaStreamingIncrementalSession.createForTest(
            scope = scope,
            decoder = EndpointDecoder(),
            initialStreamEndMs = 5_000,
            sampleRateHz = 1_000,
        )

        session.onPcm16(
            pcm16 = ByteArray(2_000),
            length = 2_000,
            speechActive = false,
            endpointDetected = false,
            streamEndMs = 6_000,
        )
        session.shutdown(drain = true)

        assertEquals(5_000L, session.state.value.finalizedSegments.single().startMs)
        assertEquals(6_000L, session.state.value.finalizedSegments.single().endMs)
        scope.cancel()
    }

    private class EndpointDecoder : StreamingDecoder {
        private var samples = 0
        var resetCount = 0
        var closeCount = 0

        override fun acceptWaveform(samples: FloatArray, sampleRateHz: Int) {
            this.samples += samples.size
        }

        override fun decodeAvailable(): Int = 1

        override fun text(): String = if (samples >= 500) "hola" else ""

        override fun isEndpoint(): Boolean = samples >= 1_000

        override fun reset() {
            resetCount++
            samples = 0
        }

        override fun inputFinished() = Unit

        override fun close() {
            closeCount++
        }
    }
}
