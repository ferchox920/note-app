package com.noteapp.recording

import com.noteapp.asr.AsrLabResult
import com.noteapp.asr.SherpaStreamingLabResult
import com.noteapp.asr.WhisperNativeTimings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AsrProcessingMetricsTest {
    @Test
    fun `whisper metrics exclude transcript content and omit unavailable temperature`() {
        val result = AsrLabResult(
            modelId = "whisper-base",
            capturePipelineId = "direct-16k",
            benchmarkConfigId = "default",
            threadCount = 4,
            maxChunkMs = 30_000,
            chunkCount = 3,
            audioDurationMs = 60_000,
            inferenceDurationMs = 12_000,
            timeToFirstTextMs = 2_000,
            realTimeFactor = 0.2,
            peakPssKb = 100_000,
            maximumThermalStatus = 1,
            maximumBatteryTemperatureC = null,
            nativeSystemInfo = "test",
            nativeTimings = WhisperNativeTimings(0f, 0f, 0f, 0f, 0f),
            transcript = "contenido sensible que no debe persistirse como métrica",
        )

        val metrics = result.toProcessingMetrics()

        assertEquals(
            listOf(
                "AUDIO_DURATION_MS",
                "INFERENCE_DURATION_MS",
                "TIME_TO_FIRST_TEXT_MS",
                "REAL_TIME_FACTOR",
                "PEAK_PSS_KB",
                "MAXIMUM_THERMAL_STATUS",
                "CHUNK_COUNT",
            ),
            metrics.map { it.name },
        )
        assertFalse(metrics.joinToString().contains("contenido sensible"))
    }

    @Test
    fun `sherpa metrics preserve operational counters`() {
        val result = SherpaStreamingLabResult(
            modelId = "sherpa-es",
            capturePipelineId = "direct-16k",
            benchmarkConfigId = "streaming-t4-f100ms",
            threadCount = 4,
            frameMs = 100,
            audioDurationMs = 60_000,
            inferenceDurationMs = 5_400,
            timeToFirstTextMs = 900,
            firstTextAudioMs = 1_500,
            realTimeFactor = 0.09,
            partialUpdateCount = 12,
            endpointCount = 4,
            decodePassCount = 200,
            peakPssKb = 80_000,
            maximumThermalStatus = 0,
            maximumBatteryTemperatureC = 31.5,
            transcript = "texto privado",
        )

        val metrics = result.toProcessingMetrics().associateBy { it.name }

        assertEquals(0.09, metrics.getValue("REAL_TIME_FACTOR").value, 0.0)
        assertEquals(12.0, metrics.getValue("PARTIAL_UPDATE_COUNT").value, 0.0)
        assertEquals(4.0, metrics.getValue("ENDPOINT_COUNT").value, 0.0)
        assertEquals(200.0, metrics.getValue("DECODE_PASS_COUNT").value, 0.0)
        assertFalse(metrics.toString().contains("texto privado"))
    }
}
